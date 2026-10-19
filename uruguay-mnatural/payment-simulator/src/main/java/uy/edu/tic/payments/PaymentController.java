package uy.edu.tic.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class PaymentController {
  private final PaymentStore store;
  private final String key, webhook, callback, returnUrl;
  private final ObjectMapper json;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  public PaymentController(
      PaymentStore store,
      ObjectMapper json,
      @Value("${simulator.api-key}") String key,
      @Value("${simulator.webhook-token}") String webhook,
      @Value("${simulator.callback-url}") String callback,
      @Value("${simulator.return-url}") String returnUrl) {
    this.store = store;
    this.json = json;
    this.key = key;
    this.webhook = webhook;
    this.callback = callback;
    this.returnUrl = returnUrl;
  }

  private void authorize(String token) {
    if (token == null
        || !MessageDigest.isEqual(
            token.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8)))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
  }

  private void available() {
    if ("UNAVAILABLE".equals(store.mode()))
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @PostMapping("/payments")
  @ResponseBody
  ResponseEntity<?> create(
      @RequestHeader(value = "X-API-Key", required = false) String token,
      @RequestHeader("Idempotency-Key") String idempotency,
      @RequestBody PaymentStore.Create c)
      throws InterruptedException {
    authorize(token);
    available();
    var payment = store.create(c, idempotency);
    if (Set.of("TIMEOUT", "LOST_RESPONSE").contains(store.mode())) Thread.sleep(7000);
    return ResponseEntity.status(201).body(payment);
  }

  @GetMapping("/payments/{id}")
  @ResponseBody
  Object get(
      @RequestHeader(value = "X-API-Key", required = false) String token, @PathVariable UUID id) {
    authorize(token);
    available();
    return store.get(id);
  }

  @GetMapping("/payments/by-reference/{ref}")
  @ResponseBody
  Object reference(
      @RequestHeader(value = "X-API-Key", required = false) String token,
      @PathVariable String ref) {
    authorize(token);
    available();
    return store.byReference(ref);
  }

  @PostMapping("/payments/{id}/decision")
  @ResponseBody
  Object apiDecision(
      @RequestHeader(value = "X-API-Key", required = false) String token,
      @PathVariable UUID id,
      @RequestBody Map<String, String> body) {
    authorize(token);
    available();
    var p = store.decide(id, body.get("action"));
    if (!"DELAY".equals(body.get("callback")))
      notifyPlatform(id, "DUPLICATE".equals(body.get("callback")));
    return p;
  }

  @PostMapping("/simulation/mode")
  @ResponseBody
  Object mode(
      @RequestHeader(value = "X-API-Key", required = false) String token,
      @RequestBody Map<String, String> body) {
    authorize(token);
    store.mode(body.get("mode"));
    return Map.of("mode", store.mode());
  }

  @GetMapping("/checkout/{id}")
  String checkout(@PathVariable UUID id, Model m) {
    m.addAttribute("payment", store.get(id));
    m.addAttribute(
        "returnUrl", returnUrl + "/tourist/reservations/" + store.get(id).merchantReference());
    return "checkout";
  }

  @PostMapping("/checkout/{id}/decision")
  String decision(
      @PathVariable UUID id,
      @RequestParam String action,
      @RequestParam(defaultValue = "NORMAL") String callbackMode) {
    available();
    store.decide(id, action);
    if (!"DELAY".equals(callbackMode)) notifyPlatform(id, "DUPLICATE".equals(callbackMode));
    return "redirect:/checkout/" + id;
  }

  @PostMapping("/checkout/{id}/notify")
  String notify(@PathVariable UUID id) {
    notifyPlatform(id, true);
    return "redirect:/checkout/" + id;
  }

  private void notifyPlatform(UUID id, boolean duplicate) {
    try {
      var request =
          HttpRequest.newBuilder(URI.create(callback))
              .timeout(Duration.ofSeconds(5))
              .header("Content-Type", "application/json")
              .header("X-Webhook-Token", webhook)
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(store.event(id))))
              .build();
      http.sendAsync(request, HttpResponse.BodyHandlers.discarding());
      if (duplicate) http.sendAsync(request, HttpResponse.BodyHandlers.discarding());
    } catch (Exception e) {
      /* The platform also reconciles via GET; a lost callback never reverses a payment. */
    }
  }
}
