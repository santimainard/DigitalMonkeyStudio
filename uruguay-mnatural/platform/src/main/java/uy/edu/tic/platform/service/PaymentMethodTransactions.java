package uy.edu.tic.platform.service;

import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.tic.platform.integration.PaymentClient.RemoteMethod;
import uy.edu.tic.platform.repository.Database;

@Service
public class PaymentMethodTransactions {
  private final Database db;

  public PaymentMethodTransactions(Database db) {
    this.db = db;
  }

  @Transactional
  public UUID save(UUID id, long user, RemoteMethod m) {
    db.one("select id from app_user where id=? for update", user);
    var old = db.optional("select tourist_id from payment_method where id=?", id);
    if (old.isPresent()) {
      if (((Number) old.get().get("tourist_id")).longValue() != user)
        throw new AccessDeniedException("Método ajeno.");
      return id;
    }
    db.update(
        "insert into"
            + " payment_method(id,tourist_id,external_token,brand,last_four,holder,expiry_month,expiry_year)"
            + " values(?,?,?,?,?,?,?,?)",
        id,
        user,
        m.token(),
        m.brand(),
        m.lastFour(),
        m.holder(),
        m.expiryMonth(),
        m.expiryYear());
    return id;
  }

  @Transactional
  public void deactivate(UUID id, long user) {
    var m = db.one("select tourist_id from payment_method where id=? for update", id);
    if (((Number) m.get("tourist_id")).longValue() != user)
      throw new AccessDeniedException("Método ajeno.");
    db.update("update payment_method set active=false where id=?", id);
  }
}
