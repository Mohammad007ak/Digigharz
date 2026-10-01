package ir.digigharz.digipay;

import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stand-in for Digipay's services until the mini-app SDK is available. */
public class Simulator implements Digipay {
  private static final AtomicLong COUNTER = new AtomicLong();
  private static final Pattern LAUNCH_TOKEN = Pattern.compile("^sim-(09\\d{9})$");

  static String ref(String kind) {
    return "sim-" + kind + "-" + Long.toString(System.currentTimeMillis(), 36) + "-"
        + Long.toString(COUNTER.incrementAndGet(), 36);
  }

  public String name() {
    return "simulator";
  }

  /** Inside the Digipay app the host passes a launch token; here it is simply "sim-<phone>". */
  public String verifyLaunchToken(String token) {
    Matcher m = LAUNCH_TOKEN.matcher(token == null ? "" : token);
    return m.matches() ? m.group(1) : null;
  }

  /** For demos the last digit decides: 0 → rejected, 1–3 → up to 5M a month, 4–9 → up to 25M. */
  public Score scoringCheck(String phone) {
    int last = phone.charAt(phone.length() - 1) - '0';
    long limit = last == 0 ? 0 : last <= 3 ? 5_000_000 : 25_000_000;
    return new Score(limit > 0, limit);
  }

  /** A phone whose second-to-last digit is 5 has an empty wallet, so debits on it fail. */
  public String createMandate(String phone, long monthlyAmount, long months) {
    boolean emptyWallet = phone.length() >= 2 && phone.charAt(phone.length() - 2) == '5';
    return ref(emptyWallet ? "mandate-failing" : "mandate");
  }

  public void revokeMandate(String mandateId) {}

  /** Mandates with "-failing-" in their id always decline. */
  public Charge charge(String mandateId, long amount, String ref) {
    if (String.valueOf(mandateId).contains("-failing-")) return new Charge(false, null, "insufficient_funds");
    return new Charge(true, ref("charge"), null);
  }

  /** The real gateway returns a URL to redirect to; the app shows its own mock page instead. */
  public Checkout createCheckout(String phone, long amount, String ref) {
    return new Checkout(ref("checkout"), null);
  }

  public Verified verifyCheckout(String checkoutRef, String action) {
    boolean pay = "pay".equals(action);
    return new Verified(pay, pay ? ref("paid") : null);
  }

  public String refund(String paymentRef, long amount) {
    return ref("refund");
  }

  public String sendPayout(String phone, boolean operator, long amount, String ref) {
    return ref("payout");
  }
}
