package uy.edu.tic.payments;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "simulation-control"})
class SimulatorTest {
  @Autowired PaymentStore store;
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;

  PaymentStore.Card card(String number) {
    var c = new PaymentStore.Card();
    c.registrationReference = UUID.randomUUID().toString();
    c.customerReference = "tourist-1";
    c.holder = "Titular de prueba";
    c.number = number;
    c.cvv = "731";
    c.expiryMonth = 12;
    c.expiryYear = 2099;
    return c;
  }

  UUID token(String number) {
    var c = card(number);
    return store.tokenize(c, c.registrationReference).token();
  }

  PaymentStore.Authorization request(UUID token) {
    return new PaymentStore.Authorization(
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        "tourist-1",
        token,
        new BigDecimal("1200.00"),
        "UYU",
        Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MICROS),
        "http://localhost:8080/api/v1/payment-events");
  }

  @AfterEach
  void normal() {
    store.mode("NORMAL");
  }

  @Test
  void tokensContainOnlyMaskedMetadataAndNeverPersistCvvOrPan() throws Exception {
    var c = card("9900000000004242");
    var m = store.tokenize(c, c.registrationReference);
    assertEquals(m, store.tokenize(c, c.registrationReference));
    assertEquals("4242", m.lastFour());
    var row = db.queryForMap("select * from simulated_method where token=?", m.token());
    assertFalse(row.values().stream().anyMatch(v -> c.number.equals(v) || c.cvv.equals(v)));
    mvc.perform(
            post("/methods")
                .header("X-API-Key", "test-api-key")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(
                    "{\"registrationReference\":\"invalid\",\"customerReference\":\"tourist-1\",\"holder\":\"Prueba\",\"number\":\"4111111111111111\",\"cvv\":\"731\",\"expiryMonth\":12,\"expiryYear\":2099}"))
        .andExpect(status().isBadRequest())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("4111111111111111"))));
  }

  @Test
  void invalidDataAndRealCardNumbersAreRefused() {
    var c = card("4111111111111111");
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> store.tokenize(c, c.registrationReference));
    c.number = "9900000000004242";
    c.cvv = "ab1";
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> store.tokenize(c, c.registrationReference));
    c.cvv = "731";
    c.expiryMonth = 13;
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> store.tokenize(c, c.registrationReference));
  }

  @Test
  void authorizesAutomaticallyAndIdempotentlyWithNoSecondSuccessfulCharge() {
    var a = request(token("9900000000004242"));
    var p = store.authorize(a, a.merchantReference());
    assertEquals("APPROVED", p.status());
    assertEquals(p, store.authorize(a, a.merchantReference()));
    var second =
        new PaymentStore.Authorization(
            UUID.randomUUID().toString(),
            a.reservationReference(),
            a.customerReference(),
            a.methodToken(),
            a.amount(),
            a.currency(),
            a.expiresAt(),
            a.callbackUrl());
    assertEquals("REJECTED", store.authorize(second, second.merchantReference()).status());
    assertEquals(
        1L,
        db.queryForObject(
            "select count(*) from simulated_payment where reservation_reference=? and"
                + " status='APPROVED'",
            Long.class,
            a.reservationReference()));
  }

  @Test
  void declinesDisabledMissingForeignAndExpiredMethods() {
    for (String number : List.of("9900000000000005", "9900000000000003")) {
      var a = request(token(number));
      assertEquals("REJECTED", store.authorize(a, a.merchantReference()).status());
    }
    var missing = request(UUID.randomUUID());
    assertEquals(
        "METHOD_NOT_FOUND", store.authorize(missing, missing.merchantReference()).declineReason());
    var t = token("9900000000004242");
    db.update("update simulated_method set customer_reference='tourist-2' where token=?", t);
    var foreign = request(t);
    assertEquals("REJECTED", store.authorize(foreign, foreign.merchantReference()).status());
    db.update(
        "update simulated_method set customer_reference='tourist-1',expiry_year=2000 where token=?",
        t);
    var expired = request(t);
    assertEquals(
        "CARD_EXPIRED", store.authorize(expired, expired.merchantReference()).declineReason());
  }

  @Test
  void expiryIsEnforcedBeforeAuthorization() {
    var a = request(token("9900000000004242"));
    a =
        new PaymentStore.Authorization(
            a.merchantReference(),
            a.reservationReference(),
            a.customerReference(),
            a.methodToken(),
            a.amount(),
            a.currency(),
            Instant.now().minusSeconds(1),
            a.callbackUrl());
    assertEquals("EXPIRED", store.authorize(a, a.merchantReference()).status());
  }

  @Test
  void partialAndFullRefundsAreCumulativeAndIdempotent() {
    var a = request(token("9900000000004242"));
    store.authorize(a, a.merchantReference());
    var c =
        new PaymentStore.Cancel(
            a.reservationReference(),
            a.customerReference(),
            a.amount(),
            a.currency(),
            a.expiresAt(),
            35);
    assertEquals(
        new BigDecimal("420.00"), store.cancelReference(a.merchantReference(), c).refundedAmount());
    var full =
        new PaymentStore.Cancel(
            a.reservationReference(),
            a.customerReference(),
            a.amount(),
            a.currency(),
            a.expiresAt(),
            100);
    var result = store.cancelReference(a.merchantReference(), full);
    assertEquals(new BigDecimal("1200.00"), result.refundedAmount());
    assertEquals(result, store.cancelReference(a.merchantReference(), c));
  }

  @Test
  void cancellationTombstonePreventsAnyDelayedAuthorization() {
    var a = request(token("9900000000004242"));
    var c =
        new PaymentStore.Cancel(
            a.reservationReference(),
            a.customerReference(),
            a.amount(),
            a.currency(),
            a.expiresAt(),
            100);
    assertEquals(
        new BigDecimal("0.00"), store.cancelReference(a.merchantReference(), c).refundedAmount());
    assertEquals("CANCELLED", store.authorize(a, a.merchantReference()).status());
  }

  @Test
  void concurrentCancellationAndAuthorizationNeverLeaveAnUnrefundedCharge() throws Exception {
    var a = request(token("9900000000004242"));
    var c =
        new PaymentStore.Cancel(
            a.reservationReference(),
            a.customerReference(),
            a.amount(),
            a.currency(),
            a.expiresAt(),
            100);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    var go = new java.util.concurrent.CountDownLatch(1);
    try {
      var pay =
          pool.submit(
              () -> {
                go.await();
                return store.authorize(a, a.merchantReference());
              });
      var cancel =
          pool.submit(
              () -> {
                go.await();
                return store.cancelReference(a.merchantReference(), c);
              });
      go.countDown();
      pay.get(10, java.util.concurrent.TimeUnit.SECONDS);
      var result = cancel.get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(
          "APPROVED".equals(result.status()) ? new BigDecimal("1200.00") : new BigDecimal("0.00"),
          result.refundedAmount());
      assertEquals(result, store.cancelReference(a.merchantReference(), c));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void noPublicCheckoutOrManualDecisionEndpointRemains() throws Exception {
    mvc.perform(get("/payments/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    for (String path :
        List.of("/checkout/" + UUID.randomUUID(), "/payments/" + UUID.randomUUID() + "/decision")) {
      mvc.perform(get(path).header("X-API-Key", "test-api-key")).andExpect(status().isNotFound());
      mvc.perform(
              post(path)
                  .header("X-API-Key", "test-api-key")
                  .contentType("application/json")
                  .content("{\"action\":\"APPROVE\"}"))
          .andExpect(status().isNotFound());
    }
  }

  @Test
  void unavailableApiDoesNotInventRejections() throws Exception {
    store.mode("UNAVAILABLE");
    mvc.perform(get("/payments/" + UUID.randomUUID()).header("X-API-Key", "test-api-key"))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void idempotencyBindsEvenAnUnknownMethodWithoutAllowingAnAlteredRetry() {
    var a = request(UUID.randomUUID());
    assertEquals("REJECTED", store.authorize(a, a.merchantReference()).status());
    var changed =
        new PaymentStore.Authorization(
            a.merchantReference(),
            a.reservationReference(),
            a.customerReference(),
            token("9900000000004242"),
            a.amount(),
            a.currency(),
            a.expiresAt(),
            a.callbackUrl());
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> store.authorize(changed, changed.merchantReference()));
  }
}
