package ir.digigharz.circles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ir.digigharz.TestSupport;
import ir.digigharz.db.Db;
import ir.digigharz.digipay.Simulator;
import ir.digigharz.lib.Fairness;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Runs the first lottery (month 2; month 1 always goes to Digipay) of many
 * separate 12-seat groups through the real service, tallies which seat won
 * each time, and checks the tally against a fair draw with a chi-square test.
 * Every draw is also re-verified the way a member's phone does it.
 *
 * <p>Statistical, so it only runs when asked: mvn test -Dtest=DrawFairnessTest -Dfairness=500
 */
@SuppressWarnings("unchecked")
class DrawFairnessTest {
  // Critical value of chi-square at p = 0.05 for 10 degrees of freedom.
  private static final double CRITICAL = 18.307;

  @Test
  void seatsWinEquallyOften() {
    String setting = System.getProperty("fairness");
    assumeTrue(setting != null, "statistical: run with -Dfairness=<rounds>");
    int rounds = setting.isEmpty() ? 500 : Integer.parseInt(setting);

    Db db = TestSupport.testDatabase();
    CircleService service = new CircleService(CircleService.Options.of(db, new Simulator()));
    Map<Long, Integer> wins = new TreeMap<>();
    for (long seat = 2; seat <= 12; seat++) wins.put(seat, 0);
    int verified = 0;
    for (int i = 0; i < rounds; i++) {
      // A fresh member each round.
      String phone = "0913" + String.format("%06d", i) + "9";
      String circleId = (String) CircleServiceTest.join(service, phone, "p12-5").get("circleId");
      service.fillWithBots(circleId);
      // The simulator makes every fourth bot "forget" to pay, which rightly keeps
      // it out of the draw. Here everyone pays, so any bias would show.
      db.run("UPDATE circle_members SET mandate_id = 'sim-mandate-' || id WHERE circle_id = ? AND mandate_id LIKE 'sim-mandate-failing-%'",
          circleId);
      String due = (String) service.startCheckout(phone, circleId).get("checkoutId");
      service.completeCheckout(phone, due, "pay");
      service.closeMonth(circleId);

      Map<String, Object> view = service.circleView(phone, circleId, false);
      Map<String, Object> draw = CircleServiceTest.list(view, "draws").stream()
          .filter(d -> d.get("month").equals(2L)).findFirst().orElseThrow();
      Map<String, Object> winner = CircleServiceTest.list(view, "members").stream()
          .filter(m -> m.get("id").equals(draw.get("winner"))).findFirst().orElseThrow();
      wins.merge((Long) winner.get("position"), 1, Integer::sum);
      if (CircleServiceTest.verifyDraw(draw, (String) view.get("nonceDigest"), (String) draw.get("winner"), (String) draw.get("reveal"))[1]) {
        verified++;
      }
    }
    double expected = rounds / 11.0;
    double chi2 = wins.values().stream().mapToDouble(n -> (n - expected) * (n - expected) / expected).sum();
    System.out.printf("draws: %d, verified: %d%n", rounds, verified);
    int most = wins.values().stream().max(Integer::compare).orElse(1);
    wins.forEach((seat, n) -> System.out.printf("seat %2d: %4d  %s%n", seat, n, "█".repeat((int) Math.round(40.0 * n / most))));
    System.out.printf("chi-square: %.2f (df 10; bias would show above %.3f at p=0.05)%n", chi2, CRITICAL);
    assertEquals(rounds, verified);
    assertTrue(chi2 < CRITICAL * 1.6, "seat wins look biased"); // p ≈ 0.001

    // The pick itself, on its own and many more times.
    int picks = 100_000;
    int[] byIndex = new int[11];
    List<String> ids = IntStream.range(0, 11).mapToObj(k -> String.format("m%02d", k)).toList();
    for (int i = 0; i < picks; i++) {
      String w = Fairness.pickWinner(Fairness.randomHex(), Fairness.sha256Hex(Fairness.randomHex()), 2, ids).winner();
      byIndex[ids.indexOf(w)]++;
    }
    double e2 = picks / 11.0;
    double chi2b = IntStream.of(byIndex).mapToDouble(n -> (n - e2) * (n - e2) / e2).sum();
    System.out.printf("pick function alone, %d times: chi-square %.2f (df 10)%n", picks, chi2b);
    assertTrue(chi2b < CRITICAL * 1.6, "the pick looks biased");
  }
}
