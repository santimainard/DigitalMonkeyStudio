package uy.edu.tic.platform.service;

import static uy.edu.tic.platform.domain.BookingRules.*;

import java.math.BigDecimal;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  public Payments(Database db, PaymentClient client, ReservationTransactions tx) {
    this.db = db;
    this.client = client;
    this.tx = tx;
  }

  public void synchronize(UUID id) {
    var r =
        db.one(
            "select r.*,p.external_payment_id from reservation r join payment p on"
                + " p.reservation_id=r.id where r.id=?",
            id);
    if (!Set.of("PENDING_PAYMENT", "PAYMENT_UNCERTAIN").contains(r.get("status").toString()))
      return;
    try {
      var remote =
          r.get("external_payment_id") == null
              ? client.byReference(id.toString())
              : client.get(r.get("external_payment_id").toString());
      if (remote == null)
        remote =
            client.create(
                id.toString(),
                (BigDecimal) r.get("total_amount"),
                r.get("currency").toString(),
                instant(r.get("expires_at")));
      if (remote == null) throw new IllegalStateException("Sin respuesta");
      tx.apply(id, remote, null);
    } catch (RuntimeException e) {
      tx.uncertain(id);
      log.warn("Pago pendiente de conciliación para {}: {}", id, e.getClass().getSimpleName());
    }
  }

  public void event(String eventId, String paymentId, String ref) {
    var remote = client.get(paymentId);
    if (remote == null || !remote.merchantReference().equals(ref))
      throw new BusinessException("INVALID_PAYMENT", "Evento no verificable.");
    tx.apply(UUID.fromString(ref), remote, eventId);
  }

  @Scheduled(fixedDelayString = "${payment.reconciliation-delay:30000}")
  public void reconcile() {
    for (var r :
        db.list(
            "select id from reservation where status in ('PENDING_PAYMENT','PAYMENT_UNCERTAIN')"
                + " order by created_at limit 100"))
      try {
        synchronize((UUID) r.get("id"));
      } catch (RuntimeException e) {
        log.warn("No se pudo conciliar {}", r.get("id"));
      }
    tx.complete();
  }
}
