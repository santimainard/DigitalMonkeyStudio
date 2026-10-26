package uy.edu.tic.payments;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentStore {
  private final JdbcTemplate db;
  private final Clock clock;
  private final String callback;
  // Deliberately non-bank numbers. Only this closed allowlist can be tokenized.
  private static final Map<String, String> FIXTURES =
      Map.of(
          "9900000000004242",
          "APPROVE",
          "9900000000000005",
          "DECLINE",
          "9900000000000003",
          "DISABLED");

  public PaymentStore(
      JdbcTemplate db, Clock clock, @Value("${simulator.callback-url}") String callback) {
    this.db = db;
    this.clock = clock;
    this.callback = callback;
  }

  public static class Card {
    public String registrationReference, customerReference, holder;

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

  public record Method(
      UUID token, String brand, String lastFour, String holder, int expiryMonth, int expiryYear) {}

  public record Authorization(
      String merchantReference,
      String reservationReference,
      String customerReference,
      UUID methodToken,
      BigDecimal amount,
      String currency,
      Instant expiresAt,
      String callbackUrl) {}

  public record Payment(
      String paymentId,
      String merchantReference,
      BigDecimal amount,
      String currency,
      String status,
      Instant expiresAt,
      Instant approvedAt,
      String declineReason) {}

  public record Cancel(
      String reservationReference,
      String customerReference,
      BigDecimal amount,
      String currency,
      Instant expiresAt,
      int refundPercent) {}

  public record Settlement(
      String paymentId,
      String merchantReference,
      BigDecimal amount,
      String currency,
      String status,
      int refundPercent,
      BigDecimal refundedAmount) {}

  private Instant instant(Object t) {
    if (t == null) return null;
    if (t instanceof OffsetDateTime d) return d.toInstant();
    if (t instanceof java.sql.Timestamp d) return d.toInstant();
    return Instant.parse(t.toString());
  }

  private Map<String, Object> one(String where, Object id) {
    var list = db.queryForList("select * from simulated_payment where " + where, id);
    if (list.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return list.get(0);
  }

  private Payment convert(Map<String, Object> p) {
    return new Payment(
        p.get("id").toString(),
        p.get("merchant_reference").toString(),
        (BigDecimal) p.get("amount"),
        p.get("currency").toString(),
        p.get("status").toString(),
        instant(p.get("expires_at")),
        instant(p.get("approved_at")),
        (String) p.get("decline_reason"));
  }

  private Method method(Map<String, Object> m) {
    return new Method(
        (UUID) m.get("token"),
        (String) m.get("brand"),
        (String) m.get("last_four"),
        (String) m.get("holder"),
        (Integer) m.get("expiry_month"),
        (Integer) m.get("expiry_year"));
  }

  private boolean reference(String s) {
    return s != null && s.matches("[a-zA-Z0-9-]{1,100}");
  }

  private void serial() {
    db.queryForList("select id from simulator_setting where id=1 for update");
  }

  @Transactional
  public Method tokenize(Card c, String key) {
    if (c == null
        || !reference(c.registrationReference)
        || !c.registrationReference.equals(key)
        || !reference(c.customerReference)
        || c.holder == null
        || !c.holder.strip().matches("[\\p{L}\\p{M}][\\p{L}\\p{M} .'’-]{0,99}")
        || c.number == null
        || !FIXTURES.containsKey(c.number)
        || c.cvv == null
        || !c.cvv.matches("[0-9]{3}")
        || c.expiryMonth < 1
        || c.expiryMonth > 12
        || c.expiryYear < 2000
        || c.expiryYear > 2100
        || YearMonth.of(c.expiryYear, c.expiryMonth).isBefore(YearMonth.now(clock)))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datos ficticios inválidos");
    serial();
    String rule = FIXTURES.get(c.number);
    String brand = "DECLINE".equals(rule) ? "Mastercard de prueba" : "Visa de prueba";
    var old = db.queryForList("select * from simulated_method where registration_reference=?", key);
    if (!old.isEmpty()) {
      var m = old.get(0);
      if (!m.get("customer_reference").equals(c.customerReference)
          || !m.get("rule_code").equals(rule)
          || !m.get("holder").equals(c.holder.strip())
          || (Integer) m.get("expiry_month") != c.expiryMonth
          || (Integer) m.get("expiry_year") != c.expiryYear)
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Referencia reutilizada");
      return method(m);
    }
    UUID token = UUID.randomUUID();
    db.update(
        "insert into"
            + " simulated_method(token,registration_reference,customer_reference,brand,last_four,holder,expiry_month,expiry_year,rule_code)"
            + " values(?,?,?,?,?,?,?,?,?)",
        token,
        key,
        c.customerReference,
        brand,
        c.number.substring(12),
        c.holder.strip(),
        c.expiryMonth,
        c.expiryYear,
        rule);
    return method(db.queryForMap("select * from simulated_method where token=?", token));
  }

  private void validate(
      String ref, String reservation, BigDecimal amount, String currency, Instant expires) {
    if (!reference(ref)
        || !reference(reservation)
        || amount == null
        || amount.signum() <= 0
        || amount.scale() > 2
        || !"UYU".equals(currency)
        || expires == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Operación inválida");
  }

  private void same(
      Map<String, Object> p,
      String reservation,
      BigDecimal amount,
      String currency,
      Instant expires) {
    if (!p.get("reservation_reference").equals(reservation)
        || ((BigDecimal) p.get("amount")).compareTo(amount) != 0
        || !p.get("currency").equals(currency)
        || !instant(p.get("expires_at")).equals(expires))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Referencia reutilizada");
  }

  @Transactional
  public Payment authorize(Authorization a, String key) {
    if (a == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    validate(
        a.merchantReference(), a.reservationReference(), a.amount(), a.currency(), a.expiresAt());
    if (!a.merchantReference().equals(key)
        || !reference(a.customerReference())
        || !callback.equals(a.callbackUrl()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Operación inválida");
    serial();
    var old = db.queryForList("select * from simulated_payment where merchant_reference=?", key);
    if (!old.isEmpty()) {
      var p = old.get(0);
      same(p, a.reservationReference(), a.amount(), a.currency(), a.expiresAt());
      if (p.get("requested_method_token") != null
              && !p.get("requested_method_token").equals(a.methodToken())
          || p.get("customer_reference") != null
              && !p.get("customer_reference").equals(a.customerReference()))
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Referencia reutilizada");
      return get((UUID) p.get("id"));
    }
    String status = "REJECTED", reason = null;
    Instant approved = null;
    var methods =
        a.methodToken() == null
            ? List.<Map<String, Object>>of()
            : db.queryForList(
                "select * from simulated_method where token=? and customer_reference=?",
                a.methodToken(),
                a.customerReference());
    Instant now = clock.instant();
    if (!now.isBefore(a.expiresAt())) {
      status = "EXPIRED";
      reason = "HOLD_EXPIRED";
    } else if (methods.isEmpty()) reason = "METHOD_NOT_FOUND";
    else {
      var m = methods.get(0);
      if (YearMonth.of((Integer) m.get("expiry_year"), (Integer) m.get("expiry_month"))
          .isBefore(YearMonth.now(clock))) reason = "CARD_EXPIRED";
      else if (!"APPROVE".equals(m.get("rule_code")))
        reason = "DISABLED".equals(m.get("rule_code")) ? "METHOD_DISABLED" : "CARD_DECLINED";
      else if (db.queryForObject(
              "select count(*) from simulated_payment where reservation_reference=? and"
                  + " status='APPROVED'",
              Long.class,
              a.reservationReference())
          > 0) reason = "ALREADY_PAID";
      else {
        status = "APPROVED";
        approved = now;
      }
    }
    UUID id = UUID.randomUUID();
    db.update(
        "insert into"
            + " simulated_payment(id,merchant_reference,reservation_reference,customer_reference,method_token,amount,currency,status,expires_at,approved_at,callback_url,event_id,decline_reason)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        key,
        a.reservationReference(),
        a.customerReference(),
        methods.isEmpty() ? null : a.methodToken(),
        a.amount(),
        a.currency(),
        status,
        a.expiresAt().atOffset(ZoneOffset.UTC),
        approved == null ? null : approved.atOffset(ZoneOffset.UTC),
        callback,
        UUID.randomUUID(),
        reason);
    db.update(
        "update simulated_payment set requested_method_token=? where id=?", a.methodToken(), id);
    return get(id);
  }

  @Transactional
  public Payment get(UUID id) {
    db.update(
        "update simulated_payment set status='EXPIRED' where id=? and status='PENDING' and"
            + " expires_at<=?",
        id,
        clock.instant().atOffset(ZoneOffset.UTC));
    return convert(one("id=?", id));
  }

  @Transactional
  public Payment byReference(String ref) {
    return get((UUID) one("merchant_reference=?", ref).get("id"));
  }

  /** A missing operation is sealed as CANCELLED before a delayed authorization can arrive. */
  @Transactional
  public Settlement cancelReference(String ref, Cancel c) {
    if (c == null
        || !reference(c.customerReference())
        || c.refundPercent() < 0
        || c.refundPercent() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    validate(ref, c.reservationReference(), c.amount(), c.currency(), c.expiresAt());
    serial();
    var old = db.queryForList("select * from simulated_payment where merchant_reference=?", ref);
    UUID id;
    if (old.isEmpty()) {
      id = UUID.randomUUID();
      db.update(
          "insert into"
              + " simulated_payment(id,merchant_reference,reservation_reference,customer_reference,amount,currency,status,expires_at,callback_url,event_id)"
              + " values(?,?,?,?,?,?,'CANCELLED',?,?,?)",
          id,
          ref,
          c.reservationReference(),
          c.customerReference(),
          c.amount(),
          c.currency(),
          c.expiresAt().atOffset(ZoneOffset.UTC),
          callback,
          UUID.randomUUID());
    } else {
      var p = old.get(0);
      same(p, c.reservationReference(), c.amount(), c.currency(), c.expiresAt());
      if (p.get("customer_reference") != null
          && !p.get("customer_reference").equals(c.customerReference()))
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Cliente distinto");
      id = (UUID) p.get("id");
    }
    var p = one("id=? for update", id);
    int percent =
        p.get("cancellation_percent") == null
            ? c.refundPercent()
            : Math.max((Integer) p.get("cancellation_percent"), c.refundPercent());
    if (p.get("cancellation_percent") != null
        && percent != (Integer) p.get("cancellation_percent")
        && percent != 100)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Solo se permite ampliar al reembolso total");
    BigDecimal refund =
        "APPROVED".equals(p.get("status"))
            ? ((BigDecimal) p.get("amount"))
                .multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
            : new BigDecimal("0.00");
    db.update(
        "update simulated_payment set cancellation_percent=?,refunded_amount=?,status=? where id=?",
        percent,
        refund,
        "PENDING".equals(p.get("status")) ? "CANCELLED" : p.get("status"),
        id);
    return new Settlement(
        id.toString(),
        ref,
        (BigDecimal) p.get("amount"),
        p.get("currency").toString(),
        "PENDING".equals(p.get("status")) ? "CANCELLED" : p.get("status").toString(),
        percent,
        refund);
  }

  public Map<String, Object> event(UUID id) {
    var p = one("id=?", id);
    return Map.of(
        "eventId",
        p.get("event_id").toString(),
        "paymentId",
        id.toString(),
        "merchantReference",
        p.get("merchant_reference"),
        "status",
        p.get("status"));
  }

  public String mode() {
    return db.queryForObject("select mode from simulator_setting where id=1", String.class);
  }

  public void mode(String mode) {
    if (mode == null || !Set.of("NORMAL", "UNAVAILABLE", "TIMEOUT", "LOST_RESPONSE").contains(mode))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    db.update("update simulator_setting set mode=? where id=1", mode);
  }
}
