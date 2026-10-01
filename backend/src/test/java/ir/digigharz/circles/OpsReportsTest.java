package ir.digigharz.circles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ir.digigharz.Json;
import ir.digigharz.TestSupport;
import ir.digigharz.db.Db;
import ir.digigharz.digipay.Simulator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class OpsReportsTest {
  static String phone(int i) {
    return CircleServiceTest.phone(i);
  }

  static Map<String, Object> m(Object o, String key) {
    return (Map<String, Object>) ((Map<String, Object>) o).get(key);
  }

  @Test
  void theAdminOverviewAddsUpMoneyInMoneyOutDebtAndRefunds() {
    Db db = TestSupport.testDatabase();
    CircleService service = new CircleService(
        CircleService.Options.of(db, new Simulator()).withWalletDebit(true).withScoringCheck(true));
    OpsReports reports = new OpsReports(db, System::currentTimeMillis);

    // Someone joins and leaves (refund), then 11 members fill a 5M circle.
    CircleServiceTest.join(service, phone(20), "p12-5");
    String first = (String) CircleServiceTest.join(service, phone(21), "p12-5").get("circleId");
    service.leave(phone(21), first);
    String circleId = null;
    for (int i = 1; i <= 10; i++) circleId = (String) CircleServiceTest.join(service, phone(i), "p12-5").get("circleId");
    assertEquals(first, circleId);

    // Month 2: nobody pays through the gateway; phone(5) has an empty wallet.
    service.closeMonth(circleId);
    Map<String, Object> o = reports.overview();

    assertEquals(1L, m(o, "circles").get("active"));
    assertEquals(11L, m(o, "people").get("users"));
    Map<String, Object> money = m(o, "money");
    assertEquals(5_000_000L, money.get("refunds"));
    // Month 1: 11 entry payments. Month 2: 10 from wallets, 1 guaranteed.
    Map<String, Object> byMethod = m(m(o, "collection"), "byMethod");
    assertEquals(55_000_000L, m(byMethod, "entry").get("amount"));
    assertEquals(50_000_000L, m(byMethod, "wallet").get("amount"));
    assertEquals(5_000_000L, money.get("guaranteed"));
    assertEquals(5_000_000L, money.get("openDebt"));
    assertEquals(1L, money.get("debtors"));
    assertEquals(60_000_000L, money.get("paidOutToOperator"));
    assertEquals(60_000_000L, money.get("paidOutToMembers"));
    List<Map<String, Object>> upcoming = (List<Map<String, Object>>) o.get("upcoming");
    assertEquals(1, upcoming.size());
    assertEquals(3L, upcoming.get(0).get("month"));

    Map<String, Object> row = reports.circles().get(0);
    assertEquals(12L, row.get("taken"));
    assertEquals(5_000_000L, row.get("debt"));
    assertEquals(Json.obj("paid", 0L, "of", 11L), row.get("thisMonth"));

    Map<String, Object> detail = reports.circle(circleId);
    Map<String, Object> debtor = ((List<Map<String, Object>>) detail.get("members")).stream()
        .filter(x -> (Long) x.get("debt") > 0).findFirst().orElseThrow();
    assertEquals("0912***0059", debtor.get("phone"));
    assertEquals("guarantee", m(m(debtor, "payments"), "2").get("method"));
    assertFalse(Json.stringify(detail).contains(phone(5)), "phones are masked");

    assertEquals(5_000_000L, reports.debtors().get(0).get("amount"));

    List<Object> kinds = reports.events(500, null).stream().map(e -> e.get("kind")).toList();
    for (String k : List.of("circle_created", "joined", "left", "refund", "circle_started", "wallet_debit",
        "wallet_failed", "guarantee", "draw", "payout")) {
      assertTrue(kinds.contains(k), "event " + k + " is logged");
    }
  }
}
