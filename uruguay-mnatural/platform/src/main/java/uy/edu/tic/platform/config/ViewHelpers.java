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

  public String day(Object date) {
    return BookingRules.instant(date)
        .atZone(Tourism.ZONE)
        .format(DateTimeFormatter.ofPattern("dd"));
  }

  public String month(Object date) {
    return BookingRules.instant(date)
        .atZone(Tourism.ZONE)
        .format(DateTimeFormatter.ofPattern("MMM", Locale.forLanguageTag("es-UY")));
  }

  public String longDate(Object date) {
    return date == null
        ? ""
        : BookingRules.instant(date)
            .atZone(Tourism.ZONE)
            .format(
                DateTimeFormatter.ofPattern(
                    "EEEE d 'de' MMMM · HH:mm", Locale.forLanguageTag("es-UY")));
  }

  public String rating(Object n) {
    return n == null
        ? "Sin valoraciones"
        : String.format(Locale.forLanguageTag("es-UY"), "%.1f", ((Number) n).doubleValue());
  }

  public String paymentReason(Object reason) {
    return switch (Objects.toString(reason, "")) {
      case "CARD_EXPIRED" -> "La tarjeta de prueba está vencida. Elegí otro método.";
      case "METHOD_DISABLED" -> "El método de prueba está inhabilitado. Elegí otro método.";
      case "METHOD_NOT_FOUND" -> "No se encontró un método válido para este titular.";
      default ->
          "El pago fue rechazado. Podés elegir otro método mientras siga vigente la reserva.";
    };
  }

  public String state(Object state) {
    return switch (Objects.toString(state, "")) {
      case "CREATED" -> "Sin iniciar";
      case "REQUESTED" -> "Procesando pago";
      case "PENDING_PAYMENT" -> "Pendiente de pago";
      case "PAYMENT_UNCERTAIN", "UNKNOWN" -> "Comprobando pago";
      case "CONFIRMED" -> "Confirmada";
      case "COMPLETED" -> "Finalizada";
      case "REJECTED" -> "Rechazada";
      case "EXPIRED" -> "Vencida";
      case "PAYMENT_CANCELLED", "CANCELLED" -> "Cancelada";
      case "REFUNDED" -> "Reembolso procesado";
      case "DENIED" -> "Denegado";
      case "APPROVED" -> "Aprobado";
      case "VERIFIED" -> "Verificado";
      case "PENDING" -> "Pendiente";
      default -> Objects.toString(state, "");
    };
  }
}
