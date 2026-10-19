package uy.edu.tic.platform.config;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Component;
import uy.edu.tic.platform.domain.BookingRules;
import uy.edu.tic.platform.service.Tourism;

@Component("fmt")
public class ViewHelpers {
  public String date(Object date) {
    return date == null
        ? ""
        : BookingRules.instant(date)
            .atZone(Tourism.ZONE)
            .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
  }

  public String input(Object date) {
    return date == null
        ? ""
        : BookingRules.instant(date)
            .atZone(Tourism.ZONE)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
  }

  public String rating(Object n) {
    return n == null
        ? "Sin valoraciones"
        : String.format(Locale.forLanguageTag("es-UY"), "%.1f", ((Number) n).doubleValue());
  }

  public String state(Object state) {
    return switch (Objects.toString(state, "")) {
      case "PENDING_PAYMENT" -> "Pendiente de pago";
      case "PAYMENT_UNCERTAIN", "UNKNOWN" -> "Comprobando pago";
      case "CONFIRMED" -> "Confirmada";
      case "COMPLETED" -> "Finalizada";
      case "REJECTED" -> "Rechazada";
      case "EXPIRED" -> "Vencida";
      case "PAYMENT_CANCELLED", "CANCELLED" -> "Pago cancelado";
      case "APPROVED" -> "Aprobado";
      case "VERIFIED" -> "Verificado";
      case "PENDING" -> "Pendiente";
      default -> Objects.toString(state, "");
    };
  }
}
