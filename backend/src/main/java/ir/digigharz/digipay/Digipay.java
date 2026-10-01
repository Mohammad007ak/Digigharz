package ir.digigharz.digipay;

import java.util.Map;

/**
 * Everything the app needs from Digipay, behind one interface. The live
 * adapter is written once the mini-app SDK docs arrive; until then
 * {@link Simulator} stands in.
 */
public interface Digipay {
  String name();

  /** The phone the Digipay host vouches for, or null. */
  String verifyLaunchToken(String token);

  record Score(boolean approved, long monthlyLimit) {}

  Score scoringCheck(String phone);

  String createMandate(String phone, long monthlyAmount, long months);

  void revokeMandate(String mandateId);

  record Charge(boolean ok, String ref, String reason) {}

  Charge charge(String mandateId, long amount, String ref);

  record Checkout(String checkoutRef, String url) {}

  Checkout createCheckout(String phone, long amount, String ref);

  record Verified(boolean ok, String ref) {}

  /** action is "pay" or "cancel" (the simulator's mock payment page). */
  Verified verifyCheckout(String checkoutRef, String action);

  String refund(String paymentRef, long amount);

  /** Sends a pot to its recipient; returns the payout reference. */
  String sendPayout(String phone, boolean operator, long amount, String ref);

  static Digipay fromEnv(Map<String, String> env) {
    if ("live".equals(env.get("DIGIPAY_MODE"))) {
      throw new IllegalStateException(
          "DIGIPAY_MODE=live is not implemented yet: waiting for the Digipay mini-app SDK.");
    }
    return new Simulator();
  }
}
