package ir.digigharz.lib;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

public final class Ids {
  private Ids() {}

  /** Same shape as fund.js newId(): time in base 36, then six random base-36 characters. */
  public static String newId() {
    StringBuilder id = new StringBuilder(Long.toString(System.currentTimeMillis(), 36));
    ThreadLocalRandom random = ThreadLocalRandom.current();
    for (int i = 0; i < 6; i++) id.append(Character.forDigit(random.nextInt(36), 36));
    return id.toString();
  }

  private static final DateTimeFormatter ISO =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  /** Like JavaScript's Date.prototype.toISOString(). */
  public static String isoString(long timestamp) {
    return ISO.format(Instant.ofEpochMilli(timestamp));
  }
}
