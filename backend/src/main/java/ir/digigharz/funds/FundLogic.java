package ir.digigharz.funds;

import ir.digigharz.Json;
import ir.digigharz.lib.Ids;
import ir.digigharz.lib.Jalali;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Bookkeeping for a family loan fund (صندوق قرض‌الحسنه), the twin of
 * src/lib/fund.js. The app never holds money: it records what members paid to
 * the manager's own bank account, and who won each monthly draw. A fund's
 * whole ledger is one JSON document, kept as-is.
 */
public final class FundLogic {
  private FundLogic() {}

  private static final Pattern MONTH = Pattern.compile("\\d{4}-\\d{2}");

  public record Due(String memberId, String type, String loanId, String month, double amount, boolean paid) {}

  public record Entry(JsonNode member, double tickets) {}

  private static boolean isPositive(JsonNode n) {
    return n != null && n.isNumber() && Double.isFinite(n.asDouble()) && n.asDouble() > 0;
  }

  public static double num(JsonNode n) {
    return n != null && n.isNumber() ? n.asDouble() : 0;
  }

  public static String text(JsonNode n) {
    return n == null || n.isNull() || n.isMissingNode() ? null : n.isString() ? n.asString() : n.toString();
  }

  /** A whole number as a long (for JSON output like the JS version's), otherwise the double. */
  public static Number out(double v) {
    return v == Math.rint(v) && Math.abs(v) < 9.007199254740992E15 ? (Number) (long) v : (Number) v;
  }

  /** Shape check for data coming from a backup file or over the network. */
  public static boolean isValidState(JsonNode value) {
    if (value == null || !value.isObject()) return false;
    JsonNode fund = value.get("fund");
    if (!isArray(value, "members") || !isArray(value, "payments") || !isArray(value, "loans")) return false;
    if (fund == null || !fund.isObject()) return false;
    if (!fund.path("name").isString()) return false;
    if (!isPositive(fund.get("contribution")) || !isPositive(fund.get("loanAmount")) || !isPositive(fund.get("installments"))) return false;
    if (!fund.path("startMonth").isString() || !MONTH.matcher(fund.get("startMonth").asString()).matches()) return false;
    for (JsonNode m : value.get("members")) {
      if (m == null || !m.isObject() || !m.path("id").isString() || !isPositive(m.get("shares"))) return false;
    }
    return true;
  }

  private static boolean isArray(JsonNode v, String key) {
    return v.get(key) != null && v.get(key).isArray();
  }

  /** Splits a loan into equal installments; the last one absorbs the remainder. */
  public static double installmentAmount(double amount, double installments, int index) {
    double base = Math.floor(amount / installments);
    return index == installments - 1 ? amount - base * (installments - 1) : base;
  }

  private static boolean matchesDue(JsonNode payment, Due due) {
    return Objects.equals(text(payment.get("memberId")), due.memberId())
        && Objects.equals(text(payment.get("type")), due.type())
        && Objects.equals(text(payment.get("month")), due.month())
        && (!"installment".equals(due.type()) || Objects.equals(text(payment.get("loanId")), due.loanId()));
  }

  /** Every amount a member owed up to (and including) the given month. */
  public static List<Due> listDues(JsonNode state, String uptoMonth) {
    JsonNode fund = state.get("fund");
    if (fund == null || fund.isNull()) return List.of();
    String startMonth = text(fund.get("startMonth"));
    List<Due> dues = new ArrayList<>();
    for (JsonNode member : state.get("members")) {
      String join = text(member.get("joinMonth"));
      String from = join != null && join.compareTo(startMonth) > 0 ? join : startMonth;
      for (String month : Jalali.monthsBetween(from, uptoMonth)) {
        dues.add(new Due(text(member.get("id")), "contribution", null, month,
            num(member.get("shares")) * num(fund.get("contribution")), false));
      }
    }
    for (JsonNode loan : state.get("loans")) {
      double installments = num(loan.get("installments"));
      for (int i = 0; i < installments; i++) {
        String month = Jalali.addMonths(text(loan.get("firstInstallmentMonth")), i);
        if (month.compareTo(uptoMonth) > 0) break;
        dues.add(new Due(text(loan.get("memberId")), "installment", text(loan.get("id")), month,
            installmentAmount(num(loan.get("amount")), installments, i), false));
      }
    }
    List<Due> result = new ArrayList<>();
    for (Due due : dues) {
      boolean paid = false;
      for (JsonNode p : state.get("payments")) if (matchesDue(p, due)) paid = true;
      result.add(new Due(due.memberId(), due.type(), due.loanId(), due.month(), due.amount(), paid));
    }
    return result;
  }

  public static List<Due> overdueDues(JsonNode state, String currentMonth) {
    return listDues(state, currentMonth).stream().filter(d -> !d.paid() && d.month().compareTo(currentMonth) < 0).toList();
  }

  public static double sumAmounts(JsonNode items) {
    double total = 0;
    for (JsonNode i : items) total += num(i.get("amount"));
    return total;
  }

  public static double fundBalance(JsonNode state) {
    return sumAmounts(state.get("payments")) - sumAmounts(state.get("loans"));
  }

  public record Progress(int paidCount, double paidAmount, double remaining, boolean done) {}

  public static Progress loanProgress(JsonNode state, JsonNode loan) {
    int count = 0;
    double paid = 0;
    String id = text(loan.get("id"));
    for (JsonNode p : state.get("payments")) {
      if ("installment".equals(text(p.get("type"))) && Objects.equals(text(p.get("loanId")), id)) {
        count++;
        paid += num(p.get("amount"));
      }
    }
    return new Progress(count, paid, num(loan.get("amount")) - paid, count >= num(loan.get("installments")));
  }

  /**
   * Each share is one ticket in the current cycle; a ticket is used up once it
   * wins a loan. When no tickets remain, the fund can start a new cycle.
   */
  public static List<Entry> lotteryEntries(JsonNode state) {
    JsonNode fund = state.get("fund");
    if (fund == null || fund.isNull()) return List.of();
    List<Entry> entries = new ArrayList<>();
    for (JsonNode member : state.get("members")) {
      long won = 0;
      for (JsonNode l : state.get("loans")) {
        if (Objects.equals(text(l.get("memberId")), text(member.get("id")))
            && Objects.equals(l.get("cycle"), fund.get("cycle"))) won++;
      }
      double tickets = num(member.get("shares")) - won;
      if (tickets > 0) entries.add(new Entry(member, tickets));
    }
    return entries;
  }

  public static JsonNode pickWinner(List<Entry> entries, DoubleSupplier random) {
    double total = entries.stream().mapToDouble(Entry::tickets).sum();
    if (total == 0) return null;
    double ticket = Math.floor(random.getAsDouble() * total);
    for (Entry entry : entries) {
      if (ticket < entry.tickets()) return entry.member();
      ticket -= entry.tickets();
    }
    return entries.get(entries.size() - 1).member();
  }

  public static ObjectNode createLoan(JsonNode fund, String memberId, String drawMonth) {
    ObjectNode loan = Json.object();
    loan.put("id", Ids.newId());
    loan.put("memberId", memberId);
    loan.set("amount", fund.get("loanAmount"));
    loan.set("installments", fund.get("installments"));
    if (fund.get("cycle") != null) loan.set("cycle", fund.get("cycle"));
    loan.put("drawMonth", drawMonth);
    loan.put("firstInstallmentMonth", Jalali.addMonths(drawMonth, 1));
    loan.put("createdAt", Ids.isoString(System.currentTimeMillis()));
    return loan;
  }
}
