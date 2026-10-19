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
    t.document = "ABC";
    tourist = accounts.register(t);
    Registration p = new Registration();
    p.firstName = "Test";
    p.lastName = "Prestador";
    p.email = providerEmail;
    p.password = "TestOnly123!";
    p.document = "XYZ";
    p.role = "PROVIDER";
    p.commercialName = "Test " + tag;
    p.legalType = "PHYSICAL";
    p.contactEmail = providerEmail;
    p.phone = "1234";
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
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
  }

  RemotePayment remote(UUID id, String status) {
    var r = db.one("select * from reservation where id=?", id);
    var expiry = BookingRules.instant(r.get("expires_at"));
    return new RemotePayment(
        "pay-" + id,
        id.toString(),
        (BigDecimal) r.get("total_amount"),
        "UYU",
        status,
        expiry,
        "APPROVED".equals(status) ? expiry.minusSeconds(5) : null,
        "http://localhost/checkout/" + id);
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
    mvc.perform(get("/activities/" + activity))
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
  void unknownPaymentRetainsCapacityAndRejectReleases() {
    UUID id = tx.reserve(instance, 2, "x");
    tx.uncertain(id);
    assertEquals(2, tourism.committed(instance));
    tx.apply(id, remote(id, "REJECTED"), null);
    assertEquals(0, tourism.committed(instance));
    assertNotNull(tx.reserve(instance, 2, "y"));
  }

  @Test
  void lateApprovalRetainsPriceAndCannotRegress() {
    UUID id = tx.reserve(instance, 1, "x");
    db.update("update activity set unit_price=9000 where id=?", activity);
    tx.uncertain(id);
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
            "/",
            "/login",
            "/register",
            "/register?role=PROVIDER",
            "/activities/" + activity,
            "/api/v1/ventures",
            "/api/v1/activities/search?q=Test",
            "/api/v1/activities/" + activity)) {
      mvc.perform(get(path)).andExpect(status().isOk());
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
                    p.checkoutUrl()),
                null));
    assertEquals("PENDING_PAYMENT", tx.detail(id).get("status"));
  }
}
