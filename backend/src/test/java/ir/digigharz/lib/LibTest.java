package ir.digigharz.lib;

import static ir.digigharz.TestSupport.tehran;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ir.digigharz.Json;
import ir.digigharz.funds.FundLogic;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** src/lib's tests: dates, the circle calendar, phone numbers and the family fund's books. */
class LibTest {
  @Test
  void addsJalaliMonthsOnTheSameDayClampingToShorterMonths() {
    long start = tehran("2026-10-07T14:00:00"); // 15 Mehr 1405
    assertEquals(new Jalali.Date(1405, 7, 15), Jalali.toJalali(start));
    assertEquals(new Jalali.Date(1405, 8, 15), Jalali.toJalali(Jalali.addJalaliMonths(start, 1)));
    assertEquals(new Jalali.Date(1406, 1, 15), Jalali.toJalali(Jalali.addJalaliMonths(start, 6)));
    assertEquals(Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).getHour(),
        Instant.ofEpochMilli(Jalali.addJalaliMonths(start, 1)).atZone(ZoneOffset.UTC).getHour());

    long endOfShahrivar = tehran("2026-09-22T10:00:00"); // 31 Shahrivar 1405
    assertEquals(new Jalali.Date(1405, 6, 31), Jalali.toJalali(endOfShahrivar));
    assertEquals(new Jalali.Date(1405, 7, 30), Jalali.toJalali(Jalali.addJalaliMonths(endOfShahrivar, 1)));
  }

  @Test
  void installmentsFallDueMonthlyAndTheDrawComesOnTheSixthDay() {
    long start = tehran("2026-10-07T14:00:00");
    assertEquals(start, Schedule.drawAt(start, 1));
    assertEquals(new Jalali.Date(1405, 8, 15), Jalali.toJalali(Schedule.dueAt(start, 2)));
    assertEquals(new Jalali.Date(1405, 8, 20), Jalali.toJalali(Schedule.drawAt(start, 2)));
    List<Map<String, Object>> plan = Schedule.scheduleOf(start, 12);
    assertEquals(12, plan.size());
    for (int i = 1; i < plan.size(); i++) assertTrue((Long) plan.get(i).get("drawAt") > (Long) plan.get(i - 1).get("drawAt"));
  }

  @Test
  void jalaliMonthArithmeticWrapsAcrossYears() {
    assertEquals("1405-02", Jalali.addMonths("1404-11", 3));
    assertEquals("1404-12", Jalali.addMonths("1405-01", -1));
    assertEquals(List.of("1404-12", "1405-01", "1405-02"), Jalali.monthsBetween("1404-12", "1405-02"));
    assertEquals(new Jalali.Date(1405, 1, 1), Jalali.toJalali(Instant.parse("2026-03-21T12:00:00Z").toEpochMilli()));
  }

  @Test
  void normalizesTheWaysPeopleTypeIranianMobileNumbers() {
    for (String input : List.of("09121234567", "+989121234567", "00989121234567", "9121234567", "۰۹۱۲ ۱۲۳ ۴۵۶۷", "0912-123-4567")) {
      assertEquals("09121234567", Phone.normalize(input), input);
    }
    for (String input : Arrays.asList("", null, "02112345678", "0912123456", "091212345678", "abc")) {
      assertNull(Phone.normalize(input), String.valueOf(input));
    }
  }

  static ObjectNode state() {
    return (ObjectNode) Json.parse("""
        {"version":1,
         "fund":{"name":"صندوق تست","contribution":1000000,"loanAmount":10000000,"installments":3,"startMonth":"1405-01","cycle":1},
         "members":[{"id":"a","name":"علی","phone":"","shares":1,"joinMonth":"1405-01"},
                    {"id":"b","name":"سارا","phone":"","shares":2,"joinMonth":"1405-02"}],
         "payments":[],"loans":[]}""");
  }

  @Test
  void contributionDuesStartAtEachMembersJoinMonthAndScaleByShares() {
    List<FundLogic.Due> dues = FundLogic.listDues(state(), "1405-03");
    assertEquals(3, dues.stream().filter(d -> d.memberId().equals("a")).count());
    List<FundLogic.Due> sara = dues.stream().filter(d -> d.memberId().equals("b")).toList();
    assertEquals(2, sara.size());
    assertEquals(2_000_000, sara.get(0).amount());
  }

  @Test
  void installmentsSplitEvenlyWithTheRemainderOnTheLastOne() {
    assertEquals(List.of(3_333_333.0, 3_333_333.0, 3_333_334.0),
        List.of(0, 1, 2).stream().map(i -> FundLogic.installmentAmount(10_000_000, 3, i)).toList());
  }

  @Test
  void paymentsMarkDuesAsPaidAndLoansCreateInstallmentsFromTheMonthAfterTheDraw() {
    ObjectNode s = state();
    ((ArrayNode) s.get("payments")).add(Json.parse(
        "{\"id\":\"p1\",\"memberId\":\"a\",\"type\":\"contribution\",\"month\":\"1405-01\",\"amount\":1000000}"));
    assertEquals(1_000_000, FundLogic.fundBalance(s));
    assertEquals(2, FundLogic.overdueDues(s, "1405-03").size()); // a's 1405-02 and b's 1405-02

    ObjectNode loan = FundLogic.createLoan(s.get("fund"), "a", "1405-01");
    assertEquals("1405-02", loan.get("firstInstallmentMonth").asString());
    ((ArrayNode) s.get("loans")).add(loan);
    List<FundLogic.Due> installments = FundLogic.listDues(s, "1405-12").stream().filter(d -> d.type().equals("installment")).toList();
    assertEquals(List.of("1405-02", "1405-03", "1405-04"), installments.stream().map(FundLogic.Due::month).toList());
    assertEquals(-9_000_000, FundLogic.fundBalance(s));

    ((ArrayNode) s.get("payments")).add(Json.parse("{\"id\":\"p\",\"memberId\":\"a\",\"type\":\"installment\",\"loanId\":\""
        + loan.get("id").asString() + "\",\"month\":\"1405-02\",\"amount\":3333333}"));
    assertEquals(new FundLogic.Progress(1, 3_333_333, 6_666_667, false), FundLogic.loanProgress(s, loan));
    assertNull(FundLogic.pickWinner(List.of(), () -> 0.0));
  }

  @Test
  void lotteryTicketsFollowSharesAndAreUsedUpByWins() {
    ObjectNode s = state();
    assertEquals(List.of(1.0, 2.0), FundLogic.lotteryEntries(s).stream().map(FundLogic.Entry::tickets).toList());
    ((ArrayNode) s.get("loans")).add(FundLogic.createLoan(s.get("fund"), "b", "1405-01"));
    assertEquals(List.of(1.0, 1.0), FundLogic.lotteryEntries(s).stream().map(FundLogic.Entry::tickets).toList());
    // pickWinner is weighted by tickets.
    List<FundLogic.Entry> entries = FundLogic.lotteryEntries(state());
    JsonNode first = FundLogic.pickWinner(entries, () -> 0.0);
    JsonNode last = FundLogic.pickWinner(entries, () -> 0.99);
    assertEquals("a", first.get("id").asString());
    assertEquals("b", last.get("id").asString());
    assertEquals("b", FundLogic.pickWinner(entries, () -> 0.34).get("id").asString());
  }
}
