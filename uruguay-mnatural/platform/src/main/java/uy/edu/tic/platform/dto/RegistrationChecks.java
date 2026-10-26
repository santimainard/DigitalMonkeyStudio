package uy.edu.tic.platform.dto;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Shared registration rules for browser submissions and direct service callers. */
public final class RegistrationChecks {
  private RegistrationChecks() {}

  private static final String EMAIL =
      "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+";

  public static boolean validEmail(String value) {
    return value != null && value.strip().length() <= 254 && value.strip().matches(EMAIL);
  }

  public static boolean validPhone(String value) {
    if (value == null || value.strip().length() > 40) return false;
    String phone = value.strip();
    int digits = phone.replaceAll("[^0-9]", "").length();
    return phone.matches("\\+?[0-9 ()-]+") && digits >= 6 && digits <= 15;
  }

  private static void text(
      Map<String, String> errors, String field, String value, int max, String label) {
    if (value == null || value.isBlank()) errors.put(field, "Completá " + label + ".");
    else if (value.strip().length() > max) errors.put(field, "Usá hasta " + max + " caracteres.");
  }

  public static Map<String, String> errors(Forms.Registration f) {
    Map<String, String> errors = new LinkedHashMap<>();
    text(errors, "firstName", f.firstName, 80, "el nombre");
    text(errors, "lastName", f.lastName, 80, "el apellido");
    for (String field : List.of("firstName", "lastName")) {
      String value = field.equals("firstName") ? f.firstName : f.lastName;
      if (!errors.containsKey(field)
          && !value.strip().matches("[\\p{L}\\p{M}][\\p{L}\\p{M} .'’\\-]*"))
        errors.put(field, "Usá letras, espacios, apóstrofes o guiones, sin números.");
    }
    if (!validEmail(f.email))
      errors.put("email", "Ingresá un correo válido, como nombre@dominio.com.");
    if (f.password == null
        || f.password.length() < 8
        || f.password.getBytes(StandardCharsets.UTF_8).length > 72)
      errors.put(
          "password", "La contraseña necesita al menos 8 caracteres y hasta 72 bytes UTF-8.");
    if (f.passwordConfirmation == null || f.passwordConfirmation.isEmpty())
      errors.put("passwordConfirmation", "Repetí la contraseña.");
    else if (!f.passwordConfirmation.equals(f.password))
      errors.put("passwordConfirmation", "Las contraseñas no coinciden.");
    if (!"TOURIST".equals(f.role) && !"PROVIDER".equals(f.role))
      errors.put("role", "Elegí un tipo de cuenta válido.");
    if ("TOURIST".equals(f.role) || "PROVIDER".equals(f.role) && "PHYSICAL".equals(f.legalType))
      text(errors, "document", f.document, 40, "el documento del titular");
    if ("PROVIDER".equals(f.role)) {
      if (!"PHYSICAL".equals(f.legalType) && !"LEGAL".equals(f.legalType))
        errors.put("legalType", "Elegí una personería válida.");
      if ("LEGAL".equals(f.legalType)) {
        text(errors, "legalName", f.legalName, 200, "la razón social");
        if (f.taxId == null || !f.taxId.matches("[0-9]{12}"))
          errors.put(
              "taxId", "El RUT debe tener exactamente 12 dígitos, sin letras ni separadores.");
      }
      text(errors, "commercialName", f.commercialName, 150, "el nombre comercial");
      if (!validEmail(f.contactEmail))
        errors.put("contactEmail", "Ingresá un correo comercial válido, como nombre@dominio.com.");
      if (!validPhone(f.phone))
        errors.put(
            "phone",
            "Ingresá entre 6 y 15 dígitos; se permite + al inicio, espacios, paréntesis y guiones,"
                + " sin letras.");
      if (f.departmentId == null) errors.put("departmentId", "Seleccioná un departamento.");
      if (f.localityId == null) errors.put("localityId", "Seleccioná una localidad.");
      text(errors, "locationDescription", f.locationDescription, 300, "la dirección o referencia");
      text(errors, "description", f.description, 5000, "la descripción");
    }
    return errors;
  }
}
