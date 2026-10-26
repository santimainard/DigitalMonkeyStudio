package uy.edu.tic.platform.service;

import static uy.edu.tic.platform.domain.BookingRules.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.tic.platform.domain.*;
import uy.edu.tic.platform.dto.Forms.ActivityForm;
import uy.edu.tic.platform.repository.Database;

@Service
public class Tourism {
  public static final ZoneId ZONE = ZoneId.of("America/Montevideo");
  public static final String COMMITTED =
      "'PENDING_PAYMENT','PAYMENT_UNCERTAIN','CONFIRMED','COMPLETED'";
  private final Database db;
  private final Accounts accounts;
  private final Clock clock;

  public Tourism(Database db, Accounts accounts, Clock clock) {
    this.db = db;
    this.accounts = accounts;
    this.clock = clock;
  }

  public static int size(int size) {
    if (size < 1 || size > 100)
      throw new BusinessException("INVALID_INPUT", "Tamaño de página entre 1 y 100.");
    return size;
  }

  public static int page(int page) {
    if (page < 0 || page > 1000000)
      throw new BusinessException("INVALID_INPUT", "Página inválida.");
    return page;
  }

  public Map<String, Object> activity(long id, boolean publicOnly) {
    return db.one(
        "select a.*,v.commercial_name,v.owner_id,v.verification_status,d.name"
            + " department_name,l.name locality_name from activity a join venture v on"
            + " v.id=a.venture_id join department d on d.id=a.department_id join locality l on"
            + " l.id=a.locality_id where a.id=?"
            + (publicOnly ? " and a.is_published=true and v.verification_status='VERIFIED'" : ""),
        id);
  }

  public Map<String, Object> owned(long id) {
    accounts.verifiedProvider();
    var a = activity(id, false);
    if (((Number) a.get("owner_id")).longValue() != accounts.id())
      throw new AccessDeniedException("Actividad ajena.");
    return a;
  }

  public Map<String, Object> search(
      String q,
      Long dep,
      Long loc,
      List<Long> categories,
      LocalDate date,
      BigDecimal min,
      BigDecimal max,
      int page,
      int size) {
    page(page);
    size(size);
    if (min != null && min.signum() < 0
        || max != null && max.signum() < 0
        || min != null && max != null && min.compareTo(max) > 0)
      throw new BusinessException("INVALID_INPUT", "Rango de precio inválido.");
    if (dep != null && loc != null) accounts.location(dep, loc);
    String from =
        " from activity a join venture v on v.id=a.venture_id join department d on"
            + " d.id=a.department_id join locality l on l.id=a.locality_id where"
            + " a.is_published=true and v.verification_status='VERIFIED'";
    List<Object> args = new ArrayList<>();
    if (q != null && !q.isBlank()) {
      String s =
          "%"
              + q.strip()
                  .toLowerCase(Locale.ROOT)
                  .replace("!", "!!")
                  .replace("%", "!%")
                  .replace("_", "!_")
              + "%";
      from +=
          " and (lower(a.name) like ? escape '!' or lower(a.description) like ? escape '!' or"
              + " lower(v.commercial_name) like ? escape '!')";
      args.addAll(List.of(s, s, s));
    }
    if (dep != null) {
      from += " and a.department_id=?";
      args.add(dep);
    }
    if (loc != null) {
      from += " and a.locality_id=?";
      args.add(loc);
    }
    if (min != null) {
      from += " and a.unit_price>=?";
      args.add(min);
    }
    if (max != null) {
      from += " and a.unit_price<=?";
      args.add(max);
    }
    if (categories != null && !categories.isEmpty()) {
      from +=
          " and exists(select 1 from activity_category ac where ac.activity_id=a.id and"
              + " ac.category_id in ("
              + String.join(",", Collections.nCopies(categories.size(), "?"))
              + "))";
      args.addAll(categories);
    }
    if (date != null) {
      from +=
          " and exists(select 1 from activity_instance ai where ai.activity_id=a.id and"
              + " ai.active=true and ai.starts_at>=? and ai.starts_at<?)";
      args.add(date.atStartOfDay(ZONE).toOffsetDateTime());
      args.add(date.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
    }
    long total = db.count("select count(*)" + from, args.toArray());
    args.add(size);
    args.add((long) page * size);
    var rows =
        db.list(
            "select a.*,v.commercial_name,d.name department_name,l.name locality_name"
                + from
                + " order by a.name,a.id limit ? offset ?",
            args.toArray());
    for (var a : rows) addRating(a);
    return pagination(rows, page, size, total);
  }

  public static Map<String, Object> pagination(List<?> content, int page, int size, long total) {
    return Map.of(
        "content",
        content,
        "page",
        page,
        "size",
        size,
        "totalElements",
        total,
        "totalPages",
        (total + size - 1) / size);
  }

  public void addRating(Map<String, Object> a) {
    var r =
        db.one(
            "select avg(cast(r.rating as numeric)) average_rating,count(*) review_count from review"
                + " r join reservation b on b.id=r.reservation_id join activity_instance i on"
                + " i.id=b.instance_id where i.activity_id=?",
            a.get("id"));
    a.putAll(r);
  }

  public List<Map<String, Object>> categories(long activity) {
    return db.list(
        "select c.* from category c join activity_category ac on ac.category_id=c.id where"
            + " ac.activity_id=? order by c.name",
        activity);
  }

  public List<Map<String, Object>> equipment(long activity) {
    return db.list(
        "select e.*,ae.mode from equipment_type e join activity_equipment ae on"
            + " ae.equipment_id=e.id where ae.activity_id=? order by e.name",
        activity);
  }

  public long committed(long instance) {
    return db.count(
        "select coalesce(sum(participant_count),0) from reservation where instance_id=? and status"
            + " in ("
            + COMMITTED
            + ")",
        instance);
  }

  public void decorateInstance(Map<String, Object> i, int lead) {
    var now = clock.instant();
    var start = instant(i.get("starts_at"));
    long used = committed(((Number) i.get("id")).longValue());
    Integer cap = (Integer) i.get("capacity");
    i.put("available", cap == null ? null : cap - used);
    i.put("deadline", utc(start.minusSeconds(lead * 60L)));
    i.put(
        "bookable",
        Boolean.TRUE.equals(i.get("active"))
            && !now.isAfter(start.minusSeconds(lead * 60L))
            && (cap == null || cap > used));
  }

  public List<Map<String, Object>> instances(long activity, boolean futureOnly) {
    var a = activity(activity, false);
    var list =
        db.list(
            "select * from activity_instance where activity_id=?"
                + (futureOnly ? " and active=true and starts_at>=?" : "")
                + " order by starts_at",
            futureOnly ? new Object[] {activity, utc(clock.instant())} : new Object[] {activity});
    for (var i : list) decorateInstance(i, (Integer) a.get("booking_lead_minutes"));
    return list;
  }

  @Transactional
  public long saveActivity(Long id, ActivityForm f) {
    accounts.verifiedProvider();
    var v = accounts.venture();
    if (id != null) owned(id);
    accounts.location(f.departmentId, f.localityId);
    if (f.published && !"VERIFIED".equals(v.get("verification_status")))
      throw new BusinessException(
          "VENTURE_NOT_VERIFIED",
          "El administrador debe aprobar el emprendimiento antes de publicar.");
    Set<Long> cats = new HashSet<>(f.categoryIds),
        pro = new HashSet<>(f.providedIds),
        req = new HashSet<>(f.requiredIds);
    if (cats.isEmpty() || !Collections.disjoint(pro, req))
      throw new BusinessException(
          "INVALID_INPUT", "Seleccioná categorías y evitá equipamiento repetido.");
    var oldCats =
        id == null
            ? Set.of()
            : new HashSet<>(
                categories(id).stream().map(r -> ((Number) r.get("id")).longValue()).toList());
    for (long c : cats)
      if (db.count("select count(*) from category where id=? and active=true", c) == 0
          && !oldCats.contains(c))
        throw new BusinessException("INVALID_INPUT", "Categoría inactiva.");
    if (cats.stream()
        .noneMatch(
            c -> db.count("select count(*) from category where id=? and active=true", c) > 0))
      throw new BusinessException("INVALID_INPUT", "Elegí al menos una categoría activa.");
    var oldEquipment =
        id == null
            ? Set.of()
            : new HashSet<>(
                equipment(id).stream().map(r -> ((Number) r.get("id")).longValue()).toList());
    for (long e : java.util.stream.Stream.concat(pro.stream(), req.stream()).toList())
      if (db.count("select count(*) from equipment_type where id=? and active=true", e) == 0
          && !oldEquipment.contains(e))
        throw new BusinessException("INVALID_INPUT", "Equipamiento inactivo.");
    if (id == null)
      id =
          db.insert(
              "insert into"
                  + " activity(venture_id,name,description,unit_price,duration_minutes,booking_lead_minutes,department_id,locality_id,location_description,min_age,conditions,is_published)"
                  + " values(?,?,?,?,?,?,?,?,?,?,?,?)",
              v.get("id"),
              f.name.strip(),
              f.description.strip(),
              f.unitPrice,
              f.durationMinutes,
              f.bookingLeadMinutes,
              f.departmentId,
              f.localityId,
              f.locationDescription.strip(),
              f.minAge,
              f.conditions.strip(),
              f.published);
    else
      db.update(
          "update activity set"
              + " name=?,description=?,unit_price=?,duration_minutes=?,booking_lead_minutes=?,department_id=?,locality_id=?,location_description=?,min_age=?,conditions=?,is_published=?"
              + " where id=?",
          f.name.strip(),
          f.description.strip(),
          f.unitPrice,
          f.durationMinutes,
          f.bookingLeadMinutes,
          f.departmentId,
          f.localityId,
          f.locationDescription.strip(),
          f.minAge,
          f.conditions.strip(),
          f.published,
          id);
    if (f.fullRefundMinutes < 0
        || f.fullRefundMinutes > 525600
        || f.lateRefundPercent < 0
        || f.lateRefundPercent > 100)
      throw new BusinessException("INVALID_INPUT", "Política de reembolso inválida.");
    db.update(
        "update activity set full_refund_minutes=?,late_refund_percent=? where id=?",
        f.fullRefundMinutes,
        f.lateRefundPercent,
        id);
    db.update("delete from activity_category where activity_id=?", id);
    for (long c : cats) db.update("insert into activity_category values(?,?)", id, c);
    db.update("delete from activity_equipment where activity_id=?", id);
    for (long e : pro) db.update("insert into activity_equipment values(?,?,'PROVIDED')", id, e);
    for (long e : req) db.update("insert into activity_equipment values(?,?,'REQUIRED')", id, e);
    return id;
  }

  @Transactional
  public void saveInstance(
      long activityId, Long id, LocalDateTime localStart, Integer capacity, boolean delete) {
    var a = owned(activityId);
    if (capacity != null && capacity < 1)
      throw new BusinessException("INVALID_INPUT", "Cupo positivo o vacío para ilimitado.");
    Instant start = localStart == null ? null : localStart.atZone(ZONE).toInstant();
    if (id == null) {
      if (start == null || !start.isAfter(clock.instant()))
        throw new BusinessException("INVALID_INPUT", "La instancia debe ser futura.");
      db.insert(
          "insert into activity_instance(activity_id,starts_at,ends_at,capacity) values(?,?,?,?)",
          activityId,
          utc(start),
          utc(start.plusSeconds(((Integer) a.get("duration_minutes")) * 60L)),
          capacity);
      return;
    }
    var i =
        db.one(
            "select * from activity_instance where id=? and activity_id=? for update",
            id,
            activityId);
    if (Boolean.TRUE.equals(i.get("cancelled")))
      throw new BusinessException("INVALID_INPUT", "La instancia fue cancelada.");
    if (!instant(i.get("starts_at")).isAfter(clock.instant()))
      throw new BusinessException("INVALID_INPUT", "No se modifica una instancia pasada.");
    long pending =
        db.count(
            "select count(*) from reservation where instance_id=? and status in"
                + " ('PENDING_PAYMENT','PAYMENT_UNCERTAIN')",
            id);
    long used = committed(id);
    if (pending > 0
        || used > 0 && (delete || start == null || !start.equals(instant(i.get("starts_at")))))
      throw new BusinessException(
          "INSTANCE_HAS_COMMITTED_RESERVATIONS",
          "Hay reservas comprometidas. No se puede reprogramar o eliminar.");
    if (capacity != null && capacity < used)
      throw new BusinessException(
          "NO_CAPACITY", "El cupo no puede ser menor a los lugares comprometidos.");
    if (delete) {
      db.update("update activity_instance set active=false where id=?", id);
      return;
    }
    if (start == null || !start.isAfter(clock.instant()))
      throw new BusinessException("INVALID_INPUT", "La instancia debe ser futura.");
    var end =
        used > 0
            ? instant(i.get("ends_at"))
            : start.plusSeconds((Integer) a.get("duration_minutes") * 60L);
    db.update(
        "update activity_instance set starts_at=?,ends_at=?,capacity=? where id=?",
        utc(start),
        utc(end),
        capacity,
        id);
  }

  @Transactional
  public void catalog(String kind, Long id, String name, String action) {
    accounts.role("ADMIN");
    String table =
        switch (kind) {
          case "categories" -> "category";
          case "equipment" -> "equipment_type";
          default -> throw new BusinessException("INVALID_INPUT", "Catálogo inválido.");
        };
    if ("save".equals(action)) {
      name = Accounts.text(name, 150, "nombre");
      if (id == null)
        db.insert(
            "insert into " + table + "(name,normalized_name) values(?,?)",
            name,
            name.toLowerCase(Locale.ROOT));
      else
        db.update(
            "update " + table + " set name=?,normalized_name=? where id=?",
            name,
            name.toLowerCase(Locale.ROOT),
            id);
    } else if ("deactivate".equals(action))
      db.update("update " + table + " set active=false where id=?", id);
    else if ("activate".equals(action))
      db.update("update " + table + " set active=true where id=?", id);
    else if ("delete".equals(action)) {
      String link =
          table.equals("category")
              ? "activity_category where category_id"
              : "activity_equipment where equipment_id";
      if (db.count("select count(*) from " + link + "=?", id) > 0)
        db.update("update " + table + " set active=false where id=?", id);
      else db.update("delete from " + table + " where id=?", id);
    } else throw new BusinessException("INVALID_INPUT", "Acción inválida.");
  }
}
