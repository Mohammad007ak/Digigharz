package ir.digigharz.lib;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The calendar of a guaranteed-plan circle (see src/lib/schedule.js).
 *
 * <p>The circle starts the moment its last seat is paid for; month 1's pot goes
 * to Digipay that day. After that each installment falls due on the same day
 * of the month, the member has five days to pay, and the draw is on the sixth.
 */
public final class Schedule {
  private Schedule() {}

  public static final int PAY_DAYS = 5;
  private static final long DAY = 24L * 60 * 60 * 1000;

  public static long dueAt(long startedAt, long month) {
    return Jalali.addJalaliMonths(startedAt, (int) month - 1);
  }

  /** Month 1 is paid out on the start day itself. */
  public static long drawAt(long startedAt, long month) {
    return month == 1 ? startedAt : dueAt(startedAt, month) + PAY_DAYS * DAY;
  }

  /** The last moment to pay through the gateway: the end of the fifth day. */
  public static long lastPayDay(long startedAt, long month) {
    return drawAt(startedAt, month) - DAY;
  }

  public static List<Map<String, Object>> scheduleOf(long startedAt, long months) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (int i = 0; i < months; i++) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("month", i + 1);
      m.put("dueAt", dueAt(startedAt, i + 1));
      m.put("lastPayDay", i == 0 ? startedAt : lastPayDay(startedAt, i + 1));
      m.put("drawAt", drawAt(startedAt, i + 1));
      result.add(m);
    }
    return result;
  }
}
