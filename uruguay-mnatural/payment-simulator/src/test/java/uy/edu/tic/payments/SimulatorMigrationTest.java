package uy.edu.tic.payments;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SimulatorMigrationTest {
  @Test
  void upgradeRetainsLegacyOperationsAndRefunds() {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:upgrade-"
                + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "sa",
            "");
    Flyway.configure().dataSource(source).target("2").load().migrate();
    var db = new JdbcTemplate(source);
    UUID id = UUID.randomUUID();
    String ref = UUID.randomUUID().toString();
    db.update(
        "insert into"
            + " simulated_payment(id,merchant_reference,amount,currency,status,expires_at,approved_at,callback_url,event_id,cancellation_percent,refunded_amount)"
            + " values(?,?,1200,'UYU','APPROVED',current_timestamp,current_timestamp,'http://localhost/callback',?,35,420)",
        id,
        ref,
        UUID.randomUUID());
    var before = db.queryForMap("select * from simulated_payment where id=?", id);
    Flyway.configure().dataSource(source).load().migrate();
    var after = db.queryForMap("select * from simulated_payment where id=?", id);
    before.forEach((k, v) -> assertEquals(v, after.get(k)));
    assertEquals(ref, after.get("reservation_reference"));
    assertEquals(1L, db.queryForObject("select count(*) from simulated_payment", Long.class));
  }
}
