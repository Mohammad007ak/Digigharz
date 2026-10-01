package ir.digigharz.circles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ir.digigharz.HttpError;
import ir.digigharz.Json;
import ir.digigharz.TestSupport;
import ir.digigharz.TestSupport.RecordingDigipay;
import ir.digigharz.db.Db;
import ir.digigharz.db.Row;
import ir.digigharz.lib.Fairness;
import ir.digigharz.lib.Jalali;
import ir.digigharz.lib.Schedule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** The guaranteed circles, as in server/circles.test.js. */
@SuppressWarnings("unchecked")
class CircleServiceTest {
  // Phones ending 4–9 get a 25M monthly limit from the simulator's scoring.
  static String phone(int i) {
    return "0912000" + String.format("%03d", i) + "9";
  }

  record Setup(Db db, CircleService service, RecordingDigipay digipay) {}

  static Setup setup(AtomicLong clock, boolean walletDebit, boolean scoringCheck) {
    Db db = TestSupport.testDatabase();
    RecordingDigipay digipay = new RecordingDigipay();
    CircleService.Options o = CircleService.Options.of(db, digipay)
        .withFormTimeout(60 * 60 * 1000)
        .withWalletDebit(walletDebit)
        .withScoringCheck(scoringCheck);
    if (clock != null) o = o.withNow(clock::get);
    return new Setup(db, new CircleService(o), digipay);
  }

  static Setup setup() {
    return setup(null, true, true);
  }

  // Joining = starting the entry checkout and paying the first share.
  static Map<String, Object> join(CircleService service, String phone, String planId) {
    String checkoutId = (String) service.join(phone, planId, null).get("checkoutId");
    return service.completeCheckout(phone, checkoutId, "pay");
  }

  static String fillPlan(CircleService service, String planId, int members) {
    String circleId = null;
    for (int i = 1; i <= members; i++) circleId = (String) join(service, phone(i), planId).get("circleId");
    return circleId;
  }

  static void rejects(int status, Executable fn) {
    assertEquals(status, assertThrows(HttpError.class, fn).status());
  }

  static Map<String, Object> circleOf(Map<String, Object> view) {
    return (Map<String, Object>) view.get("circle");
  }

  static List<Map<String, Object>> list(Map<String, Object> view, String key) {
    return (List<Map<String, Object>>) view.get(key);
  }

  static Map<String, Object> me(Map<String, Object> view) {
    return list(view, "members").stream().filter(m -> (Boolean) m.get("isMe")).findFirst().orElseThrow();
  }

  @Test
  void withTheScoringCheckOffAnyoneCanJoinAnyPlan() {
    CircleService service = setup(null, true, false).service();
    // Phones ending 0 are rejected and 1–3 limited to 5M when the check is on.
    join(service, "09120000000", "p12-10");
    join(service, "09120000001", "p12-10");
    join(service, "09120000001", "p6-10");
    assertEquals(true, service.eligibility("09120000000").get("approved"));
  }

  @Test
  void scoringGatesWhoCanJoinAndHowMuchTheyCanCommit() {
    CircleService service = setup().service();
    rejects(403, () -> service.join("09120000000", "p12-5", null));
    // Limit 5M: the 5M plan fits, the 10M plan doesn't, and a second 5M plan doesn't either.
    join(service, "09120000001", "p12-5");
    rejects(403, () -> service.join("09120000001", "p12-10", null));
    rejects(409, () -> service.join("09120000001", "p12-5", null));
  }

  @Test
  void aCircleStartsWhenFullTheOperatorTakesMonth1AndEveryoneReceivesExactlyOnce() {
    Setup s = setup();
    CircleService service = s.service();
    String circleId = fillPlan(service, "p12-5", 11);

    Map<String, Object> view = service.circleView(phone(1), circleId, false);
    assertEquals("active", circleOf(view).get("status"));
    assertEquals(12, list(view, "members").size());
    assertTrue((Boolean) list(view, "members").get(0).get("isOperator"));
    assertTrue(((String) view.get("nonceDigest")).matches("[0-9a-f]{64}"));
    // Month 1's pot went to the operator the moment the circle started.
    assertEquals("operator", list(view, "draws").get(0).get("kind"));
    assertEquals(2L, circleOf(view).get("currentMonth"));
    assertEquals(1, s.digipay().payouts.size());

    for (int m = 2; m <= 12; m++) service.closeMonth(circleId);

    // Seat 5's wallet is empty in the simulator: it never paid, so it was left
    // out of every draw and month 12's pot is held for it, not handed over.
    String debtor = phone(5);
    view = service.circleView(debtor, circleId, false);
    assertEquals("completed", circleOf(view).get("status"));
    assertEquals(1L, circleOf(view).get("held"));
    assertNull(me(view).get("wonMonth"));
    assertEquals(11, s.digipay().payouts.size());
    // Once it settles everything it owes, the held pot is paid to it.
    String checkoutId = (String) service.startCheckout(debtor, circleId).get("checkoutId");
    Map<String, Object> claimed = (Map<String, Object>) service.completeCheckout(debtor, checkoutId, "pay").get("claimed");
    assertEquals(Map.of("month", 12L, "pot", 60_000_000L), claimed);

    view = service.circleView(phone(1), circleId, false);
    List<Long> won = new ArrayList<>(list(view, "members").stream().map(m -> (Long) m.get("wonMonth")).toList());
    Collections.sort(won);
    assertEquals(LongStream.rangeClosed(1, 12).boxed().toList(), won);
    assertEquals("operator", list(view, "draws").get(0).get("kind"));
    assertEquals("last", list(view, "draws").get(11).get("kind"));
    assertEquals(10, list(view, "draws").stream().filter(d -> "lottery".equals(d.get("kind"))).count());
    assertEquals(12, s.digipay().payouts.size());
    assertTrue(s.digipay().payouts.stream().allMatch(p -> p.amount() == 60_000_000));
    assertTrue(s.digipay().payouts.get(0).operator());
  }

  /** The browser's check (src/lib/fairness.js verifyDraw). */
  static boolean[] verifyDraw(Map<String, Object> d, String digest, String winner, String reveal) {
    boolean linkOk = Fairness.sha256Hex(reveal).equals(d.get("previous"));
    List<String> eligible = ((List<Object>) d.get("eligible")).stream().map(Object::toString).toList();
    Fairness.Pick pick = Fairness.pickWinner(reveal, digest, (Long) d.get("month"), eligible);
    return new boolean[] {linkOk, linkOk && pick.winner().equals(winner)};
  }

  @Test
  void everyLotteryCanBeVerifiedByAMemberAndTamperingIsDetected() {
    CircleService service = setup().service();
    String circleId = fillPlan(service, "p12-5", 11);
    for (int m = 2; m <= 5; m++) service.closeMonth(circleId);

    Map<String, Object> view = service.circleView(phone(3), circleId, false);
    String digest = (String) view.get("nonceDigest");
    List<Map<String, Object>> lotteries = list(view, "draws").stream().filter(d -> "lottery".equals(d.get("kind"))).toList();
    assertEquals(4, lotteries.size());
    for (Map<String, Object> d : lotteries) {
      assertTrue(verifyDraw(d, digest, (String) d.get("winner"), (String) d.get("reveal"))[1], "month " + d.get("month"));
    }
    Map<String, Object> first = lotteries.get(0);
    String forged = ((List<Object>) first.get("eligible")).stream().map(Object::toString)
        .filter(id -> !id.equals(first.get("winner"))).findFirst().orElseThrow();
    assertFalse(verifyDraw(first, digest, forged, (String) first.get("reveal"))[1]);
    assertFalse(verifyDraw(first, digest, (String) first.get("winner"), "0".repeat(64))[0]);
  }

  @Test
  void theFirstShareIsPaidOnJoiningAndNothingIsSeatedUntilItIs() {
    CircleService service = setup().service();
    String checkoutId = (String) service.join(phone(1), "p12-5", null).get("checkoutId");
    assertEquals(5_000_000L, service.getCheckout(phone(1), checkoutId).get("amount"));
    assertEquals(0, service.myCircles(phone(1)).size());

    Map<String, Object> cancelled = service.completeCheckout(phone(1), checkoutId, "cancel");
    assertEquals(Json.obj("ok", false, "circleId", null), cancelled);
    assertEquals(0, service.myCircles(phone(1)).size());

    // Two entry payments for the same plan: one seat, the second is refunded.
    String a = (String) service.join(phone(1), "p12-5", null).get("checkoutId");
    String b = (String) service.join(phone(1), "p12-5", null).get("checkoutId");
    Map<String, Object> first = service.completeCheckout(phone(1), a, "pay");
    Map<String, Object> second = service.completeCheckout(phone(1), b, "pay");
    assertEquals(true, second.get("refunded"));
    assertEquals(first.get("circleId"), second.get("circleId"));
    String circleId = (String) first.get("circleId");
    assertEquals(2, list(service.circleView(phone(1), circleId, false), "members").size());

    for (int i = 2; i <= 11; i++) join(service, phone(i), "p12-5");
    Map<String, Object> view = service.circleView(phone(3), circleId, false);
    assertEquals("active", circleOf(view).get("status"));
    assertEquals(Json.obj("month", 1L, "amount", 5_000_000L, "status", "paid", "method", "entry"), list(view, "contributions").get(0));
    assertEquals(5_000_000L, circleOf(view).get("dueNow")); // month 2 is open, due a month from the start
  }

  @Test
  void unpaidSharesComeFromTheWalletIfThatFailsTheGuaranteeCoversThemAndDebtorsSitOutDraws() {
    CircleService service = setup().service();
    String debtor = phone(5); // second-to-last digit 5: empty wallet in the simulator
    String circleId = fillPlan(service, "p12-5", 11);

    service.closeMonth(circleId); // month 2: nobody used the gateway
    Map<String, Object> payer = service.circleView(phone(2), circleId, false);
    assertEquals("wallet", list(payer, "contributions").get(1).get("method"));
    assertEquals(0L, circleOf(payer).get("owed"));

    Map<String, Object> view = service.circleView(debtor, circleId, false);
    assertEquals("covered", list(view, "contributions").get(1).get("status"));
    assertEquals(5_000_000L, circleOf(view).get("owed")); // month 2, paid by the guarantee
    assertEquals(5_000_000L, circleOf(view).get("dueNow")); // month 3, not late yet

    service.closeMonth(circleId); // month 3: still unpaid
    view = service.circleView(debtor, circleId, false);
    String myId = (String) me(view).get("id");
    assertFalse(((List<Object>) list(view, "draws").get(2).get("eligible")).contains(myId), "a member who owes is left out");

    String checkoutId = (String) service.startCheckout(debtor, circleId).get("checkoutId");
    assertEquals(15_000_000L, service.getCheckout(debtor, checkoutId).get("amount"));
    service.completeCheckout(debtor, checkoutId, "pay");
    view = service.circleView(debtor, circleId, false);
    assertEquals(0L, circleOf(view).get("owed"));
    assertEquals(List.of("paid", "settled", "settled", "paid"), list(view, "contributions").stream().map(c -> c.get("status")).toList());
  }

  @Test
  void membersOnlySeeSeatsNeverEachOthersPhonesAndStrangersSeeNothing() {
    CircleService service = setup().service();
    String circleId = fillPlan(service, "p12-5", 11);
    Map<String, Object> view = service.circleView(phone(2), circleId, false);
    assertFalse(Json.stringify(view).contains("09120"));
    rejects(404, () -> service.circleView("09129999999", circleId, false));
  }

  @Test
  void aMonthCantBeClosedTwiceAtOnce() {
    CircleService service = setup().service();
    String circleId = fillPlan(service, "p12-5", 11);
    List<CompletableFuture<Object>> runs = List.of(
        CompletableFuture.supplyAsync(() -> service.closeMonth(circleId)),
        CompletableFuture.supplyAsync(() -> service.closeMonth(circleId)));
    long ok = runs.stream().filter(f -> {
      try {
        f.join();
        return true;
      } catch (RuntimeException e) {
        return false;
      }
    }).count();
    assertEquals(1, ok);
    assertEquals(3L, circleOf(service.circleView(phone(1), circleId, false)).get("currentMonth"));
  }

  @Test
  void eachDrawRunsByItselfOnTheSixthDayAfterItsDueDateAndItsRevealPlaysOnce() {
    AtomicLong clock = new AtomicLong(TestSupport.tehran("2026-10-07T14:00:00")); // 15 Mehr 1405
    Setup s = setup(clock, true, true);
    CircleService service = s.service();
    String circleId = fillPlan(service, "p12-5", 11);
    assertEquals(1, s.digipay().payouts.size()); // month 1, to Digipay, on the start day

    long drawDay = Schedule.drawAt(clock.get(), 2); // 20 Aban: due 15 Aban, five days to pay
    assertEquals(new Jalali.Date(1405, 8, 20), Jalali.toJalali(drawDay));
    clock.set(drawDay - 60 * 1000);
    assertEquals(1, list(service.circleView(phone(1), circleId, false), "draws").size());

    clock.set(drawDay);
    Map<String, Object> view = service.circleView(phone(1), circleId, false);
    assertEquals(2, list(view, "draws").size());
    assertEquals("lottery", list(view, "draws").get(1).get("kind"));
    assertEquals(1L, circleOf(view).get("seenMonth"));
    service.markSeen(phone(1), circleId, 2);
    service.markSeen(phone(1), circleId, 9); // can't mark a draw that hasn't happened
    view = service.circleView(phone(1), circleId, false);
    assertEquals(2L, circleOf(view).get("seenMonth"));

    // If the server was down for months, the missed draws all run, in order.
    clock.set(Schedule.drawAt((Long) circleOf(view).get("startedAt"), 5));
    assertEquals(
        List.of(List.of(6L, 5L)), // five pots paid out: Digipay's month 1 and four draws
        service.myCircles(phone(1)).stream().map(c -> List.of(c.get("currentMonth"), c.get("received"))).toList());
  }

  @Test
  void opsCanFillAFormingCircleWithSimulatedMembersToStartIt() {
    CircleService service = setup().service();
    String circleId = (String) join(service, phone(1), "p6-10").get("circleId");
    service.fillWithBots(circleId);
    Map<String, Object> view = service.circleView(phone(1), circleId, false);
    assertEquals("active", circleOf(view).get("status"));
    assertEquals(6, list(view, "members").size());
    service.closeMonth(circleId);
    service.closeMonth(circleId);
    service.closeMonth(circleId);
    // Failing bots' month-2 shares were covered by the guarantee.
    assertEquals(60_000_000L, list(service.circleView(phone(1), circleId, false), "draws").get(2).get("pot"));
  }

  @Test
  void withWalletDebitsOffNoMandateIsTakenAndAnUnpaidShareGoesStraightToTheGuarantee() {
    Setup s = setup(null, false, true);
    CircleService service = s.service();
    String circleId = fillPlan(service, "p12-5", 11);
    Row me = s.db().get("SELECT * FROM circle_members WHERE phone = ? AND circle_id = ?", phone(3), circleId);
    assertNull(me.str("mandate_id"));
    // Everyone but phone(3) pays month 2 through the gateway.
    for (int i = 1; i <= 11; i++) {
      if (i == 3) continue;
      String due = (String) service.startCheckout(phone(i), circleId).get("checkoutId");
      service.completeCheckout(phone(i), due, "pay");
    }
    service.closeMonth(circleId);
    List<Row> rows = s.db().all("SELECT member_id, status, method FROM contributions WHERE circle_id = ? AND month = 2", circleId);
    assertFalse(rows.stream().anyMatch(r -> "wallet".equals(r.str("method"))));
    assertEquals("covered", rows.stream().filter(r -> r.str("member_id").equals(me.str("id"))).findFirst().orElseThrow().str("status"));
    assertEquals(5_000_000L, service.myCircles(phone(3)).get(0).get("owed"));
  }

  @Test
  void aRetiredPlanCantBeJoinedAndThePlanListOffersOnlyCurrentPlans() {
    CircleService service = setup().service();
    rejects(410, () -> service.join(phone(1), "p24-10", null));
    assertEquals(List.of("p6-5", "p6-10", "p12-5", "p12-10"), service.planSummaries().stream().map(p -> p.get("id")).toList());
  }

  @Test
  void aSixMonthCircleRunsItsSixMonthsAndPaysOutEverySeat() {
    Setup s = setup();
    CircleService service = s.service();
    String circleId = (String) join(service, phone(1), "p6-5").get("circleId");
    service.fillWithBots(circleId);
    // Everyone pays, so every month has a winner (some bots otherwise "forget").
    s.db().run(
        "UPDATE circle_members SET mandate_id = 'sim-mandate-' || id WHERE circle_id = ? AND mandate_id LIKE 'sim-mandate-failing-%'",
        circleId);
    Map<String, Object> view = service.circleView(phone(1), circleId, false);
    while ("active".equals(circleOf(view).get("status"))) {
      try {
        String due = (String) service.startCheckout(phone(1), circleId).get("checkoutId");
        service.completeCheckout(phone(1), due, "pay");
      } catch (HttpError nothingDue) {
        // Already paid.
      }
      service.closeMonth(circleId);
      view = service.circleView(phone(1), circleId, false);
    }
    assertEquals("completed", circleOf(view).get("status"));
    assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), list(view, "draws").stream().map(d -> d.get("month")).toList());
    assertTrue(list(view, "draws").stream().allMatch(d -> d.get("pot").equals(30_000_000L)));
    assertEquals(6, list(view, "draws").stream().map(d -> d.get("winner")).distinct().count());
  }

  @Test
  void aCircleThatDoesntFillBeforeItsDeadlineExpiresReleasingAndRefundingItsMembers() {
    AtomicLong clock = new AtomicLong(System.currentTimeMillis());
    Setup s = setup(clock, true, true);
    CircleService service = s.service();
    String circleId = (String) join(service, phone(1), "p12-5").get("circleId");
    join(service, phone(2), "p12-5");
    assertEquals(1, service.myCircles(phone(1)).size());

    clock.addAndGet(61 * 60 * 1000);
    assertEquals("expired", circleOf(service.circleView(phone(1), circleId, false)).get("status"));
    assertEquals(0, service.myCircles(phone(1)).size());
    assertEquals(List.of(5_000_000L, 5_000_000L), s.digipay().refunds.stream().map(TestSupport.Refund::amount).toList());
    // Released members can queue again, into a fresh circle.
    assertNotEquals(circleId, join(service, phone(1), "p12-5").get("circleId"));
  }

  @Test
  void membersCanLeaveWhileWaitingGetTheirFirstShareBackAndSeatsBehindThemMoveUp() {
    Setup s = setup();
    CircleService service = s.service();
    String circleId = fillPlan(service, "p12-5", 4); // phone(i) sits in seat i + 1
    service.leave(phone(2), circleId);
    assertEquals(1, s.digipay().refunds.size());
    Map<String, Object> view = service.circleView(phone(4), circleId, false);
    assertEquals(List.of(1L, 2L, 3L, 4L), list(view, "members").stream().map(m -> m.get("position")).toList());
    assertEquals(4L, circleOf(view).get("position")); // was seat 5
    join(service, phone(9), "p12-5"); // takes seat 5 without clashing

    String full = fillPlan(service, "p12-10", 11);
    rejects(400, () -> service.leave(phone(1), full));
  }

  @Test
  void whenOnlyDebtorsAreLeftPotsAreHeldSettlingClaimsOneTheRestGoToDigipayAfterTheGracePeriod() {
    AtomicLong clock = new AtomicLong(TestSupport.tehran("2026-10-07T14:00:00"));
    Setup s = setup(clock, true, true);
    CircleService service = s.service();
    long day = 24L * 60 * 60 * 1000;
    // Second-to-last digit 5: an empty wallet, so these three never pay after joining.
    List<String> debtors = List.of(phone(15), phone(25), phone(35));
    List<String> everyone = new ArrayList<>();
    for (int i : new int[] {1, 2, 3, 4, 6, 7, 8, 9}) everyone.add(phone(i));
    everyone.addAll(debtors);
    String circleId = null;
    for (String p : everyone) circleId = (String) join(service, p, "p12-5").get("circleId");
    for (int m = 2; m <= 12; m++) service.closeMonth(circleId);

    // Months 2–9 went to the eight who paid; 10–12 had nobody clear to draw.
    Map<String, Object> view = service.circleView(debtors.get(0), circleId, false);
    assertEquals("completed", circleOf(view).get("status"));
    assertEquals(3L, circleOf(view).get("held"));
    assertEquals(9, s.digipay().payouts.size());
    assertTrue(list(view, "draws").stream().allMatch(d -> (Long) d.get("month") <= 9));
    assertEquals(55_000_000L, circleOf(view).get("owed")); // months 2–12, all paid by the guarantee

    // A month later: 5% late fee on the 55M, then settling claims the first held pot.
    clock.addAndGet(30 * day);
    String checkoutId = (String) service.startCheckout(debtors.get(0), circleId).get("checkoutId");
    Map<String, Object> checkout = service.getCheckout(debtors.get(0), checkoutId);
    assertEquals(2_750_000L, checkout.get("lateFee"));
    assertEquals(57_750_000L, checkout.get("amount"));
    Map<String, Object> claimed = (Map<String, Object>) service.completeCheckout(debtors.get(0), checkoutId, "pay").get("claimed");
    assertEquals(Map.of("month", 10L, "pot", 60_000_000L), claimed);
    assertEquals(10, s.digipay().payouts.size());
    assertFalse(s.digipay().payouts.get(9).operator());

    // Past the grace period the two unclaimed pots are Digipay's, and they pay
    // off the other two's debts; those two forfeit what they paid to join.
    view = service.circleView(debtors.get(1), circleId, false);
    clock.set((Long) circleOf(view).get("claimBy") + day);
    service.runDueDraws();
    assertEquals(12, s.digipay().payouts.size());
    assertTrue(s.digipay().payouts.subList(10, 12).stream().allMatch(p -> p.operator() && p.amount() == 60_000_000));
    for (String p : debtors.subList(1, 3)) {
      view = service.circleView(p, circleId, false);
      assertEquals(0L, circleOf(view).get("held"));
      assertEquals(0L, circleOf(view).get("owed"));
      assertNull(me(view).get("wonMonth"));
    }
    List<Object> kinds = new ArrayList<>(List.of("operator"));
    kinds.addAll(Collections.nCopies(8, "lottery"));
    kinds.addAll(List.of("last", "operator", "operator"));
    assertEquals(kinds, list(view, "draws").stream().map(d -> d.get("kind")).toList());
  }
}
