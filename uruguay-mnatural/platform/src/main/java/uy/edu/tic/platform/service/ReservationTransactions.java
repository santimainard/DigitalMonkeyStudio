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
  private final PaymentAttemptTransactions attempts;

  public ReservationTransactions(
      Database db,
      Accounts accounts,
      Tourism tourism,
      Clock clock,
      @Value("${payment.hold-minutes:10}") long hold,
      PaymentAttemptTransactions attempts) {
    this.db = db;
    this.accounts = accounts;
    this.tourism = tourism;
    this.clock = clock;
    this.hold = hold;
    this.attempts = attempts;
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
    db.update(
        "update reservation set full_refund_minutes=?,late_refund_percent=? where id=?",
        a.get("full_refund_minutes"),
        a.get("late_refund_percent"),
        id);
    return id;
  }

  @Transactional
  public void expire(UUID id) {
    var r = attempts.lock(id);
    if (Set.of("PENDING_PAYMENT", "PAYMENT_UNCERTAIN").contains(r.get("status").toString())
        && !clock.instant().isBefore(instant(r.get("expires_at")))
        && db.count(
                "select count(*) from payment_attempt where reservation_id=? and status in"
                    + " ('REQUESTED','UNKNOWN','PENDING','CREATED','APPROVED','REFUNDED')",
                id)
            == 0) {
      db.update("update reservation set status='EXPIRED' where id=?", id);
      db.update("update payment set status='EXPIRED' where reservation_id=?", id);
    }
  }

  @Transactional
  public void apply(UUID id, RemotePayment remote, String eventId) {
    var r = attempts.lock(id);
    var p = db.one("select * from payment where reservation_id=?", id);
    var a =
        db.one(
            "select * from payment_attempt where reservation_id=? and merchant_reference=? for"
                + " update",
            id,
            remote.merchantReference());
    if (!"UYU".equals(remote.currency())
        || remote.amount() == null
        || ((BigDecimal) r.get("total_amount")).compareTo(remote.amount()) != 0
        || !instant(a.get("expires_at")).equals(remote.expiresAt())
        || remote.paymentId() == null
        || a.get("external_payment_id") != null
            && !a.get("external_payment_id").equals(remote.paymentId()))
      throw new BusinessException(
          "INVALID_PAYMENT", "La referencia, moneda, plazo o importe no coinciden.");
    if (!Set.of("APPROVED", "REJECTED", "CANCELLED", "EXPIRED", "PENDING")
        .contains(remote.status()))
      throw new BusinessException("INVALID_PAYMENT", "Estado desconocido.");
    if ("APPROVED".equals(remote.status())
        && (remote.approvedAt() == null || !remote.approvedAt().isBefore(remote.expiresAt())))
      throw new BusinessException("INVALID_PAYMENT", "Aprobación fuera del plazo.");
    if (eventId != null
        && db.count("select count(*) from payment_event where event_id=?", eventId) > 0) return;
    if (Set.of("REQUESTED", "UNKNOWN", "PENDING", "CREATED").contains(a.get("status").toString())) {
      if (!a.get("id").equals(p.get("current_attempt_id")))
        throw new BusinessException("INVALID_PAYMENT", "El intento no está vigente.");
      String reason =
          Set.of(
                      "CARD_DECLINED",
                      "CARD_EXPIRED",
                      "METHOD_DISABLED",
                      "METHOD_NOT_FOUND",
                      "ALREADY_PAID",
                      "HOLD_EXPIRED")
                  .contains(Objects.toString(remote.declineReason(), ""))
              ? remote.declineReason()
              : null;
      db.update(
          "update payment_attempt set"
              + " external_payment_id=?,status=?,approved_at=?,last_checked_at=?,decline_reason=?,approved_reservation_id=?"
              + " where id=?",
          remote.paymentId(),
          remote.status(),
          remote.approvedAt() == null ? null : utc(remote.approvedAt()),
          utc(clock.instant()),
          reason,
          "APPROVED".equals(remote.status()) ? id : null,
          a.get("id"));
      db.update(
          "update payment set"
              + " external_payment_id=?,status=?,approved_at=?,last_checked_at=?,checkout_url=null"
              + " where reservation_id=?",
          remote.paymentId(),
          remote.status(),
          remote.approvedAt() == null ? null : utc(remote.approvedAt()),
          utc(clock.instant()),
          id);
      if (db.count("select count(*) from cancellation where reservation_id=?", id) == 0) {
        String next =
            switch (remote.status()) {
              case "APPROVED" ->
                  clock.instant().isBefore(instant(r.get("ends_at_snapshot")))
                      ? "CONFIRMED"
                      : "COMPLETED";
              case "EXPIRED" -> "EXPIRED";
              case "CANCELLED" ->
                  clock.instant().isBefore(instant(r.get("expires_at")))
                      ? "PAYMENT_CANCELLED"
                      : "EXPIRED";
              case "REJECTED" ->
                  clock.instant().isBefore(instant(r.get("expires_at")))
                      ? "PENDING_PAYMENT"
                      : "EXPIRED";
              default -> "PAYMENT_UNCERTAIN";
            };
        db.update(
            "update reservation set status=?,code=? where id=?",
            next,
            Set.of("CONFIRMED", "COMPLETED").contains(next) ? "UM-" + id : r.get("code"),
            id);
      }
    }
    if (eventId != null)
      db.update(
          "insert into payment_event(event_id,payment_id,received_at,processed_at,attempt_id)"
              + " values(?,?,?,?,?)",
          eventId,
          remote.paymentId(),
          utc(clock.instant()),
          utc(clock.instant()),
          a.get("id"));
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
                + " payment_status,u.first_name,u.last_name,u.email,u.document from"
                + " reservation r join payment p on p.reservation_id=r.id join app_user u on"
                + " u.id=r.tourist_id where r.id=?",
            id);
    if (((Number) r.get("tourist_id")).longValue() != accounts.id())
      throw new AccessDeniedException("Reserva ajena.");
    var cancellation = db.optional("select * from cancellation where reservation_id=?", id);
    r.put("cancellation", cancellation.orElse(null));
    r.put(
        "attempts",
        db.list(
            "select"
                + " a.id,a.method_id,a.status,a.amount,a.currency,a.created_at,a.approved_at,a.decline_reason,m.brand,m.last_four"
                + " from payment_attempt a left join payment_method m on m.id=a.method_id where"
                + " a.reservation_id=? order by a.created_at desc,a.id",
            id));
    r.put(
        "can_pay",
        "PENDING_PAYMENT".equals(r.get("status"))
            && clock.instant().isBefore(instant(r.get("expires_at")))
            && cancellation.isEmpty()
            && db.count(
                    "select count(*) from payment_attempt where reservation_id=? and status in"
                        + " ('REQUESTED','UNKNOWN','CREATED','PENDING','APPROVED','REFUNDED')",
                    id)
                == 0);

    r.put(
        "can_cancel",
        clock.instant().isBefore(instant(r.get("starts_at_snapshot")))
            && Set.of("PENDING_PAYMENT", "PAYMENT_UNCERTAIN", "CONFIRMED")
                .contains(r.get("status").toString())
            && cancellation.isEmpty());
    r.put("refund_percent", CancellationTransactions.refundPercent(r, clock.instant()));
    r.put(
        "can_review",
        cancellation.isEmpty()
            && canReview(
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
    if (db.count("select count(*) from cancellation where reservation_id=?", id) > 0
        || !canReview(clock.instant(), instant(r.get("ends_at_snapshot")), payment))
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
