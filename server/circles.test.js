import { test } from "node:test";
import assert from "node:assert/strict";
import { testDatabase } from "./test-db.js";
import { createCircleService } from "./circles.js";
import { createDigipaySimulator } from "./digipay/simulator.js";
import { verifyDraw } from "../src/lib/fairness.js";
import { drawAt } from "../src/lib/schedule.js";
import { toJalali } from "../src/lib/jalali.js";

// Phones ending 4–9 get a 25M monthly limit from the simulator's scoring.
const phone = (i) => `0912000${String(i).padStart(3, "0")}9`;

async function setup({ clock, walletDebit = true, scoringCheck = true } = {}) {
  const db = await testDatabase();
  const digipay = createDigipaySimulator();
  const payouts = [];
  const send = digipay.payouts.send;
  digipay.payouts.send = async (p) => {
    payouts.push(p);
    return send(p);
  };
  const refunds = [];
  const refund = digipay.payments.refund;
  digipay.payments.refund = async (r) => {
    refunds.push(r);
    return refund(r);
  };
  const now = clock ? () => clock.t : Date.now;
  return {
    db,
    service: createCircleService({
      db,
      digipay,
      now,
      formTimeoutMs: 60 * 60 * 1000,
      walletDebit,
      scoringCheck,
    }),
    payouts,
    refunds,
  };
}

// Joining = starting the entry checkout and paying the first share.
async function join(service, { phone, planId }) {
  const { checkoutId } = await service.join({ phone, planId });
  return service.completeCheckout({ phone, id: checkoutId, action: "pay" });
}

async function fillPlan(service, planId, members) {
  let circleId;
  for (let i = 1; i <= members; i++) ({ circleId } = await join(service, { phone: phone(i), planId }));
  return circleId;
}

test("with the scoring check off, anyone can join any plan", async () => {
  const { service } = await setup({ scoringCheck: false });
  // Phones ending 0 are rejected and 1–3 limited to 5M when the check is on.
  await join(service, { phone: "09120000000", planId: "p12-10" });
  await join(service, { phone: "09120000001", planId: "p12-10" });
  await join(service, { phone: "09120000001", planId: "p6-10" });
  assert.equal((await service.eligibility("09120000000")).approved, true);
});

test("scoring gates who can join and how much they can commit", async () => {
  const { service } = await setup();
  await assert.rejects(service.join({ phone: "09120000000", planId: "p12-5" }), { status: 403 });
  // Limit 5M: the 5M plan fits, the 10M plan doesn't, and a second 5M plan doesn't either.
  await join(service, { phone: "09120000001", planId: "p12-5" });
  await assert.rejects(service.join({ phone: "09120000001", planId: "p12-10" }), { status: 403 });
  await assert.rejects(service.join({ phone: "09120000001", planId: "p12-5" }), { status: 409 });
});

test("a circle starts when full, the operator takes month 1, and everyone receives exactly once", async () => {
  const { service, payouts } = await setup();
  const circleId = await fillPlan(service, "p12-5", 11);

  let view = await service.circleView(phone(1), circleId);
  assert.equal(view.circle.status, "active");
  assert.equal(view.members.length, 12);
  assert.ok(view.members[0].isOperator);
  assert.match(view.nonceDigest, /^[0-9a-f]{64}$/);
  // Month 1's pot went to the operator the moment the circle started.
  assert.equal(view.draws[0].kind, "operator");
  assert.equal(view.circle.currentMonth, 2);
  assert.equal(payouts.length, 1);

  for (let m = 2; m <= 12; m++) await service.closeMonth(circleId);

  // Seat 5's wallet is empty in the simulator: it never paid, so it was left
  // out of every draw and month 12's pot is held for it, not handed over.
  const debtor = phone(5);
  view = await service.circleView(debtor, circleId);
  assert.equal(view.circle.status, "completed");
  assert.equal(view.circle.held, 1);
  assert.equal(view.members.find((m) => m.isMe).wonMonth, null);
  assert.equal(payouts.length, 11);
  // Once it settles everything it owes, the held pot is paid to it.
  const { checkoutId } = await service.startCheckout({ phone: debtor, circleId });
  const { claimed } = await service.completeCheckout({ phone: debtor, id: checkoutId, action: "pay" });
  assert.deepEqual(claimed, { month: 12, pot: 60_000_000 });

  view = await service.circleView(phone(1), circleId);
  assert.deepEqual(
    view.members.map((m) => m.wonMonth).sort((a, b) => a - b),
    Array.from({ length: 12 }, (_, i) => i + 1),
  );
  assert.equal(view.draws[0].kind, "operator");
  assert.equal(view.draws[11].kind, "last");
  assert.equal(view.draws.filter((d) => d.kind === "lottery").length, 10);
  assert.equal(payouts.length, 12);
  assert.ok(payouts.every((p) => p.amount === 60_000_000));
  assert.ok(payouts[0].operator);
});

test("every lottery can be verified by a member, and tampering is detected", async () => {
  const { service } = await setup();
  const circleId = await fillPlan(service, "p12-5", 11);
  for (let m = 2; m <= 5; m++) await service.closeMonth(circleId);

  const view = await service.circleView(phone(3), circleId);
  const lotteries = view.draws.filter((d) => d.kind === "lottery");
  assert.equal(lotteries.length, 4);
  for (const d of lotteries) {
    const result = await verifyDraw({ ...d, digest: view.nonceDigest });
    assert.ok(result.ok, `draw for month ${d.month} should verify`);
  }

  const [first] = lotteries;
  const forgedWinner = first.eligible.find((id) => id !== first.winner);
  assert.equal(
    (
      await verifyDraw({
        ...first,
        digest: view.nonceDigest,
        winner: forgedWinner,
      })
    ).ok,
    false,
  );
  const forgedReveal = await verifyDraw({
    ...first,
    digest: view.nonceDigest,
    reveal: "0".repeat(64),
  });
  assert.equal(forgedReveal.linkOk, false);
});

test("the first share is paid on joining and nothing is seated until it is", async () => {
  const { service } = await setup();
  const { checkoutId } = await service.join({
    phone: phone(1),
    planId: "p12-5",
  });
  assert.equal((await service.getCheckout(phone(1), checkoutId)).amount, 5_000_000);
  assert.equal((await service.myCircles(phone(1))).length, 0);

  const cancelled = await service.completeCheckout({
    phone: phone(1),
    id: checkoutId,
    action: "cancel",
  });
  assert.deepEqual(cancelled, { ok: false, circleId: null });
  assert.equal((await service.myCircles(phone(1))).length, 0);

  // Two entry payments for the same plan: one seat, the second is refunded.
  const a = await service.join({ phone: phone(1), planId: "p12-5" });
  const b = await service.join({ phone: phone(1), planId: "p12-5" });
  const first = await service.completeCheckout({
    phone: phone(1),
    id: a.checkoutId,
    action: "pay",
  });
  const second = await service.completeCheckout({
    phone: phone(1),
    id: b.checkoutId,
    action: "pay",
  });
  assert.equal(second.refunded, true);
  assert.equal(second.circleId, first.circleId);
  assert.equal((await service.circleView(phone(1), first.circleId)).members.length, 2);

  for (let i = 2; i <= 11; i++) await join(service, { phone: phone(i), planId: "p12-5" });
  const view = await service.circleView(phone(3), first.circleId);
  assert.equal(view.circle.status, "active");
  assert.deepEqual(view.contributions[0], {
    month: 1,
    amount: 5_000_000,
    status: "paid",
    method: "entry",
  });
  assert.equal(view.circle.dueNow, 5_000_000); // month 2 is open, due a month from the start
});

test("unpaid shares come from the wallet; if that fails, the guarantee covers them and debtors sit out draws", async () => {
  const { service } = await setup();
  const debtor = phone(5); // second-to-last digit 5: empty wallet in the simulator
  const circleId = await fillPlan(service, "p12-5", 11);

  // Month 1 (everyone's first share) was paid out when the circle started.
  await service.closeMonth(circleId); // month 2: nobody used the gateway
  const payer = await service.circleView(phone(2), circleId);
  assert.equal(payer.contributions[1].method, "wallet");
  assert.equal(payer.circle.owed, 0);

  let view = await service.circleView(debtor, circleId);
  assert.equal(view.contributions[1].status, "covered");
  assert.equal(view.circle.owed, 5_000_000); // month 2, paid by the guarantee
  assert.equal(view.circle.dueNow, 5_000_000); // month 3, not late yet

  await service.closeMonth(circleId); // month 3: still unpaid
  view = await service.circleView(debtor, circleId);
  const me = view.members.find((m) => m.isMe);
  assert.ok(!view.draws[2].eligible.includes(me.id), "a member who owes is left out of the draw");

  const { checkoutId } = await service.startCheckout({
    phone: debtor,
    circleId,
  });
  assert.equal((await service.getCheckout(debtor, checkoutId)).amount, 15_000_000);
  await service.completeCheckout({
    phone: debtor,
    id: checkoutId,
    action: "pay",
  });
  view = await service.circleView(debtor, circleId);
  assert.equal(view.circle.owed, 0);
  assert.deepEqual(
    view.contributions.map((c) => c.status),
    ["paid", "settled", "settled", "paid"],
  );
});

test("members only see seats, never each other's phones, and strangers see nothing", async () => {
  const { service } = await setup();
  const circleId = await fillPlan(service, "p12-5", 11);
  const view = await service.circleView(phone(2), circleId);
  assert.ok(!JSON.stringify(view).includes("09120"));
  await assert.rejects(service.circleView("09129999999", circleId), {
    status: 404,
  });
});

test("a month can't be closed twice at once", async () => {
  const { service } = await setup();
  const circleId = await fillPlan(service, "p12-5", 11);
  const results = await Promise.allSettled([service.closeMonth(circleId), service.closeMonth(circleId)]);
  assert.equal(results.filter((r) => r.status === "fulfilled").length, 1);
  assert.equal((await service.circleView(phone(1), circleId)).circle.currentMonth, 3);
});

test("each draw runs by itself on the sixth day after its due date, and its reveal plays once", async () => {
  const clock = { t: new Date("2026-10-07T14:00:00+03:30").getTime() }; // 15 Mehr 1405
  const { service, payouts } = await setup({ clock });
  const circleId = await fillPlan(service, "p12-5", 11);
  assert.equal(payouts.length, 1); // month 1, to Digipay, on the start day

  const drawDay = drawAt(clock.t, 2); // 20 Aban: due 15 Aban, five days to pay
  assert.deepEqual(toJalali(new Date(drawDay)), { year: 1405, month: 8, day: 20 });
  clock.t = drawDay - 60 * 1000;
  assert.equal((await service.circleView(phone(1), circleId)).draws.length, 1);

  clock.t = drawDay;
  let view = await service.circleView(phone(1), circleId);
  assert.equal(view.draws.length, 2);
  assert.equal(view.draws[1].kind, "lottery");
  assert.equal(view.circle.seenMonth, 1);
  await service.markSeen({ phone: phone(1), circleId, month: 2 });
  await service.markSeen({ phone: phone(1), circleId, month: 9 }); // can't mark a draw that hasn't happened
  view = await service.circleView(phone(1), circleId);
  assert.equal(view.circle.seenMonth, 2);

  // If the server was down for months, the missed draws all run, in order.
  clock.t = drawAt(view.circle.startedAt, 5);
  assert.deepEqual(
    (await service.myCircles(phone(1))).map((c) => [c.currentMonth, c.received]),
    [[6, 5]], // five pots paid out: Digipay's month 1 and four draws
  );
});

test("ops can fill a forming circle with simulated members to start it", async () => {
  const { service } = await setup();
  const { circleId } = await join(service, {
    phone: phone(1),
    planId: "p6-10",
  });
  await service.fillWithBots(circleId);
  const view = await service.circleView(phone(1), circleId);
  assert.equal(view.circle.status, "active");
  assert.equal(view.members.length, 6);
  await service.closeMonth(circleId);
  await service.closeMonth(circleId);
  await service.closeMonth(circleId);
  // Failing bots' month-2 shares were covered by the guarantee.
  assert.equal((await service.circleView(phone(1), circleId)).draws[2].pot, 60_000_000);
});

test("with wallet debits off, no mandate is taken and an unpaid share goes straight to the guarantee", async () => {
  const { service, db } = await setup({ walletDebit: false });
  const circleId = await fillPlan(service, "p12-5", 11);
  const me = await db.get("SELECT * FROM circle_members WHERE phone = ? AND circle_id = ?", phone(3), circleId);
  assert.equal(me.mandate_id, null);
  // Everyone but phone(3) pays month 2 through the gateway.
  for (let i = 1; i <= 11; i++) {
    if (i === 3) continue;
    const due = await service.startCheckout({ phone: phone(i), circleId });
    await service.completeCheckout({ phone: phone(i), id: due.checkoutId, action: "pay" });
  }
  await service.closeMonth(circleId);
  const rows = await db.all(
    "SELECT member_id, status, method FROM contributions WHERE circle_id = ? AND month = 2",
    circleId,
  );
  assert.ok(!rows.some((r) => r.method === "wallet"));
  assert.equal(rows.find((r) => r.member_id === me.id).status, "covered");
  assert.equal((await service.myCircles(phone(3)))[0].owed, 5_000_000);
});

test("a retired plan can't be joined, and the plan list offers only current plans", async () => {
  const { service } = await setup();
  await assert.rejects(service.join({ phone: phone(1), planId: "p24-10" }), { status: 410 });
  const ids = (await service.planSummaries()).map((p) => p.id);
  assert.deepEqual(ids, ["p6-5", "p6-10", "p12-5", "p12-10"]);
});

test("a six-month circle runs its six months and pays out every seat", async () => {
  const { service, db } = await setup();
  const { circleId } = await join(service, { phone: phone(1), planId: "p6-5" });
  await service.fillWithBots(circleId);
  // Everyone pays, so every month has a winner (some bots otherwise "forget").
  await db.run(
    "UPDATE circle_members SET mandate_id = 'sim-mandate-' || id WHERE circle_id = ? AND mandate_id LIKE 'sim-mandate-failing-%'",
    circleId,
  );
  let view = await service.circleView(phone(1), circleId);
  while (view.circle.status === "active") {
    const due = await service.startCheckout({ phone: phone(1), circleId }).catch(() => null);
    if (due) await service.completeCheckout({ phone: phone(1), id: due.checkoutId, action: "pay" });
    await service.closeMonth(circleId);
    view = await service.circleView(phone(1), circleId);
  }
  assert.equal(view.circle.status, "completed");
  assert.deepEqual(
    view.draws.map((d) => d.month),
    [1, 2, 3, 4, 5, 6],
  );
  assert.ok(view.draws.every((d) => d.pot === 30_000_000));
  assert.equal(new Set(view.draws.map((d) => d.winner)).size, 6);
});

test("a circle that doesn't fill before its deadline expires, releasing and refunding its members", async () => {
  const clock = { t: Date.now() };
  const { service, refunds } = await setup({ clock });
  const { circleId } = await join(service, {
    phone: phone(1),
    planId: "p12-5",
  });
  await join(service, { phone: phone(2), planId: "p12-5" });
  assert.equal((await service.myCircles(phone(1))).length, 1);

  clock.t += 61 * 60 * 1000;
  assert.equal((await service.circleView(phone(1), circleId)).circle.status, "expired");
  assert.equal((await service.myCircles(phone(1))).length, 0);
  assert.deepEqual(
    refunds.map((r) => r.amount),
    [5_000_000, 5_000_000],
  );
  // Released members can queue again, into a fresh circle.
  const again = await join(service, { phone: phone(1), planId: "p12-5" });
  assert.notEqual(again.circleId, circleId);
});

test("members can leave while waiting, get their first share back, and seats behind them move up", async () => {
  const { service, refunds } = await setup();
  const circleId = await fillPlan(service, "p12-5", 4); // phone(i) sits in seat i + 1
  await service.leave({ phone: phone(2), circleId });
  assert.equal(refunds.length, 1);
  const view = await service.circleView(phone(4), circleId);
  assert.deepEqual(
    view.members.map((m) => m.position),
    [1, 2, 3, 4],
  );
  assert.equal(view.circle.position, 4); // was seat 5
  await join(service, { phone: phone(9), planId: "p12-5" }); // takes seat 5 without clashing

  const full = await fillPlan(service, "p12-10", 11);
  await assert.rejects(service.leave({ phone: phone(1), circleId: full }), {
    status: 400,
  });
});

test("when only debtors are left, pots are held; settling with the late fee claims one, the rest go to Digipay after the grace period", async () => {
  const clock = { t: new Date("2026-10-07T14:00:00+03:30").getTime() };
  const { service, payouts } = await setup({ clock });
  const DAY = 24 * 60 * 60 * 1000;
  // Second-to-last digit 5: an empty wallet, so these three never pay after joining.
  const debtors = [phone(15), phone(25), phone(35)];
  let circleId;
  for (const p of [1, 2, 3, 4, 6, 7, 8, 9].map(phone).concat(debtors))
    ({ circleId } = await join(service, { phone: p, planId: "p12-5" }));
  for (let m = 2; m <= 12; m++) await service.closeMonth(circleId);

  // Months 2–9 went to the eight who paid; 10–12 had nobody clear to draw.
  let view = await service.circleView(debtors[0], circleId);
  assert.equal(view.circle.status, "completed");
  assert.equal(view.circle.held, 3);
  assert.equal(payouts.length, 9);
  assert.ok(view.draws.every((d) => d.month <= 9));
  assert.equal(view.circle.owed, 55_000_000); // months 2–12, all paid by the guarantee

  // A month later: 5% late fee on the 55M, then settling claims the first held pot.
  clock.t += 30 * DAY;
  const { checkoutId } = await service.startCheckout({ phone: debtors[0], circleId });
  const checkout = await service.getCheckout(debtors[0], checkoutId);
  assert.equal(checkout.lateFee, 2_750_000);
  assert.equal(checkout.amount, 57_750_000);
  const { claimed } = await service.completeCheckout({ phone: debtors[0], id: checkoutId, action: "pay" });
  assert.deepEqual(claimed, { month: 10, pot: 60_000_000 });
  assert.equal(payouts.length, 10);
  assert.equal(payouts.at(-1).operator, false);

  // Past the grace period the two unclaimed pots are Digipay's, and they pay
  // off the other two's debts; those two forfeit what they paid to join.
  view = await service.circleView(debtors[1], circleId);
  clock.t = view.circle.claimBy + DAY;
  await service.runDueDraws();
  assert.equal(payouts.length, 12);
  assert.ok(payouts.slice(-2).every((p) => p.operator && p.amount === 60_000_000));
  for (const p of debtors.slice(1)) {
    view = await service.circleView(p, circleId);
    assert.equal(view.circle.held, 0);
    assert.equal(view.circle.owed, 0);
    assert.equal(view.members.find((m) => m.isMe).wonMonth, null);
  }
  assert.deepEqual(
    view.draws.map((d) => d.kind),
    ["operator", ...Array(8).fill("lottery"), "last", "operator", "operator"],
  );
});
