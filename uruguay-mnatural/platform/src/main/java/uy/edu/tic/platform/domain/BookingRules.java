package uy.edu.tic.platform.domain;

import java.math.BigDecimal;
import java.time.*;

public final class BookingRules {
  private BookingRules() {}

  public static void validate(
      Instant now, Instant start, int lead, int participants, Integer capacity, long committed) {
    if (participants < 1)
      throw new BusinessException("INVALID_INPUT", "La cantidad debe ser positiva.");
    if (now.isAfter(start.minusSeconds(lead * 60L)))
      throw new BusinessException("BOOKING_DEADLINE_PASSED", "Terminó el plazo para reservar.");
    if (capacity != null && participants > capacity - committed)
      throw new BusinessException("NO_CAPACITY", "No hay lugares suficientes.");
  }

  public static BigDecimal total(BigDecimal price, int participants) {
    return price.multiply(BigDecimal.valueOf(participants)).setScale(2);
  }

  public static boolean canReview(Instant now, Instant end, String payment) {
    return "APPROVED".equals(payment) && !now.isBefore(end);
  }

  public static Instant instant(Object value) {
    if (value instanceof Instant i) return i;
    if (value instanceof OffsetDateTime o) return o.toInstant();
    if (value instanceof java.sql.Timestamp t) return t.toInstant();
    return Instant.parse(value.toString());
  }

  public static OffsetDateTime utc(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }
}
