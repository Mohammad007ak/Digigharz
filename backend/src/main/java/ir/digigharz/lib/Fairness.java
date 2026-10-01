package ir.digigharz.lib;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedList;
import java.util.List;

/**
 * Provably fair draws for guaranteed circles; must give exactly the results of
 * src/lib/fairness.js, which members' browsers use to verify every draw.
 *
 * <p>When a circle is created the server picks a random secret and hashes it
 * into a chain: anchor = H(H(...H(secret))). Only the anchor is published.
 * Each lottery reveals the next link back towards the secret, so
 * sha256(reveal_k) == reveal_{k-1} (reveal_0 is the anchor). Members' own
 * nonces, fixed when the circle fills, are mixed into every seed.
 */
public final class Fairness {
  private Fairness() {}

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final HexFormat HEX = HexFormat.of();

  public static String sha256Hex(String text) {
    try {
      return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String randomHex(int bytes) {
    byte[] b = new byte[bytes];
    RANDOM.nextBytes(b);
    return HEX.formatHex(b);
  }

  public static String randomHex() {
    return randomHex(32);
  }

  /** Returns [anchor, reveal_1, …, reveal_length]; reveal_length is the secret. */
  public static List<String> buildChain(String secret, long length) {
    LinkedList<String> chain = new LinkedList<>();
    chain.add(secret);
    for (long i = 0; i < length; i++) chain.addFirst(sha256Hex(chain.getFirst()));
    return new ArrayList<>(chain);
  }

  public static String nonceDigest(List<String> noncesInJoinOrder) {
    return sha256Hex(String.join("|", noncesInJoinOrder));
  }

  public record Pick(String seed, String winner) {}

  /** Entrants are sorted so the order they're listed in can't affect the result. */
  public static Pick pickWinner(String reveal, String digest, long month, List<String> eligible) {
    List<String> entrants = new ArrayList<>(eligible);
    entrants.sort(null);
    String seed = sha256Hex(reveal + "|" + digest + "|" + month);
    int index = new BigInteger(seed.substring(0, 16), 16).mod(BigInteger.valueOf(entrants.size())).intValue();
    return new Pick(seed, entrants.get(index));
  }
}
