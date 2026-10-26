package uy.edu.tic.platform.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;

public final class Forms {
  public static class Registration {
    @NotBlank(message = "Completá este campo.")
    @Size(max = 80, message = "Usá hasta 80 caracteres.")
    public String firstName;

    @NotBlank(message = "Completá este campo.")
    @Size(max = 80, message = "Usá hasta 80 caracteres.")
    public String lastName;

    @NotBlank(message = "Completá este campo.")
    @Email(message = "Ingresá un correo válido, como nombre@dominio.com.")
    @Size(max = 254, message = "Usá hasta 254 caracteres.")
    public String email;

    @Size(max = 40, message = "Usá hasta 40 caracteres.")
    public String document;

    @NotBlank(message = "Completá este campo.")
    @Size(min = 8, max = 72, message = "Usá entre 8 y 72 caracteres para la contraseña.")
    public String password;

    @NotBlank(message = "Repetí la contraseña.")
    @Size(max = 72, message = "Usá hasta 72 caracteres.")
    public String passwordConfirmation;

    public String role = "TOURIST";
    public String legalType = "PHYSICAL",
        commercialName,
        legalName,
        taxId,
        contactEmail,
        phone,
        locationDescription,
        description;
    public Long departmentId, localityId;
  }

  public static class ActivityForm {
    @NotBlank
    @Size(max = 150)
    public String name;

    @NotBlank
    @Size(max = 5000)
    public String description;

    @NotNull
    @DecimalMin("0.01")
    @Digits(integer = 10, fraction = 2)
    public BigDecimal unitPrice;

    @Min(1)
    @Max(525600)
    public int durationMinutes = 60;

    @Min(0)
    @Max(525600)
    public int bookingLeadMinutes;

    @Min(0)
    @Max(525600)
    public int fullRefundMinutes;

    @Min(0)
    @Max(100)
    public int lateRefundPercent = 100;

    @NotNull public Long departmentId;
    @NotNull public Long localityId;

    @NotBlank
    @Size(max = 300)
    public String locationDescription;

    @Min(0)
    @Max(120)
    public Integer minAge;

    @NotBlank
    @Size(max = 5000)
    public String conditions;

    public boolean published;
    @NotEmpty public List<Long> categoryIds = new ArrayList<>();
    public List<Long> providedIds = new ArrayList<>(), requiredIds = new ArrayList<>();
  }

  public record ReservationRequest(
      @NotNull Long instanceId, @Min(1) @Max(100000) int participantCount) {}

  public record ReviewRequest(@Min(1) @Max(5) int rating, @Size(max = 2000) String comment) {}

  public record PaymentEvent(
      @NotBlank @Size(max = 100) String eventId,
      @NotBlank @Size(max = 100) String paymentId,
      @NotBlank @Size(max = 100) String merchantReference,
      String status) {}
}
