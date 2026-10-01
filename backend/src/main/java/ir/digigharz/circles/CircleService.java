package ir.digigharz.circles;

import static ir.digigharz.Json.obj;

import ir.digigharz.HttpError;
import ir.digigharz.Json;
import ir.digigharz.db.Db;
import ir.digigharz.db.Handle;
import ir.digigharz.db.Row;
import ir.digigharz.digipay.Digipay;
import ir.digigharz.lib.Fairness;
import ir.digigharz.lib.Ids;
import ir.digigharz.lib.Plans;
import ir.digigharz.lib.Plans.Plan;
import ir.digigharz.lib.Schedule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Guaranteed circles: the paid product, with Digipay as operator. The calendar
 * (due dates, five payment days, draw on the sixth) lives in {@link Schedule}.
 *
 * <p>A circle runs one plan. It fills up while "forming", then runs one month
 * per cycle. Each month every member owes one share and one member receives
 * the pot (size × share):
 *
 * <ul>
 *   <li>month 1 → the operator (position 1), always;
 *   <li>last month → whoever hasn't received yet, if they owe nothing;
 *   <li>other months → a provably fair lottery among members who haven't
 *       received yet and don't owe anything.
 * </ul>
 *
 * A member who owes the guarantee takes no pot until they settle. If the only
 * ones still waiting owe, the month's pot is held by the operator; whoever
 * settles first (the debt plus a late fee of 5% a month, pro rata) is paid a
 * held pot at once. Pots still held a grace period (30 days) after the last
 * draw are the operator's: they cover the debt, and the member forfeits the
 * share they paid on joining.
 *
 * <p>Month 1 is everyone's first share, paid on joining; it goes to the operator
 * the moment the circle starts. Members pay later months through the cash
 * gateway. Whatever is still unpaid when the month closes (the draw, on the
 * sixth day) is debited from their Digipay wallet when wallet debits are on;
 * otherwise, or if the wallet can't cover it, the operator pays in their place
 * ("covered") so the pot is always whole, and the member owes it.
 *
 * <p>Joining costs the first share up front, through the gateway; only then is
 * a seat taken. A forming circle that doesn't fill before its deadline expires,
 * and everyone in it is released and refunded (as when someone leaves).
 */
public class CircleService {
  private static final Pattern NONCE = Pattern.compile("^[0-9a-f]{32,64}$");
  private static final long HOUR = 60L * 60 * 1000;
  private static final long DAY = 24 * HOUR;

  public record Options(
      Db db,
      Digipay digipay,
      LongSupplier now,
      long formTimeoutMs,
      double lateFeeMonthly,
      long claimGraceMs,
      // Debit a member's wallet for a share unpaid by the end of the payment
      // days. Off for now: unpaid shares go straight to the guarantee.
      boolean walletDebit,
      // Gate joining on Digipay's credit score and monthly limit. Off for now.
      boolean scoringCheck) {
    public static Options of(Db db, Digipay digipay) {
      return new Options(db, digipay, System::currentTimeMillis, HOUR, 0.05, 30 * DAY, false, false);
    }

    public Options withNow(LongSupplier now) {
      return new Options(db, digipay, now, formTimeoutMs, lateFeeMonthly, claimGraceMs, walletDebit, scoringCheck);
    }

    public Options withFormTimeout(long ms) {
      return new Options(db, digipay, now, ms, lateFeeMonthly, claimGraceMs, walletDebit, scoringCheck);
    }

    public Options withWalletDebit(boolean on) {
      return new Options(db, digipay, now, formTimeoutMs, lateFeeMonthly, claimGraceMs, on, scoringCheck);
    }

    public Options withScoringCheck(boolean on) {
      return new Options(db, digipay, now, formTimeoutMs, lateFeeMonthly, claimGraceMs, walletDebit, on);
    }
  }

  private final Db db;
  private final Digipay digipay;
  private final LongSupplier clock;
  private final long formTimeoutMs;
  private final double lateFeeMonthly;
  private final long claimGraceMs;
  private final boolean walletDebit;
  private final boolean scoringCheck;

  public CircleService(Options o) {
    this.db = o.db();
    this.digipay = o.digipay();
    this.clock = o.now();
    this.formTimeoutMs = o.formTimeoutMs();
    this.lateFeeMonthly = o.lateFeeMonthly();
    this.claimGraceMs = o.claimGraceMs();
    this.walletDebit = o.walletDebit();
    this.scoringCheck = o.scoringCheck();
  }

  public boolean walletDebit() {
    return walletDebit;
  }

  private long now() {
    return clock.getAsLong();
  }

  private Row getCircle(String id, Handle d) {
    return d.get("SELECT * FROM circles WHERE id = ?", id);
  }

  private Row getCircle(String id) {
    return getCircle(id, db);
  }

  private List<Row> membersOf(String circleId, Handle d) {
    return d.all("SELECT * FROM circle_members WHERE circle_id = ? ORDER BY position", circleId);
  }

  // In a Postgres transaction, lock the circle row so two joins can't both
  // take the last seat. SQLite transactions already run one at a time.
  private Row lockCircle(Handle tx, String id) {
    return tx.get("SELECT * FROM circles WHERE id = ?" + ("postgres".equals(tx.kind()) ? " FOR UPDATE" : ""), id);
  }

  private long countMembers(String circleId, Handle d) {
    return d.get("SELECT COUNT(*) AS n FROM circle_members WHERE circle_id = ?", circleId).n("n");
  }

  // The late fee on a share the guarantee paid: 5% a month of it, pro rata
  // for each whole day since it was covered, to the nearest thousand toman.
  private long lateFeeOf(Row row) {
    if (!"covered".equals(row.str("status")) || row.lng("settled_at") != null) return 0;
    long days = Math.floorDiv(Math.max(0, now() - row.n("paid_at")), DAY);
    return Math.round(row.n("amount") * lateFeeMonthly * days / 30 / 1000) * 1000;
  }

  private long lateFees(List<Row> rows) {
    return rows.stream().mapToLong(this::lateFeeOf).sum();
  }

  // When unclaimed held pots become the operator's.
  private long claimDeadline(Row circle) {
    return Schedule.drawAt(circle.n("started_at"), circle.n("months")) + claimGraceMs;
  }

  private static Row find(List<Row> members, String id) {
    return members.stream().filter(m -> Objects.equals(m.str("id"), id)).findFirst().orElse(null);
  }

  private static Row operatorOf(List<Row> members) {
    return members.stream().filter(m -> m.flag("is_operator")).findFirst().orElse(null);
  }

  // The audit trail behind the operator's reports: one row per money
  // movement or lifecycle step.
  private void log(Handle d, String kind, String circleId, String memberId, String phone, Long amount, Object detail) {
    d.run(
        "INSERT INTO ops_events (id, at, kind, circle_id, member_id, phone, amount, detail) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        Ids.newId(),
        now(),
        kind,
        circleId,
        memberId,
        phone,
        amount,
        detail == null ? null : Json.stringify(detail));
  }

  // ---------- forming ----------

  private String createCircle(Plan plan) {
    String secret = Fairness.randomHex();
    String anchor = Fairness.buildChain(secret, plan.months()).get(0);
    String id = Ids.newId();
    db.transaction(tx -> {
      tx.run(
          "INSERT INTO circles (id, plan_id, status, size, months, share, chain_secret, anchor, created_at, deadline)"
              + " VALUES (?, ?, 'forming', ?, ?, ?, ?, ?, ?, ?)",
          id, plan.id(), plan.size(), plan.months(), plan.share(), secret, anchor, now(), now() + formTimeoutMs);
      tx.run(
          "INSERT INTO circle_members (id, circle_id, position, phone, is_operator, nonce, pay_method, joined_at)"
              + " VALUES (?, ?, 1, NULL, 1, ?, 'operator', ?)",
          Ids.newId(), id, Fairness.randomHex(16), now());
      log(tx, "circle_created", id, null, null, null, obj("planId", plan.id()));
      return null;
    });
    return id;
  }

  private String openCircleFor(Plan plan) {
    Row open = db.get(
        "SELECT id FROM circles WHERE plan_id = ? AND status = 'forming' AND deadline > ? ORDER BY created_at LIMIT 1",
        plan.id(), now());
    return open != null ? open.str("id") : createCircle(plan);
  }

  /** Releases everyone from circles that didn't fill before their deadline. */
  public void expireStale() {
    for (Row stale : db.all("SELECT id FROM circles WHERE status = 'forming' AND deadline <= ?", now())) {
      String id = stale.str("id");
      if (db.run("UPDATE circles SET status = 'expired' WHERE id = ? AND status = 'forming'", id) == 0) continue;
      Row circle = getCircle(id);
      List<Row> members = membersOf(id, db);
      log(db, "circle_expired", id, null, null, null, obj("taken", members.size(), "size", circle.n("size")));
      for (Row m : members) release(m, circle);
    }
  }

  // Undo what a seat holds: the wallet mandate and the prepaid first share.
  private void release(Row member, Row circle) {
    if (member.str("mandate_id") != null) digipay.revokeMandate(member.str("mandate_id"));
    if (member.str("entry_ref") != null && !member.flag("is_bot")) {
      digipay.refund(member.str("entry_ref"), circle.n("share"));
      log(db, "refund", circle.str("id"), member.str("id"), member.str("phone"), circle.n("share"),
          obj("reason", "expired".equals(circle.str("status")) ? "expired" : "left"));
    }
  }

  private void openMonth(Handle tx, String circleId, long month, long share) {
    for (Row m : membersOf(circleId, tx)) {
      // Month 1 was paid on joining.
      String method = m.flag("is_operator") ? "operator" : month == 1 && m.str("entry_ref") != null ? "entry" : null;
      tx.run(
          "INSERT INTO contributions (circle_id, member_id, month, amount, status, method, ref, paid_at)"
              + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
          circleId, m.str("id"), month, share, method != null ? "paid" : "due", method,
          "entry".equals(method) ? m.str("entry_ref") : null, method != null ? now() : null);
    }
  }

  // Runs inside the transaction that takes the last seat.
  private void startCircle(Handle tx, Row circle) {
    tx.run("UPDATE circles SET status = 'active', started_at = ?, current_month = 1 WHERE id = ?", now(), circle.str("id"));
    openMonth(tx, circle.str("id"), 1, circle.n("share"));
    log(tx, "circle_started", circle.str("id"), null, null, null, obj("fillMs", now() - circle.n("created_at")));
  }

  private void publishNonceDigest(String circleId) {
    Row circle = getCircle(circleId);
    if ("forming".equals(circle.str("status")) || circle.str("nonce_digest") != null) return;
    String digest = Fairness.nonceDigest(membersOf(circleId, db).stream().map(m -> m.str("nonce")).toList());
    db.run("UPDATE circles SET nonce_digest = ? WHERE id = ? AND nonce_digest IS NULL", digest, circleId);
  }

  private long committedMonthly(String phone) {
    return db.get(
            "SELECT COALESCE(SUM(c.share), 0) AS total FROM circle_members m JOIN circles c ON c.id = m.circle_id"
                + " WHERE m.phone = ? AND c.status IN ('forming', 'active')",
            phone)
        .n("total");
  }

  public Map<String, Object> eligibility(String phone) {
    if (!scoringCheck) {
      return obj("approved", true, "monthlyLimit", null, "committed", committedMonthly(phone),
          "available", 9007199254740991L);
    }
    Digipay.Score score = digipay.scoringCheck(phone);
    long committed = committedMonthly(phone);
    return obj("approved", score.approved(), "monthlyLimit", score.monthlyLimit(), "committed", committed,
        "available", Math.max(0, score.monthlyLimit() - committed));
  }

  private Row seatIn(String phone, String planId) {
    return db.get(
        "SELECT m.circle_id FROM circle_members m JOIN circles c ON c.id = m.circle_id"
            + " WHERE m.phone = ? AND c.plan_id = ? AND c.status IN ('forming', 'active')",
        phone, planId);
  }

  /**
   * Step 1 of joining: check the member can join, then send them to the gateway
   * to pay the first share. The seat is taken in {@link #completeCheckout}.
   */
  public Map<String, Object> join(String phone, String planId, String nonce) {
    Plan plan = planId == null ? null : Plans.byId(planId);
    if (plan == null) throw new HttpError(404, "این طرح پیدا نشد.");
    if (plan.retired()) throw new HttpError(410, "این طرح دیگر ارائه نمی‌شود.");
    expireStale();
    if (seatIn(phone, plan.id()) != null) throw new HttpError(409, "شما در یک دوره‌ی فعال از همین طرح عضو هستید.");

    Map<String, Object> e = eligibility(phone);
    if (!(Boolean) e.get("approved")) {
      throw new HttpError(403, "در حال حاضر امکان عضویت در طرح‌ها برای شما فعال نیست.");
    }
    if ((Long) e.get("available") < plan.share()) {
      throw new HttpError(403, "سقف اعتبار ماهانه‌ی شما برای این طرح کافی نیست. طرح کوچک‌تری را امتحان کنید.");
    }

    // A nonce from the member's own device: entropy the server couldn't know
    // when it committed to the hash chain.
    String memberNonce = nonce != null && NONCE.matcher(nonce).matches() ? nonce : Fairness.randomHex(16);
    String id = Ids.newId();
    Digipay.Checkout checkout = digipay.createCheckout(phone, plan.share(), id);
    db.run(
        "INSERT INTO checkouts (id, kind, phone, plan_id, nonce, items, amount, status, ref, created_at)"
            + " VALUES (?, 'entry', ?, ?, ?, '[1]', ?, 'pending', ?, ?)",
        id, phone, plan.id(), memberNonce, plan.share(), checkout.checkoutRef(), now());
    return obj("checkoutId", id, "redirectUrl", checkout.url());
  }

  private record Seat(String circleId, String memberId) {}

  // Step 2, once the first share is paid: take a seat in the plan's open
  // circle (a fresh one if none is open).
  private Seat seat(String phone, Plan plan, String nonce, String entryRef) {
    // The wallet-debit fallback the member agrees to in the terms (when on).
    String mandateId = walletDebit ? digipay.createMandate(phone, plan.share(), plan.months()) : null;

    for (int attempt = 0; attempt < 5; attempt++) {
      String circleId = openCircleFor(plan);
      Seat joined = db.transaction(tx -> {
        Row circle = lockCircle(tx, circleId);
        long taken = countMembers(circleId, tx);
        if (!"forming".equals(circle.str("status")) || taken >= circle.n("size")) return null;
        String memberId = Ids.newId();
        tx.run(
            "INSERT INTO circle_members (id, circle_id, position, phone, nonce, pay_method, mandate_id, entry_ref, joined_at)"
                + " VALUES (?, ?, ?, ?, ?, 'manual', ?, ?, ?)",
            memberId, circleId, taken + 1, phone, nonce, mandateId, entryRef, now());
        log(tx, "joined", circleId, memberId, phone, plan.share(), obj("position", taken + 1));
        if (taken + 1 == circle.n("size")) startCircle(tx, circle);
        return new Seat(circleId, memberId);
      });
      if (joined != null) {
        publishNonceDigest(joined.circleId());
        runDueDraws(joined.circleId()); // pays month 1 out if this seat started it
        return joined;
      }
    }
    throw new HttpError(503, "لطفاً دوباره تلاش کنید.");
  }

  /**
   * Leaving is only possible while waiting for the circle to fill. Seats behind
   * the leaver move up so positions stay 1..n.
   */
  public void leave(String phone, String circleId) {
    Row member = memberFor(phone, circleId);
    boolean left = db.transaction(tx -> {
      if (!"forming".equals(lockCircle(tx, circleId).str("status"))) return false;
      tx.run("DELETE FROM circle_members WHERE id = ?", member.str("id"));
      tx.run("UPDATE circle_members SET position = -position WHERE circle_id = ? AND position > ?",
          circleId, member.n("position"));
      tx.run("UPDATE circle_members SET position = -position - 1 WHERE circle_id = ? AND position < 0", circleId);
      return true;
    });
    if (!left) throw new HttpError(400, "گروه شروع شده و دیگر نمی‌شود از آن خارج شد.");
    log(db, "left", circleId, member.str("id"), phone, null, null);
    release(member, getCircle(circleId));
  }

  // ---------- running ----------

  /**
   * Runs every draw whose day has come (month 1's on the start day itself).
   * Called on a timer and whenever a circle is looked at, so nothing depends
   * on the timer alone. A failure is logged and retried next time.
   */
  public void runDueDraws(String circleId) {
    List<Row> circles;
    if (circleId != null) {
      Row c = getCircle(circleId);
      circles = c == null ? List.of() : List.of(c);
    } else {
      circles = db.all("SELECT * FROM circles WHERE status = 'active' AND closing = 0");
    }
    for (Row circle : circles) {
      while ("active".equals(circle.str("status"))
          && !circle.flag("closing")
          && now() >= Schedule.drawAt(circle.n("started_at"), circle.n("current_month"))) {
        try {
          closeMonth(circle.str("id"));
        } catch (HttpError error) {
          if (error.status() != 409) System.err.printf("draw for circle %s failed: %s%n", circle.str("id"), error.getMessage());
          break;
        } catch (RuntimeException error) {
          System.err.printf("draw for circle %s failed: %s%n", circle.str("id"), error.getMessage());
          break;
        }
        circle = getCircle(circle.str("id"));
      }
    }
    forfeitUnclaimed(circleId);
  }

  public void runDueDraws() {
    runDueDraws(null);
  }

  // Sends a month's pot to its recipient and records the payout.
  private void payOut(String circleId, long month, Row recipient, long pot) {
    String ref = digipay.sendPayout(recipient.str("phone"), recipient.flag("is_operator"), pot, circleId + ":" + month);
    db.run("UPDATE circle_draws SET payout_ref = ? WHERE circle_id = ? AND month = ?", ref, circleId, month);
    log(db, "payout", circleId, recipient.str("id"), recipient.str("phone"), pot,
        obj("month", month, "operator", recipient.flag("is_operator"), "ref", ref));
  }

  private record Claim(Row member, Row held) {}

  // A member who has just settled everything they owed takes a held pot, if
  // there is one, straight away: it's recorded as that month's recipient.
  private Map<String, Object> claimHeld(String circleId, String memberId) {
    Claim claim = db.transaction(tx -> {
      lockCircle(tx, circleId);
      Row member = tx.get("SELECT * FROM circle_members WHERE id = ?", memberId);
      if (member == null || member.lng("won_month") != null) return null;
      if (!outstandingOf(circleId, memberId, tx).isEmpty()) return null;
      Row held = tx.get("SELECT * FROM held_pots WHERE circle_id = ? AND status = 'held' ORDER BY month LIMIT 1", circleId);
      if (held == null) return null;
      tx.run("UPDATE held_pots SET status = 'claimed', member_id = ?, resolved_at = ? WHERE circle_id = ? AND month = ?",
          memberId, now(), circleId, held.n("month"));
      tx.run(
          "INSERT INTO circle_draws (circle_id, month, kind, eligible, winner_member_id, pot, created_at)"
              + " VALUES (?, ?, 'last', ?, ?, ?, ?)",
          circleId, held.n("month"), Json.stringify(List.of(memberId)), memberId, held.n("pot"), now());
      tx.run("UPDATE circle_members SET won_month = ? WHERE id = ?", held.n("month"), memberId);
      log(tx, "held_claimed", circleId, memberId, member.str("phone"), held.n("pot"),
          obj("month", held.n("month"), "position", member.n("position")));
      return new Claim(member, held);
    });
    if (claim == null) return null;
    payOut(circleId, claim.held().n("month"), claim.member(), claim.held().n("pot"));
    return obj("month", claim.held().n("month"), "pot", claim.held().n("pot"));
  }

  private record Taken(Row operator, List<Row> held) {}

  // Past the grace period after the last draw, pots nobody settled for are
  // the operator's. They cover the debts of those still waiting, who lose
  // the share they paid on joining.
  private void forfeitUnclaimed(String circleId) {
    List<Row> circles;
    if (circleId != null) {
      Row c = getCircle(circleId);
      circles = c != null && "completed".equals(c.str("status")) ? List.of(c) : List.of();
    } else {
      circles = db.all(
          "SELECT DISTINCT c.* FROM circles c JOIN held_pots h ON h.circle_id = c.id"
              + " WHERE c.status = 'completed' AND h.status = 'held'");
    }
    for (Row circle : circles) {
      if (now() < claimDeadline(circle)) continue;
      String id = circle.str("id");
      Taken taken = db.transaction(tx -> {
        lockCircle(tx, id);
        List<Row> held = tx.all("SELECT * FROM held_pots WHERE circle_id = ? AND status = 'held' ORDER BY month", id);
        if (held.isEmpty()) return null;
        List<Row> members = membersOf(id, tx);
        Row operator = operatorOf(members);
        for (Row h : held) {
          tx.run("UPDATE held_pots SET status = 'forfeited', resolved_at = ? WHERE circle_id = ? AND month = ?",
              now(), id, h.n("month"));
          tx.run(
              "INSERT INTO circle_draws (circle_id, month, kind, eligible, winner_member_id, pot, created_at)"
                  + " VALUES (?, ?, 'operator', ?, ?, ?, ?)",
              id, h.n("month"), Json.stringify(List.of(operator.str("id"))), operator.str("id"), h.n("pot"), now());
          log(tx, "forfeit", id, null, null, h.n("pot"), obj("month", h.n("month")));
        }
        // Their debts are paid off by the pots the operator kept.
        for (Row m : members) {
          if (m.lng("won_month") != null || m.flag("is_operator")) continue;
          List<Row> debt = tx.all(
              "SELECT amount FROM contributions WHERE circle_id = ? AND member_id = ? AND status = 'covered' AND settled_at IS NULL",
              id, m.str("id"));
          if (debt.isEmpty()) continue;
          tx.run(
              "UPDATE contributions SET settled_at = ?, method = 'forfeit' WHERE circle_id = ? AND member_id = ?"
                  + " AND status = 'covered' AND settled_at IS NULL",
              now(), id, m.str("id"));
          log(tx, "debt_forfeited", id, m.str("id"), m.str("phone"), debt.stream().mapToLong(d -> d.n("amount")).sum(), null);
        }
        return new Taken(operator, held);
      });
      if (taken != null) for (Row h : taken.held()) payOut(id, h.n("month"), taken.operator(), h.n("pot"));
    }
  }

  private record Draw(String kind, Long drawNo, String reveal, String seed, String winner, List<String> eligible) {}

  public Map<String, Object> closeMonth(String circleId) {
    int claimed = db.run("UPDATE circles SET closing = 1 WHERE id = ? AND status = 'active' AND closing = 0", circleId);
    if (claimed == 0) throw new HttpError(409, "این دوره فعال نیست یا در حال پردازش است.");

    try {
      publishNonceDigest(circleId);
      Row circle = getCircle(circleId);
      long month = circle.n("current_month");
      List<Row> members = membersOf(circleId, db);

      // 1. Debit the wallet of anyone who hasn't paid through the gateway
      // (when wallet debits are on). Simulated members always pay by now:
      // with debits off, as if through the gateway.
      List<Row> due = db.all("SELECT * FROM contributions WHERE circle_id = ? AND month = ? AND status = 'due'", circleId, month);
      for (Row d : due) {
        Row member = find(members, d.str("member_id"));
        if (member.flag("is_operator") || member.str("mandate_id") == null) continue;
        if (!walletDebit && !member.flag("is_bot")) continue;
        String method = walletDebit ? "wallet" : "manual";
        Digipay.Charge charge = digipay.charge(member.str("mandate_id"), d.n("amount"), circleId + ":" + month + ":" + member.str("id"));
        if (charge.ok()) {
          db.run(
              "UPDATE contributions SET status = 'paid', method = ?, ref = ?, paid_at = ?"
                  + " WHERE circle_id = ? AND member_id = ? AND month = ? AND status = 'due'",
              method, charge.ref(), now(), circleId, member.str("id"), month);
          if (walletDebit) log(db, "wallet_debit", circleId, member.str("id"), member.str("phone"), d.n("amount"), obj("month", month));
        } else if (walletDebit) {
          log(db, "wallet_failed", circleId, member.str("id"), member.str("phone"), d.n("amount"),
              obj("month", month, "reason", charge.reason()));
        }
      }

      // 2. Pick this month's recipient: never someone who owes the guarantee.
      List<Row> waiting = members.stream().filter(m -> m.lng("won_month") == null).toList();
      Set<String> owing = db.all(
              "SELECT DISTINCT member_id FROM contributions WHERE circle_id = ?"
                  + " AND ((status = 'covered' AND settled_at IS NULL) OR (month = ? AND status = 'due'))",
              circleId, month)
          .stream().map(r -> r.str("member_id")).collect(Collectors.toCollection(HashSet::new));
      List<Row> clear = waiting.stream().filter(m -> !owing.contains(m.str("id"))).toList();
      Draw draw;
      if (month == 1) {
        String operator = operatorOf(members).str("id");
        draw = new Draw("operator", null, null, null, operator, List.of(operator));
      } else if (clear.isEmpty()) {
        // Everyone still waiting owes: no draw; the operator holds the pot
        // until one of them settles.
        draw = new Draw("held", null, null, null, null, null);
      } else if (waiting.size() == 1) {
        String last = clear.get(0).str("id");
        draw = new Draw("last", null, null, null, last, List.of(last));
      } else {
        List<String> eligible = clear.stream().map(m -> m.str("id")).toList();
        long drawNo = db.get("SELECT COUNT(*) AS n FROM circle_draws WHERE circle_id = ? AND kind = 'lottery'", circleId).n("n") + 1;
        String reveal = Fairness.buildChain(circle.str("chain_secret"), circle.n("months")).get((int) drawNo);
        Fairness.Pick pick = Fairness.pickWinner(reveal, getCircle(circleId).str("nonce_digest"), month, eligible);
        draw = new Draw("lottery", drawNo, reveal, pick.seed(), pick.winner(), eligible);
      }

      // 3. Record it: the guarantee covers anyone still unpaid.
      long pot = circle.n("size") * circle.n("share");
      Draw chosen = draw;
      db.transaction(tx -> {
        List<Row> uncovered = tx.all(
            "SELECT member_id, amount FROM contributions WHERE circle_id = ? AND month = ? AND status = 'due'", circleId, month);
        tx.run(
            "UPDATE contributions SET status = 'covered', method = 'guarantee', paid_at = ?"
                + " WHERE circle_id = ? AND month = ? AND status = 'due'",
            now(), circleId, month);
        for (Row u : uncovered) {
          Row m = find(members, u.str("member_id"));
          log(tx, "guarantee", circleId, u.str("member_id"), m == null ? null : m.str("phone"), u.n("amount"), obj("month", month));
        }
        if ("held".equals(chosen.kind())) {
          tx.run("INSERT INTO held_pots (circle_id, month, pot, held_at, status) VALUES (?, ?, ?, ?, 'held')",
              circleId, month, pot, now());
          log(tx, "held", circleId, null, null, pot, obj("month", month, "owing", waiting.size()));
        } else {
          tx.run(
              "INSERT INTO circle_draws (circle_id, month, kind, draw_no, reveal, seed, eligible, winner_member_id, pot, created_at)"
                  + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
              circleId, month, chosen.kind(), chosen.drawNo(), chosen.reveal(), chosen.seed(),
              Json.stringify(chosen.eligible()), chosen.winner(), pot, now());
          tx.run("UPDATE circle_members SET won_month = ? WHERE id = ?", month, chosen.winner());
          Row winner = find(members, chosen.winner());
          log(tx, "draw", circleId, chosen.winner(), winner.str("phone"), pot,
              obj("month", month, "kind", chosen.kind(), "eligible", chosen.eligible().size(), "position", winner.n("position")));
        }
        if (month == circle.n("months")) {
          tx.run("UPDATE circles SET status = 'completed', closing = 0 WHERE id = ?", circleId);
        } else {
          tx.run("UPDATE circles SET current_month = ?, closing = 0 WHERE id = ?", month + 1, circleId);
          openMonth(tx, circleId, month + 1, circle.n("share"));
        }
        return null;
      });

      // 4. Pay the pot out. A failed payout can be retried; the draw stands.
      if ("held".equals(draw.kind())) return obj("month", month, "kind", "held", "winner", null, "pot", pot);
      payOut(circleId, month, find(members, draw.winner()), pot);
      return obj("month", month, "kind", draw.kind(), "winner", draw.winner(), "pot", pot);
    } catch (RuntimeException error) {
      db.run("UPDATE circles SET closing = 0 WHERE id = ?", circleId);
      throw error;
    }
  }

  // ---------- manual payments ----------

  private Row memberFor(String phone, String circleId) {
    Row member = db.get("SELECT * FROM circle_members WHERE circle_id = ? AND phone = ?", circleId, phone);
    if (member == null) throw new HttpError(404, "این دوره پیدا نشد.");
    return member;
  }

  private List<Row> outstandingOf(String circleId, String memberId, Handle d) {
    return d.all(
        "SELECT month, amount, status, paid_at, settled_at FROM contributions WHERE circle_id = ? AND member_id = ?"
            + " AND (status = 'due' OR (status = 'covered' AND settled_at IS NULL)) ORDER BY month",
        circleId, memberId);
  }

  public Map<String, Object> startCheckout(String phone, String circleId) {
    Row member = memberFor(phone, circleId);
    List<Row> items = outstandingOf(circleId, member.str("id"), db);
    if (items.isEmpty()) throw new HttpError(400, "پرداخت معوقی ندارید.");
    // What's owed, plus the late fee on shares the guarantee paid.
    long amount = items.stream().mapToLong(i -> i.n("amount")).sum() + lateFees(items);
    String id = Ids.newId();
    Digipay.Checkout checkout = digipay.createCheckout(phone, amount, id);
    db.run(
        "INSERT INTO checkouts (id, kind, phone, plan_id, circle_id, member_id, items, amount, status, ref, created_at)"
            + " VALUES (?, 'dues', ?, ?, ?, ?, ?, ?, 'pending', ?, ?)",
        id, phone, getCircle(circleId).str("plan_id"), circleId, member.str("id"),
        Json.stringify(items.stream().map(i -> i.n("month")).toList()), amount, checkout.checkoutRef(), now());
    return obj("checkoutId", id, "redirectUrl", checkout.url());
  }

  public Map<String, Object> getCheckout(String phone, String id) {
    Row checkout = db.get("SELECT * FROM checkouts WHERE id = ? AND phone = ?", id, phone);
    if (checkout == null) throw new HttpError(404, "پرداخت پیدا نشد.");
    // The late fee is whatever the checkout asks beyond the shares themselves.
    List<Object> months = Json.parseList(checkout.str("items"));
    long shares = checkout.n("amount");
    if ("dues".equals(checkout.str("kind")) && !months.isEmpty()) {
      List<Object> params = new ArrayList<>(List.of(checkout.str("circle_id"), checkout.str("member_id")));
      params.addAll(months);
      shares = db.get(
              "SELECT COALESCE(SUM(amount), 0) AS total FROM contributions WHERE circle_id = ? AND member_id = ? AND month IN ("
                  + String.join(", ", Collections.nCopies(months.size(), "?")) + ")",
              params.toArray())
          .n("total");
    }
    return obj(
        "id", checkout.str("id"),
        "kind", checkout.str("kind"),
        "amount", checkout.n("amount"),
        "lateFee", Math.max(0, checkout.n("amount") - shares),
        "status", checkout.str("status"),
        "months", months,
        "circleId", checkout.str("circle_id"),
        "planId", checkout.str("plan_id"));
  }

  public Map<String, Object> completeCheckout(String phone, String id, String action) {
    Row checkout = db.get("SELECT * FROM checkouts WHERE id = ? AND phone = ?", id, phone);
    if (checkout == null) throw new HttpError(404, "پرداخت پیدا نشد.");
    if (!"pending".equals(checkout.str("status"))) throw new HttpError(400, "این پرداخت قبلاً نهایی شده است.");
    Digipay.Verified result = digipay.verifyCheckout(checkout.str("ref"), action);
    if ("entry".equals(checkout.str("kind"))) return completeEntry(checkout, result);
    String circleId = checkout.str("circle_id");
    String memberId = checkout.str("member_id");
    db.transaction(tx -> {
      int changes = tx.run("UPDATE checkouts SET status = ?, ref = ? WHERE id = ? AND status = 'pending'",
          result.ok() ? "paid" : "cancelled", result.ref() != null ? result.ref() : checkout.str("ref"), id);
      if (changes == 0) throw new HttpError(400, "این پرداخت قبلاً نهایی شده است.");
      if (!result.ok()) return null;
      long paid = 0;
      long settled = 0;
      for (Object month : Json.parseList(checkout.str("items"))) {
        Row row = tx.get("SELECT amount, status, settled_at FROM contributions WHERE circle_id = ? AND member_id = ? AND month = ?",
            circleId, memberId, month);
        tx.run(
            "UPDATE contributions SET status = 'paid', method = 'manual', ref = ?, paid_at = ?"
                + " WHERE circle_id = ? AND member_id = ? AND month = ? AND status = 'due'",
            result.ref(), now(), circleId, memberId, month);
        tx.run(
            "UPDATE contributions SET settled_at = ?"
                + " WHERE circle_id = ? AND member_id = ? AND month = ? AND status = 'covered' AND settled_at IS NULL",
            now(), circleId, memberId, month);
        if (row != null && "due".equals(row.str("status"))) paid += row.n("amount");
        if (row != null && "covered".equals(row.str("status")) && row.lng("settled_at") == null) settled += row.n("amount");
      }
      String who = checkout.str("phone");
      if (paid != 0) log(tx, "gateway_payment", circleId, memberId, who, paid, null);
      if (settled != 0) log(tx, "debt_settled", circleId, memberId, who, settled, null);
      long fee = checkout.n("amount") - paid - settled;
      if (fee > 0) log(tx, "late_fee", circleId, memberId, who, fee, null);
      return null;
    });
    // All settled: a pot held for the members who owed is theirs now.
    Map<String, Object> claimed = result.ok() ? claimHeld(circleId, memberId) : null;
    return obj("ok", result.ok(), "circleId", circleId, "claimed", claimed);
  }

  private Map<String, Object> completeEntry(Row checkout, Digipay.Verified result) {
    int claimed = db.run("UPDATE checkouts SET status = ?, ref = ? WHERE id = ? AND status = 'pending'",
        result.ok() ? "paid" : "cancelled", result.ref() != null ? result.ref() : checkout.str("ref"), checkout.str("id"));
    if (claimed == 0) throw new HttpError(400, "این پرداخت قبلاً نهایی شده است.");
    if (!result.ok()) return obj("ok", false, "circleId", null);

    Plan plan = Plans.byId(checkout.str("plan_id"));
    expireStale();
    // Paid twice for the same plan (two tabs): keep the seat, return the money.
    Row existing = seatIn(checkout.str("phone"), plan.id());
    if (existing != null) {
      digipay.refund(result.ref(), checkout.n("amount"));
      db.run("UPDATE checkouts SET status = 'refunded' WHERE id = ?", checkout.str("id"));
      log(db, "refund", existing.str("circle_id"), null, checkout.str("phone"), checkout.n("amount"), obj("reason", "duplicate"));
      return obj("ok", true, "refunded", true, "circleId", existing.str("circle_id"));
    }
    Seat seat = seat(checkout.str("phone"), plan, checkout.str("nonce"), result.ref());
    db.run("UPDATE checkouts SET circle_id = ?, member_id = ? WHERE id = ?", seat.circleId(), seat.memberId(), checkout.str("id"));
    return obj("ok", true, "circleId", seat.circleId());
  }

  // ---------- views ----------

  public List<Map<String, Object>> planSummaries() {
    expireStale();
    List<Map<String, Object>> summaries = new ArrayList<>();
    for (Plan plan : Plans.OFFERED) {
      Row open = db.get(
          "SELECT c.id, COUNT(m.id) AS taken FROM circles c JOIN circle_members m ON m.circle_id = c.id"
              + " WHERE c.plan_id = ? AND c.status = 'forming' GROUP BY c.id, c.created_at ORDER BY c.created_at LIMIT 1",
          plan.id());
      Map<String, Object> s = plan.toMap();
      s.put("pot", plan.pot());
      s.put("taken", open != null ? open.n("taken") : 1);
      summaries.add(s);
    }
    return summaries;
  }

  /** The reveal animation plays once per draw; this records that it did. */
  public void markSeen(String phone, String circleId, long month) {
    Row member = memberFor(phone, circleId);
    Long max = db.get("SELECT MAX(month) AS m FROM circle_draws WHERE circle_id = ?", circleId).lng("m");
    long latest = max != null ? max : 1;
    long seen = Math.min(Math.max(month, member.n("seen_month")), latest);
    db.run("UPDATE circle_members SET seen_month = ? WHERE id = ?", seen, member.str("id"));
  }

  private Map<String, Object> summarize(Row circle, Row member) {
    String id = circle.str("id");
    long taken = countMembers(id, db);
    List<Row> outstanding = outstandingOf(id, member.str("id"), db);
    // Debt is only what the guarantee paid for; this month's share isn't late yet.
    long owed = outstanding.stream().filter(i -> "covered".equals(i.str("status"))).mapToLong(i -> i.n("amount")).sum();
    long dueNow = outstanding.stream().filter(i -> "due".equals(i.str("status"))).mapToLong(i -> i.n("amount")).sum();
    // How many seats have been paid their pot so far (Digipay's month 1 included).
    long received = db.get("SELECT COUNT(*) AS n FROM circle_draws WHERE circle_id = ? AND payout_ref IS NOT NULL", id).n("n");
    // Pots Digipay is holding because everyone still waiting owes.
    long held = db.get("SELECT COUNT(*) AS n FROM held_pots WHERE circle_id = ? AND status = 'held'", id).n("n");
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("id", id);
    s.put("planId", circle.str("plan_id"));
    s.put("status", circle.str("status"));
    s.put("size", circle.n("size"));
    s.put("months", circle.n("months"));
    s.put("share", circle.n("share"));
    s.put("pot", circle.n("size") * circle.n("share"));
    s.put("currentMonth", circle.n("current_month"));
    s.put("deadline", circle.lng("deadline"));
    s.put("startedAt", circle.lng("started_at"));
    s.put("seenMonth", member.n("seen_month"));
    s.put("taken", taken);
    s.put("position", member.n("position"));
    s.put("payMethod", member.str("pay_method"));
    s.put("wonMonth", member.lng("won_month"));
    s.put("received", received);
    s.put("owed", owed);
    s.put("lateFee", lateFees(outstanding));
    s.put("dueNow", dueNow);
    s.put("held", held);
    // A held pot unclaimed by then is Digipay's.
    s.put("claimBy", held != 0 && circle.lng("started_at") != null ? (Object) claimDeadline(circle) : null);
    return s;
  }

  public List<Map<String, Object>> myCircles(String phone) {
    expireStale();
    runDueDraws();
    List<Row> rows = db.all(
        "SELECT c.*, m.id AS member_id FROM circle_members m JOIN circles c ON c.id = m.circle_id"
            + " WHERE m.phone = ? AND c.status != 'expired' ORDER BY c.status = 'completed', c.created_at DESC",
        phone);
    List<Map<String, Object>> result = new ArrayList<>();
    for (Row row : rows) {
      result.add(summarize(row, db.get("SELECT * FROM circle_members WHERE id = ?", row.str("member_id"))));
    }
    return result;
  }

  /** Members see each other only by seat number; phones never leave the server. */
  public Map<String, Object> circleView(String phone, String circleId, boolean ops) {
    expireStale();
    runDueDraws(circleId);
    Row circle = getCircle(circleId);
    if (circle == null) throw new HttpError(404, "این دوره پیدا نشد.");
    List<Row> members = membersOf(circleId, db);
    Row me = phone == null ? null : members.stream().filter(m -> phone.equals(m.str("phone"))).findFirst().orElse(null);
    if (me == null && !ops) throw new HttpError(404, "این دوره پیدا نشد.");

    List<Row> draws = db.all("SELECT * FROM circle_draws WHERE circle_id = ? ORDER BY month", circleId);
    Map<Long, String> reveals = new HashMap<>();
    for (Row d : draws) if ("lottery".equals(d.str("kind"))) reveals.put(d.n("draw_no"), d.str("reveal"));
    List<Row> contributions = me != null
        ? db.all("SELECT month, amount, status, method, settled_at FROM contributions WHERE circle_id = ? AND member_id = ? ORDER BY month",
            circleId, me.str("id"))
        : List.of();

    Map<String, Object> summary;
    if (me != null) {
      summary = summarize(circle, me);
    } else {
      summary = summarize(circle, members.get(0));
      summary.put("position", null);
      summary.put("owed", 0L);
      summary.put("dueNow", 0L);
    }
    String meId = me == null ? null : me.str("id");
    boolean forming = "forming".equals(circle.str("status"));

    Map<String, Object> view = new LinkedHashMap<>();
    view.put("circle", summary);
    view.put("anchor", circle.str("anchor"));
    view.put("nonceDigest", circle.str("nonce_digest"));
    view.put("members", members.stream().map(m -> obj(
        "id", m.str("id"),
        "position", m.n("position"),
        "isOperator", m.flag("is_operator"),
        "isMe", m.str("id").equals(meId),
        "wonMonth", m.lng("won_month"),
        "nonce", forming ? null : m.str("nonce"))).toList());
    view.put("draws", draws.stream().map(d -> {
      boolean lottery = "lottery".equals(d.str("kind"));
      return obj(
          "month", d.n("month"),
          "kind", d.str("kind"),
          "drawNo", d.lng("draw_no"),
          "reveal", d.str("reveal"),
          "previous", lottery ? (d.n("draw_no") == 1 ? circle.str("anchor") : reveals.get(d.n("draw_no") - 1)) : null,
          "seed", d.str("seed"),
          "eligible", Json.parseList(d.str("eligible")),
          "winner", d.str("winner_member_id"),
          "pot", d.n("pot"),
          "paidOut", d.str("payout_ref") != null,
          "createdAt", d.n("created_at"));
    }).toList());
    view.put("walletDebit", walletDebit);
    // Months whose pot Digipay is holding (until claimed or kept).
    view.put("heldMonths", db.all("SELECT month FROM held_pots WHERE circle_id = ? AND status = 'held'", circleId)
        .stream().map(h -> h.n("month")).toList());
    view.put("contributions", contributions.stream().map(c -> obj(
        "month", c.n("month"),
        "amount", c.n("amount"),
        "status", "covered".equals(c.str("status")) && c.lng("settled_at") != null ? "settled" : c.str("status"),
        "method", c.str("method"))).toList());
    return view;
  }

  // ---------- ops / simulator ----------

  /**
   * Seats simulated members so a demo circle can start. Every fourth bot
   * "forgets" to pay, which shows the guarantee at work.
   */
  public void fillWithBots(String circleId) {
    boolean filled = db.transaction(tx -> {
      Row circle = lockCircle(tx, circleId);
      if (circle == null || !"forming".equals(circle.str("status"))) return false;
      long taken = countMembers(circleId, tx);
      while (taken < circle.n("size")) {
        taken += 1;
        boolean failing = taken % 4 == 0;
        tx.run(
            "INSERT INTO circle_members (id, circle_id, position, phone, is_bot, nonce, pay_method, mandate_id, entry_ref, joined_at)"
                + " VALUES (?, ?, ?, NULL, 1, ?, 'auto', ?, ?, ?)",
            Ids.newId(), circleId, taken, Fairness.randomHex(16),
            "sim-mandate-" + (failing ? "failing-" : "") + Ids.newId(), "sim-entry-" + Ids.newId(), now());
      }
      startCircle(tx, circle);
      return true;
    });
    if (!filled) throw new HttpError(400, "فقط دوره‌ی در حال تکمیل را می‌شود پر کرد.");
    publishNonceDigest(circleId);
    runDueDraws(circleId);
  }
}
