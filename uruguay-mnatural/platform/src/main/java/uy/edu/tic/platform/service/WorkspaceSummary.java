package uy.edu.tic.platform.service;

import static uy.edu.tic.platform.domain.BookingRules.utc;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;
import uy.edu.tic.platform.repository.Database;

/** Read-only dashboard queries, scoped to the authenticated account. */
@Service
public class WorkspaceSummary {
  private final Database db;
  private final Accounts accounts;
  private final Clock clock;

  public WorkspaceSummary(Database db, Accounts accounts, Clock clock) {
    this.db = db;
    this.accounts = accounts;
    this.clock = clock;
  }

  public void tourist(Model model) {
    accounts.role("TOURIST");
    long user = accounts.id();
    var now = utc(clock.instant());
    model.addAttribute(
        "upcomingCount",
        db.count(
            "select count(*) from reservation r where tourist_id=? and status='CONFIRMED' and"
                + " starts_at_snapshot>? and not exists(select 1 from cancellation c where"
                + " c.reservation_id=r.id)",
            user,
            now));
    model.addAttribute(
        "pendingCount",
        db.count(
            "select count(*) from reservation r where tourist_id=? and status in"
                + " ('PENDING_PAYMENT','PAYMENT_UNCERTAIN') and not exists(select 1 from"
                + " cancellation c where c.reservation_id=r.id)",
            user));
    String reviewFrom =
        " from reservation r join payment p on p.reservation_id=r.id where r.tourist_id=? and"
            + " p.status='APPROVED' and r.ends_at_snapshot<=? and not exists(select 1 from review x"
            + " where x.reservation_id=r.id) and not exists(select 1 from cancellation c where"
            + " c.reservation_id=r.id)";
    model.addAttribute("reviewCount", db.count("select count(*)" + reviewFrom, user, now));
    model.addAttribute(
        "reviewReservations",
        db.list(
            "select r.*" + reviewFrom + " order by r.ends_at_snapshot desc,r.id limit 3",
            user,
            now));
    model.addAttribute(
        "nextReservation",
        db.optional(
                "select r.* from reservation r where tourist_id=? and status='CONFIRMED' and"
                    + " starts_at_snapshot>? and not exists(select 1 from cancellation c where"
                    + " c.reservation_id=r.id) order by starts_at_snapshot,r.id limit 1",
                user,
                now)
            .orElse(null));
    model.addAttribute(
        "inspiration",
        db.list(
            "select a.id,a.name,a.unit_price,d.name department_name from activity a join venture v"
                + " on v.id=a.venture_id join department d on d.id=a.department_id where"
                + " a.is_published=true and v.verification_status='VERIFIED' order by a.id desc"
                + " limit 3"));
  }

  public void provider(Model model) {
    accounts.verifiedProvider();
    long user = accounts.id();
    var now = utc(clock.instant());
    model.addAttribute(
        "publishedCount",
        db.count(
            "select count(*) from activity a join venture v on v.id=a.venture_id where v.owner_id=?"
                + " and a.is_published=true",
            user));
    model.addAttribute(
        "scheduledCount",
        db.count(
            "select count(*) from activity_instance i join activity a on a.id=i.activity_id join"
                + " venture v on v.id=a.venture_id where v.owner_id=? and i.active=true and"
                + " i.starts_at>?",
            user,
            now));
    model.addAttribute(
        "guestCount",
        db.count(
            "select coalesce(sum(r.participant_count),0) from reservation r join activity_instance"
                + " i on i.id=r.instance_id join activity a on a.id=i.activity_id join venture v on"
                + " v.id=a.venture_id where v.owner_id=? and r.status='CONFIRMED' and i.starts_at>?"
                + " and not exists(select 1 from cancellation c where c.reservation_id=r.id)",
            user,
            now));
    model.addAttribute(
        "agenda",
        db.list(
            "select i.*,a.name,(select coalesce(sum(r.participant_count),0) from reservation r"
                + " where r.instance_id=i.id and r.status in ("
                + Tourism.COMMITTED
                + ")) reserved_people from activity_instance i join activity a on"
                + " a.id=i.activity_id join venture v on v.id=a.venture_id where v.owner_id=? and"
                + " i.active=true and i.starts_at>? order by i.starts_at,i.id limit 4",
            user,
            now));
    model.addAttribute(
        "refundPendingCount",
        db.count(
            "select count(*) from cancellation c join reservation r on r.id=c.reservation_id join"
                + " activity_instance i on i.id=r.instance_id join activity a on a.id=i.activity_id"
                + " join venture v on v.id=a.venture_id where v.owner_id=? and c.completed_at is"
                + " null",
            user));
  }
}
