package uy.edu.tic.platform.service;

import java.time.*;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import uy.edu.tic.platform.domain.BusinessException;
import uy.edu.tic.platform.integration.PaymentClient;
import uy.edu.tic.platform.repository.Database;

@Service
public class PaymentMethods {
  private final Database db;
  private final Accounts accounts;
  private final PaymentClient client;
  private final PaymentMethodTransactions tx;
  private final Clock clock;

  public PaymentMethods(
      Database db,
      Accounts accounts,
      PaymentClient client,
      PaymentMethodTransactions tx,
      Clock clock) {
    this.db = db;
    this.accounts = accounts;
    this.client = client;
    this.tx = tx;
    this.clock = clock;
  }

  public List<Map<String, Object>> list() {
    accounts.role("TOURIST");
    return db.list(
        "select id,brand,last_four,holder,expiry_month,expiry_year,active from payment_method where"
            + " tourist_id=? order by created_at,id",
        accounts.id());
  }

  public List<Map<String, Object>> active() {
    return list().stream().filter(m -> Boolean.TRUE.equals(m.get("active"))).toList();
  }

  public static void validate(PaymentClient.Card c, Clock clock) {
    if (c == null
        || c.holder == null
        || !c.holder.strip().matches("[\\p{L}\\p{M}][\\p{L}\\p{M} .'’-]{0,99}")
        || c.number == null
        || !Set.of("9900000000004242", "9900000000000005", "9900000000000003").contains(c.number)
        || c.cvv == null
        || !c.cvv.matches("[0-9]{3}")
        || c.expiryMonth < 1
        || c.expiryMonth > 12
        || c.expiryYear < 2000
        || c.expiryYear > 2100
        || YearMonth.of(c.expiryYear, c.expiryMonth).isBefore(YearMonth.now(clock)))
      throw new BusinessException(
          "INVALID_INPUT",
          "Revisá titular, tarjeta ficticia permitida, vencimiento y CVV de tres dígitos. No se"
              + " aceptan tarjetas bancarias reales.");
  }

  public UUID register(UUID id, PaymentClient.Card card) {
    accounts.role("TOURIST");
    long user = accounts.id();
    var old = db.optional("select tourist_id from payment_method where id=?", id);
    if (old.isPresent()) {
      if (((Number) old.get().get("tourist_id")).longValue() != user)
        throw new AccessDeniedException("Método ajeno.");
      return id;
    }
    validate(card, clock);
    PaymentClient.RemoteMethod result;
    try {
      result = client.tokenize(card, "tourist-" + user, id.toString());
    } catch (RuntimeException e) {
      throw new BusinessException(
          "PAYMENT_SERVICE_UNAVAILABLE",
          "No se pudo registrar la tarjeta de prueba. Intentá de nuevo más tarde.");
    }
    if (result == null
        || result.token() == null
        || !Set.of("Visa de prueba", "Mastercard de prueba").contains(result.brand())
        || !card.number.substring(12).equals(result.lastFour())
        || !card.holder.strip().equals(result.holder())
        || card.expiryMonth != result.expiryMonth()
        || card.expiryYear != result.expiryYear())
      throw new BusinessException("INVALID_PAYMENT", "El método remoto no pudo verificarse.");
    return tx.save(id, user, result);
  }

  public void deactivate(UUID id) {
    accounts.role("TOURIST");
    tx.deactivate(id, accounts.id());
  }
}
