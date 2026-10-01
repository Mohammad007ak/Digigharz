package ir.digigharz.web;

import static ir.digigharz.Json.obj;
import static ir.digigharz.funds.FundLogic.out;

import ir.digigharz.HttpError;
import ir.digigharz.Json;
import ir.digigharz.db.Handle;
import ir.digigharz.db.Row;
import ir.digigharz.funds.FundLogic;
import ir.digigharz.funds.FundLogic.Due;
import ir.digigharz.lib.Ids;
import ir.digigharz.lib.Jalali;
import ir.digigharz.lib.Phone;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Family funds: the manager's ledger, the server-side draw, and members' read-only view. */
@RestController
public class FundController {
  private static final int MAX_FUNDS_PER_OWNER = 20;

  private final Server s;

  public FundController(Server s) {
    this.s = s;
  }

  private record Fund(String id, String ownerPhone, JsonNode data, long version) {}

  private Fund loadFund(String id) {
    Row row = s.db.get("SELECT * FROM funds WHERE id = ?", id);
    if (row == null) throw new HttpError(404, "صندوق پیدا نشد.");
    return new Fund(row.str("id"), row.str("owner_phone"), Json.parse(row.str("data")), row.n("version"));
  }

  // Report "not found" to non-managers so fund ids can't be probed.
  private Fund loadManagedFund(String id, String phone) {
    Fund fund = loadFund(id);
    if (!fund.ownerPhone().equals(phone)) throw new HttpError(404, "صندوق پیدا نشد.");
    return fund;
  }

  private void syncMembers(Handle tx, String fundId, JsonNode data) {
    tx.run("DELETE FROM fund_members WHERE fund_id = ?", fundId);
    JsonNode demo = data.get("demo");
    if (demo != null && demo.asBoolean(false)) return;
    for (JsonNode member : data.get("members")) {
      JsonNode p = member.get("phone");
      String phone = p == null || p.isNull() ? null : Phone.normalize(p.isString() ? p.asString() : p.toString());
      if (phone != null) {
        tx.run("INSERT INTO fund_members (fund_id, member_id, phone) VALUES (?, ?, ?)", fundId, member.get("id").asString(), phone);
      }
    }
  }

  // Saves only if nobody else saved since `version` was read.
  private void writeFund(Handle tx, String id, JsonNode data, long version) {
    int changes = tx.run("UPDATE funds SET data = ?, version = ?, updated_at = ? WHERE id = ? AND version = ?",
        Json.stringify(data), version + 1, s.now(), id, version);
    if (changes == 0) throw new HttpError(409, "این صندوق در جای دیگری تغییر کرده است.");
    syncMembers(tx, id, data);
  }

  private static ObjectNode validData(JsonNode data) {
    if (!FundLogic.isValidState(data)) throw new HttpError(400, "اطلاعات صندوق معتبر نیست.");
    ObjectNode copy = (ObjectNode) data.deepCopy();
    ObjectNode fund = Json.object();
    fund.put("cycle", 1);
    fund.setAll((ObjectNode) data.get("fund"));
    copy.set("fund", fund);
    return copy;
  }

  private String month() {
    return Jalali.currentMonthKey(s.now());
  }

  @GetMapping("/api/funds")
  public Map<String, Object> list(HttpServletRequest req) {
    String phone = s.requireLogin(req);
    String month = month();
    List<Map<String, Object>> managed = new ArrayList<>();
    for (Row row : s.db.all("SELECT id, data FROM funds WHERE owner_phone = ? ORDER BY created_at", phone)) {
      JsonNode data = Json.parse(row.str("data"));
      Set<String> late = new HashSet<>();
      for (Due d : FundLogic.overdueDues(data, month)) late.add(d.memberId());
      managed.add(obj(
          "id", row.str("id"),
          "name", data.get("fund").get("name").asString(),
          "members", data.get("members").size(),
          "balance", out(FundLogic.fundBalance(data)),
          "lateMembers", late.size()));
    }
    List<Map<String, Object>> member = s.db.all(
            "SELECT DISTINCT f.id, f.data, f.created_at FROM fund_members m JOIN funds f ON f.id = m.fund_id"
                + " WHERE m.phone = ? AND f.owner_phone != ? ORDER BY f.created_at",
            phone, phone)
        .stream()
        .map(row -> obj("id", row.str("id"), "name", Json.parse(row.str("data")).get("fund").get("name").asString()))
        .toList();
    return obj("managed", managed, "member", member);
  }

  @PostMapping("/api/funds")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> create(HttpServletRequest req) {
    String phone = s.requireLogin(req);
    ObjectNode data = validData(Body.of(req).get("data"));
    long count = s.db.get("SELECT COUNT(*) AS count FROM funds WHERE owner_phone = ?", phone).n("count");
    if (count >= MAX_FUNDS_PER_OWNER) throw new HttpError(400, "به سقف تعداد صندوق رسیده‌اید.");

    String id = Ids.newId();
    s.db.transaction(tx -> {
      tx.run("INSERT INTO funds (id, owner_phone, data, version, created_at, updated_at) VALUES (?, ?, ?, 1, ?, ?)",
          id, phone, Json.stringify(data), s.now(), s.now());
      syncMembers(tx, id, data);
      return null;
    });
    return obj("id", id, "data", data, "version", 1);
  }

  @GetMapping("/api/funds/{id}")
  public Map<String, Object> get(HttpServletRequest req, @PathVariable String id) {
    Fund fund = loadManagedFund(id, s.requireLogin(req));
    return obj("id", fund.id(), "data", fund.data(), "version", fund.version());
  }

  @PutMapping("/api/funds/{id}")
  public Map<String, Object> save(HttpServletRequest req, @PathVariable String id) {
    Fund fund = loadManagedFund(id, s.requireLogin(req));
    Body body = Body.of(req);
    JsonNode version = body.get("version");
    if (version == null || !version.isNumber() || version.asDouble() != fund.version()) {
      throw new HttpError(409, "این صندوق در جای دیگری تغییر کرده است.", obj("data", fund.data(), "version", fund.version()));
    }
    ObjectNode data = validData(body.get("data"));
    try {
      s.db.transaction(tx -> {
        writeFund(tx, fund.id(), data, fund.version());
        return null;
      });
    } catch (HttpError error) {
      if (error.status() != 409) throw error;
      Fund latest = loadFund(fund.id());
      throw new HttpError(409, error.getMessage(), obj("data", latest.data(), "version", latest.version()));
    }
    return obj("version", fund.version() + 1);
  }

  @DeleteMapping("/api/funds/{id}")
  public Map<String, Object> delete(HttpServletRequest req, @PathVariable String id) {
    Fund fund = loadManagedFund(id, s.requireLogin(req));
    s.db.transaction(tx -> {
      tx.run("DELETE FROM draws WHERE fund_id = ?", fund.id());
      tx.run("DELETE FROM fund_members WHERE fund_id = ?", fund.id());
      tx.run("DELETE FROM funds WHERE id = ?", fund.id());
      return null;
    });
    return obj("ok", true);
  }

  // ---------- lottery ----------
  // Draws happen on the server with a secure random source and are logged,
  // so a manager can't quietly re-roll until a friend wins.

  @PostMapping("/api/funds/{id}/draws")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> draw(HttpServletRequest req, @PathVariable String id) {
    Fund fund = loadManagedFund(id, s.requireLogin(req));
    List<FundLogic.Entry> entries = FundLogic.lotteryEntries(fund.data());
    if (entries.isEmpty()) throw new HttpError(400, "همه‌ی اعضا در این دور وام گرفته‌اند.");
    if (FundLogic.fundBalance(fund.data()) < FundLogic.num(fund.data().get("fund").get("loanAmount"))) {
      throw new HttpError(400, "موجودی صندوق برای یک وام کافی نیست.");
    }

    JsonNode winner = FundLogic.pickWinner(entries, s.o.random);
    String winnerId = FundLogic.text(winner.get("id"));
    String winnerName = FundLogic.text(winner.get("name"));
    Map<String, Object> draw = obj("id", Ids.newId(), "month", month(), "winnerId", winnerId, "winnerName", winnerName);
    ArrayNode logged = Json.NODES.arrayNode();
    for (FundLogic.Entry e : entries) {
      ObjectNode entry = logged.addObject();
      entry.put("memberId", FundLogic.text(e.member().get("id")));
      entry.set("name", e.member().get("name"));
      entry.put("tickets", out(e.tickets()).longValue());
    }
    s.db.transaction(tx -> {
      tx.run("UPDATE draws SET status = 'cancelled', resolved_at = ? WHERE fund_id = ? AND status = 'pending'", s.now(), fund.id());
      tx.run(
          "INSERT INTO draws (id, fund_id, month, winner_member_id, winner_name, entries, status, created_at)"
              + " VALUES (?, ?, ?, ?, ?, ?, 'pending', ?)",
          draw.get("id"), fund.id(), draw.get("month"), winnerId, winnerName == null ? "" : winnerName,
          Json.stringify(logged), s.now());
      return null;
    });
    return obj("draw", draw);
  }

  private Row loadPendingDraw(String fundId, String drawId) {
    Row draw = s.db.get("SELECT * FROM draws WHERE id = ? AND fund_id = ?", drawId, fundId);
    if (draw == null) throw new HttpError(404, "قرعه پیدا نشد.");
    if (!"pending".equals(draw.str("status"))) throw new HttpError(400, "این قرعه قبلاً نهایی شده است.");
    return draw;
  }

  @PostMapping("/api/funds/{id}/draws/{drawId}/confirm")
  public Map<String, Object> confirm(HttpServletRequest req, @PathVariable String id, @PathVariable String drawId) {
    Fund fund = loadManagedFund(id, s.requireLogin(req));
    Row draw = loadPendingDraw(fund.id(), drawId);
    String winner = draw.str("winner_member_id");
    boolean stillMember = false;
    for (JsonNode m : fund.data().get("members")) if (winner.equals(FundLogic.text(m.get("id")))) stillMember = true;
    if (!stillMember) throw new HttpError(400, "برنده دیگر عضو صندوق نیست.");
    ObjectNode loan = FundLogic.createLoan(fund.data().get("fund"), winner, draw.str("month"));
    loan.put("drawId", draw.str("id"));
    ObjectNode data = (ObjectNode) fund.data().deepCopy();
    ((ArrayNode) data.get("loans")).add(loan);
    s.db.transaction(tx -> {
      writeFund(tx, fund.id(), data, fund.version());
      int changes = tx.run("UPDATE draws SET status = 'confirmed', resolved_at = ? WHERE id = ? AND status = 'pending'",
          s.now(), draw.str("id"));
      if (changes == 0) throw new HttpError(400, "این قرعه قبلاً نهایی شده است.");
      return null;
    });
    return obj("data", data, "version", fund.version() + 1);
  }

  @PostMapping("/api/funds/{id}/draws/{drawId}/cancel")
  public Map<String, Object> cancel(HttpServletRequest req, @PathVariable String id, @PathVariable String drawId) {
    Fund fund = loadManagedFund(id, s.requireLogin(req));
    Row draw = loadPendingDraw(fund.id(), drawId);
    s.db.run("UPDATE draws SET status = 'cancelled', resolved_at = ? WHERE id = ? AND status = 'pending'", s.now(), draw.str("id"));
    return obj("ok", true);
  }

  // ---------- member view ----------
  // What an ordinary member may see: their own ledger, plus the fund-wide
  // facts that make the fund trustworthy (balance, loans, every draw).

  @GetMapping("/api/funds/{id}/view")
  public Map<String, Object> view(HttpServletRequest req, @PathVariable String id) {
    String phone = s.requireLogin(req);
    Fund fund = loadFund(id);
    boolean isManager = fund.ownerPhone().equals(phone);
    Row membership = s.db.get("SELECT member_id FROM fund_members WHERE fund_id = ? AND phone = ?", fund.id(), phone);
    if (!isManager && membership == null) throw new HttpError(404, "صندوق پیدا نشد.");

    JsonNode data = fund.data();
    String month = month();
    Map<String, JsonNode> nameOf = new HashMap<>();
    JsonNode member = null;
    for (JsonNode m : data.get("members")) {
      nameOf.put(FundLogic.text(m.get("id")), m.get("name"));
      if (membership != null && member == null && membership.str("member_id").equals(FundLogic.text(m.get("id")))) member = m;
    }

    Map<String, Object> me = null;
    if (member != null) {
      String memberId = FundLogic.text(member.get("id"));
      List<Due> dues = FundLogic.listDues(data, month).stream().filter(d -> memberId.equals(d.memberId())).toList();
      double overdue = dues.stream().filter(d -> !d.paid() && d.month().compareTo(month) < 0).mapToDouble(Due::amount).sum();
      List<Map<String, Object>> loans = new ArrayList<>();
      for (JsonNode l : data.get("loans")) {
        if (!memberId.equals(FundLogic.text(l.get("memberId")))) continue;
        FundLogic.Progress p = FundLogic.loanProgress(data, l);
        loans.add(obj(
            "amount", l.get("amount"),
            "installments", l.get("installments"),
            "firstInstallmentMonth", l.get("firstInstallmentMonth"),
            "paidCount", p.paidCount(),
            "paidAmount", out(p.paidAmount()),
            "remaining", out(p.remaining()),
            "done", p.done()));
      }
      me = obj(
          "name", member.get("name"),
          "shares", member.get("shares"),
          "overdueAmount", out(overdue),
          "dues", dues.stream()
              .sorted(Comparator.comparing(Due::month).reversed().thenComparing(Due::type))
              .map(d -> obj("type", d.type(), "month", d.month(), "amount", out(d.amount()), "paid", d.paid()))
              .toList(),
          "loans", loans);
    }

    List<Map<String, Object>> draws = s.db.all(
            "SELECT month, winner_name, status, created_at FROM draws WHERE fund_id = ? ORDER BY created_at DESC", fund.id())
        .stream()
        .map(d -> obj("month", d.str("month"), "winnerName", d.str("winner_name"), "status", d.str("status"), "createdAt", d.n("created_at")))
        .toList();

    List<JsonNode> loans = new ArrayList<>();
    data.get("loans").forEach(loans::add);
    loans.sort(Comparator.comparing((JsonNode l) -> String.valueOf(FundLogic.text(l.get("drawMonth")))).reversed());

    JsonNode f = data.get("fund");
    double totalShares = 0;
    for (JsonNode m : data.get("members")) totalShares += FundLogic.num(m.get("shares"));
    return obj(
        "fund", obj(
            "name", f.get("name"),
            "contribution", f.get("contribution"),
            "loanAmount", f.get("loanAmount"),
            "installments", f.get("installments"),
            "cycle", f.get("cycle"),
            "cardNumber", f.get("cardNumber") == null || f.get("cardNumber").isNull() ? "" : f.get("cardNumber"),
            "cardHolder", f.get("cardHolder") == null || f.get("cardHolder").isNull() ? "" : f.get("cardHolder")),
        "isManager", isManager,
        "currentMonth", month,
        "balance", out(FundLogic.fundBalance(data)),
        "memberCount", data.get("members").size(),
        "totalShares", out(totalShares),
        "me", me,
        "loans", loans.stream().map(l -> {
          JsonNode name = nameOf.get(FundLogic.text(l.get("memberId")));
          JsonNode drawId = l.get("drawId");
          return obj(
              "memberName", name != null && !name.isNull() ? name : (Object) "عضو سابق",
              "amount", l.get("amount"),
              "drawMonth", l.get("drawMonth"),
              "viaDraw", drawId != null && !drawId.isNull() && !(drawId.isString() && drawId.asString().isEmpty()));
        }).toList(),
        "draws", draws);
  }
}
