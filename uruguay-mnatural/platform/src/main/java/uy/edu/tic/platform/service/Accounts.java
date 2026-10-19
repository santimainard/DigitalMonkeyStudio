package uy.edu.tic.platform.service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.tic.platform.domain.BusinessException;
import uy.edu.tic.platform.dto.Forms.Registration;
import uy.edu.tic.platform.repository.Database;

@Service
public class Accounts {
  private final Database db;
  private final PasswordEncoder encoder;

  public Accounts(Database db, PasswordEncoder encoder) {
    this.db = db;
    this.encoder = encoder;
  }

  public Map<String, Object> current() {
    var a = SecurityContextHolder.getContext().getAuthentication();
    if (a == null || !a.isAuthenticated()) throw new AccessDeniedException("Iniciá sesión");
    return db.one("select * from app_user where email=?", a.getName());
  }

  public long id() {
    return ((Number) current().get("id")).longValue();
  }

  public Map<String, Object> venture() {
    return db.one("select * from venture where owner_id=?", id());
  }

  public void role(String expected) {
    if (!expected.equals(current().get("role")))
      throw new AccessDeniedException("No tenés permiso para esta acción.");
  }

  public static String text(String value, int max, String label) {
    if (value == null || value.isBlank() || value.strip().length() > max)
      throw new BusinessException("INVALID_INPUT", "Revisá el campo " + label + ".");
    return value.strip();
  }

  public void location(Long dep, Long loc) {
    if (dep == null
        || loc == null
        || db.count("select count(*) from locality where id=? and department_id=?", loc, dep) == 0)
      throw new BusinessException("INVALID_INPUT", "La localidad no corresponde al departamento.");
  }

  @Transactional
  public long register(Registration f) {
    if (!Set.of("TOURIST", "PROVIDER").contains(f.role))
      throw new BusinessException("INVALID_INPUT", "Rol inválido.");
    String email = text(f.email, 254, "correo").toLowerCase(Locale.ROOT);
    if (db.count("select count(*) from app_user where email=?", email) > 0)
      throw new BusinessException("EMAIL_ALREADY_USED", "Ese correo ya está registrado.");
    if (f.password == null
        || f.password.length() < 8
        || f.password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new BusinessException(
          "INVALID_INPUT", "La contraseña necesita al menos 8 caracteres y hasta 72 bytes.");
    if (f.role.equals("TOURIST") || "PHYSICAL".equals(f.legalType))
      text(f.document, 40, "documento");
    long userId =
        db.insert(
            "insert into app_user(first_name,last_name,email,document,password_hash,role)"
                + " values(?,?,?,?,?,?)",
            text(f.firstName, 80, "nombre"),
            text(f.lastName, 80, "apellido"),
            email,
            f.document,
            encoder.encode(f.password),
            f.role);
    if (f.role.equals("PROVIDER")) {
      if (!Set.of("PHYSICAL", "LEGAL").contains(f.legalType))
        throw new BusinessException("INVALID_INPUT", "Personería inválida.");
      location(f.departmentId, f.localityId);
      if (f.legalType.equals("LEGAL")) {
        text(f.legalName, 200, "razón social");
        text(f.taxId, 40, "RUT");
      }
      String contact = text(f.contactEmail, 254, "correo comercial");
      if (!contact.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))
        throw new BusinessException("INVALID_INPUT", "Correo comercial inválido.");
      db.insert(
          "insert into"
              + " venture(owner_id,legal_type,commercial_name,legal_name,tax_id,contact_email,phone,department_id,locality_id,location_description,description)"
              + " values(?,?,?,?,?,?,?,?,?,?,?)",
          userId,
          f.legalType,
          text(f.commercialName, 150, "nombre comercial"),
          f.legalName,
          f.taxId,
          contact,
          text(f.phone, 40, "teléfono"),
          f.departmentId,
          f.localityId,
          text(f.locationDescription, 300, "ubicación"),
          text(f.description, 5000, "descripción"));
    }
    return userId;
  }

  @Transactional
  public void approve(long id) {
    role("ADMIN");
    db.update(
        "update venture set"
            + " verification_status='VERIFIED',verified_by=?,verified_at=current_timestamp where"
            + " id=? and verification_status='PENDING'",
        id(),
        id);
    db.one("select id from venture where id=?", id);
  }

  @Transactional
  public void updateVenture(
      String email, String phone, Long dep, Long loc, String address, String description) {
    role("PROVIDER");
    location(dep, loc);
    email = text(email, 254, "correo");
    if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))
      throw new BusinessException("INVALID_INPUT", "Correo inválido.");
    db.update(
        "update venture set"
            + " contact_email=?,phone=?,department_id=?,locality_id=?,location_description=?,description=?"
            + " where owner_id=?",
        email,
        text(phone, 40, "teléfono"),
        dep,
        loc,
        text(address, 300, "ubicación"),
        text(description, 5000, "descripción"),
        id());
  }
}
