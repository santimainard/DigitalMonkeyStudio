package uy.edu.tic.platform.controller;

import java.util.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import uy.edu.tic.platform.domain.BusinessException;
import uy.edu.tic.platform.integration.PaymentClient;
import uy.edu.tic.platform.service.*;

@Controller
public class PaymentMethodsController {
  private final PaymentMethods methods;
  private final Accounts accounts;

  public PaymentMethodsController(PaymentMethods methods, Accounts accounts) {
    this.methods = methods;
    this.accounts = accounts;
  }

  @GetMapping("/tourist/payment-methods")
  String page(Model m) {
    accounts.role("TOURIST");
    m.addAttribute("viewer", accounts.current());
    m.addAttribute("methods", methods.list());
    m.addAttribute("registrationKey", UUID.randomUUID());
    return "payment-methods";
  }

  @PostMapping("/tourist/payment-methods")
  String register(
      @RequestParam UUID key,
      @RequestParam String holder,
      @RequestParam String number,
      @RequestParam String cvv,
      @RequestParam int expiryMonth,
      @RequestParam int expiryYear,
      RedirectAttributes flash) {
    var card = new PaymentClient.Card();
    card.holder = holder;
    card.number = number;
    card.cvv = cvv;
    card.expiryMonth = expiryMonth;
    card.expiryYear = expiryYear;
    try {
      methods.register(key, card);
      flash.addFlashAttribute("success", "Tarjeta de prueba registrada.");
    } catch (BusinessException e) {
      flash.addFlashAttribute("cardError", e.getMessage());
    }
    return "redirect:/tourist/payment-methods";
  }

  @PostMapping("/tourist/payment-methods/{id}/deactivate")
  String deactivate(@PathVariable UUID id) {
    methods.deactivate(id);
    return "redirect:/tourist/payment-methods";
  }

  @GetMapping("/api/v1/payment-methods")
  @ResponseBody
  Object list() {
    return ApiController.camel(methods.list());
  }

  @PostMapping("/api/v1/payment-methods")
  @ResponseBody
  Object registerApi(
      @RequestHeader("Idempotency-Key") UUID key, @RequestBody PaymentClient.Card card) {
    return Map.of("id", methods.register(key, card));
  }

  @PostMapping("/api/v1/payment-methods/{id}/deactivate")
  @ResponseBody
  Object deactivateApi(@PathVariable UUID id) {
    methods.deactivate(id);
    return Map.of("id", id, "active", false);
  }
}
