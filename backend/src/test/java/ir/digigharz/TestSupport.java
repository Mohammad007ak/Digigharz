package ir.digigharz;

import ir.digigharz.db.Db;
import ir.digigharz.digipay.Simulator;
import ir.digigharz.lib.Fairness;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TestSupport {
  private TestSupport() {}

  /**
   * A fresh, empty database for one test. SQLite in memory, or PostgreSQL (each
   * test in its own schema) when TEST_DATABASE_URL is set.
   */
  public static Db testDatabase() {
    String url = System.getenv("TEST_DATABASE_URL");
    return url != null && !url.isEmpty()
        ? Db.open(url, "test_" + Fairness.randomHex(6))
        : Db.open(":memory:");
  }

  public record Payout(String phone, boolean operator, long amount, String ref) {}

  public record Refund(String paymentRef, long amount) {}

  /** The simulator, keeping a record of every payout and refund. */
  public static class RecordingDigipay extends Simulator {
    public final List<Payout> payouts = Collections.synchronizedList(new ArrayList<>());
    public final List<Refund> refunds = Collections.synchronizedList(new ArrayList<>());

    @Override
    public String sendPayout(String phone, boolean operator, long amount, String ref) {
      payouts.add(new Payout(phone, operator, amount, ref));
      return super.sendPayout(phone, operator, amount, ref);
    }

    @Override
    public String refund(String paymentRef, long amount) {
      refunds.add(new Refund(paymentRef, amount));
      return super.refund(paymentRef, amount);
    }
  }

  /** Tehran local time (+03:30), as in the JS tests. */
  public static long tehran(String isoLocal) {
    return java.time.OffsetDateTime.parse(isoLocal + "+03:30").toInstant().toEpochMilli();
  }
}
