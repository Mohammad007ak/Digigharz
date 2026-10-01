package ir.digigharz.lib;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The guaranteed plans (mirrors src/lib/plans.js, which the app shows). Position
 * 1 in every circle is the operator (Digipay): it pays its share like everyone
 * else and always takes month 1. A retired plan is no longer offered, but
 * circles already running on it keep working until they finish.
 */
public final class Plans {
  private Plans() {}

  public record Plan(String id, String title, int size, int months, long share, String flag, boolean retired) {
    public long pot() {
      return size * share;
    }

    public Map<String, Object> toMap() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", id);
      m.put("title", title);
      m.put("size", size);
      m.put("months", months);
      m.put("share", share);
      if (flag != null) m.put("flag", flag);
      if (retired) m.put("retired", true);
      return m;
    }
  }

  public static final List<Plan> PLANS = List.of(
      new Plan("p6-5", "طرح شش‌ماهه", 6, 6, 5_000_000, "تازه", false),
      new Plan("p6-10", "طرح شش‌ماهه‌ی ویژه", 6, 6, 10_000_000, null, false),
      new Plan("p12-5", "طرح یک‌ساله", 12, 12, 5_000_000, "پرطرفدار", false),
      new Plan("p12-10", "طرح یک‌ساله‌ی ویژه", 12, 12, 10_000_000, null, false),
      new Plan("p24-10", "طرح دوساله", 24, 24, 10_000_000, null, true));

  public static final List<Plan> OFFERED = PLANS.stream().filter(p -> !p.retired()).toList();

  public static Plan byId(String id) {
    return PLANS.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
  }
}
