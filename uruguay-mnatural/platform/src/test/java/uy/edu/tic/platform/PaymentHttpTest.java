package uy.edu.tic.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import uy.edu.tic.platform.integration.PaymentClient;

class PaymentHttpTest {
  @Test
  void sendsAuthenticatedIdempotentRequestAndReadsRemoteResult() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var key = new AtomicReference<String>();
    var body = new AtomicReference<String>();
    var json = new ObjectMapper().findAndRegisterModules();
    Instant expires = Instant.parse("2026-11-01T15:10:00Z");
    server.createContext(
        "/payments",
        exchange -> {
          key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          if (!"test-key".equals(exchange.getRequestHeaders().getFirst("X-API-Key"))) {
            exchange.sendResponseHeaders(401, -1);
          } else {
            byte[] data =
                json.writeValueAsBytes(
                    new PaymentClient.RemotePayment(
                        "payment-1",
                        "reservation-1",
                        new BigDecimal("2400.00"),
                        "UYU",
                        "PENDING",
                        expires,
                        null,
                        null));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, data.length);
            exchange.getResponseBody().write(data);
          }
          exchange.close();
        });
    server.start();
    try {
      var client =
          new PaymentClient(
              json,
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "test-key",
              "http://localhost/api/v1/payment-events");
      var result =
          client.authorize(
              "reservation-1",
              "booking-1",
              "tourist-1",
              java.util.UUID.randomUUID(),
              new BigDecimal("2400.00"),
              "UYU",
              expires);
      assertEquals("reservation-1", key.get());
      assertEquals("reservation-1", json.readTree(body.get()).get("merchantReference").asText());
      assertEquals("PENDING", result.status());
      assertEquals(expires, result.expiresAt());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void distinguishesMissingOperationFromUnavailableService() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/payments/by-reference/missing",
        exchange -> {
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });
    server.createContext(
        "/payments/failing",
        exchange -> {
          exchange.sendResponseHeaders(503, -1);
          exchange.close();
        });
    server.start();
    try {
      var client =
          new PaymentClient(
              new ObjectMapper(),
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "key",
              "http://localhost/callback");
      assertNull(client.byReference("missing"));
      assertThrows(IllegalStateException.class, () -> client.get("failing"));
    } finally {
      server.stop(0);
    }
  }
}
