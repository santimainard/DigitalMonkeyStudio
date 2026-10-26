package uy.edu.tic.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import uy.edu.tic.platform.domain.*;
import uy.edu.tic.platform.dto.Forms.*;
import uy.edu.tic.platform.integration.PaymentClient.RemotePayment;
import uy.edu.tic.platform.repository.Database;
import uy.edu.tic.platform.service.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired Database db;
  @Autowired Accounts accounts;
  @Autowired Tourism tourism;
  @Autowired ReservationTransactions tx;
  @Autowired CancellationTransactions cancellations;
  @Autowired PaymentAttemptTransactions attempts;
  @Autowired PaymentMethods methods;
  @Autowired Payments payments;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  uy.edu.tic.platform.integration.PaymentClient client;

  UUID methodId, declinedMethod, approvedToken, declinedToken;
  long activity, instance, tourist, provider;
  String touristEmail, providerEmail;

  void login(String email) {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(email, "unused", List.of()));
  }

  @BeforeEach
  void setup() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    touristEmail = "tourist" + tag + "@example.test";
    providerEmail = "provider" + tag + "@example.test";
    Registration t = new Registration();
    t.firstName = "Test";
    t.lastName = "Turista";
    t.email = touristEmail;
    t.password = "TestOnly123!";
    t.passwordConfirmation = t.password;
    t.document = "ABC";
    tourist = accounts.register(t);
    Registration p = new Registration();
    p.firstName = "Test";
    p.lastName = "Prestador";
    p.email = providerEmail;
    p.password = "TestOnly123!";
    p.passwordConfirmation = p.password;
    p.document = "XYZ";
    p.role = "PROVIDER";
    p.commercialName = "Test " + tag;
    p.legalType = "PHYSICAL";
    p.contactEmail = providerEmail;
    p.phone = "099123456";
    p.departmentId = 10L;
    p.localityId = 10L;
    p.locationDescription = "Montevideo";
    p.description = "Prueba";
    provider = accounts.register(p);
    login(providerEmail);
    long venture = ((Number) accounts.venture().get("id")).longValue();
    login("admin@example.test");
    accounts.approve(venture);
    login(providerEmail);
    ActivityForm f = new ActivityForm();
    f.name = "Test " + tag;
    f.description = "Descripción de prueba";
    f.unitPrice = new BigDecimal("1200.00");
    f.durationMinutes = 60;
    f.departmentId = 10L;
    f.localityId = 10L;
    f.locationDescription = "Rambla";
    f.conditions = "Llegar a tiempo";
    f.categoryIds = List.of(1L);
    f.published = true;
    activity = tourism.saveActivity(null, f);
    tourism.saveInstance(
        activity,
        null,
        LocalDateTime.now(Tourism.ZONE).plusDays(1).withSecond(0).withNano(0),
        2,
        false);
    instance = ((Number) tourism.instances(activity, false).get(0).get("id")).longValue();
    login(touristEmail);
    methodId = UUID.randomUUID();
    declinedMethod = UUID.randomUUID();
    approvedToken = UUID.randomUUID();
    declinedToken = UUID.randomUUID();
    db.update(
        "insert into"
            + " payment_method(id,tourist_id,external_token,brand,last_four,holder,expiry_month,expiry_year)"
            + " values(?,?,?,'Visa de prueba','4242','Titular de prueba',12,2099)",
        methodId,
        tourist,
        approvedToken);
    db.update(
        "insert into"
            + " payment_method(id,tourist_id,external_token,brand,last_four,holder,expiry_month,expiry_year)"
            + " values(?,?,?,'Mastercard de prueba','0005','Titular de prueba',12,2099)",
        declinedMethod,
        tourist,
        declinedToken);
    org.mockito.Mockito.when(
            client.authorize(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            call -> {
              assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              String ref = call.getArgument(0);
              boolean decline = declinedToken.equals(call.getArgument(3));
              return new RemotePayment(
                  "pay-" + ref,
                  ref,
                  call.getArgument(4),
                  "UYU",
                  decline ? "REJECTED" : "APPROVED",
                  call.getArgument(6),
                  decline ? null : Instant.now(),
                  decline ? "CARD_DECLINED" : null);
            });
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
  }

  RemotePayment remote(UUID id, String status) {
    var r = db.one("select * from reservation where id=?", id);
    var expiry = BookingRules.instant(r.get("expires_at"));
    if (db.count("select count(*) from payment_attempt where reservation_id=?", id) == 0)
      attempts.begin(id, methodId, "test-attempt");
    var a =
        db.one(
            "select a.* from payment_attempt a join payment p on p.current_attempt_id=a.id where"
                + " p.reservation_id=?",
            id);
    return new RemotePayment(
        "pay-" + id,
        a.get("merchant_reference").toString(),
        (BigDecimal) r.get("total_amount"),
        "UYU",
        status,
        expiry,
        "APPROVED".equals(status) ? expiry.minusSeconds(5) : null,
        null);
  }

  @Test
  void fullPaidReviewFlowAndDuplicateEvents() throws Exception {
    UUID id = tx.reserve(instance, 2, "same");
    assertEquals(id, tx.reserve(instance, 2, "same"));
    assertThrows(BusinessException.class, () -> tx.reserve(instance, 1, "same"));
    assertThrows(BusinessException.class, () -> tx.reserve(instance, 1, "new"));
    tx.apply(id, remote(id, "APPROVED"), "evt-" + id);
    tx.apply(id, remote(id, "APPROVED"), "evt-" + id);
    assertEquals(2, tourism.committed(instance));
    assertEquals("CONFIRMED", tx.detail(id).get("status"));
    assertThrows(BusinessException.class, () -> tx.review(id, 5, "Antes de terminar"));
    db.update(
        "update reservation set ends_at_snapshot=? where id=?",
        OffsetDateTime.now().minusMinutes(1),
        id);
    tx.review(id, 5, "<script>alert('x')</script>");
    assertThrows(BusinessException.class, () -> tx.review(id, 5, "Duplicado"));
    mvc.perform(get("/activities/" + activity).with(user(touristEmail).roles("TOURIST")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;")));
    mvc.perform(
            get("/api/v1/activities/top-rated")
                .param("month", YearMonth.now(Tourism.ZONE).toString())
                .param("departmentId", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());
  }

  @Test
  void unknownAndRejectedPaymentRetainCapacityUntilCancellationOrExpiry() {
    UUID id = tx.reserve(instance, 2, "x");
    attempts.uncertain(attempts.begin(id, methodId, "test-attempt"));
    assertEquals(2, tourism.committed(instance));
    tx.apply(id, remote(id, "REJECTED"), null);
    assertEquals("PENDING_PAYMENT", tx.detail(id).get("status"));
    assertEquals(2, tourism.committed(instance));
    cancellations.tourist(id);
    cancellations.finishUnpaid(id);
    assertEquals(0, tourism.committed(instance));
    assertNotNull(tx.reserve(instance, 2, "y"));
  }

  @Test
  void lateApprovalRetainsPriceAndCannotRegress() {
    UUID id = tx.reserve(instance, 1, "x");
    db.update("update activity set unit_price=9000 where id=?", activity);
    attempts.uncertain(attempts.begin(id, methodId, "test-attempt"));
    tx.apply(id, remote(id, "APPROVED"), null);
    tx.apply(id, remote(id, "REJECTED"), null);
    assertEquals(new BigDecimal("1200.00"), tx.detail(id).get("total_amount"));
    assertEquals("APPROVED", tx.detail(id).get("payment_status"));
  }

  @Test
  void ownershipCsrfAndAdminBoundary() throws Exception {
    UUID id = tx.reserve(instance, 1, "mine");
    mvc.perform(get("/tourist/reservations/" + id).with(user(providerEmail).roles("TOURIST")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/tourist/reservations")
                .with(user(touristEmail).roles("TOURIST"))
                .param("instanceId", instance + "")
                .param("participantCount", "1")
                .param("key", "x"))
        .andExpect(status().isForbidden());
    mvc.perform(
            patch("/api/v1/admin/ventures/1/verification")
                .with(httpBasic(touristEmail, "TestOnly123!"))
                .contentType("application/json")
                .content("{\"status\":\"VERIFIED\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void paidInstanceCannotBeDeleted() {
    UUID id = tx.reserve(instance, 1, "paid");
    tx.apply(id, remote(id, "APPROVED"), null);
    login(providerEmail);
    assertThrows(
        BusinessException.class, () -> tourism.saveInstance(activity, instance, null, null, true));
  }

  @Test
  void simultaneousRequestsCannotOversell() throws Exception {
    db.update("update activity_instance set capacity=1 where id=?", instance);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
    List<Future<Boolean>> futures = new ArrayList<>();
    for (int n = 0; n < 2; n++) {
      String key = "concurrent-" + n;
      futures.add(
          pool.submit(
              () -> {
                login(touristEmail);
                ready.countDown();
                go.await();
                try {
                  tx.reserve(instance, 1, key);
                  return true;
                } catch (BusinessException e) {
                  return false;
                } finally {
                  SecurityContextHolder.clearContext();
                }
              }));
    }
    assertTrue(ready.await(5, TimeUnit.SECONDS));
    go.countDown();
    int success = 0;
    for (var f : futures) if (f.get(10, TimeUnit.SECONDS)) success++;
    pool.shutdownNow();
    assertEquals(1, success);
    assertEquals(1, tourism.committed(instance));
  }

  @Test
  void pagesAndApisRender() throws Exception {
    for (String path :
        List.of(
            "/explore",
            "/login",
            "/register",
            "/register?role=PROVIDER",
            "/activities/" + activity,
            "/api/v1/ventures",
            "/api/v1/activities/search?q=Test",
            "/api/v1/activities/" + activity)) {
      mvc.perform(get(path).with(user(touristEmail).roles("TOURIST"))).andExpect(status().isOk());
    }
    for (String path :
        List.of(
            "/provider",
            "/provider/activities/new",
            "/provider/activities/" + activity + "/edit",
            "/provider/activities/" + activity + "/instances",
            "/provider/reservations")) {
      mvc.perform(get(path).with(user(providerEmail).roles("PROVIDER"))).andExpect(status().isOk());
    }
    mvc.perform(get("/admin").with(user("admin@example.test").roles("ADMIN")))
        .andExpect(status().isOk());
    mvc.perform(
            get("/tourist/prepare")
                .param("instanceId", instance + "")
                .with(user(touristEmail).roles("TOURIST")))
        .andExpect(status().isOk());
    login(touristEmail);
    UUID id = tx.reserve(instance, 1, "view");
    mvc.perform(get("/tourist/reservations/" + id).with(user(touristEmail).roles("TOURIST")))
        .andExpect(status().isOk());
    mvc.perform(get("/tourist/reservations").with(user(touristEmail).roles("TOURIST")))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/v1/activities/by-day")
                .param("date", LocalDate.now(Tourism.ZONE).plusDays(1).toString())
                .param("departmentId", "10"))
        .andExpect(status().isOk());
  }

  @Test
  void duplicateEmailAndRegistrationRollback() {
    Registration r = new Registration();
    r.firstName = "A";
    r.lastName = "B";
    r.email = touristEmail;
    r.password = "TestOnly123!";
    r.passwordConfirmation = r.password;
    r.document = "12";
    assertThrows(BusinessException.class, () -> accounts.register(r));
    r.email = "invalid-" + UUID.randomUUID() + "@example.test";
    r.role = "PROVIDER";
    assertThrows(BusinessException.class, () -> accounts.register(r));
    assertEquals(0, db.count("select count(*) from app_user where email=?", r.email));
  }

  @Test
  void forgedPaymentAmountDoesNotConfirm() {
    UUID id = tx.reserve(instance, 1, "bad");
    var p = remote(id, "APPROVED");
    assertThrows(
        BusinessException.class,
        () ->
            tx.apply(
                id,
                new RemotePayment(
                    p.paymentId(),
                    p.merchantReference(),
                    BigDecimal.ONE,
                    "UYU",
                    p.status(),
                    p.expiresAt(),
                    p.approvedAt(),
                    p.declineReason()),
                null));
    assertEquals("PAYMENT_UNCERTAIN", tx.detail(id).get("status"));
  }

  @Test
  void pendingDeniedProviderAndRoleNavigation() throws Exception {
    login(providerEmail);
    long venture = ((Number) accounts.venture().get("id")).longValue();
    db.update("update venture set verification_status='PENDING' where id=?", venture);
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> tourism.owned(activity));
    mvc.perform(get("/provider").with(user(providerEmail).roles("PROVIDER")))
        .andExpect(status().isOk())
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("pendiente de habilitación")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(">Explorar<"))));
    for (String path :
        List.of(
            "/provider/activities/new",
            "/provider/activities/" + activity + "/edit",
            "/provider/reservations"))
      mvc.perform(get(path).with(user(providerEmail).roles("PROVIDER")))
          .andExpect(status().isForbidden());
    mvc.perform(post("/provider/venture").with(user(providerEmail).roles("PROVIDER")).with(csrf()))
        .andExpect(status().isForbidden());
    for (String role : List.of("PROVIDER", "ADMIN"))
      for (String path : List.of("/explore", "/activities/" + activity))
        mvc.perform(
                get(path)
                    .with(
                        user(role.equals("ADMIN") ? "admin@example.test" : providerEmail)
                            .roles(role)))
            .andExpect(status().isForbidden());
    SecurityContextHolder.clearContext();
    mvc.perform(get("/").with(anonymous()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrlPattern("**/login"));
    login("admin@example.test");
    assertThrows(BusinessException.class, () -> accounts.deny(venture, " "));
    accounts.deny(venture, "Faltan datos");
    mvc.perform(get("/provider").with(user(providerEmail).roles("PROVIDER")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Faltan datos")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Volver a solicitar"))));
    mvc.perform(post("/provider/reapply").with(user(providerEmail).roles("PROVIDER")).with(csrf()))
        .andExpect(status().isForbidden());
    assertEquals(
        "Faltan datos", db.one("select * from venture where id=?", venture).get("denial_reason"));
    login("admin@example.test");
    assertThrows(BusinessException.class, () -> accounts.approve(venture));
  }

  @Test
  void cancellationUsesSnapshotAndReconcilesIdempotently() throws Exception {
    db.update(
        "update activity set full_refund_minutes=2880,late_refund_percent=35 where id=?", activity);
    UUID id = tx.reserve(instance, 2, "cancel");
    tx.apply(id, remote(id, "APPROVED"), null);
    db.update("update activity set late_refund_percent=99 where id=?", activity);
    cancellations.tourist(id);
    cancellations.tourist(id);
    assertEquals(
        35, db.one("select * from cancellation where reservation_id=?", id).get("refund_percent"));
    assertEquals(2, tourism.committed(instance)); // unknown outcome retains capacity
    assertFalse((Boolean) tx.detail(id).get("can_review"));
    mvc.perform(get("/tourist/reservations/" + id).with(user(touristEmail).roles("TOURIST")))
        .andExpect(status().isOk())
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("Pendiente de conciliación")));
    var result =
        new uy.edu.tic.platform.integration.PaymentClient.Settlement(
            "pay-" + id,
            db.one("select merchant_reference from payment_attempt where reservation_id=?", id)
                .get("merchant_reference")
                .toString(),
            new BigDecimal("2400.00"),
            "UYU",
            "APPROVED",
            35,
            new BigDecimal("840.00"));
    cancellations.finish(id, result);
    cancellations.finish(id, result);
    login(touristEmail);
    cancellations.tourist(id);
    tx.apply(id, remote(id, "APPROVED"), null);
    assertEquals("PAYMENT_CANCELLED", tx.detail(id).get("status"));
    assertEquals(0, tourism.committed(instance));
    assertEquals(
        new BigDecimal("840.00"),
        db.one("select * from cancellation where reservation_id=?", id).get("refunded_amount"));
    assertThrows(BusinessException.class, () -> tx.review(id, 5, "Cancelada"));
  }

  @Test
  void providerCancellationRefundsAllAndClosesInstance() {
    UUID id = tx.reserve(instance, 1, "provider-cancel");
    tx.apply(id, remote(id, "APPROVED"), null);
    login(providerEmail);
    assertEquals(List.of(id), cancellations.provider(activity, instance));
    assertEquals(List.of(id), cancellations.provider(activity, instance));
    assertEquals(
        100, db.one("select * from cancellation where reservation_id=?", id).get("refund_percent"));
    assertThrows(
        BusinessException.class,
        () ->
            tourism.saveInstance(
                activity, instance, LocalDateTime.now(Tourism.ZONE).plusDays(2), 2, false));
    login(touristEmail);
    assertThrows(BusinessException.class, () -> tx.reserve(instance, 1, "closed"));
    cancellations.finish(
        id,
        new uy.edu.tic.platform.integration.PaymentClient.Settlement(
            "pay-" + id,
            db.one("select merchant_reference from payment_attempt where reservation_id=?", id)
                .get("merchant_reference")
                .toString(),
            new BigDecimal("1200.00"),
            "UYU",
            "APPROVED",
            100,
            new BigDecimal("1200.00")));
    assertEquals(0, tourism.committed(instance));
  }

  @Test
  void cancellationRejectsForeignOwnersAndStartedInstances() {
    UUID id = tx.reserve(instance, 1, "ownership");
    login(providerEmail);
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> cancellations.tourist(id));
    login("admin@example.test");
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> cancellations.provider(activity, instance));
    login(touristEmail);
    db.update(
        "update reservation set starts_at_snapshot=? where id=?",
        OffsetDateTime.now().minusSeconds(1),
        id);
    assertThrows(BusinessException.class, () -> cancellations.tourist(id));
    login(providerEmail);
    db.update(
        "update activity_instance set starts_at=?,ends_at=? where id=?",
        OffsetDateTime.now().minusSeconds(1),
        OffsetDateTime.now().plusMinutes(60),
        instance);
    assertThrows(BusinessException.class, () -> cancellations.provider(activity, instance));
  }

  @Test
  void refundBoundaryIsInclusive() {
    Instant start = Instant.parse("2030-01-01T15:00:00Z");
    var r =
        Map.<String, Object>of(
            "starts_at_snapshot", start, "full_refund_minutes", 60, "late_refund_percent", 0);
    assertEquals(100, CancellationTransactions.refundPercent(r, start.minusSeconds(3600)));
    assertEquals(0, CancellationTransactions.refundPercent(r, start.minusSeconds(3599)));
  }

  @Test
  void providerUpgradesPendingRefundAndRejectsStaleSettlement() {
    db.update(
        "update activity set full_refund_minutes=525600,late_refund_percent=25 where id=?",
        activity);
    UUID id = tx.reserve(instance, 1, "upgrade");
    tx.apply(id, remote(id, "APPROVED"), null);
    cancellations.tourist(id);
    login(providerEmail);
    cancellations.provider(activity, instance);
    var stale =
        new uy.edu.tic.platform.integration.PaymentClient.Settlement(
            "pay-" + id,
            db.one("select merchant_reference from payment_attempt where reservation_id=?", id)
                .get("merchant_reference")
                .toString(),
            new BigDecimal("1200.00"),
            "UYU",
            "APPROVED",
            25,
            new BigDecimal("300.00"));
    assertThrows(BusinessException.class, () -> cancellations.finish(id, stale));
    assertEquals(1, tourism.committed(instance));
    cancellations.finish(
        id,
        new uy.edu.tic.platform.integration.PaymentClient.Settlement(
            "pay-" + id,
            db.one("select merchant_reference from payment_attempt where reservation_id=?", id)
                .get("merchant_reference")
                .toString(),
            new BigDecimal("1200.00"),
            "UYU",
            "APPROVED",
            100,
            new BigDecimal("1200.00")));
    assertEquals(0, tourism.committed(instance));
  }

  @Test
  void registrationRequiresMatchingPasswordConfirmationForBothRoles() throws Exception {
    for (String role : List.of("TOURIST", "PROVIDER")) {
      String email = "confirm-" + UUID.randomUUID() + "@example.test";
      var registration = new Registration();
      registration.role = role;
      registration.email = email;
      registration.password = "ConfirmationTest123!";
      assertThrows(BusinessException.class, () -> accounts.register(registration));
      registration.passwordConfirmation = "DifferentTest123!";
      assertThrows(BusinessException.class, () -> accounts.register(registration));
      assertEquals(0, db.count("select count(*) from app_user where email=?", email));
      for (String confirmation : List.of("", "DifferentTest123!", "ConfirmationTest123!")) {
        var request =
            post("/register")
                .with(anonymous())
                .with(csrf())
                .param("role", role)
                .param("firstName", "Confirmación")
                .param("lastName", "Prueba")
                .param("email", email)
                .param("document", "DEMO")
                .param("password", "ConfirmationTest123!")
                .param("passwordConfirmation", confirmation)
                .param("legalType", "PHYSICAL")
                .param("commercialName", "Emprendimiento de prueba")
                .param("contactEmail", email)
                .param("phone", "099123456")
                .param("departmentId", "10")
                .param("localityId", "10")
                .param("locationDescription", "Montevideo")
                .param("description", "Prueba de alta");
        if (confirmation.equals("ConfirmationTest123!")) {
          mvc.perform(request)
              .andExpect(status().is3xxRedirection())
              .andExpect(redirectedUrl("/login"));
          var created = db.one("select * from app_user where email=?", email);
          assertEquals(role, created.get("role"));
          assertNotEquals("ConfirmationTest123!", created.get("password_hash"));
          assertEquals(
              role.equals("PROVIDER") ? 1 : 0,
              db.count("select count(*) from venture where owner_id=?", created.get("id")));
        } else {
          mvc.perform(request)
              .andExpect(status().isOk())
              .andExpect(view().name("register"))
              .andExpect(
                  content()
                      .string(
                          org.hamcrest.Matchers.containsString("name=\"passwordConfirmation\"")))
              .andExpect(
                  content()
                      .string(
                          org.hamcrest.Matchers.not(
                              org.hamcrest.Matchers.containsString("ConfirmationTest123!"))))
              .andExpect(
                  content()
                      .string(
                          org.hamcrest.Matchers.not(
                              org.hamcrest.Matchers.containsString("DifferentTest123!"))));
          assertEquals(0, db.count("select count(*) from app_user where email=?", email));
        }
      }
    }
  }

  @Test
  void invalidRegistrationFieldsHaveInlineErrorsAndDoNotCreateAccounts() throws Exception {
    String email = "validation-" + UUID.randomUUID() + "@example.test";
    mvc.perform(
            post("/register")
                .with(anonymous())
                .with(csrf())
                .param("role", "PROVIDER")
                .param("legalType", "LEGAL")
                .param("firstName", "Ana123")
                .param("lastName", "Pérez")
                .param("email", email)
                .param("password", "TestOnly123!")
                .param("passwordConfirmation", "Different123!")
                .param("commercialName", "Prueba")
                .param("contactEmail", "sin-arroba")
                .param("phone", "099abc456")
                .param("departmentId", "10")
                .param("localityId", "10")
                .param("locationDescription", "Montevideo")
                .param("description", "Prueba")
                .param("taxId", "123")
                .param("legalName", ""))
        .andExpect(status().isOk())
        .andExpect(view().name("register"))
        .andExpect(
            model()
                .attributeHasFieldErrors(
                    "form",
                    "firstName",
                    "passwordConfirmation",
                    "contactEmail",
                    "phone",
                    "taxId",
                    "legalName"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("field-error")))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.containsString("Las contraseñas no coinciden.")));
    assertEquals(0, db.count("select count(*) from app_user where email=?", email));
  }

  @Test
  void legalFieldsAreRequiredOnlyForLegalPersonsAndIgnoredForPhysicalPersons() throws Exception {
    String email = "physical-" + UUID.randomUUID() + "@example.test";
    var r = new Registration();
    r.firstName = "María-José";
    r.lastName = "O’Neill";
    r.email = email;
    r.document = "ABC-123";
    r.password = "TestOnly123!";
    r.passwordConfirmation = r.password;
    r.role = "PROVIDER";
    r.legalType = "PHYSICAL";
    r.commercialName = "Prueba";
    r.contactEmail = email;
    r.phone = "+598 (99) 123-456";
    r.departmentId = 10L;
    r.localityId = 10L;
    r.locationDescription = "Montevideo";
    r.description = "Prueba";
    r.legalName = "No corresponde";
    r.taxId = "invalid";
    long owner = accounts.register(r);
    var v = db.one("select * from venture where owner_id=?", owner);
    assertNull(v.get("legal_name"));
    assertNull(v.get("tax_id"));
    r.email = "legal-" + UUID.randomUUID() + "@example.test";
    r.legalType = "LEGAL";
    r.legalName = "";
    assertThrows(BusinessException.class, () -> accounts.register(r));
    assertEquals(0, db.count("select count(*) from app_user where email=?", r.email));
    r.legalName = "Razón social de prueba";
    r.taxId = "123456789012";
    assertNotNull(accounts.register(r));
    mvc.perform(get("/register?role=PROVIDER").with(anonymous()))
        .andExpect(status().isOk())
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("data-legal-fields hidden")));
  }

  @Test
  void workspaceSummariesAreScopedAndIndependentOfHistoryPagination() throws Exception {
    UUID confirmed = tx.reserve(instance, 1, "dashboard-confirmed");
    tx.apply(confirmed, remote(confirmed, "APPROVED"), null);
    tx.reserve(instance, 1, "dashboard-pending");
    mvc.perform(get("/tourist/reservations?page=1").with(user(touristEmail).roles("TOURIST")))
        .andExpect(status().isOk())
        .andExpect(model().attribute("upcomingCount", 1L))
        .andExpect(model().attribute("pendingCount", 1L))
        .andExpect(model().attribute("reviewCount", 0L))
        .andExpect(
            model().attribute("nextReservation", org.hamcrest.Matchers.hasEntry("id", confirmed)));
    mvc.perform(get("/provider").with(user(providerEmail).roles("PROVIDER")))
        .andExpect(status().isOk())
        .andExpect(model().attribute("publishedCount", 1L))
        .andExpect(model().attribute("scheduledCount", 1L))
        .andExpect(model().attribute("guestCount", 1L));
    login(providerEmail);
    long venture = ((Number) accounts.venture().get("id")).longValue();
    db.update("update venture set verification_status='PENDING' where id=?", venture);
    mvc.perform(get("/provider").with(user(providerEmail).roles("PROVIDER")))
        .andExpect(status().isOk())
        .andExpect(view().name("provider-status"))
        .andExpect(model().attributeDoesNotExist("agenda", "guestCount", "activities"));
  }

  @Test
  void reservationCreationNeverAuthorizesAndExplicitPaymentIsIdempotent() throws Exception {
    UUID id = tx.reserve(instance, 1, "explicit-pay");
    assertEquals(0, db.count("select count(*) from payment_attempt where reservation_id=?", id));
    org.mockito.Mockito.verifyNoInteractions(client);
    payments.pay(id, methodId, "click-1");
    assertEquals("CONFIRMED", tx.detail(id).get("status"));
    payments.pay(id, methodId, "click-1");
    assertThrows(BusinessException.class, () -> payments.pay(id, methodId, "click-2"));
    org.mockito.Mockito.verify(client, org.mockito.Mockito.times(1))
        .authorize(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
    assertEquals(1, db.count("select count(*) from payment_attempt where reservation_id=?", id));
  }

  @Test
  void rejectedAttemptCanBeRetriedWithAnotherMethodWithoutLosingHistory() {
    UUID id = tx.reserve(instance, 1, "retry");
    payments.pay(id, declinedMethod, "first");
    assertEquals("PENDING_PAYMENT", tx.detail(id).get("status"));
    assertEquals("REJECTED", tx.detail(id).get("payment_status"));
    assertEquals(1, tourism.committed(instance));
    assertThrows(BusinessException.class, () -> payments.pay(id, declinedMethod, "same-card"));
    payments.pay(id, methodId, "second");
    assertEquals("CONFIRMED", tx.detail(id).get("status"));
    assertEquals(2, db.count("select count(*) from payment_attempt where reservation_id=?", id));
    assertEquals(
        1,
        db.count(
            "select count(*) from payment_attempt where reservation_id=? and status='APPROVED'",
            id));
    assertEquals(
        1,
        db.count(
            "select count(*) from payment_attempt where reservation_id=? and status='REJECTED'",
            id));
  }

  @Test
  void uncertainAttemptBlocksAnotherChargeAndReconcilesLateApproval() {
    UUID id = tx.reserve(instance, 1, "lost-response");
    org.mockito.Mockito.when(
            client.authorize(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("Timeout"));
    payments.pay(id, methodId, "click");
    assertEquals("PAYMENT_UNCERTAIN", tx.detail(id).get("status"));
    assertEquals(1, tourism.committed(instance));
    assertThrows(BusinessException.class, () -> payments.pay(id, declinedMethod, "other"));
    var remote = remote(id, "APPROVED");
    org.mockito.Mockito.when(client.byReference(remote.merchantReference())).thenReturn(remote);
    payments.synchronize(id);
    assertEquals("CONFIRMED", tx.detail(id).get("status"));
    assertEquals(1, db.count("select count(*) from payment_attempt where reservation_id=?", id));
  }

  @Test
  void foreignAndDisabledMethodsCannotStartAttempts() {
    UUID id = tx.reserve(instance, 1, "protected");
    UUID foreign = UUID.randomUUID();
    db.update(
        "insert into"
            + " payment_method(id,tourist_id,external_token,brand,last_four,holder,expiry_month,expiry_year)"
            + " values(?,?,?,'Visa de prueba','4242','Otra persona',12,2099)",
        foreign,
        provider,
        UUID.randomUUID());
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> payments.pay(id, foreign, "foreign"));
    methods.deactivate(methodId);
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> payments.pay(id, methodId, "disabled"));
    assertEquals(0, db.count("select count(*) from payment_attempt where reservation_id=?", id));
    org.mockito.Mockito.verifyNoInteractions(client);
  }

  @Test
  void unstartedReservationExpiresAndCancellationDoesNotCreateAnyCharge() {
    UUID id = tx.reserve(instance, 1, "expire");
    db.update(
        "update reservation set expires_at=? where id=?", OffsetDateTime.now().minusSeconds(1), id);
    tx.expire(id);
    assertEquals("EXPIRED", tx.detail(id).get("status"));
    assertEquals(0, tourism.committed(instance));
    assertThrows(BusinessException.class, () -> payments.pay(id, methodId, "late"));
    UUID unpaid = tx.reserve(instance, 1, "unpaid-cancel");
    cancellations.tourist(unpaid);
    payments.settleCancellation(unpaid);
    assertEquals("PAYMENT_CANCELLED", tx.detail(unpaid).get("status"));
    assertEquals(
        new BigDecimal("0.00"),
        db.one("select * from cancellation where reservation_id=?", unpaid).get("refunded_amount"));
    org.mockito.Mockito.verifyNoInteractions(client);
  }

  @Test
  void methodRegistrationStoresOnlyTokenMetadataAndNeverCachesCardDataInSession() throws Exception {
    var c = new uy.edu.tic.platform.integration.PaymentClient.Card();
    c.holder = "Titular de prueba";
    c.number = "9900000000004242";
    c.cvv = "731";
    c.expiryMonth = 12;
    c.expiryYear = 2099;
    UUID key = UUID.randomUUID(), token = UUID.randomUUID();
    org.mockito.Mockito.when(
            client.tokenize(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(
            call -> {
              assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              return new uy.edu.tic.platform.integration.PaymentClient.RemoteMethod(
                  token, "Visa de prueba", "4242", c.holder, 12, 2099);
            });
    methods.register(key, c);
    var row = db.one("select * from payment_method where id=?", key);
    assertEquals(token, row.get("external_token"));
    assertFalse(row.values().stream().anyMatch(v -> c.number.equals(v) || c.cvv.equals(v)));
    String safe = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(c);
    assertFalse(safe.contains(c.number));
    assertFalse(safe.contains(c.cvv));
    mvc.perform(get("/tourist/payment-methods").with(user(touristEmail).roles("TOURIST")))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(c.number))))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("value=\"731\""))));
    var request =
        mvc.perform(
                post("/tourist/payment-methods")
                    .with(anonymous())
                    .with(csrf())
                    .param("number", c.number)
                    .param("cvv", c.cvv))
            .andExpect(status().is3xxRedirection())
            .andReturn()
            .getRequest();
    assertTrue(
        request.getSession(false) == null
            || request.getSession(false).getAttribute("SPRING_SECURITY_SAVED_REQUEST") == null);
  }

  @Test
  void payingRequiresCsrfAndTheAuthenticatedReservationOwner() throws Exception {
    UUID id = tx.reserve(instance, 1, "csrf");
    mvc.perform(
            post("/api/v1/reservations/" + id + "/pay")
                .with(user(touristEmail).roles("TOURIST"))
                .header("Idempotency-Key", "pay")
                .contentType("application/json")
                .content("{\"methodId\":\"" + methodId + "\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/reservations/" + id + "/pay")
                .with(user(providerEmail).roles("PROVIDER"))
                .with(csrf())
                .header("Idempotency-Key", "pay")
                .contentType("application/json")
                .content("{\"methodId\":\"" + methodId + "\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/reservations/" + id + "/pay")
                .with(user(touristEmail).roles("TOURIST"))
                .with(csrf())
                .header("Idempotency-Key", "tampered")
                .contentType("application/json")
                .content("{\"methodId\":\"" + methodId + "\",\"action\":\"APPROVE\"}"))
        .andExpect(status().isBadRequest());
    assertEquals(0, db.count("select count(*) from payment_attempt where reservation_id=?", id));
  }

  @Test
  void concurrentPaymentIntentsCannotCreateTwoAuthorizations() throws Exception {
    UUID id = tx.reserve(instance, 1, "concurrent-pay");
    var pool = Executors.newFixedThreadPool(2);
    var go = new CountDownLatch(1);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int n = 0; n < 2; n++) {
        String key = "pay-" + n;
        results.add(
            pool.submit(
                () -> {
                  login(touristEmail);
                  go.await();
                  try {
                    attempts.begin(id, methodId, key);
                    return true;
                  } catch (BusinessException e) {
                    return false;
                  } finally {
                    SecurityContextHolder.clearContext();
                  }
                }));
      }
      go.countDown();
      int success = 0;
      for (var f : results) if (f.get(10, TimeUnit.SECONDS)) success++;
      assertEquals(1, success);
      assertEquals(1, db.count("select count(*) from payment_attempt where reservation_id=?", id));
      payments.synchronize(id);
      assertEquals("CONFIRMED", tx.detail(id).get("status"));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void lateApprovalAfterHoldExpiryKeepsItsReservedCapacityAndConfirms() {
    UUID id = tx.reserve(instance, 1, "late-canonical");
    attempts.begin(id, methodId, "intent");
    var expiry = OffsetDateTime.now().minusSeconds(1);
    db.update("update reservation set expires_at=? where id=?", expiry, id);
    db.update("update payment_attempt set expires_at=? where reservation_id=?", expiry, id);
    tx.expire(id);
    assertEquals(1, tourism.committed(instance));
    tx.apply(id, remote(id, "APPROVED"), null);
    assertEquals("CONFIRMED", tx.detail(id).get("status"));
    assertEquals(1, tourism.committed(instance));
  }
}
