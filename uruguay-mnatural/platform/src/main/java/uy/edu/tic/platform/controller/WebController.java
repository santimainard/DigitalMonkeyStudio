package uy.edu.tic.platform.controller;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import uy.edu.tic.platform.domain.*;
import uy.edu.tic.platform.dto.Forms.*;
import uy.edu.tic.platform.repository.Database;
import uy.edu.tic.platform.service.*;

@Controller
public class WebController {
  private final Database db;
  private final Accounts accounts;
  private final Tourism tourism;
  private final ReservationTransactions reservations;
  private final Payments payments;
  private final CancellationTransactions cancellations;

  public WebController(
      Database db,
      Accounts accounts,
      Tourism tourism,
      ReservationTransactions reservations,
      Payments payments,
      CancellationTransactions cancellations) {
    this.db = db;
    this.accounts = accounts;
    this.tourism = tourism;
    this.reservations = reservations;
    this.payments = payments;
    this.cancellations = cancellations;
  }

  @InitBinder
  void bind(WebDataBinder binder) {
    binder.initDirectFieldAccess();
  }

  @ModelAttribute
  void common(Model m) {
    m.addAttribute("departments", db.list("select * from department order by name"));
    m.addAttribute("localities", db.list("select * from locality order by name"));
    m.addAttribute("categories", db.list("select * from category order by name"));
    m.addAttribute("equipmentTypes", db.list("select * from equipment_type order by name"));
    try {
      m.addAttribute("viewer", accounts.current());
    } catch (Exception ignored) {
    }
  }

  @GetMapping("/")
  String entry() {
    return "redirect:/dashboard";
  }

  @GetMapping("/explore")
  String home(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(required = false) Long departmentId,
      @RequestParam(required = false) Long localityId,
      @RequestParam(required = false) List<Long> categoryIds,
      @RequestParam(required = false) LocalDate date,
      @RequestParam(required = false) BigDecimal min,
      @RequestParam(required = false) BigDecimal max,
      @RequestParam(defaultValue = "0") int page,
      Model m) {
    m.addAttribute(
        "result",
        tourism.search(q, departmentId, localityId, categoryIds, date, min, max, page, 20));
    return "home";
  }

  @GetMapping("/activities/{id}")
  String detail(@PathVariable long id, @RequestParam(defaultValue = "0") int reviewPage, Model m) {
    Tourism.page(reviewPage);
    var a = tourism.activity(id, true);
    tourism.addRating(a);
    m.addAttribute("activity", a);
    m.addAttribute("instances", tourism.instances(id, true));
    m.addAttribute("activityCategories", tourism.categories(id));
    m.addAttribute("equipment", tourism.equipment(id));
    m.addAttribute(
        "reviews",
        db.list(
            "select r.rating,r.comment,r.created_at,u.first_name from review r join reservation b"
                + " on b.id=r.reservation_id join activity_instance i on i.id=b.instance_id join"
                + " app_user u on u.id=b.tourist_id where i.activity_id=? order by r.created_at"
                + " desc,r.id desc limit 20 offset ?",
            id,
            reviewPage * 20L));
    m.addAttribute("reviewPage", reviewPage);
    return "detail";
  }

  @GetMapping("/login")
  String login() {
    return "login";
  }

  @GetMapping("/register")
  String register(@RequestParam(defaultValue = "TOURIST") String role, Model m) {
    Registration f = new Registration();
    f.role = "PROVIDER".equals(role) ? "PROVIDER" : "TOURIST";
    m.addAttribute("form", f);
    return "register";
  }

  @PostMapping("/register")
  String register(
      @Valid @ModelAttribute("form") Registration f,
      BindingResult errors,
      RedirectAttributes flash) {
    if (errors.hasErrors()) return "register";
    try {
      accounts.register(f);
    } catch (BusinessException e) {
      errors.reject("registration", e.getMessage());
      return "register";
    }
    flash.addFlashAttribute("success", "Cuenta creada. Ya podés iniciar sesión.");
    return "redirect:/login";
  }

  @GetMapping("/dashboard")
  String dashboard() {
    return switch (accounts.current().get("role").toString()) {
      case "ADMIN" -> "redirect:/admin";
      case "PROVIDER" -> "redirect:/provider";
      default -> "redirect:/tourist/reservations";
    };
  }

  @GetMapping("/tourist/prepare")
  String prepare(
      @RequestParam long instanceId,
      @RequestParam(defaultValue = "1") int participantCount,
      Model m) {
    accounts.role("TOURIST");
    if (participantCount < 1 || participantCount > 100000)
      throw new BusinessException("INVALID_INPUT", "Cantidad inválida.");
    var i = db.one("select * from activity_instance where id=?", instanceId);
    var a = tourism.activity(((Number) i.get("activity_id")).longValue(), true);
    m.addAttribute("instance", i);
    m.addAttribute("activity", a);
    m.addAttribute("count", participantCount);
    m.addAttribute("total", BookingRules.total((BigDecimal) a.get("unit_price"), participantCount));
    m.addAttribute("key", UUID.randomUUID().toString());
    return "prepare";
  }

  @PostMapping("/tourist/reservations")
  String reserve(
      @RequestParam long instanceId, @RequestParam int participantCount, @RequestParam String key) {
    var id = reservations.reserve(instanceId, participantCount, key);
    payments.synchronize(id);
    return "redirect:/tourist/reservations/" + id;
  }

  @PostMapping("/tourist/reservations/{id}/cancel")
  String cancel(@PathVariable UUID id) {
    cancellations.tourist(id);
    payments.settleCancellation(id);
    return "redirect:/tourist/reservations/" + id;
  }

  @PostMapping("/provider/activities/{id}/instances/{instanceId}/cancel")
  String cancelInstance(@PathVariable long id, @PathVariable long instanceId) {
    for (UUID reservation : cancellations.provider(id, instanceId))
      payments.settleCancellation(reservation);
    return "redirect:/provider/activities/" + id + "/instances";
  }

  @GetMapping("/tourist/reservations")
  String history(@RequestParam(defaultValue = "0") int page, Model m) {
    Tourism.page(page);
    reservations.complete();
    long total = db.count("select count(*) from reservation where tourist_id=?", accounts.id());
    m.addAttribute(
        "result",
        Tourism.pagination(
            db.list(
                "select r.*,p.status payment_status from reservation r join payment p on"
                    + " p.reservation_id=r.id where r.tourist_id=? order by r.created_at desc,r.id"
                    + " limit 20 offset ?",
                accounts.id(),
                page * 20L),
            page,
            20,
            total));
    return "history";
  }

  @GetMapping("/tourist/reservations/{id}")
  String reservation(@PathVariable UUID id, Model m) {
    m.addAttribute("reservation", reservations.detail(id));
    return "reservation";
  }

  @PostMapping("/tourist/reservations/{id}/refresh")
  String refresh(@PathVariable UUID id) {
    reservations.detail(id);
    payments.synchronize(id);
    if (db.count("select count(*) from cancellation where reservation_id=?", id) > 0)
      payments.settleCancellation(id);
    return "redirect:/tourist/reservations/" + id;
  }

  @PostMapping("/tourist/reservations/{id}/review")
  String review(
      @PathVariable UUID id, @Valid @ModelAttribute ReviewRequest f, RedirectAttributes flash) {
    reservations.review(id, f.rating(), f.comment());
    flash.addFlashAttribute("success", "Tu reseña fue publicada.");
    return "redirect:/tourist/reservations/" + id;
  }

  @ModelAttribute
  void providerGate(jakarta.servlet.http.HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (path.startsWith("/provider/") && !path.equals("/provider/reapply"))
      accounts.verifiedProvider();
  }

  @PostMapping("/provider/reapply")
  String reapply() {
    accounts.reapply();
    return "redirect:/provider";
  }

  @PostMapping("/admin/ventures/{id}/deny")
  String deny(
      @PathVariable long id,
      @RequestParam String reason,
      @RequestParam(defaultValue = "false") boolean allowRetry) {
    accounts.deny(id, reason, allowRetry);
    return "redirect:/admin";
  }

  @GetMapping("/provider")
  String provider(Model m) {
    var venture = accounts.venture();
    m.addAttribute("venture", venture);
    if (!"VERIFIED".equals(venture.get("verification_status"))) return "provider-status";
    m.addAttribute(
        "activities",
        db.list(
            "select a.* from activity a join venture v on v.id=a.venture_id where v.owner_id=?"
                + " order by a.id desc",
            accounts.id()));
    return "provider";
  }

  @PostMapping("/provider/venture")
  String venture(
      @RequestParam String contactEmail,
      @RequestParam String phone,
      @RequestParam Long departmentId,
      @RequestParam Long localityId,
      @RequestParam String locationDescription,
      @RequestParam String description,
      RedirectAttributes flash) {
    accounts.updateVenture(
        contactEmail, phone, departmentId, localityId, locationDescription, description);
    flash.addFlashAttribute("success", "Emprendimiento actualizado.");
    return "redirect:/provider";
  }

  @GetMapping("/provider/activities/new")
  String newActivity(Model m) {
    m.addAttribute("form", new ActivityForm());
    return "activity-form";
  }

  @GetMapping("/provider/activities/{id}/edit")
  String edit(@PathVariable long id, Model m) {
    var a = tourism.owned(id);
    ActivityForm f = new ActivityForm();
    f.name = (String) a.get("name");
    f.description = (String) a.get("description");
    f.unitPrice = (BigDecimal) a.get("unit_price");
    f.durationMinutes = (Integer) a.get("duration_minutes");
    f.bookingLeadMinutes = (Integer) a.get("booking_lead_minutes");
    f.fullRefundMinutes = (Integer) a.get("full_refund_minutes");
    f.lateRefundPercent = (Integer) a.get("late_refund_percent");
    f.departmentId = ((Number) a.get("department_id")).longValue();
    f.localityId = ((Number) a.get("locality_id")).longValue();
    f.locationDescription = (String) a.get("location_description");
    f.minAge = (Integer) a.get("min_age");
    f.conditions = (String) a.get("conditions");
    f.published = (Boolean) a.get("is_published");
    f.categoryIds =
        tourism.categories(id).stream().map(c -> ((Number) c.get("id")).longValue()).toList();
    for (var e : tourism.equipment(id)) {
      ("PROVIDED".equals(e.get("mode")) ? f.providedIds : f.requiredIds)
          .add(((Number) e.get("id")).longValue());
    }
    m.addAttribute("form", f);
    m.addAttribute("activityId", id);
    return "activity-form";
  }

  @PostMapping("/provider/activities")
  String save(
      @RequestParam(required = false) Long activityId,
      @Valid @ModelAttribute("form") ActivityForm f,
      BindingResult errors,
      Model m) {
    m.addAttribute("activityId", activityId);
    if (errors.hasErrors()) return "activity-form";
    try {
      long id = tourism.saveActivity(activityId, f);
      return "redirect:/provider/activities/" + id + "/instances";
    } catch (BusinessException e) {
      errors.reject("activity", e.getMessage());
      return "activity-form";
    }
  }

  @GetMapping("/provider/activities/{id}/instances")
  String instances(@PathVariable long id, Model m) {
    m.addAttribute("activity", tourism.owned(id));
    m.addAttribute("instances", tourism.instances(id, false));
    return "instances";
  }

  @PostMapping("/provider/activities/{id}/instances")
  String instance(
      @PathVariable long id,
      @RequestParam(required = false) Long instanceId,
      @RequestParam(required = false) LocalDateTime startsAt,
      @RequestParam(required = false) Integer capacity,
      @RequestParam(defaultValue = "false") boolean delete) {
    tourism.saveInstance(id, instanceId, startsAt, capacity, delete);
    return "redirect:/provider/activities/" + id + "/instances";
  }

  @GetMapping("/provider/reservations")
  String providerReservations(
      @RequestParam(required = false) Long activityId,
      @RequestParam(required = false) Long instanceId,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "0") int page,
      Model m) {
    Tourism.page(page);
    String from =
        " from reservation r join payment p on p.reservation_id=r.id join activity_instance i on"
            + " i.id=r.instance_id join activity a on a.id=i.activity_id join venture v on"
            + " v.id=a.venture_id join app_user u on u.id=r.tourist_id left join cancellation c on"
            + " c.reservation_id=r.id where v.owner_id=?";
    List<Object> args = new ArrayList<>(List.of(accounts.id()));
    if (activityId != null) {
      from += " and a.id=?";
      args.add(activityId);
    }
    if (instanceId != null) {
      from += " and i.id=?";
      args.add(instanceId);
    }
    if (!status.isBlank()) {
      from += " and r.status=?";
      args.add(status);
    }
    long total = db.count("select count(*)" + from, args.toArray());
    args.add(page * 20L);
    m.addAttribute(
        "result",
        Tourism.pagination(
            db.list(
                "select r.*,p.status"
                    + " payment_status,u.first_name,u.last_name,u.email,u.document,c.requested_at"
                    + " cancellation_requested_at,c.completed_at"
                    + " cancellation_completed_at,c.refunded_amount,c.refund_percent"
                    + " cancellation_refund_percent"
                    + from
                    + " order by r.created_at desc,r.id limit 20 offset ?",
                args.toArray()),
            page,
            20,
            total));
    m.addAttribute(
        "people",
        db.count(
            "select coalesce(sum(r.participant_count),0)"
                + from
                + " and r.status in ("
                + Tourism.COMMITTED
                + ")",
            args.subList(0, args.size() - 1).toArray()));
    return "provider-reservations";
  }

  @GetMapping("/admin")
  String admin(Model m) {
    m.addAttribute(
        "ventures",
        db.list(
            "select v.*,u.first_name,u.last_name,u.document from venture v join app_user u on"
                + " u.id=v.owner_id order by v.verification_status,v.id"));
    return "admin";
  }

  @PostMapping("/admin/ventures/{id}/approve")
  String approve(@PathVariable long id) {
    accounts.approve(id);
    return "redirect:/admin";
  }

  @PostMapping("/admin/catalogs/{kind}")
  String catalog(
      @PathVariable String kind,
      @RequestParam(required = false) Long id,
      @RequestParam(defaultValue = "") String name,
      @RequestParam(defaultValue = "save") String action) {
    tourism.catalog(kind, id, name, action);
    return "redirect:/admin";
  }
}
