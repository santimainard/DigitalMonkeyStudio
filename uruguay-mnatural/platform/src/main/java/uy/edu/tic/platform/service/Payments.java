package uy.edu.tic.platform.service;

import static uy.edu.tic.platform.domain.BookingRules.*;

import java.math.BigDecimal;
import java.util.*;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import uy.edu.tic.platform.domain.BusinessException;
import uy.edu.tic.platform.integration.PaymentClient;
import uy.edu.tic.platform.repository.Database;

@Service
public class Payments {
  private static final Logger log = LoggerFactory.getLogger(Payments.class);
  private final Database db;
  private final PaymentClient client;
  private final ReservationTransactions tx;
  private final CancellationTransactions cancellations;
  private final PaymentAttemptTransactions attempts;
  private final java.time.Clock clock;

  public Payments(
      Database db,
      PaymentClient client,
      ReservationTransactions tx,
      CancellationTransactions cancellations,
      PaymentAttemptTransactions attempts,
      java.time.Clock clock) {
    this.db = db;
    this.client = client;
    this.tx = tx;
    this.cancellations = cancellations;
    this.attempts = attempts;
    this.clock = clock;
  }

  public void pay(UUID reservation, UUID method, String key) {
    UUID attempt = attempts.begin(reservation, method, key);
    synchronizeAttempt(attempt);
  }

  public void synchronize(UUID id) {
    tx.expire(id);
    var p = db.one("select current_attempt_id from payment where reservation_id=?", id);
    if (p.get("current_attempt_id") != null) synchronizeAttempt((UUID) p.get("current_attempt_id"));
  }

  private void synchronizeAttempt(UUID id) {
    var a =
        db.one(
            "select a.*,r.tourist_id,m.external_token from payment_attempt a join reservation r on"
                + " r.id=a.reservation_id left join payment_method m on m.id=a.method_id where"
                + " a.id=?",
            id);
    if (!Set.of("REQUESTED", "UNKNOWN", "CREATED", "PENDING").contains(a.get("status").toString()))
      return;
    UUID reservation = (UUID) a.get("reservation_id");
    if (db.count("select count(*) from cancellation where reservation_id=?", reservation) > 0) {
      settleCancellation(reservation);
      return;
    }
    try {
      var remote = client.byReference(a.get("merchant_reference").toString());
      if (remote == null) {
        if (Boolean.TRUE.equals(a.get("legacy"))) {
          if (!clock.instant().isBefore(instant(a.get("expires_at")))) {
            var sealed =
                client.cancelReference(
                    a.get("merchant_reference").toString(),
                    reservation.toString(),
                    "tourist-" + a.get("tourist_id"),
                    (BigDecimal) a.get("amount"),
                    a.get("currency").toString(),
                    instant(a.get("expires_at")),
                    0);
            remote = client.get(sealed.paymentId());
          } else return;
        } else
          remote =
              client.authorize(
                  a.get("merchant_reference").toString(),
                  reservation.toString(),
                  "tourist-" + a.get("tourist_id"),
                  (UUID) a.get("external_token"),
                  (BigDecimal) a.get("amount"),
                  a.get("currency").toString(),
                  instant(a.get("expires_at")));
      }
      if (remote == null) throw new IllegalStateException("Sin resultado");
      tx.apply(reservation, remote, null);
    } catch (RuntimeException e) {
      attempts.uncertain(id);
      log.warn("Intento pendiente de conciliación {}: {}", id, e.getClass().getSimpleName());
    }
  }

  public void settleCancellation(UUID id) {
    var c =
        db.one(
            "select c.*,r.total_amount,r.currency,r.expires_at,r.tourist_id,p.current_attempt_id"
                + " from cancellation c join reservation r on r.id=c.reservation_id join payment p"
                + " on p.reservation_id=r.id where c.reservation_id=?",
            id);
    if (c.get("completed_at") != null) return;
    try {
      if (c.get("current_attempt_id") == null) {
        cancellations.finishUnpaid(id);
        return;
      }
      var a = db.one("select * from payment_attempt where id=?", c.get("current_attempt_id"));
      var result =
          client.cancelReference(
              a.get("merchant_reference").toString(),
              id.toString(),
              "tourist-" + c.get("tourist_id"),
              (BigDecimal) c.get("total_amount"),
              c.get("currency").toString(),
              instant(c.get("expires_at")),
              (Integer) c.get("refund_percent"));
      cancellations.finish(id, result);
    } catch (RuntimeException e) {
      log.warn("Cancelación pendiente de conciliación {}: {}", id, e.getClass().getSimpleName());
    }
  }

  public void event(String eventId, String paymentId, String ref) {
    var a = db.one("select reservation_id from payment_attempt where merchant_reference=?", ref);
    var remote = client.get(paymentId);
    if (remote == null || !ref.equals(remote.merchantReference()))
      throw new BusinessException("INVALID_PAYMENT", "Evento no verificable.");
    tx.apply((UUID) a.get("reservation_id"), remote, eventId);
  }

  @Scheduled(fixedDelayString = "${payment.reconciliation-delay:30000}")
  public void reconcile() {
    for (var r :
        db.list(
            "select id from reservation where status in ('PENDING_PAYMENT','PAYMENT_UNCERTAIN')"
                + " order by created_at limit 100")) {
      try {
        synchronize((UUID) r.get("id"));
      } catch (RuntimeException e) {
        log.warn("No se pudo conciliar {}", r.get("id"));
      }
    }
    for (var c :
        db.list(
            "select reservation_id from cancellation where completed_at is null order by"
                + " requested_at limit 100")) settleCancellation((UUID) c.get("reservation_id"));
    tx.complete();
  }
}
