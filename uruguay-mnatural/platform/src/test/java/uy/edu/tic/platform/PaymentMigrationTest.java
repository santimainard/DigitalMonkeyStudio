package uy.edu.tic.platform;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PaymentMigrationTest {
  @Test
  void upgradePreservesApprovedPaymentsReservationsAndExistingEvents() {
    String url =
        "jdbc:h2:mem:upgrade-"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    var source = new DriverManagerDataSource(url, "sa", "");
    Flyway.configure().dataSource(source).target("5").load().migrate();
    var db = new JdbcTemplate(source);
    db.update(
        "insert into app_user(id,first_name,last_name,email,password_hash,role)"
            + " values(1,'Turista','Prueba','tourist@migration.test','test-hash','TOURIST'),(2,'Prestador','Prueba','provider@migration.test','test-hash','PROVIDER')");
    db.update(
        "insert into"
            + " venture(id,owner_id,legal_type,commercial_name,contact_email,phone,department_id,locality_id,location_description,description)"
            + " values(1,2,'PHYSICAL','Prueba','contact@migration.test','099123456',10,10,'Montevideo','Prueba')");
    db.update(
        "insert into"
            + " activity(id,venture_id,name,description,unit_price,duration_minutes,booking_lead_minutes,department_id,locality_id,location_description,conditions)"
            + " values(1,1,'Prueba','Prueba',1200,60,0,10,10,'Montevideo','Prueba')");
    db.update(
        "insert into activity_instance(id,activity_id,starts_at,ends_at,capacity)"
            + " values(1,1,current_timestamp,dateadd('HOUR',1,current_timestamp),2)");
    UUID reservation = UUID.randomUUID(), payment = UUID.randomUUID();
    db.update(
        "insert into"
            + " reservation(id,tourist_id,instance_id,idempotency_key,participant_count,status,expires_at,activity_name_snapshot,location_snapshot,unit_price_snapshot,currency,total_amount,starts_at_snapshot,ends_at_snapshot,lead_minutes_snapshot,created_at,code)"
            + " values(?,1,1,'legacy',1,'CONFIRMED',current_timestamp,'Prueba','Montevideo',1200,'UYU',1200,current_timestamp,dateadd('HOUR',1,current_timestamp),0,current_timestamp,'UM-legacy')",
        reservation);
    db.update(
        "insert into"
            + " payment(id,reservation_id,merchant_reference,external_payment_id,status,amount,currency,approved_at)"
            + " values(?,?,?,'legacy-external','APPROVED',1200,'UYU',current_timestamp)",
        payment,
        reservation,
        reservation.toString());
    db.update(
        "insert into payment_event(event_id,payment_id,received_at,processed_at)"
            + " values('legacy-event','legacy-external',current_timestamp,current_timestamp)");
    var before = db.queryForMap("select * from payment where id=?", payment);
    Flyway.configure().dataSource(source).load().migrate();
    var after = db.queryForMap("select * from payment where id=?", payment);
    before.forEach((k, v) -> assertEquals(v, after.get(k)));
    assertEquals(payment, after.get("current_attempt_id"));
    var attempt = db.queryForMap("select * from payment_attempt where id=?", payment);
    assertEquals(true, attempt.get("legacy"));
    assertEquals(reservation, attempt.get("approved_reservation_id"));
    assertEquals(
        payment,
        db.queryForObject(
            "select attempt_id from payment_event where event_id='legacy-event'", UUID.class));
    assertEquals(
        "CONFIRMED",
        db.queryForObject("select status from reservation where id=?", String.class, reservation));
    assertEquals(1L, db.queryForObject("select count(*) from payment", Long.class));
  }
}
