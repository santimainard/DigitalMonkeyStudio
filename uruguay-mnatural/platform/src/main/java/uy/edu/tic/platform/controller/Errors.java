package uy.edu.tic.platform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;
import uy.edu.tic.platform.domain.BusinessException;

@ControllerAdvice
public class Errors {
  private final ObjectMapper json;

  public Errors(ObjectMapper json) {
    this.json = json;
  }

  @ExceptionHandler({
    BusinessException.class,
    NoSuchElementException.class,
    AccessDeniedException.class,
    BindException.class,
    DataIntegrityViolationException.class,
    IllegalArgumentException.class,
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class,
    java.time.DateTimeException.class
  })
  public ModelAndView error(Exception e, HttpServletRequest request, HttpServletResponse response)
      throws Exception {
    String code = "INVALID_INPUT", message = "Revisá los datos enviados.";
    int status = 400;
    List<Map<String, String>> fields = new ArrayList<>();
    if (e instanceof BusinessException b) {
      code = b.code;
      message = b.getMessage();
      status =
          "PAYMENT_SERVICE_UNAVAILABLE".equals(code)
              ? 503
              : (Set.of("INVALID_INPUT", "INVALID_PAYMENT").contains(code) ? 400 : 409);
    } else if (e instanceof NoSuchElementException) {
      status = 404;
      code = "NOT_FOUND";
      message = "No se encontró el recurso.";
    } else if (e instanceof AccessDeniedException) {
      status = 403;
      code = "FORBIDDEN";
      message = "No tenés permiso para acceder.";
    } else if (e instanceof DataIntegrityViolationException) {
      status = 409;
      code = "DATA_CONFLICT";
      message =
          "Los datos ya existen o están siendo utilizados. Revisá correos, nombres y fechas"
              + " duplicadas.";
    } else if (e instanceof BindException b)
      b.getFieldErrors()
          .forEach(
              f ->
                  fields.add(
                      Map.of(
                          "field",
                          f.getField(),
                          "message",
                          Objects.toString(f.getDefaultMessage(), "Valor inválido"))));
    String correlation = UUID.randomUUID().toString();
    response.setStatus(status);
    response.setCharacterEncoding("UTF-8");
    if (request.getRequestURI().startsWith("/api/")) {
      response.setContentType("application/json");
      json.writeValue(
          response.getWriter(),
          Map.of(
              "code",
              code,
              "message",
              message,
              "fieldErrors",
              fields,
              "correlationId",
              correlation));
      return null;
    }
    return new ModelAndView(
        "error",
        Map.of("message", message, "fields", fields, "code", code, "correlationId", correlation));
  }
}
