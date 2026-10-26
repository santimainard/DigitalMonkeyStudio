package uy.edu.tic.platform.service;

import static uy.edu.tic.platform.domain.BookingRules.*;

import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.tic.platform.domain.BusinessException;
import uy.edu.tic.platform.integration.PaymentClient.Settlement;
import uy.edu.tic.platform.repository.Database;

/** Durable cancellation requests; no network calls while database locks are held. */
@Service
public class CancellationTransactions {
  private final Database db;
  private final Accounts accounts;
  private final Tourism tourism;
  private final Clock clock;

  public CancellationTransactions(Database db, Accounts accounts, Tourism tourism, Clock clock) {
    this.db = db;
    this.accounts = accounts;
    this.tourism = tourism;
    this.clock = clock;
  }

  public static int refundPercent(Map<String, Object> r, Instant now) {
    return !now.isAfter(
            instant(r.get("starts_at_snapshot"))
                .minusSeconds(((Number) r.get("full_refund_minutes")).longValue() * 60))
        ? 100
        : ((Number) r.get("late_refund_percent")).intValue();
  }

  private Map<String, Object> lock(UUID id) {
    var r = db.one("select instance_id from reservation where id=?", id);
    db.one("select id from activity_instance where id=? for update", r.get("instance_id"));
    return db.one("select * from reservation where id=? for update", id);
  }

  private void beforeStart(Object start) {
    if (!clock.instant().isBefore(instant(start)))
      throw new BusinessException(
          "CANCELLATION_CLOSED", "No se puede cancelar una instancia que ya comenzó.");
  }

  private void request(Map<String, Object> r, String actor, int percent) {
    UUID id = (UUID) r.get("id");
    if (db.count("select count(*) from cancellation where reservation_id=?", id) > 0) {
      if ("PROVIDER".equals(actor))
        db.update(
            "update cancellation set refund_percent=100,requested_by='PROVIDER' where"
                + " reservation_id=? and completed_at is null",
            id);
      return;
    }
    if (!Set.of("PENDING_PAYMENT", "PAYMENT_UNCERTAIN", "CONFIRMED")
        .contains(r.get("status").toString()))
      throw new BusinessException("INVALID_INPUT", "La reserva no admite cancelación.");
    db.update(
        "insert into cancellation(reservation_id,requested_by,refund_percent,requested_at)"
            + " values(?,?,?,?)",
        id,
        actor,
        percent,
        utc(clock.instant()));
  }

  @Transactional
  public void tourist(UUID id) {
    accounts.role("TOURIST");
    var r = lock(id);
    if (((Number) r.get("tourist_id")).longValue() != accounts.id())
      throw new org.springframework.security.access.AccessDeniedException("Reserva ajena.");
    if (db.count("select count(*) from cancellation where reservation_id=?", id) > 0) return;
    beforeStart(r.get("starts_at_snapshot"));
    request(r, "TOURIST", refundPercent(r, clock.instant()));
  }

  @Transactional
  public List<UUID> provider(long activity, long instance) {
    tourism.owned(activity);
    var i =
        db.one(
            "select * from activity_instance where id=? and activity_id=? for update",
            instance,
            activity);
    if (!Boolean.TRUE.equals(i.get("cancelled"))) {
      beforeStart(i.get("starts_at"));
      db.update("update activity_instance set active=false,cancelled=true where id=?", instance);
    }
    var rows =
        db.list(
            "select * from reservation where instance_id=? and status in"
                + " ('PENDING_PAYMENT','PAYMENT_UNCERTAIN','CONFIRMED') order by id for update",
            instance);
    for (var r : rows) request(r, "PROVIDER", 100);
    return db
        .list(
            "select c.reservation_id from cancellation c join reservation r on"
                + " r.id=c.reservation_id where r.instance_id=? and c.completed_at is null",
            instance)
        .stream()
        .map(r -> (UUID) r.get("reservation_id"))
        .toList();
  }

  @Transactional
  public void finishUnpaid(UUID id) {
    lock(id);
    var c = db.one("select * from cancellation where reservation_id=? for update", id);
    if (c.get("completed_at") != null) return;
    if (db.count(
            "select count(*) from payment_attempt where reservation_id=? and status in"
                + " ('REQUESTED','UNKNOWN','CREATED','PENDING','APPROVED','REFUNDED')",
            id)
        > 0)
      throw new BusinessException("PAYMENT_UNCERTAIN", "Es necesario comprobar el intento remoto.");
    db.update("update payment set status='CANCELLED' where reservation_id=?", id);
    db.update("update reservation set status='PAYMENT_CANCELLED' where id=?", id);
    db.update(
        "update cancellation set completed_at=?,refunded_amount=0 where reservation_id=?",
        utc(clock.instant()),
        id);
  }

  @Transactional
  public void finish(UUID id, Settlement remote) {
    var r = lock(id);
    var c = db.one("select * from cancellation where reservation_id=? for update", id);
    if (c.get("completed_at") != null) return;
    var p = db.one("select * from payment where reservation_id=?", id);
    var a =
        db.one("select * from payment_attempt where id=? for update", p.get("current_attempt_id"));
    if (remote == null
        || !a.get("merchant_reference").equals(remote.merchantReference())
        || !"UYU".equals(remote.currency())
        || remote.amount() == null
        || remote.amount().compareTo((BigDecimal) r.get("total_amount")) != 0
        || remote.refundPercent() != ((Number) c.get("refund_percent")).intValue()
        || (p.get("external_payment_id") != null
            && !p.get("external_payment_id").equals(remote.paymentId()))
        || !Set.of("APPROVED", "REJECTED", "CANCELLED", "EXPIRED").contains(remote.status()))
      throw new BusinessException("INVALID_PAYMENT", "Cancelación remota no verificable.");
    BigDecimal expected =
        "APPROVED".equals(remote.status())
            ? remote
                .amount()
                .multiply(BigDecimal.valueOf(remote.refundPercent()))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
            : new BigDecimal("0.00");
    if (remote.refundedAmount() == null || expected.compareTo(remote.refundedAmount()) != 0)
      throw new BusinessException("INVALID_PAYMENT", "Importe de reembolso incorrecto.");
    db.update(
        "update payment_attempt set"
            + " external_payment_id=?,status=?,last_checked_at=?,approved_reservation_id=? where"
            + " id=?",
        remote.paymentId(),
        "APPROVED".equals(remote.status()) ? "REFUNDED" : remote.status(),
        utc(clock.instant()),
        "APPROVED".equals(remote.status()) ? id : null,
        a.get("id"));
    db.update(
        "update payment set external_payment_id=?,status=?,last_checked_at=? where"
            + " reservation_id=?",
        remote.paymentId(),
        "APPROVED".equals(remote.status()) ? "REFUNDED" : remote.status(),
        utc(clock.instant()),
        id);
    db.update("update reservation set status='PAYMENT_CANCELLED' where id=?", id);
    db.update(
        "update cancellation set completed_at=?,refunded_amount=? where reservation_id=?",
        utc(clock.instant()),
        expected,
        id);
  }
}
