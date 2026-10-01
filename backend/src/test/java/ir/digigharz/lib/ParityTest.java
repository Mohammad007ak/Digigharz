package ir.digigharz.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The browser verifies draws and shows dates with the JS code in src/lib, so
 * the server must give exactly the same answers. The vectors come from that
 * code: node scripts/java-port-vectors.mjs
 */
class ParityTest {
  private static String resource(String name) throws IOException {
    try (InputStream in = ParityTest.class.getResourceAsStream("/vectors/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void jalaliDatesMatchTheBrowser() throws IOException {
    String[] lines = resource("jalali.txt").split("\n");
    String[] header = lines[0].split(" ");
    long start = Long.parseLong(header[0]);
    long step = Long.parseLong(header[1]);
    for (int i = 1; i < lines.length; i++) {
      long t = start + (i - 1) * step;
      Jalali.Date d = Jalali.toJalali(t);
      assertEquals(lines[i], d.year() + "-" + d.month() + "-" + d.day(), "at " + t);
    }
  }

  @Test
  void addingJalaliMonthsMatchesTheBrowser() throws IOException {
    for (JsonNode v : JSON.readTree(resource("add-months.json"))) {
      long t = v.get(0).asLong();
      int n = v.get(1).asInt();
      assertEquals(v.get(2).asLong(), Jalali.addJalaliMonths(t, n), "from " + t + " plus " + n);
    }
  }

  @Test
  void drawsMatchTheBrowsersVerifier() throws IOException {
    for (JsonNode v : JSON.readTree(resource("fairness.json"))) {
      List<String> chain = Fairness.buildChain(v.get("secret").asString(), v.get("length").asLong());
      assertEquals(v.get("anchor").asString(), chain.get(0));
      assertEquals(v.get("anchor").asString(), Fairness.buildChain(chain.get(1), 1).get(0));
      List<String> nonces = new ArrayList<>();
      v.get("nonces").forEach(n -> nonces.add(n.asString()));
      assertEquals(v.get("digest").asString(), Fairness.nonceDigest(nonces));
      List<String> eligible = new ArrayList<>();
      v.get("eligible").forEach(n -> eligible.add(n.asString()));
      Fairness.Pick pick =
          Fairness.pickWinner(v.get("reveal").asString(), v.get("digest").asString(), v.get("month").asLong(), eligible);
      assertEquals(v.get("seed").asString(), pick.seed());
      assertEquals(v.get("winner").asString(), pick.winner());
    }
  }
}
