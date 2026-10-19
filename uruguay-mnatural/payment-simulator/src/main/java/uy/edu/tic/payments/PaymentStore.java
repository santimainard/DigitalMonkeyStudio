package uy.edu.tic.payments;

import java.math.BigDecimal;
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
  private final String publicUrl, callback;

  public PaymentStore(
      JdbcTemplate db,
      Clock clock,
      @Value("${simulator.public-url}") String publicUrl,
      @Value("${simulator.callback-url}") String callback) {
    this.db = db;
    this.clock = clock;
    this.publicUrl = publicUrl;
    this.callback = callback;
  }

  public record Create(
      String merchantReference,
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
      String checkoutUrl) {}

  private Instant instant(Object t) {
    if (t == null) return null;
    if (t instanceof OffsetDateTime d) return d.toInstant();
    if (t instanceof java.sql.Timestamp d) return d.toInstant();
    return Instant.parse(t.toString());
  }

  private Payment convert(Map<String, Object> p) {
    return new Payment(
        p.get("id").toString(),
        (String) p.get("merchant_reference"),
        (BigDecimal) p.get("amount"),
        (String) p.get("currency"),
        (String) p.get("status"),
        instant(p.get("expires_at")),
        instant(p.get("approved_at")),
        publicUrl + "/checkout/" + p.get("id"));
  }

  private Map<String, Object> one(String where, Object id) {
    var rows = db.queryForList("select * from simulated_payment where " + where, id);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return rows.get(0);
  }

  public String mode() {
    return db.queryForObject("select mode from simulator_setting where id=1", String.class);
  }

  public void mode(String mode) {
    if (!Set.of("NORMAL", "UNAVAILABLE", "TIMEOUT", "LOST_RESPONSE").contains(mode))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    db.update("update simulator_setting set mode=? where id=1", mode);
  }

  @Transactional
  public Payment create(Create c, String key) {
    if (c == null
        || c.merchantReference() == null
        || !c.merchantReference().matches("[a-zA-Z0-9-]{1,100}")
        || !c.merchantReference().equals(key)
        || c.amount() == null
        || c.amount().signum() <= 0
        || c.amount().scale() > 2
        || !"UYU".equals(c.currency())
        || c.expiresAt() == null
        || !callback.equals(c.callbackUrl()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datos inválidos");
    // Serialize create operations; retries must not create two external charges.
    db.queryForList("select id from simulator_setting where id=1 for update");
    var old =
        db.queryForList(
            "select * from simulated_payment where merchant_reference=?", c.merchantReference());
    if (!old.isEmpty()) {
      var p = convert(old.get(0));
      if (p.amount().compareTo(c.amount()) != 0
          || !p.currency().equals(c.currency())
          || !p.expiresAt().equals(c.expiresAt()))
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Clave reutilizada");
      return get(UUID.fromString(p.paymentId()));
    }
    UUID id = UUID.randomUUID();
    String status = clock.instant().isBefore(c.expiresAt()) ? "PENDING" : "EXPIRED";
    db.update(
        "insert into"
            + " simulated_payment(id,merchant_reference,amount,currency,status,expires_at,callback_url,event_id)"
            + " values(?,?,?,?,?,?,?,?)",
        id,
        c.merchantReference(),
        c.amount(),
        c.currency(),
        status,
        c.expiresAt().atOffset(ZoneOffset.UTC),
        callback,
        UUID.randomUUID());
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
    var p = one("merchant_reference=?", ref);
    return get((UUID) p.get("id"));
  }

  @Transactional
  public Payment decide(UUID id, String action) {
    var p = one("id=? for update", id);
    Instant now = clock.instant();
    if (!now.isBefore(instant(p.get("expires_at")))) {
      db.update(
          "update simulated_payment set status='EXPIRED' where id=? and status='PENDING'", id);
      return get(id);
    }
    if (!"PENDING".equals(p.get("status"))) return convert(p);
    String status =
        switch (action) {
          case "APPROVE" -> "APPROVED";
          case "REJECT" -> "REJECTED";
          case "CANCEL" -> "CANCELLED";
          default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        };
    db.update(
        "update simulated_payment set status=?,approved_at=? where id=?",
        status,
        "APPROVED".equals(status) ? now.atOffset(ZoneOffset.UTC) : null,
        id);
    return get(id);
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
}
