package ir.digigharz.circles;

import static ir.digigharz.Json.obj;

import ir.digigharz.HttpError;
import ir.digigharz.Json;
import ir.digigharz.db.Db;
import ir.digigharz.db.Row;
import ir.digigharz.lib.Plans;
import ir.digigharz.lib.Schedule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Reports for the operator's admin panel: what's happening across every
 * guaranteed-plan circle, where the money went, who owes what, and the audit
 * trail. Read-only; phones are masked.
 */
public class OpsReports {
  private static final long FIVE_MINUTES = 5 * 60 * 1000;

  private final Db db;

  public OpsReports(Db db, LongSupplier now) {
    this.db = db;
  }

  public static String maskPhone(String phone) {
    return phone == null ? null : phone.substring(0, 4) + "***" + phone.substring(phone.length() - 4);
  }

  // Who a seat belongs to, as the operator should see it.
  private static String seatOwner(Row m) {
    return m.flag("is_operator") ? "operator" : m.flag("is_bot") ? "bot" : "member";
  }

  private long sum(String sql) {
    return db.get(sql).n("total");
  }

  public Map<String, Object> overview() {
    Map<String, Long> byStatus = new HashMap<>();
    for (Row r : db.all("SELECT status, COUNT(*) AS n FROM circles GROUP BY status")) byStatus.put(r.str("status"), r.n("n"));
    Row people = db.get(
        "SELECT COUNT(*) AS seats, COUNT(DISTINCT m.phone) AS users FROM circle_members m"
            + " JOIN circles c ON c.id = m.circle_id"
            + " WHERE m.is_operator = 0 AND m.is_bot = 0 AND c.status IN ('forming', 'active', 'completed')");

    // Where each member installment came from. The operator's own share is
    // reported apart: it is Digipay's money, not collection.
    List<Row> methods = db.all(
        "SELECT method, COUNT(*) AS n, COALESCE(SUM(amount), 0) AS total FROM contributions"
            + " WHERE status IN ('paid', 'covered') AND method != 'operator' GROUP BY method");
    Map<String, Object> byMethod = new LinkedHashMap<>();
    Map<String, Row> methodRows = new HashMap<>();
    for (Row r : methods) {
      byMethod.put(r.str("method"), obj("count", r.n("n"), "amount", r.n("total")));
      methodRows.put(r.str("method"), r);
    }
    long collectedByMembers = 0;
    for (String k : List.of("entry", "manual", "wallet")) {
      if (methodRows.containsKey(k)) collectedByMembers += methodRows.get(k).n("total");
    }
    long guaranteed = methodRows.containsKey("guarantee") ? methodRows.get("guarantee").n("total") : 0;
    long guaranteedCount = methodRows.containsKey("guarantee") ? methodRows.get("guarantee").n("n") : 0;
    long installments = methods.stream().mapToLong(r -> r.n("n")).sum();

    Row paidOut = db.get(
        "SELECT COALESCE(SUM(CASE WHEN m.is_operator = 1 THEN d.pot ELSE 0 END), 0) AS operator,"
            + " COALESCE(SUM(CASE WHEN m.is_operator = 0 THEN d.pot ELSE 0 END), 0) AS members,"
            + " COUNT(*) AS draws"
            + " FROM circle_draws d JOIN circle_members m ON m.id = d.winner_member_id WHERE d.payout_ref IS NOT NULL");
    long openDebt = sum("SELECT COALESCE(SUM(amount), 0) AS total FROM contributions WHERE status = 'covered' AND settled_at IS NULL");
    long recovered = sum("SELECT COALESCE(SUM(amount), 0) AS total FROM contributions WHERE status = 'covered' AND settled_at IS NOT NULL");
    long debtors = db.get("SELECT COUNT(DISTINCT member_id) AS n FROM contributions WHERE status = 'covered' AND settled_at IS NULL").n("n");
    long refunds = sum("SELECT COALESCE(SUM(amount), 0) AS total FROM ops_events WHERE kind = 'refund'");

    List<Long> fillTimes = db.all("SELECT created_at, started_at FROM circles WHERE started_at IS NOT NULL").stream()
        .map(c -> c.n("started_at") - c.n("created_at")).toList();
    Map<String, Object> fill = obj(
        "count", fillTimes.size(),
        "averageMs", fillTimes.isEmpty() ? null : Math.round(fillTimes.stream().mapToLong(Long::longValue).sum() / (double) fillTimes.size()),
        "underFiveMinutes", fillTimes.stream().filter(t -> t <= FIVE_MINUTES).count());

    List<Map<String, Object>> upcoming = db.all(
            "SELECT id, plan_id, started_at, current_month, months FROM circles WHERE status = 'active'")
        .stream()
        .map(c -> obj("id", c.str("id"), "planId", c.str("plan_id"), "month", c.n("current_month"),
            "at", Schedule.drawAt(c.n("started_at"), c.n("current_month"))))
        .sorted(Comparator.comparingLong(m -> (Long) m.get("at")))
        .limit(6)
        .toList();

    List<Map<String, Object>> plans = new ArrayList<>();
    for (Plans.Plan plan : Plans.PLANS) {
      Row row = db.get(
          "SELECT"
              + " COALESCE(SUM(CASE WHEN status = 'forming' THEN 1 ELSE 0 END), 0) AS forming,"
              + " COALESCE(SUM(CASE WHEN status = 'active' THEN 1 ELSE 0 END), 0) AS active,"
              + " COALESCE(SUM(CASE WHEN status = 'completed' THEN 1 ELSE 0 END), 0) AS completed,"
              + " COALESCE(SUM(CASE WHEN status = 'expired' THEN 1 ELSE 0 END), 0) AS expired"
              + " FROM circles WHERE plan_id = ?",
          plan.id());
      plans.add(obj("id", plan.id(), "title", plan.title(), "size", plan.size(), "share", plan.share(),
          "forming", row.n("forming"), "active", row.n("active"), "completed", row.n("completed"), "expired", row.n("expired")));
    }

    Row family = db.get("SELECT COUNT(*) AS funds, (SELECT COUNT(*) FROM fund_members) AS members FROM funds");

    return obj(
        "circles", obj(
            "forming", byStatus.getOrDefault("forming", 0L),
            "active", byStatus.getOrDefault("active", 0L),
            "completed", byStatus.getOrDefault("completed", 0L),
            "expired", byStatus.getOrDefault("expired", 0L)),
        "people", obj("users", people.n("users"), "seats", people.n("seats")),
        "money", obj(
            "collectedByMembers", collectedByMembers,
            "guaranteed", guaranteed,
            "openDebt", openDebt,
            "recovered", recovered,
            "debtors", debtors,
            "refunds", refunds,
            "paidOutToMembers", paidOut.n("members"),
            "paidOutToOperator", paidOut.n("operator"),
            "draws", paidOut.n("draws")),
        // Share of installments paid without the guarantee stepping in.
        "collection", obj(
            "installments", installments,
            "byMethod", byMethod,
            "paidRate", installments == 0 ? null : (installments - guaranteedCount) / (double) installments),
        "fill", fill,
        "upcoming", upcoming,
        "plans", plans,
        "family", obj("funds", family.n("funds"), "members", family.n("members")));
  }

  public List<Map<String, Object>> circles() {
    List<Row> rows = db.all(
        "SELECT c.*,"
            + " (SELECT COUNT(*) FROM circle_members m WHERE m.circle_id = c.id) AS taken,"
            + " (SELECT COALESCE(SUM(amount), 0) FROM contributions x"
            + "    WHERE x.circle_id = c.id AND x.status = 'covered' AND x.settled_at IS NULL) AS debt,"
            + " (SELECT COUNT(*) FROM contributions x"
            + "    WHERE x.circle_id = c.id AND x.month = c.current_month AND x.method IS DISTINCT FROM 'operator') AS due_count,"
            + " (SELECT COUNT(*) FROM contributions x"
            + "    WHERE x.circle_id = c.id AND x.month = c.current_month AND x.status = 'paid'"
            + "      AND x.method IS DISTINCT FROM 'operator') AS paid_count"
            + " FROM circles c ORDER BY c.created_at DESC");
    return rows.stream().map(c -> {
      boolean active = "active".equals(c.str("status"));
      return obj(
          "id", c.str("id"),
          "planId", c.str("plan_id"),
          "status", c.str("status"),
          "size", c.n("size"),
          "months", c.n("months"),
          "share", c.n("share"),
          "taken", c.n("taken"),
          "currentMonth", c.n("current_month"),
          "createdAt", c.n("created_at"),
          "startedAt", c.lng("started_at"),
          "deadline", c.n("deadline"),
          "nextDrawAt", active ? Schedule.drawAt(c.n("started_at"), c.n("current_month")) : null,
          "thisMonth", active ? obj("paid", c.n("paid_count"), "of", c.n("due_count")) : null,
          "debt", c.n("debt"));
    }).toList();
  }

  public Map<String, Object> circle(String id) {
    Row c = db.get("SELECT * FROM circles WHERE id = ?", id);
    if (c == null) throw new HttpError(404, "این دوره پیدا نشد.");
    List<Row> members = db.all("SELECT * FROM circle_members WHERE circle_id = ? ORDER BY position", id);
    List<Row> contributions = db.all(
        "SELECT member_id, month, amount, status, method, settled_at FROM contributions WHERE circle_id = ? ORDER BY month", id);
    List<Row> draws = db.all("SELECT * FROM circle_draws WHERE circle_id = ? ORDER BY month", id);
    List<Row> events = db.all("SELECT * FROM ops_events WHERE circle_id = ? ORDER BY at DESC LIMIT 100", id);

    Map<String, Map<String, Map<String, Object>>> paymentsOf = new HashMap<>();
    for (Row m : members) paymentsOf.put(m.str("id"), new LinkedHashMap<>());
    for (Row x : contributions) {
      paymentsOf.get(x.str("member_id")).put(String.valueOf(x.n("month")), obj(
          "status", "covered".equals(x.str("status")) && x.lng("settled_at") != null ? "settled" : x.str("status"),
          "method", x.str("method"),
          "amount", x.n("amount")));
    }
    Map<String, Long> positionOf = new HashMap<>();
    for (Row m : members) positionOf.put(m.str("id"), m.n("position"));
    Plans.Plan plan = Plans.byId(c.str("plan_id"));

    Map<String, Object> circle = obj(
        "id", c.str("id"),
        "planId", c.str("plan_id"),
        "title", plan == null ? null : plan.title(),
        "status", c.str("status"),
        "size", c.n("size"),
        "months", c.n("months"),
        "share", c.n("share"),
        "pot", c.n("size") * c.n("share"),
        "currentMonth", c.n("current_month"),
        "createdAt", c.n("created_at"),
        "startedAt", c.lng("started_at"),
        "deadline", c.n("deadline"),
        "anchor", c.str("anchor"),
        "schedule", c.lng("started_at") != null ? Schedule.scheduleOf(c.n("started_at"), c.n("months")) : List.of());
    return obj(
        "circle", circle,
        "members", members.stream().map(m -> {
          Map<String, Map<String, Object>> payments = paymentsOf.get(m.str("id"));
          long debt = payments.values().stream().filter(p -> "covered".equals(p.get("status")))
              .mapToLong(p -> (Long) p.get("amount")).sum();
          return obj(
              "id", m.str("id"),
              "position", m.n("position"),
              "owner", seatOwner(m),
              "phone", maskPhone(m.str("phone")),
              "wonMonth", m.lng("won_month"),
              "joinedAt", m.n("joined_at"),
              "debt", debt,
              "payments", payments);
        }).toList(),
        "draws", draws.stream().map(d -> obj(
            "month", d.n("month"),
            "kind", d.str("kind"),
            "winnerPosition", positionOf.get(d.str("winner_member_id")),
            "eligible", Json.parseList(d.str("eligible")).size(),
            "pot", d.n("pot"),
            "paidOut", d.str("payout_ref") != null,
            "payoutRef", d.str("payout_ref"),
            "at", d.n("created_at"))).toList(),
        "events", events.stream().map(e -> eventView(e, positionOf)).toList());
  }

  public List<Map<String, Object>> debtors() {
    List<Row> rows = db.all(
        "SELECT x.circle_id, x.member_id, COUNT(*) AS months, SUM(x.amount) AS amount, MIN(x.paid_at) AS since,"
            + " m.position, m.phone, m.is_bot, c.plan_id"
            + " FROM contributions x"
            + " JOIN circle_members m ON m.id = x.member_id"
            + " JOIN circles c ON c.id = x.circle_id"
            + " WHERE x.status = 'covered' AND x.settled_at IS NULL"
            + " GROUP BY x.circle_id, x.member_id, m.position, m.phone, m.is_bot, c.plan_id"
            + " ORDER BY amount DESC, since");
    return rows.stream().map(r -> obj(
        "circleId", r.str("circle_id"),
        "planId", r.str("plan_id"),
        "position", r.n("position"),
        "owner", r.flag("is_bot") ? "bot" : "member",
        "phone", maskPhone(r.str("phone")),
        "months", r.n("months"),
        "amount", r.n("amount"),
        "since", r.lng("since"))).toList();
  }

  public List<Map<String, Object>> events(Object limit, String kind) {
    long n = Math.min(Math.max(parseLimit(limit), 1), 500);
    List<Row> rows = kind != null
        ? db.all("SELECT * FROM ops_events WHERE kind = ? ORDER BY at DESC LIMIT ?", kind, n)
        : db.all("SELECT * FROM ops_events ORDER BY at DESC LIMIT ?", n);
    Map<String, String> plans = new HashMap<>();
    for (Row c : db.all("SELECT id, plan_id FROM circles")) plans.put(c.str("id"), c.str("plan_id"));
    return rows.stream().map(e -> {
      Map<String, Object> v = eventView(e, null);
      v.put("planId", e.str("circle_id") == null ? null : plans.get(e.str("circle_id")));
      return v;
    }).toList();
  }

  // Number(limit) || 100, as whole events.
  private static long parseLimit(Object limit) {
    if (limit == null) return 100;
    try {
      double d = Double.parseDouble(limit.toString().trim());
      long v = (long) d;
      return v == 0 || Double.isNaN(d) ? 100 : v;
    } catch (NumberFormatException e) {
      return 100;
    }
  }

  private static Map<String, Object> eventView(Row e, Map<String, Long> positionOf) {
    return obj(
        "id", e.str("id"),
        "at", e.n("at"),
        "kind", e.str("kind"),
        "circleId", e.str("circle_id"),
        "position", positionOf == null ? null : positionOf.get(e.str("member_id")),
        "phone", maskPhone(e.str("phone")),
        "amount", e.lng("amount"),
        "detail", e.str("detail") == null ? null : Json.toPlain(Json.parse(e.str("detail"))));
  }
}
