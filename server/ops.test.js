import { test } from "node:test";
import assert from "node:assert/strict";
import { testDatabase } from "./test-db.js";
import { createCircleService } from "./circles.js";
import { createOpsReports } from "./ops.js";
import { createDigipaySimulator } from "./digipay/simulator.js";

const phone = (i) => `0912000${String(i).padStart(3, "0")}9`;

async function join(service, p, planId = "p12-5") {
  const { checkoutId } = await service.join({ phone: p, planId });
  return service.completeCheckout({ phone: p, id: checkoutId, action: "pay" });
}

test("the admin overview adds up money in, money out, debt and refunds", async () => {
  const db = await testDatabase();
  const service = createCircleService({ db, digipay: createDigipaySimulator(), walletDebit: true, scoringCheck: true });
  const reports = createOpsReports({ db });

  // Someone joins and leaves (refund), then 11 members fill a 5M circle.
  await join(service, phone(20));
  const { circleId: first } = await join(service, phone(21));
  await service.leave({ phone: phone(21), circleId: first });
  let circleId;
  for (let i = 1; i <= 10; i++) ({ circleId } = await join(service, phone(i)));
  assert.equal(circleId, first);

  // Month 2: nobody pays through the gateway; phone(5) has an empty wallet.
  await service.closeMonth(circleId);
  const o = await reports.overview();

  assert.equal(o.circles.active, 1);
  assert.equal(o.people.users, 11);
  assert.equal(o.money.refunds, 5_000_000);
  // Month 1: 11 entry payments. Month 2: 10 from wallets, 1 guaranteed.
  assert.equal(o.collection.byMethod.entry.amount, 55_000_000);
  assert.equal(o.collection.byMethod.wallet.amount, 50_000_000);
  assert.equal(o.money.guaranteed, 5_000_000);
  assert.equal(o.money.openDebt, 5_000_000);
  assert.equal(o.money.debtors, 1);
  assert.equal(o.money.paidOutToOperator, 60_000_000);
  assert.equal(o.money.paidOutToMembers, 60_000_000);
  assert.equal(o.upcoming.length, 1);
  assert.equal(o.upcoming[0].month, 3);

  const [row] = await reports.circles();
  assert.equal(row.taken, 12);
  assert.equal(row.debt, 5_000_000);
  assert.deepEqual(row.thisMonth, { paid: 0, of: 11 });

  const detail = await reports.circle(circleId);
  const debtor = detail.members.find((m) => m.debt > 0);
  assert.equal(debtor.phone, "0912***0059");
  assert.equal(debtor.payments[2].method, "guarantee");
  assert.ok(!JSON.stringify(detail).includes(phone(5)), "phones are masked");

  const [d] = await reports.debtors();
  assert.equal(d.amount, 5_000_000);

  const kinds = (await reports.events({ limit: 500 })).map((e) => e.kind);
  for (const k of [
    "circle_created",
    "joined",
    "left",
    "refund",
    "circle_started",
    "wallet_debit",
    "wallet_failed",
    "guarantee",
    "draw",
    "payout",
  ]) {
    assert.ok(kinds.includes(k), `event ${k} is logged`);
  }
});
