package uy.edu.tic.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PaymentController {
  private final PaymentStore store;
  private final ObjectMapper json;
  private final String webhook, callback;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  public PaymentController(
      PaymentStore store,
      ObjectMapper json,
      @Value("${simulator.webhook-token}") String webhook,
      @Value("${simulator.callback-url}") String callback) {
    this.store = store;
    this.json = json;
    this.webhook = webhook;
    this.callback = callback;
  }

  private void available() {
    if ("UNAVAILABLE".equals(store.mode()))
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @PostMapping("/methods")
  @ResponseStatus(HttpStatus.CREATED)
  PaymentStore.Method tokenize(
      @RequestHeader("Idempotency-Key") String key, @RequestBody PaymentStore.Card card) {
    available();
    return store.tokenize(card, key);
  }

  @PostMapping("/payments")
  @ResponseStatus(HttpStatus.CREATED)
  PaymentStore.Payment pay(
      @RequestHeader("Idempotency-Key") String key, @RequestBody PaymentStore.Authorization request)
      throws InterruptedException {
    available();
    String mode = store.mode();
    if ("TIMEOUT".equals(mode)) Thread.sleep(7000);
    var p = store.authorize(request, key);
    if (!Set.of("TIMEOUT", "LOST_RESPONSE").contains(mode))
      notifyPlatform(UUID.fromString(p.paymentId()));
    if ("LOST_RESPONSE".equals(mode)) Thread.sleep(7000);
    return p;
  }

  @GetMapping("/payments/{id}")
  PaymentStore.Payment get(@PathVariable UUID id) {
    available();
    return store.get(id);
  }

  @GetMapping("/payments/by-reference/{ref}")
  PaymentStore.Payment reference(@PathVariable String ref) {
    available();
    return store.byReference(ref);
  }

  @PostMapping("/payments/by-reference/{ref}/cancellation")
  PaymentStore.Settlement cancel(@PathVariable String ref, @RequestBody PaymentStore.Cancel request)
      throws InterruptedException {
    available();
    var result = store.cancelReference(ref, request);
    if ("LOST_RESPONSE".equals(store.mode())) Thread.sleep(7000);
    return result;
  }

  private void notifyPlatform(UUID id) {
    try {
      var request =
          HttpRequest.newBuilder(URI.create(callback))
              .timeout(Duration.ofSeconds(5))
              .header("Content-Type", "application/json")
              .header("X-Webhook-Token", webhook)
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(store.event(id))))
              .build();
      http.sendAsync(request, HttpResponse.BodyHandlers.discarding());
    } catch (Exception ignored) {
      /* Canonical GET reconciliation remains available. */
    }
  }
}
