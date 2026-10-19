package uy.edu.tic.platform.config;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import uy.edu.tic.platform.repository.Database;

@Component
public class Bootstrap implements CommandLineRunner {
  private final Database db;
  private final PasswordEncoder encoder;
  private final String email, password, demoPassword;
  private final boolean demo;

  public Bootstrap(
      Database db,
      PasswordEncoder encoder,
      @Value("${app.admin-email}") String email,
      @Value("${app.admin-password}") String password,
      @Value("${app.demo:false}") boolean demo,
      @Value("${DEMO_PASSWORD:}") String demoPassword) {
    this.db = db;
    this.encoder = encoder;
    this.email = email.strip().toLowerCase(Locale.ROOT);
    this.password = password;
    this.demo = demo;
    this.demoPassword = demoPassword;
  }

  @Override
  public void run(String... args) {
    if (db.count("select count(*) from app_user where email=?", email) == 0) {
      if (password.length() < 8)
        throw new IllegalStateException("ADMIN_PASSWORD debe tener al menos 8 caracteres.");
      db.insert(
          "insert into app_user(first_name,last_name,email,password_hash,role)"
              + " values('Administrador','UM',?,?,'ADMIN')",
          email,
          encoder.encode(password));
    }
    if (demo
        && db.count("select count(*) from app_user where email='prestador@example.test'") == 0) {
      if (demoPassword.length() < 8)
        throw new IllegalStateException(
            "DEMO_PASSWORD es obligatorio al activar datos de demostración.");
      long provider =
          db.insert(
              "insert into app_user(first_name,last_name,email,document,password_hash,role)"
                  + " values('Ana','Demo','prestador@example.test','DEMO-P',?,'PROVIDER')",
              encoder.encode(demoPassword));
      db.insert(
          "insert into app_user(first_name,last_name,email,document,password_hash,role)"
              + " values('Felipe','Demo','turista@example.test','DEMO-T',?,'TOURIST')",
          encoder.encode(demoPassword));
      long venture =
          db.insert(
              "insert into"
                  + " venture(owner_id,legal_type,commercial_name,contact_email,phone,department_id,locality_id,location_description,description,verification_status,verified_at)"
                  + " values(?,'PHYSICAL','Senderos del"
                  + " Uruguay','contacto@example.test','00000000',8,8,'Centro de"
                  + " Minas','Emprendimiento ficticio para"
                  + " demostración.','VERIFIED',current_timestamp)",
              provider);
      String[] names = {
        "Senderismo entre sierras", "Sabores de Montevideo", "Atardecer en la costa"
      };
      String[] descriptions = {
        "Un recorrido guiado para conocer los paisajes serranos y disfrutar del aire libre.",
        "Una caminata por la ciudad con historias, mercados y sabores locales.",
        "Una experiencia tranquila junto al mar para descubrir el paisaje de nuestra costa."
      };
      int[] deps = {8, 10, 14};
      int[] locs = {8, 10, 21};
      for (int n = 0; n < names.length; n++) {
        long activity =
            db.insert(
                "insert into"
                    + " activity(venture_id,name,description,unit_price,duration_minutes,booking_lead_minutes,department_id,locality_id,location_description,conditions,is_published)"
                    + " values(?,?,?,?,120,30,?,?,'Punto de encuentro de demostración','Llegar 15"
                    + " minutos antes. Actividad de demostración.',true)",
                venture,
                names[n],
                descriptions[n],
                1200 + n * 300,
                deps[n],
                locs[n]);
        db.update("insert into activity_category values(?,?)", activity, n + 1);
        db.update("insert into activity_equipment values(?,4,'REQUIRED')", activity);
        for (int day : List.of(1, 3, 7)) {
          var start =
              LocalDate.now(ZoneId.of("America/Montevideo"))
                  .plusDays(day)
                  .atTime(14, 0)
                  .atZone(ZoneId.of("America/Montevideo"))
                  .toOffsetDateTime();
          db.insert(
              "insert into activity_instance(activity_id,starts_at,ends_at,capacity)"
                  + " values(?,?,?,12)",
              activity,
              start,
              start.plusHours(2));
        }
      }
    }
  }
}
