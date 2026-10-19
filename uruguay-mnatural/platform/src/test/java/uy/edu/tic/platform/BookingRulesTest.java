package uy.edu.tic.platform;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;
import uy.edu.tic.platform.domain.*;

class BookingRulesTest {
  private final Instant start = Instant.parse("2026-11-01T15:00:00Z");

  @Test
  void deadlineIsInclusive() {
    assertDoesNotThrow(() -> BookingRules.validate(start.minusSeconds(1800), start, 30, 2, 8, 6));
    assertThrows(
        BusinessException.class,
        () -> BookingRules.validate(start.minusSeconds(1799), start, 30, 1, 8, 6));
  }

  @Test
  void zeroLeadAllowsExactStart() {
    assertDoesNotThrow(() -> BookingRules.validate(start, start, 0, 1, 1, 0));
    assertThrows(
        BusinessException.class,
        () -> BookingRules.validate(start.plusNanos(1), start, 0, 1, 1, 0));
  }

  @Test
  void countsPeopleAndSupportsUnlimited() {
    assertThrows(
        BusinessException.class,
        () -> BookingRules.validate(start.minusSeconds(10), start, 0, 3, 8, 6));
    assertDoesNotThrow(
        () -> BookingRules.validate(start.minusSeconds(10), start, 0, 100, null, 9999));
  }

  @Test
  void exactMoney() {
    assertEquals(new BigDecimal("2400.00"), BookingRules.total(new BigDecimal("1200.00"), 2));
  }

  @Test
  void reviewRequiresPaymentAndEnd() {
    assertFalse(BookingRules.canReview(start.minusNanos(1), start, "APPROVED"));
    assertTrue(BookingRules.canReview(start, start, "APPROVED"));
    assertFalse(BookingRules.canReview(start.plusSeconds(86400), start, "PENDING"));
  }
}
