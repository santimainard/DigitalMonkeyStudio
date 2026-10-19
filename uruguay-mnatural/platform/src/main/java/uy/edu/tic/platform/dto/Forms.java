package uy.edu.tic.platform.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;

public final class Forms {
  public static class Registration {
    @NotBlank
    @Size(max = 80)
    public String firstName;

    @NotBlank
    @Size(max = 80)
    public String lastName;

    @NotBlank
    @Email
    @Size(max = 254)
    public String email;

    @Size(max = 40)
    public String document;

    @NotBlank
    @Size(min = 8, max = 72)
    public String password;

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
