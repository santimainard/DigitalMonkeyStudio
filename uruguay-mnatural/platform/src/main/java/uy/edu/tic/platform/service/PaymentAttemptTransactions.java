package uy.edu.tic.platform.service;

import static uy.edu.tic.platform.domain.BookingRules.*;

import java.time.*;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.tic.platform.domain.BusinessException;
import uy.edu.tic.platform.repository.Database;

@Service
public class PaymentAttemptTransactions {
  private final Database db;
  private final Accounts accounts;
  private final Clock clock;

  public PaymentAttemptTransactions(Database db, Accounts accounts, Clock clock) {
    this.db = db;
    this.accounts = accounts;
    this.clock = clock;
  }

  public Map<String, Object> lock(UUID id) {
    var r = db.one("select instance_id from reservation where id=?", id);
    db.one("select id from activity_instance where id=? for update", r.get("instance_id"));
    return db.one("select * from reservation where id=? for update", id);
  }

  @Transactional
  public UUID begin(UUID id, UUID methodId, String key) {
    accounts.role("TOURIST");
    key = Accounts.text(key, 100, "clave del intento");
    var r = lock(id);
    if (((Number) r.get("tourist_id")).longValue() != accounts.id())
      throw new AccessDeniedException("Reserva ajena.");
    var old =
        db.optional(
            "select * from payment_attempt where reservation_id=? and client_key=?", id, key);
    if (old.isPresent()) {
      if (!methodId.equals(old.get().get("method_id")))
        throw new BusinessException("IDEMPOTENCY_CONFLICT", "La clave se usó con otro método.");
      return (UUID) old.get().get("id");
    }
    var p = db.one("select * from payment where reservation_id=?", id);
    if (!Set.of("PENDING_PAYMENT", "PAYMENT_UNCERTAIN").contains(r.get("status").toString())
        || Set.of("APPROVED", "REFUNDED").contains(p.get("status").toString())
        || db.count("select count(*) from cancellation where reservation_id=?", id) > 0)
      throw new BusinessException("PAYMENT_NOT_ALLOWED", "Esta reserva no admite nuevos pagos.");
    if (!clock.instant().isBefore(instant(r.get("expires_at"))))
      throw new BusinessException("PAYMENT_NOT_ALLOWED", "El plazo para pagar venció.");
    if (db.count(
            "select count(*) from payment_attempt where reservation_id=? and status in"
                + " ('REQUESTED','UNKNOWN','PENDING','CREATED')",
            id)
        > 0)
      throw new BusinessException(
          "PAYMENT_UNCERTAIN",
          "El intento anterior está siendo verificado. No se puede iniciar otro cobro.");
    var method = db.one("select * from payment_method where id=? for update", methodId);
    if (((Number) method.get("tourist_id")).longValue() != accounts.id()
        || !Boolean.TRUE.equals(method.get("active")))
      throw new AccessDeniedException("Método ajeno o desactivado.");
    if (p.get("current_attempt_id") != null) {
      var last = db.one("select * from payment_attempt where id=?", p.get("current_attempt_id"));
      if ("REJECTED".equals(last.get("status")) && methodId.equals(last.get("method_id")))
        throw new BusinessException(
            "METHOD_RETRY_REQUIRED", "Elegí otro método después de un rechazo.");
    }
    UUID attempt = UUID.randomUUID();
    db.update(
        "insert into"
            + " payment_attempt(id,reservation_id,method_id,client_key,merchant_reference,status,amount,currency,expires_at,created_at)"
            + " values(?,?,?,?,?,'REQUESTED',?,?,?,?)",
        attempt,
        id,
        methodId,
        key,
        attempt.toString(),
        r.get("total_amount"),
        r.get("currency"),
        r.get("expires_at"),
        utc(clock.instant()));
    db.update(
        "update payment set"
            + " current_attempt_id=?,status='REQUESTED',external_payment_id=null,approved_at=null,checkout_url=null"
            + " where reservation_id=?",
        attempt,
        id);
    db.update("update reservation set status='PAYMENT_UNCERTAIN' where id=?", id);
    return attempt;
  }

  @Transactional
  public void uncertain(UUID attempt) {
    var a = db.one("select reservation_id from payment_attempt where id=?", attempt);
    var r = lock((UUID) a.get("reservation_id"));
    int changed =
        db.update(
            "update payment_attempt set status='UNKNOWN',last_checked_at=? where id=? and status in"
                + " ('REQUESTED','UNKNOWN','PENDING','CREATED')",
            utc(clock.instant()),
            attempt);
    if (changed > 0) {
      db.update(
          "update payment set status='UNKNOWN' where reservation_id=? and current_attempt_id=?",
          r.get("id"),
          attempt);
      db.update(
          "update reservation set status='PAYMENT_UNCERTAIN' where id=? and"
              + " status='PENDING_PAYMENT'",
          r.get("id"));
    }
  }
}
