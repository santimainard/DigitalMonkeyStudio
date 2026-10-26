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

  public boolean providerEnabled() {
    return "VERIFIED".equals(venture().get("verification_status"));
  }

  public void verifiedProvider() {
    role("PROVIDER");
    if (!providerEnabled())
      throw new AccessDeniedException(
          "Tu emprendimiento debe ser habilitado por un administrador.");
  }

  @Transactional
  public void deny(long ventureId, String reason, boolean allowRetry) {
    role("ADMIN");
    var v = db.one("select * from venture where id=? for update", ventureId);
    if (!"PENDING".equals(v.get("verification_status")) || v.get("denial_reason") != null)
      throw new BusinessException(
          "INVALID_INPUT", "Solo se puede denegar una solicitud pendiente.");
    db.update(
        "update venture set"
            + " denial_reason=?,allow_reapply=?,reviewed_by=?,reviewed_at=current_timestamp where"
            + " id=?",
        text(reason, 2000, "motivo de denegación"),
        allowRetry,
        id(),
        ventureId);
  }

  @Transactional
  public void reapply() {
    role("PROVIDER");
    var v = db.one("select * from venture where owner_id=? for update", id());
    if (v.get("denial_reason") == null || !Boolean.TRUE.equals(v.get("allow_reapply")))
      throw new AccessDeniedException("El administrador no permitió una nueva solicitud.");
    db.update("update venture set denial_reason=null,allow_reapply=false where id=?", v.get("id"));
  }

  public static String text(String value, int max, String label) {
    if (value == null || value.isBlank() || value.strip().length() > max)
      throw new BusinessException("INVALID_INPUT", "Revisá el campo " + label + ".");
    return value.strip();
  }

  private static String email(String value, String label) {
    String result = text(value, 254, label);
    if (!result.matches(
        "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+"))
      throw new BusinessException(
          "INVALID_INPUT", "Revisá el " + label + ": usá un formato como nombre@dominio.com.");
    return result;
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
    String email = email(f.email, "correo de acceso").toLowerCase(Locale.ROOT);
    if (db.count("select count(*) from app_user where email=?", email) > 0)
      throw new BusinessException("EMAIL_ALREADY_USED", "Ese correo ya está registrado.");
    if (f.password == null
        || f.password.length() < 8
        || f.password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new BusinessException(
          "INVALID_INPUT", "La contraseña necesita al menos 8 caracteres y hasta 72 bytes.");
    if (f.role.equals("TOURIST") || "PHYSICAL".equals(f.legalType))
      text(f.document, 40, "documento");
    if (!f.password.equals(f.passwordConfirmation))
      throw new BusinessException("INVALID_INPUT", "Las contraseñas no coinciden.");
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
        if (f.taxId == null || !f.taxId.matches("[0-9]{12}"))
          throw new BusinessException(
              "INVALID_INPUT",
              "El RUT debe contener exactamente 12 dígitos, sin letras ni separadores.");
      }
      String contact = email(f.contactEmail, "correo comercial");
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
    var v = db.one("select * from venture where id=? for update", id);
    if (v.get("denial_reason") != null)
      throw new BusinessException(
          "INVALID_INPUT", "El prestador debe volver a solicitar habilitación antes de aprobar.");
    db.update(
        "update venture set"
            + " verification_status='VERIFIED',verified_by=?,verified_at=current_timestamp where"
            + " id=? and verification_status='PENDING' and denial_reason is null",
        id(),
        id);
    db.one("select id from venture where id=?", id);
  }

  @Transactional
  public void updateVenture(
      String email, String phone, Long dep, Long loc, String address, String description) {
    verifiedProvider();
    location(dep, loc);
    email = email(email, "correo comercial");
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
