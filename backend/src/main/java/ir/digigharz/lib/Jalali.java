package ir.digigharz.lib;

import com.ibm.icu.util.Calendar;
import com.ibm.icu.util.PersianCalendar;
import com.ibm.icu.util.TimeZone;

/**
 * Jalali (Persian calendar) helpers, the server-side twin of src/lib/jalali.js.
 * Both use ICU's Persian calendar in Iran time, so the server and every
 * browser agree on dates. Months are keys like "1405-07" that sort as strings.
 */
public final class Jalali {
  private Jalali() {}

  public record Date(int year, int month, int day) {}

  private static final TimeZone TEHRAN = TimeZone.getTimeZone("Asia/Tehran");
  private static final long DAY = 24L * 60 * 60 * 1000;

  public static Date toJalali(long timestamp) {
    PersianCalendar calendar = new PersianCalendar(TEHRAN);
    calendar.setTimeInMillis(timestamp);
    return new Date(
        calendar.get(Calendar.EXTENDED_YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH));
  }

  public static String makeMonthKey(int year, int month) {
    return year + "-" + (month < 10 ? "0" : "") + month;
  }

  public static int[] parseMonthKey(String key) {
    String[] parts = key.split("-");
    return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
  }

  public static String currentMonthKey(long timestamp) {
    Date d = toJalali(timestamp);
    return makeMonthKey(d.year(), d.month());
  }

  public static String addMonths(String key, int count) {
    int[] ym = parseMonthKey(key);
    int index = ym[0] * 12 + (ym[1] - 1) + count;
    return makeMonthKey(Math.floorDiv(index, 12), Math.floorMod(index, 12) + 1);
  }

  public static java.util.List<String> monthsBetween(String from, String to) {
    var months = new java.util.ArrayList<String>();
    for (String key = from; key.compareTo(to) <= 0; key = addMonths(key, 1)) months.add(key);
    return months;
  }

  private static int monthIndex(Date d) {
    return d.year() * 12 + d.month() - 1;
  }

  /**
   * The same moment {@code count} Jalali months later, on the same day of the
   * month (or the month's last day, when it's shorter). Iran has no daylight
   * saving time, so stepping whole days keeps the time of day.
   */
  public static long addJalaliMonths(long timestamp, int count) {
    Date start = toJalali(timestamp);
    int target = monthIndex(start) + count;
    long t = timestamp + Math.round(count * 30.44) * DAY;
    for (int i = 0; i < 40; i++) {
      int current = monthIndex(toJalali(t));
      if (current == target) break;
      t += (current < target ? 1 : -1) * DAY;
    }
    for (int i = 0; i < 40; i++) {
      int day = toJalali(t).day();
      if (day == start.day()) break;
      if (day > start.day()) t -= DAY;
      else if (monthIndex(toJalali(t + DAY)) != target) break; // month ended first
      else t += DAY;
    }
    return t;
  }
}
