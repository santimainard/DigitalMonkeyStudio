package uy.edu.tic.platform.integration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
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
      String declineReason) {}

  public record RemoteMethod(
      UUID token, String brand, String lastFour, String holder, int expiryMonth, int expiryYear) {}

  public record Settlement(
      String paymentId,
      String merchantReference,
      BigDecimal amount,
      String currency,
      String status,
      int refundPercent,
      BigDecimal refundedAmount) {}

  public static class Card {
    public String holder;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public String number;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public String cvv;

    public int expiryMonth, expiryYear;

    @Override
    public String toString() {
      return "Card[redacted]";
    }
  }

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

  public RemoteMethod tokenize(Card c, String customer, String registration) {
    // Explicit outbound mapping: the input DTO itself is never serializable with sensitive fields.
    return request(
        "POST",
        "/methods",
        Map.of(
            "holder",
            c.holder,
            "number",
            c.number,
            "cvv",
            c.cvv,
            "expiryMonth",
            c.expiryMonth,
            "expiryYear",
            c.expiryYear,
            "customerReference",
            customer,
            "registrationReference",
            registration),
        registration,
        RemoteMethod.class);
  }

  public RemotePayment authorize(
      String ref,
      String reservation,
      String customer,
      UUID method,
      BigDecimal amount,
      String currency,
      Instant expires) {
    return request(
        "POST",
        "/payments",
        Map.of(
            "merchantReference",
            ref,
            "reservationReference",
            reservation,
            "customerReference",
            customer,
            "methodToken",
            method,
            "amount",
            amount,
            "currency",
            currency,
            "expiresAt",
            expires.toString(),
            "callbackUrl",
            callback),
        ref,
        RemotePayment.class);
  }

  public RemotePayment get(String id) {
    return request("GET", "/payments/" + id, null, null, RemotePayment.class);
  }

  public RemotePayment byReference(String ref) {
    return request("GET", "/payments/by-reference/" + ref, null, null, RemotePayment.class);
  }

  public Settlement cancelReference(
      String ref,
      String reservation,
      String customer,
      BigDecimal amount,
      String currency,
      Instant expires,
      int percent) {
    return request(
        "POST",
        "/payments/by-reference/" + ref + "/cancellation",
        Map.of(
            "reservationReference",
            reservation,
            "customerReference",
            customer,
            "amount",
            amount,
            "currency",
            currency,
            "expiresAt",
            expires.toString(),
            "refundPercent",
            percent),
        null,
        Settlement.class);
  }

  private <T> T request(String verb, String path, Object body, String idempotency, Class<T> type) {
    try {
      var request =
          HttpRequest.newBuilder(URI.create(base + path))
              .timeout(Duration.ofSeconds(5))
              .header("X-API-Key", key)
              .header("Content-Type", "application/json");
      if (idempotency != null) request.header("Idempotency-Key", idempotency);
      request.method(
          verb,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404 && "GET".equals(verb)) return null;
      if (response.statusCode() / 100 != 2)
        throw new IllegalStateException(
            "Respuesta del servicio de pagos: " + response.statusCode());
      return json.readValue(response.body(), type);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Operación interrumpida");
    } catch (Exception e) {
      throw new IllegalStateException("No se pudo comprobar la operación de pagos");
    }
  }
}
