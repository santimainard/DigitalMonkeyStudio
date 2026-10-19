package uy.edu.tic.platform.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PaymentClient {
  public record RemotePayment(
      String paymentId,
      String merchantReference,
      BigDecimal amount,
      String currency,
      String status,
      Instant expiresAt,
      Instant approvedAt,
      String checkoutUrl) {}

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final ObjectMapper json;
  private final String base, key, callback;

  public PaymentClient(
      ObjectMapper json,
      @Value("${payment.base-url}") String base,
      @Value("${payment.api-key}") String key,
      @Value("${payment.callback-url}") String callback) {
    this.json = json;
    this.base = base;
    this.key = key;
    this.callback = callback;
  }

  public RemotePayment get(String id) {
    return call("GET", "/payments/" + id, null, null);
  }

  public RemotePayment byReference(String ref) {
    return call("GET", "/payments/by-reference/" + ref, null, null);
  }

  public RemotePayment create(String ref, BigDecimal amount, String currency, Instant expires) {
    return call(
        "POST",
        "/payments",
        Map.of(
            "merchantReference",
            ref,
            "amount",
            amount,
            "currency",
            currency,
            "expiresAt",
            expires.toString(),
            "callbackUrl",
            callback),
        ref);
  }

  private RemotePayment call(String method, String path, Object body, String idempotency) {
    try {
      var request =
          HttpRequest.newBuilder(URI.create(base + path))
              .timeout(Duration.ofSeconds(5))
              .header("X-API-Key", key)
              .header("Content-Type", "application/json");
      if (idempotency != null) request.header("Idempotency-Key", idempotency);
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404) return null;
      if (response.statusCode() / 100 != 2)
        throw new IllegalStateException("Servicio externo devolvió " + response.statusCode());
      return json.readValue(response.body(), RemotePayment.class);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Pago interrumpido", e);
    } catch (Exception e) {
      throw new IllegalStateException("No se pudo comprobar el pago", e);
    }
  }
}
