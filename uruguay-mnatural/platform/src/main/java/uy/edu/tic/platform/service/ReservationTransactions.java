package uy.edu.tic.platform.service;

import static uy.edu.tic.platform.domain.BookingRules.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.tic.platform.domain.*;
import uy.edu.tic.platform.integration.PaymentClient.RemotePayment;
import uy.edu.tic.platform.repository.Database;

@Service
public class ReservationTransactions {
  private final Database db;
  private final Accounts accounts;
  private final Tourism tourism;
  private final Clock clock;
  private final long hold;

  public ReservationTransactions(
      Database db,
      Accounts accounts,
      Tourism tourism,
      Clock clock,
      @Value("${payment.hold-minutes:10}") long hold) {
    this.db = db;
    this.accounts = accounts;
    this.tourism = tourism;
    this.clock = clock;
    this.hold = hold;
  }

  @Transactional
  public UUID reserve(long instance, int count, String key) {
    accounts.role("TOURIST");
    key = Accounts.text(key, 100, "clave de operación");
    long tourist = accounts.id();
    // A user lock makes the idempotency check atomic even across different instances.
    db.one("select id from app_user where id=? for update", tourist);
    var old =
        db.optional(
            "select * from reservation where tourist_id=? and idempotency_key=?", tourist, key);
    if (old.isPresent()) {
      var r = old.get();
      if (((Number) r.get("instance_id")).longValue() != instance
          || (Integer) r.get("participant_count") != count)
        throw new BusinessException("IDEMPOTENCY_CONFLICT", "La clave ya se usó con otros datos.");
      return (UUID) r.get("id");
    }
    var i = db.one("select * from activity_instance where id=? for update", instance);
    var a = tourism.activity(((Number) i.get("activity_id")).longValue(), true);
    if (!Boolean.TRUE.equals(i.get("active")))
      throw new BusinessException("INVALID_INPUT", "Instancia inactiva.");
    Instant now = clock.instant();
    validate(
        now,
        instant(i.get("starts_at")),
        (Integer) a.get("booking_lead_minutes"),
        count,
        (Integer) i.get("capacity"),
        tourism.committed(instance));
    UUID id = UUID.randomUUID();
    BigDecimal price = (BigDecimal) a.get("unit_price"), amount = total(price, count);
    db.update(
        "insert into"
            + " reservation(id,tourist_id,instance_id,idempotency_key,participant_count,status,expires_at,activity_name_snapshot,location_snapshot,unit_price_snapshot,currency,total_amount,starts_at_snapshot,ends_at_snapshot,lead_minutes_snapshot,created_at)"
            + " values(?,?,?,?,?,'PENDING_PAYMENT',?,?,?,?,?,?,?,?,?,?)",
        id,
        tourist,
        instance,
        key,
        count,
        utc(now.plusSeconds(hold * 60)),
        a.get("name"),
        a.get("location_description"),
        price,
        "UYU",
        amount,
        i.get("starts_at"),
        i.get("ends_at"),
        a.get("booking_lead_minutes"),
        utc(now));
    db.update(
        "insert into payment(id,reservation_id,merchant_reference,status,amount,currency)"
            + " values(?,?,?,'CREATED',?,'UYU')",
        UUID.randomUUID(),
        id,
        id.toString(),
        amount);
    return id;
  }

  @Transactional
  public void uncertain(UUID id) {
    db.update(
        "update reservation set status='PAYMENT_UNCERTAIN' where id=? and status in"
            + " ('PENDING_PAYMENT','PAYMENT_UNCERTAIN')",
        id);
    db.update(
        "update payment set status='UNKNOWN',last_checked_at=? where reservation_id=? and status"
            + " not in ('APPROVED','REJECTED','CANCELLED','EXPIRED')",
        utc(clock.instant()),
        id);
  }

  @Transactional
  public void apply(UUID id, RemotePayment remote, String eventId) {
    var reference = db.one("select instance_id from reservation where id=?", id);
    db.one("select id from activity_instance where id=? for update", reference.get("instance_id"));
    var r = db.one("select * from reservation where id=? for update", id);
    var p = db.one("select * from payment where reservation_id=?", id);
    if (eventId != null
        && db.count("select count(*) from payment_event where event_id=?", eventId) > 0) return;
    if (!id.toString().equals(remote.merchantReference())
        || !"UYU".equals(remote.currency())
        || remote.amount() == null
        || ((BigDecimal) r.get("total_amount")).compareTo(remote.amount()) != 0
        || !instant(r.get("expires_at")).equals(remote.expiresAt()))
      throw new BusinessException(
          "INVALID_PAYMENT", "La referencia, moneda, plazo o importe no coinciden.");
    if (p.get("external_payment_id") != null
        && !p.get("external_payment_id").equals(remote.paymentId()))
      throw new BusinessException("INVALID_PAYMENT", "Identificador de pago distinto.");
    String current = r.get("status").toString();
    if (!Set.of("PENDING_PAYMENT", "PAYMENT_UNCERTAIN").contains(current)) return;
    String next =
        switch (remote.status()) {
          case "APPROVED" ->
              !clock.instant().isBefore(instant(r.get("ends_at_snapshot")))
                  ? "COMPLETED"
                  : "CONFIRMED";
          case "REJECTED" -> "REJECTED";
          case "CANCELLED" -> "PAYMENT_CANCELLED";
          case "EXPIRED" -> "EXPIRED";
          case "PENDING", "CREATED" -> "PENDING_PAYMENT";
          default -> throw new BusinessException("INVALID_PAYMENT", "Estado remoto desconocido.");
        };
    if ("APPROVED".equals(remote.status())
        && (remote.approvedAt() == null || !remote.approvedAt().isBefore(remote.expiresAt())))
      throw new BusinessException("INVALID_PAYMENT", "Aprobación fuera del plazo.");
    String code = Set.of("CONFIRMED", "COMPLETED").contains(next) ? "UM-" + id.toString() : null;
    db.update(
        "update payment set"
            + " external_payment_id=?,status=?,checkout_url=?,approved_at=?,last_checked_at=? where"
            + " reservation_id=?",
        remote.paymentId(),
        remote.status(),
        remote.checkoutUrl(),
        remote.approvedAt() == null ? null : utc(remote.approvedAt()),
        utc(clock.instant()),
        id);
    db.update("update reservation set status=?,code=? where id=?", next, code, id);
    if (eventId != null)
      db.update(
          "insert into payment_event(event_id,payment_id,received_at,processed_at) values(?,?,?,?)",
          eventId,
          remote.paymentId(),
          utc(clock.instant()),
          utc(clock.instant()));
  }

  @Transactional
  public void complete() {
    db.update(
        "update reservation set status='COMPLETED' where status='CONFIRMED' and ends_at_snapshot<=?"
            + " and exists(select 1 from payment p where p.reservation_id=reservation.id and"
            + " p.status='APPROVED')",
        utc(clock.instant()));
  }

  public Map<String, Object> detail(UUID id) {
    complete();
    var r =
        db.one(
            "select r.*,p.status"
                + " payment_status,p.checkout_url,u.first_name,u.last_name,u.email,u.document from"
                + " reservation r join payment p on p.reservation_id=r.id join app_user u on"
                + " u.id=r.tourist_id where r.id=?",
            id);
    if (((Number) r.get("tourist_id")).longValue() != accounts.id())
      throw new AccessDeniedException("Reserva ajena.");
    r.put(
        "can_review",
        canReview(
                clock.instant(),
                instant(r.get("ends_at_snapshot")),
                r.get("payment_status").toString())
            && db.count("select count(*) from review where reservation_id=?", id) == 0);
    return r;
  }

  @Transactional
  public void review(UUID id, int rating, String comment) {
    accounts.role("TOURIST");
    var r = db.one("select * from reservation where id=? for update", id);
    if (((Number) r.get("tourist_id")).longValue() != accounts.id())
      throw new AccessDeniedException("Reserva ajena.");
    String payment =
        db.one("select status from payment where reservation_id=?", id).get("status").toString();
    if (!canReview(clock.instant(), instant(r.get("ends_at_snapshot")), payment))
      throw new BusinessException(
          "REVIEW_NOT_ALLOWED", "La actividad debe haber terminado y estar pagada.");
    if (rating < 1 || rating > 5 || comment != null && comment.length() > 2000)
      throw new BusinessException(
          "INVALID_INPUT", "Valoración de 1 a 5 y comentario de hasta 2000 caracteres.");
    if (db.count("select count(*) from review where reservation_id=?", id) > 0)
      throw new BusinessException("REVIEW_ALREADY_EXISTS", "Ya reseñaste esta reserva.");
    db.insert(
        "insert into review(reservation_id,rating,comment,created_at) values(?,?,?,?)",
        id,
        rating,
        comment == null ? null : comment.strip(),
        utc(clock.instant()));
    db.update("update reservation set status='COMPLETED' where id=?", id);
  }
}
