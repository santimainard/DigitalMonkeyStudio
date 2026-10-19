package uy.edu.tic.payments;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SimulatorTest {
  @Autowired PaymentStore store;
  @Autowired MockMvc mvc;

  PaymentStore.Create request(Instant expiry) {
    return new PaymentStore.Create(
        UUID.randomUUID().toString(),
        new BigDecimal("1200.00"),
        "UYU",
        expiry,
        "http://localhost:8080/api/v1/payment-events");
  }

  @Test
  void idempotentAndTerminal() {
    var r =
        request(Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    var p = store.create(r, r.merchantReference());
    assertEquals(p.paymentId(), store.create(r, r.merchantReference()).paymentId());
    store.decide(UUID.fromString(p.paymentId()), "APPROVE");
    assertEquals("APPROVED", store.decide(UUID.fromString(p.paymentId()), "REJECT").status());
  }

  @Test
  void expiresWithoutApproving() {
    var r = request(Instant.now().minusSeconds(1));
    var p = store.create(r, r.merchantReference());
    assertEquals("EXPIRED", p.status());
    assertEquals("EXPIRED", store.decide(UUID.fromString(p.paymentId()), "APPROVE").status());
  }

  @Test
  void apiAuthenticationAndCheckout() throws Exception {
    var r = request(Instant.now().plusSeconds(600));
    var p = store.create(r, r.merchantReference());
    mvc.perform(get("/payments/" + p.paymentId())).andExpect(status().isUnauthorized());
    mvc.perform(get("/payments/" + p.paymentId()).header("X-API-Key", "test-api-key"))
        .andExpect(status().isOk());
    mvc.perform(get("/checkout/" + p.paymentId())).andExpect(status().isOk());
    mvc.perform(post("/checkout/" + p.paymentId() + "/decision").param("action", "APPROVE"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/checkout/" + p.paymentId() + "/decision")
                .with(csrf())
                .param("action", "APPROVE")
                .param("callbackMode", "DELAY"))
        .andExpect(status().is3xxRedirection());
  }

  @Test
  void unavailableAndRecovery() throws Exception {
    store.mode("UNAVAILABLE");
    mvc.perform(get("/payments/" + UUID.randomUUID()).header("X-API-Key", "test-api-key"))
        .andExpect(status().isServiceUnavailable());
    store.mode("NORMAL");
  }

  @Test
  void refusesUnconfiguredCallback() {
    var r =
        new PaymentStore.Create(
            UUID.randomUUID().toString(),
            BigDecimal.TEN,
            "UYU",
            Instant.now().plusSeconds(300),
            "http://invalid.example");
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> store.create(r, r.merchantReference()));
  }
}
