package uy.edu.tic.platform.controller;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import uy.edu.tic.platform.domain.*;
import uy.edu.tic.platform.dto.Forms.*;
import uy.edu.tic.platform.repository.Database;
import uy.edu.tic.platform.service.*;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
  private final Database db;
  private final Tourism tourism;
  private final Accounts accounts;
  private final ReservationTransactions reservations;
  private final Payments payments;
  private final CancellationTransactions cancellations;
  private final String webhook;

  public ApiController(
      Database db,
      Tourism tourism,
      Accounts accounts,
      ReservationTransactions reservations,
      Payments payments,
      CancellationTransactions cancellations,
      @Value("${payment.webhook-token}") String webhook) {
    this.db = db;
    this.tourism = tourism;
    this.accounts = accounts;
    this.reservations = reservations;
    this.payments = payments;
    this.cancellations = cancellations;
    this.webhook = webhook;
  }

  @GetMapping("/ventures")
  Object ventures(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) Long departmentId,
      @RequestParam(required = false) Long localityId) {
    Tourism.page(page);
    Tourism.size(size);
    if (departmentId != null && localityId != null) accounts.location(departmentId, localityId);
    String where = " where 1=1";
    List<Object> args = new ArrayList<>();
    if (departmentId != null) {
      where += " and v.department_id=?";
      args.add(departmentId);
    }
    if (localityId != null) {
      where += " and v.locality_id=?";
      args.add(localityId);
    }
    long total = db.count("select count(*) from venture v" + where, args.toArray());
    args.add(size);
    args.add((long) page * size);
    return camel(
        Tourism.pagination(
            db.list(
                "select v.id,v.commercial_name,v.description,case when v.denial_reason is not null"
                    + " then 'DENIED' else v.verification_status end"
                    + " verification_status,v.contact_email,v.phone,v.department_id,d.name"
                    + " department_name,v.locality_id,l.name locality_name,v.location_description"
                    + " from venture v join department d on d.id=v.department_id join locality l on"
                    + " l.id=v.locality_id"
                    + where
                    + " order by v.id limit ? offset ?",
                args.toArray()),
            page,
            size,
            total));
  }

  @GetMapping("/activities/search")
  Object search(
      @RequestParam String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    q = Accounts.text(q, 100, "palabra clave");
    return camel(tourism.search(q, null, null, null, null, null, null, page, size));
  }

  @GetMapping("/activities/by-day")
  Object day(
      @RequestParam LocalDate date,
      @RequestParam(required = false) Long departmentId,
      @RequestParam(required = false) Long localityId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    Tourism.page(page);
    Tourism.size(size);
    if (departmentId == null && localityId == null)
      throw new BusinessException("INVALID_INPUT", "Indicá departamento o localidad.");
    if (departmentId != null && localityId != null) accounts.location(departmentId, localityId);
    String from =
        " from activity_instance i join activity a on a.id=i.activity_id join venture v on"
            + " v.id=a.venture_id where i.active=true and a.is_published=true and"
            + " v.verification_status='VERIFIED' and i.starts_at>=? and i.starts_at<?";
    List<Object> args =
        new ArrayList<>(
            List.of(
                date.atStartOfDay(Tourism.ZONE).toOffsetDateTime(),
                date.plusDays(1).atStartOfDay(Tourism.ZONE).toOffsetDateTime()));
    if (departmentId != null) {
      from += " and a.department_id=?";
      args.add(departmentId);
    }
    if (localityId != null) {
      from += " and a.locality_id=?";
      args.add(localityId);
    }
    long total = db.count("select count(*)" + from, args.toArray());
    args.add(size);
    args.add((long) page * size);
    var list =
        db.list(
            "select i.*,a.name"
                + " activity_name,a.department_id,a.locality_id,a.location_description,a.booking_lead_minutes"
                + from
                + " order by i.starts_at,i.id limit ? offset ?",
            args.toArray());
    for (var i : list) tourism.decorateInstance(i, (Integer) i.get("booking_lead_minutes"));
    return camel(Tourism.pagination(list, page, size, total));
  }

  @GetMapping("/activities/top-rated")
  Object top(@RequestParam String month, @RequestParam long departmentId) {
    YearMonth ym = YearMonth.parse(month);
    var start = ym.atDay(1).atStartOfDay(Tourism.ZONE).toOffsetDateTime();
    var end = ym.plusMonths(1).atDay(1).atStartOfDay(Tourism.ZONE).toOffsetDateTime();
    var items =
        db.list(
            "select a.id activity_id,a.name,a.department_id,avg(cast(r.rating as numeric))"
                + " monthly_average,count(*) monthly_review_count from review r join reservation b"
                + " on b.id=r.reservation_id join activity_instance i on i.id=b.instance_id join"
                + " activity a on a.id=i.activity_id join venture v on v.id=a.venture_id where"
                + " a.is_published=true and v.verification_status='VERIFIED' and a.department_id=?"
                + " and r.created_at>=? and r.created_at<? group by a.id,a.name,a.department_id"
                + " order by avg(cast(r.rating as numeric)) desc,count(*) desc,a.id limit 3",
            departmentId,
            start,
            end);
    return camel(Map.of("items", items, "month", month, "departmentId", departmentId));
  }

  @GetMapping("/activities/{id}")
  Object detail(@PathVariable long id) {
    var a = tourism.activity(id, true);
    a.remove("owner_id");
    tourism.addRating(a);
    a.put("categories", tourism.categories(id));
    a.put("equipment", tourism.equipment(id));
    a.put("instances", tourism.instances(id, true));
    return camel(a);
  }

  @PatchMapping("/admin/ventures/{id}/verification")
  Object approve(@PathVariable long id, @RequestBody Map<String, String> body) {
    if ("DENIED".equals(body.get("status"))) {
      accounts.deny(id, body.get("reason"));
      return Map.of("id", id, "status", "DENIED");
    }
    if (!"VERIFIED".equals(body.get("status")))
      throw new BusinessException("INVALID_INPUT", "Solo se admite VERIFIED o DENIED.");
    accounts.approve(id);
    var v = db.one("select * from venture where id=?", id);
    return Map.of(
        "id",
        id,
        "status",
        v.get("denial_reason") != null ? "DENIED" : v.get("verification_status"));
  }

  @PostMapping("/reservations")
  ResponseEntity<?> reserve(
      @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ReservationRequest f) {
    var id = reservations.reserve(f.instanceId(), f.participantCount(), key);
    return ResponseEntity.status(201).body(camel(reservations.detail(id)));
  }

  @GetMapping("/reservations/{id}")
  Object reservation(@PathVariable UUID id) {
    return camel(reservations.detail(id));
  }

  public record PayRequest(UUID methodId) {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void unsupported(String name, Object value) {
      throw new IllegalArgumentException("Parámetro no permitido para pagar.");
    }
  }

  @PostMapping("/reservations/{id}/pay")
  Object pay(
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody PayRequest f) {
    if (f.methodId() == null)
      throw new BusinessException("INVALID_INPUT", "Elegí un método de pago.");
    payments.pay(id, f.methodId(), key);
    return camel(reservations.detail(id));
  }

  @PostMapping("/reservations/{id}/cancellation")
  Object cancel(@PathVariable UUID id) {
    cancellations.tourist(id);
    payments.settleCancellation(id);
    return camel(reservations.detail(id));
  }

  @PostMapping("/reservations/{id}/reviews")
  ResponseEntity<?> review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest f) {
    reservations.review(id, f.rating(), f.comment());
    return ResponseEntity.status(201).body(Map.of("reservationId", id, "rating", f.rating()));
  }

  @PostMapping("/payment-events")
  ResponseEntity<?> event(
      @RequestHeader(value = "X-Webhook-Token", defaultValue = "") String token,
      @Valid @RequestBody PaymentEvent event) {
    if (!MessageDigest.isEqual(
        token.getBytes(StandardCharsets.UTF_8), webhook.getBytes(StandardCharsets.UTF_8)))
      return ResponseEntity.status(401).body(Map.of("code", "UNAUTHENTICATED"));
    payments.event(event.eventId(), event.paymentId(), event.merchantReference());
    return ResponseEntity.ok(Map.of("received", true));
  }

  public static Object camel(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      map.forEach(
          (key, v) -> {
            String s = key.toString();
            var name = new StringBuilder();
            boolean up = false;
            for (char c : s.toCharArray()) {
              if (c == '_') {
                up = true;
              } else {
                name.append(up ? Character.toUpperCase(c) : c);
                up = false;
              }
            }
            String n = name.toString();
            if (n.equals("available")) n = "availableCapacity";
            if (n.equals("deadline")) n = "bookingDeadline";
            if (n.equals("commercialName") && map.containsKey("venture_id")) n = "ventureName";
            out.put(n, camel(v));
          });
      return out;
    }
    if (value instanceof List<?> list) return list.stream().map(ApiController::camel).toList();
    return value;
  }
}
