package ir.digigharz.analytics;

import static ir.digigharz.Json.obj;

import ir.digigharz.HttpError;
import ir.digigharz.Json;
import ir.digigharz.db.Db;
import ir.digigharz.db.Row;
import ir.digigharz.lib.Fairness;
import ir.digigharz.lib.Plans;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Product analytics for the admin panel: installs, users, active users,
 * feature funnels, cohorts, subscription purchases, platforms, acquisition
 * channels, and the error log. A port of the Node server's analytics.js.
 *
 * <p>Most events are recorded on the server, from the API requests themselves
 * (a login, a join, a paid checkout), so they can't be blocked or faked by an
 * ad blocker. The app adds the few things only it knows: that it was opened
 * (the first open of a device is its "install"), which screen is showing, and
 * the errors it hit. Every request from the app carries the device's id,
 * platform, version and acquisition channel in headers.
 */
public class Analytics {
  public static final long DAY_MS = 24L * 60 * 60 * 1000;
  // Iran has had no daylight saving since 2022: Tehran is always UTC+3:30.
  public static final long TEHRAN_OFFSET = (long) (3.5 * 60 * 60 * 1000);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final List<String> PLATFORMS = List.of("android", "ios", "web");

  // ---------- day helpers (Tehran calendar days as YYYY-MM-DD) ----------

  public static String tehranDay(long t) {
    return Instant.ofEpochMilli(t + TEHRAN_OFFSET).atZone(ZoneOffset.UTC).toLocalDate().toString();
  }

  public static String addDays(String day, long n) {
    return LocalDate.parse(day).plusDays(n).toString();
  }

  public static long daysBetween(String from, String to) {
    return ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to));
  }

  /** Start of a Tehran day, as a timestamp. */
  public static long dayStart(String day) {
    return LocalDate.parse(day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - TEHRAN_OFFSET;
  }

  static String newId() {
    byte[] raw = new byte[9];
    RANDOM.nextBytes(raw);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  static String clip(Object value, int n) {
    if (value == null) return null;
    String s = value.toString();
    return s.length() > n ? s.substring(0, n) : s;
  }

  static String safeDecode(String v) {
    if (v == null || v.isEmpty()) return null;
    try {
      // decodeURIComponent: a "+" stays a "+".
      return URLDecoder.decode(v.replace("+", "%2B"), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return v;
    }
  }

  /** JavaScript's Number(value): NaN for missing or non-numeric text, 0 for blank. */
  static double num(String v) {
    if (v == null) return Double.NaN;
    String t = v.strip();
    if (t.isEmpty()) return 0;
    try {
      return Double.parseDouble(t);
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }

  /** Number(v) || fallback. */
  static double numOr(String v, double fallback) {
    double d = num(v);
    return Double.isNaN(d) || d == 0 ? fallback : d;
  }

  public static String platformFromAgent(String ua) {
    String s = ua == null ? "" : ua;
    if (Pattern.compile("iPhone|iPad|iPod", Pattern.CASE_INSENSITIVE).matcher(s).find()) return "ios";
    if (Pattern.compile("Android", Pattern.CASE_INSENSITIVE).matcher(s).find()) return "android";
    return "web";
  }

  /** "google", "instagram", "digipay", ... always lowercase; "direct" when nothing says where the user came from. */
  public static String normalizeChannel(String value) {
    String v = (value == null ? "" : value).strip().toLowerCase().replaceAll("[^a-z0-9_.-]", "");
    if (v.length() > 40) v = v.substring(0, 40);
    return v.isEmpty() ? "direct" : v;
  }

  /** The device, platform and channel the app reports with every request. */
  public record Ctx(String deviceId, String platform, String osVersion, String appVersion, String channel, String campaign) {
    public static final Ctx NONE = new Ctx(null, null, null, null, null, null);

    public static Ctx of(HttpServletRequest req) {
      String device = req.getHeader("x-device-id");
      String reported = req.getHeader("x-platform") == null ? "" : req.getHeader("x-platform").toLowerCase();
      return new Ctx(
          device != null && device.matches("[A-Za-z0-9_-]{8,64}") ? device : null,
          PLATFORMS.contains(reported) ? reported : platformFromAgent(req.getHeader("user-agent")),
          clip(req.getHeader("x-os-version"), 24),
          clip(req.getHeader("x-app-version"), 24),
          normalizeChannel(req.getHeader("x-channel")),
          clip(safeDecode(req.getHeader("x-campaign")), 60));
    }
  }

  /** What each API call means as a product event. Only successful calls count. */
  public record RouteEvent(String method, Pattern pattern, String name) {}

  public static final List<RouteEvent> ROUTE_EVENTS = List.of(
      new RouteEvent("POST", Pattern.compile("^/api/auth/request-code$"), "otp_requested"),
      new RouteEvent("POST", Pattern.compile("^/api/auth/verify$"), "login"),
      new RouteEvent("POST", Pattern.compile("^/api/auth/digipay$"), "login"),
      new RouteEvent("POST", Pattern.compile("^/api/me/tour$"), "tour_completed"),
      new RouteEvent("GET", Pattern.compile("^/api/plans$"), "plans_viewed"),
      new RouteEvent("GET", Pattern.compile("^/api/eligibility$"), "eligibility_checked"),
      new RouteEvent("POST", Pattern.compile("^/api/circles/join$"), "join_started"),
      new RouteEvent("GET", Pattern.compile("^/api/circles/[^/]+$"), "circle_viewed"),
      new RouteEvent("POST", Pattern.compile("^/api/circles/[^/]+/pay$"), "dues_checkout_started"),
      new RouteEvent("POST", Pattern.compile("^/api/circles/[^/]+/leave$"), "circle_left"),
      new RouteEvent("POST", Pattern.compile("^/api/checkouts/([^/]+)/complete$"), "checkout_completed"),
      new RouteEvent("POST", Pattern.compile("^/api/funds$"), "fund_created"),
      new RouteEvent("PUT", Pattern.compile("^/api/funds/[^/]+$"), "fund_updated"),
      new RouteEvent("POST", Pattern.compile("^/api/funds/[^/]+/draws$"), "family_draw"),
      new RouteEvent("GET", Pattern.compile("^/api/funds/[^/]+/view$"), "member_view_opened"));

  public static RouteEvent match(String method, String path) {
    for (RouteEvent r : ROUTE_EVENTS) if (r.method.equals(method) && r.pattern.matcher(path).find()) return r;
    return null;
  }

  /** Names and descriptions shown in the panel and given to the AI. */
  public static final Map<String, String> EVENT_LABELS = new LinkedHashMap<>();

  static {
    String[][] labels = {
      {"install", "نصب (اولین باز شدن اپ روی یک دستگاه)"},
      {"app_open", "باز شدن اپ"},
      {"screen_view", "دیدن صفحه"},
      {"otp_requested", "درخواست کد ورود"},
      {"signup", "ثبت‌نام (اولین ورود)"},
      {"login", "ورود"},
      {"tour_completed", "پایان تور معرفی"},
      {"plans_viewed", "دیدن طرح‌ها"},
      {"eligibility_checked", "بررسی اعتبار"},
      {"join_started", "شروع عضویت در طرح"},
      {"subscription_purchased", "خرید اشتراک (پرداخت قسط اول)"},
      {"checkout_cancelled", "انصراف از پرداخت"},
      {"circle_viewed", "دیدن دوره"},
      {"dues_checkout_started", "شروع پرداخت قسط"},
      {"dues_paid", "پرداخت قسط"},
      {"circle_left", "خروج از صف"},
      {"fund_created", "ساخت صندوق خانوادگی"},
      {"fund_updated", "ویرایش صندوق"},
      {"family_draw", "قرعه‌ی صندوق خانوادگی"},
      {"member_view_opened", "دیدن صفحه‌ی عضو صندوق"},
    };
    for (String[] l : labels) EVENT_LABELS.put(l[0], l[1]);
  }

  static String label(String name) {
    return EVENT_LABELS.getOrDefault(name, name);
  }

  public static final List<String> DEFAULT_FUNNEL =
      List.of("install", "otp_requested", "login", "plans_viewed", "join_started", "subscription_purchased");

  private static final Pattern HEX_ID = Pattern.compile("\\b[0-9a-f]{8,}\\b", Pattern.CASE_INSENSITIVE);

  static String fingerprintOf(String source, String message, String stack) {
    // Numbers and ids change between occurrences of the same bug.
    java.util.function.Function<String, String> norm =
        s -> HEX_ID.matcher(s == null ? "" : s).replaceAll("#").replaceAll("\\d+", "#");
    String frame = "";
    for (String line : (stack == null ? "" : stack).split("\n", -1)) {
      if (line.contains("at ") || line.contains("@")) {
        frame = line;
        break;
      }
    }
    String key = source + "|" + norm.apply(message) + "|" + norm.apply(frame.replaceFirst("\\?[^:)]*", ""));
    return Fairness.sha256Hex(key).substring(0, 16);
  }

  // ---------- the service ----------

  private final Db db;
  private final LongSupplier now;
  // Tracking runs after the response, so it never slows or breaks a request.
  private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "analytics");
    t.setDaemon(true);
    return t;
  });

  public Analytics(Db db, LongSupplier now) {
    this.db = db;
    this.now = now;
  }

  // Background work is stamped with the time of the request it came from.
  private final ThreadLocal<Long> at = new ThreadLocal<>();

  private long time() {
    Long t = at.get();
    return t != null ? t : now.getAsLong();
  }

  /** Runs after the response, stamped with the current time. */
  public void later(Runnable task) {
    long t = now.getAsLong();
    background.execute(() -> {
      at.set(t);
      try {
        task.run();
      } catch (RuntimeException e) {
        System.err.println("analytics: " + e.getMessage());
      } finally {
        at.remove();
      }
    });
  }

  /** Waits for the tracking queued so far (tests). */
  public void settle() {
    try {
      background.submit(() -> {}).get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public void shutdown() {
    background.shutdown();
  }

  // ---------- recording ----------

  private boolean touchDevice(Ctx ctx, String phone) {
    if (ctx.deviceId() == null) return false;
    long t = time();
    Row existing = db.get("SELECT id, phone FROM devices WHERE id = ?", ctx.deviceId());
    if (existing != null) {
      db.run(
          "UPDATE devices SET last_at = ?, phone = COALESCE(?, phone), app_version = COALESCE(?, app_version),"
              + " os_version = COALESCE(?, os_version) WHERE id = ?",
          t, phone, ctx.appVersion(), ctx.osVersion(), ctx.deviceId());
      return false;
    }
    int changes = db.run(
        "INSERT INTO devices (id, phone, platform, os_version, app_version, channel, campaign, first_at, first_day, last_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
        ctx.deviceId(), phone, ctx.platform(), ctx.osVersion(), ctx.appVersion(), ctx.channel(), ctx.campaign(),
        t, tehranDay(t), t);
    return changes > 0;
  }

  private static boolean isEmptyProps(Object props) {
    if (props == null) return true;
    if (props instanceof Map<?, ?> m) return m.isEmpty();
    if (props instanceof JsonNode n) return n.isEmpty();
    return false;
  }

  private void insertEvent(String name, Ctx ctx, String phone, Object props) {
    long t = time();
    db.run(
        "INSERT INTO analytics_events (id, at, day, name, device_id, phone, platform, channel, props)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        newId(), t, tehranDay(t), name, ctx.deviceId(), phone, ctx.platform(), ctx.channel(),
        isEmptyProps(props) ? null : clip(Json.stringify(props), 2000));
    if (phone != null) db.run("UPDATE users SET last_at = ?, last_day = ? WHERE phone = ?", t, tehranDay(t), phone);
  }

  /** A device's first ever request is its install, whichever call it is. */
  public void track(String name, Ctx ctx, String phone, Object props) {
    if (touchDevice(ctx, phone)) insertEvent("install", ctx, phone, obj("campaign", ctx.campaign()));
    insertEvent(name, ctx, phone, props);
  }

  public void recordLogin(Ctx ctx, String phone, String method) {
    long t = time();
    // Attribution: where the device that signed up first came from.
    Row device = ctx.deviceId() != null ? db.get("SELECT channel, campaign FROM devices WHERE id = ?", ctx.deviceId()) : null;
    String channel = device != null ? device.str("channel") : ctx.channel();
    String campaign = device != null ? device.str("campaign") : ctx.campaign();
    int changes = db.run(
        "INSERT INTO users (phone, first_at, first_day, last_at, last_day, platform, channel, campaign, app_version, device_id, logins)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1) ON CONFLICT (phone) DO NOTHING",
        phone, t, tehranDay(t), t, tehranDay(t), ctx.platform(), channel, campaign, ctx.appVersion(), ctx.deviceId());
    if (changes > 0) {
      track("signup", ctx, phone, obj("method", method));
    } else {
      // A user's platform is the one they use now; the channel stays first touch.
      db.run(
          "UPDATE users SET logins = logins + 1, platform = ?, app_version = COALESCE(?, app_version),"
              + " device_id = COALESCE(?, device_id) WHERE phone = ?",
          ctx.platform(), ctx.appVersion(), ctx.deviceId(), phone);
      // Accounts from before analytics existed: fill in what we now know.
      db.run("UPDATE users SET channel = ? WHERE phone = ? AND channel = 'unknown'", channel, phone);
    }
    track("login", ctx, phone, obj("method", method));
  }

  /**
   * One successful API call, already matched to its event (see the filter).
   * signedInPhone is set by sign-in routes; phone is the caller's session.
   */
  public void recordRequest(RouteEvent match, String path, Ctx ctx, String signedInPhone, String phone, String planId) {
    String name = match.name();
    if (name.equals("login")) {
      if (signedInPhone != null) recordLogin(ctx, signedInPhone, path.endsWith("digipay") ? "digipay" : "otp");
      return;
    }
    if (name.equals("checkout_completed")) {
      Matcher m = match.pattern().matcher(path);
      if (!m.find()) return;
      Row c = db.get("SELECT kind, plan_id, amount, status FROM checkouts WHERE id = ?", m.group(1));
      if (c == null) return;
      Map<String, Object> props = obj("planId", c.str("plan_id"), "amount", c.lng("amount"));
      if ("paid".equals(c.str("status"))) track("entry".equals(c.str("kind")) ? "subscription_purchased" : "dues_paid", ctx, phone, props);
      else if ("cancelled".equals(c.str("status"))) track("checkout_cancelled", ctx, phone, props);
      return;
    }
    Map<String, Object> props = new LinkedHashMap<>();
    if (planId != null && !planId.isEmpty()) props.put("planId", clip(planId, 40));
    track(name, ctx, phone, props);
  }

  private static final Set<String> SERVER_ONLY = Set.of("install", "signup", "login", "subscription_purchased", "dues_paid");

  /** Events the app reports itself (app_open, screen_view). */
  public Map<String, Object> collect(Ctx ctx, String phone, JsonNode body) {
    if (ctx.deviceId() == null) throw new HttpError(400, "شناسه‌ی دستگاه لازم است.");
    JsonNode events = body != null && body.isObject() ? body.get("events") : null;
    if (events != null && events.isArray()) {
      int i = 0;
      for (JsonNode e : events) {
        if (i++ >= 30) break;
        JsonNode n = e != null && e.isObject() ? e.get("name") : null;
        String name = n == null || n.isNull() ? "" : n.isString() ? n.asString() : n.toString();
        if (!name.matches("[a-z][a-z0-9_]{1,40}")) continue;
        if (ROUTE_EVENTS.stream().anyMatch(r -> r.name().equals(name)) || SERVER_ONLY.contains(name)) continue;
        JsonNode p = e.get("props");
        track(name, ctx, phone, p != null && (p.isObject() || p.isArray()) ? Json.toPlain(p) : null);
      }
    }
    return obj("ok", true);
  }

  /** One error from the app or the server; grouped by fingerprint. */
  public void logError(String source, String level, String message, String stack, String url, Object context, Ctx ctx, String phone) {
    long t = time();
    String msg = clip(message, 500);
    if (msg == null || msg.isEmpty()) msg = "Unknown error";
    String stk = clip(stack, 4000);
    String fp = fingerprintOf(source, msg, stk);
    Ctx c = ctx == null ? Ctx.NONE : ctx;
    db.run(
        "INSERT INTO error_logs (id, fingerprint, at, day, source, level, message, stack, url, device_id, phone, platform, app_version, context)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        newId(), fp, t, tehranDay(t), source,
        List.of("error", "warning", "fatal").contains(level) ? level : "error",
        msg, stk, clip(url, 300), c.deviceId(), phone, c.platform(), c.appVersion(),
        context == null ? null : clip(Json.stringify(context), 2000));
    // A bug marked fixed that happens again is reopened as a regression.
    db.run(
        "INSERT INTO error_groups (fingerprint, source, message, stack, url, status, count, first_at, last_at)"
            + " VALUES (?, ?, ?, ?, ?, 'open', 1, ?, ?)"
            + " ON CONFLICT (fingerprint) DO UPDATE SET count = error_groups.count + 1, last_at = excluded.last_at,"
            + " stack = COALESCE(excluded.stack, error_groups.stack), url = COALESCE(excluded.url, error_groups.url),"
            + " status = CASE WHEN error_groups.status = 'resolved' THEN 'regressed' ELSE error_groups.status END",
        fp, source, msg, stk, clip(url, 300), t, t);
  }

  private static String textOf(JsonNode n) {
    if (n == null || n.isNull()) return null;
    return n.isString() ? n.asString() : n.toString();
  }

  public Map<String, Object> collectErrors(Ctx ctx, String phone, JsonNode body) {
    JsonNode errors = body != null && body.isObject() ? body.get("errors") : null;
    if (errors != null && errors.isArray()) {
      int i = 0;
      for (JsonNode e : errors) {
        if (i++ >= 10) break;
        if (e == null || !e.isObject()) continue;
        String message = textOf(e.get("message"));
        if (message == null || message.isEmpty()) continue;
        JsonNode context = e.get("context");
        logError("app", textOf(e.get("level")), message, textOf(e.get("stack")), textOf(e.get("url")),
            context == null || context.isNull() ? null : Json.toPlain(context), ctx, phone);
      }
    }
    return obj("ok", true);
  }

  /** Accounts that signed in before analytics existed still belong in the user list; platform and channel unknown. */
  public int backfillUsers() {
    List<Row> rows = db.all(
        "SELECT phone, MIN(t) AS t FROM ("
            + " SELECT phone, created_at AS t FROM checkouts"
            + " UNION ALL SELECT phone, joined_at AS t FROM circle_members WHERE is_bot = 0 AND is_operator = 0 AND phone IS NOT NULL"
            + " UNION ALL SELECT owner_phone AS phone, created_at AS t FROM funds"
            + " UNION ALL SELECT phone, seen_at AS t FROM tour_seen"
            + " ) x WHERE phone IS NOT NULL AND phone NOT IN (SELECT phone FROM users) GROUP BY phone");
    for (Row r : rows) {
      long t = r.n("t");
      db.run(
          "INSERT INTO users (phone, first_at, first_day, last_at, last_day, platform, channel, logins)"
              + " VALUES (?, ?, ?, ?, ?, 'unknown', 'unknown', 0) ON CONFLICT (phone) DO NOTHING",
          r.str("phone"), t, tehranDay(t), t, tehranDay(t));
    }
    return rows.size();
  }

  // ---------- reports ----------

  record Range(int n, String from, String to) {}

  Range range(String days) {
    int n = (int) Math.min(Math.max(numOr(days, 30), 1), 365);
    String to = tehranDay(now.getAsLong());
    return new Range(n, addDays(to, -(n - 1)), to);
  }

  static List<String> dayList(String from, String to) {
    List<String> out = new ArrayList<>();
    long n = daysBetween(from, to);
    for (int i = 0; i <= n; i++) out.add(addDays(from, i));
    return out;
  }

  static Map<String, Long> byKey(List<Row> rows, String key) {
    Map<String, Long> m = new HashMap<>();
    for (Row r : rows) m.put(r.str(key), r.n("n"));
    return m;
  }

  record Filter(String sql, List<Object> params) {}

  static Filter filterSql(String alias, String platform, String channel) {
    StringBuilder sql = new StringBuilder();
    List<Object> params = new ArrayList<>();
    if (platform != null && !platform.isEmpty() && !platform.equals("all")) {
      sql.append(" AND ").append(alias).append(".platform = ?");
      params.add(platform);
    }
    if (channel != null && !channel.isEmpty() && !channel.equals("all")) {
      sql.append(" AND ").append(alias).append(".channel = ?");
      params.add(channel);
    }
    return new Filter(sql.toString(), params);
  }

  static Object[] args(Object... parts) {
    List<Object> out = new ArrayList<>();
    for (Object p : parts) {
      if (p instanceof Collection<?> c) out.addAll(c);
      else out.add(p);
    }
    return out.toArray();
  }

  private long count(String sql, Object... params) {
    Row r = db.get(sql, params);
    return r == null ? 0 : r.n("n");
  }

  long activeUsers(String fromDay, String toDay, String platform, String channel) {
    Filter w = filterSql("e", platform, channel);
    return count(
        "SELECT COUNT(DISTINCT COALESCE(e.device_id, e.phone)) AS n FROM analytics_events e WHERE e.day >= ? AND e.day <= ?" + w.sql(),
        args(fromDay, toDay, w.params()));
  }

  public Map<String, Object> growth(Map<String, String> q) {
    Range r = range(q.get("days"));
    String platform = q.get("platform"), channel = q.get("channel");
    Filter dw = filterSql("d", platform, channel), uw = filterSql("u", platform, channel), ew = filterSql("e", platform, channel);
    Map<String, Long> installs = byKey(db.all(
        "SELECT d.first_day AS day, COUNT(*) AS n FROM devices d WHERE d.first_day >= ?" + dw.sql() + " GROUP BY d.first_day",
        args(r.from(), dw.params())), "day");
    Map<String, Long> signups = byKey(db.all(
        "SELECT u.first_day AS day, COUNT(*) AS n FROM users u WHERE u.first_day >= ?" + uw.sql() + " GROUP BY u.first_day",
        args(r.from(), uw.params())), "day");
    Map<String, Long> dau = byKey(db.all(
        "SELECT e.day, COUNT(DISTINCT COALESCE(e.device_id, e.phone)) AS n FROM analytics_events e WHERE e.day >= ?" + ew.sql()
            + " GROUP BY e.day",
        args(r.from(), ew.params())), "day");
    Map<String, Long> purchases = byKey(db.all(
        "SELECT e.day, COUNT(*) AS n FROM analytics_events e WHERE e.name = 'subscription_purchased' AND e.day >= ?" + ew.sql()
            + " GROUP BY e.day",
        args(r.from(), ew.params())), "day");
    List<Map<String, Object>> series = new ArrayList<>();
    long ti = 0, ts = 0, tp = 0;
    for (String day : dayList(r.from(), r.to())) {
      long i = installs.getOrDefault(day, 0L), s = signups.getOrDefault(day, 0L), p = purchases.getOrDefault(day, 0L);
      ti += i;
      ts += s;
      tp += p;
      series.add(obj("day", day, "installs", i, "signups", s, "active", dau.getOrDefault(day, 0L), "purchases", p));
    }
    String today = r.to();
    Map<String, Object> totals = obj(
        "users", count("SELECT COUNT(*) AS n FROM users u WHERE 1 = 1" + uw.sql(), args(uw.params())),
        "devices", count("SELECT COUNT(*) AS n FROM devices d WHERE 1 = 1" + dw.sql(), args(dw.params())),
        "dau", activeUsers(today, today, platform, channel),
        "dauYesterday", activeUsers(addDays(today, -1), addDays(today, -1), platform, channel),
        "wau", activeUsers(addDays(today, -6), today, platform, channel),
        "mau", activeUsers(addDays(today, -29), today, platform, channel),
        "installs", ti,
        "signups", ts,
        "purchases", tp);
    // Same length window just before this one, for the change arrows.
    String prevFrom = addDays(r.from(), -r.n()), prevTo = addDays(r.from(), -1);
    Map<String, Object> previous = obj(
        "installs", count("SELECT COUNT(*) AS n FROM devices d WHERE d.first_day >= ? AND d.first_day <= ?" + dw.sql(),
            args(prevFrom, prevTo, dw.params())),
        "signups", count("SELECT COUNT(*) AS n FROM users u WHERE u.first_day >= ? AND u.first_day <= ?" + uw.sql(),
            args(prevFrom, prevTo, uw.params())),
        "purchases", count(
            "SELECT COUNT(*) AS n FROM analytics_events e WHERE e.name = 'subscription_purchased' AND e.day >= ? AND e.day <= ?" + ew.sql(),
            args(prevFrom, prevTo, ew.params())));
    Map<String, Object> out = obj("from", r.from(), "to", r.to(), "series", series, "totals", totals, "previous", previous);
    out.put("platforms", split("platform", r.from(), r.to(), platform, channel));
    out.put("channels", split("channel", r.from(), r.to(), platform, channel));
    return out;
  }

  private static String keyOf(Row r) {
    String k = r.str("k");
    return k == null ? "unknown" : k;
  }

  private static long field(List<Row> rows, String k, String f) {
    for (Row r : rows) if (keyOf(r).equals(k)) return r.n(f);
    return 0;
  }

  /** Platforms or acquisition channels, side by side. */
  private List<Map<String, Object>> split(String column, String from, String to, String platform, String channel) {
    long fromT = dayStart(from);
    Filter w = filterSql("x", platform, channel);
    List<Row> installs = db.all("SELECT x." + column + " AS k, COUNT(*) AS n FROM devices x WHERE x.first_day >= ? AND x.first_day <= ?"
        + w.sql() + " GROUP BY x." + column, args(from, to, w.params()));
    List<Row> signups = db.all("SELECT x." + column + " AS k, COUNT(*) AS n FROM users x WHERE x.first_day >= ? AND x.first_day <= ?"
        + w.sql() + " GROUP BY x." + column, args(from, to, w.params()));
    List<Row> active = db.all("SELECT x." + column + " AS k, COUNT(DISTINCT COALESCE(x.device_id, x.phone)) AS n FROM analytics_events x"
        + " WHERE x.day >= ? AND x.day <= ?" + w.sql() + " GROUP BY x." + column, args(from, to, w.params()));
    // Paid subscriptions by the buyer's platform/channel (first touch).
    List<Row> buyers = db.all("SELECT x." + column + " AS k, COUNT(*) AS n, COUNT(DISTINCT c.phone) AS buyers,"
        + " COALESCE(SUM(c.amount), 0) AS revenue FROM checkouts c JOIN users x ON x.phone = c.phone"
        + " WHERE c.kind = 'entry' AND c.status = 'paid' AND c.created_at >= ?" + w.sql() + " GROUP BY x." + column,
        args(fromT, w.params()));
    Set<String> keys = new LinkedHashSet<>();
    for (List<Row> rows : List.of(installs, signups, active, buyers)) for (Row r : rows) keys.add(keyOf(r));
    List<Map<String, Object>> out = new ArrayList<>();
    for (String k : keys) {
      long s = field(signups, k, "n");
      long b = field(buyers, k, "buyers");
      out.add(obj(
          "key", k,
          "installs", field(installs, k, "n"),
          "signups", s,
          "active", field(active, k, "n"),
          "purchases", field(buyers, k, "n"),
          "buyers", b,
          "revenue", field(buyers, k, "revenue"),
          "conversion", s != 0 ? (Object) (b / (double) s) : null));
    }
    out.sort((a, c) -> Long.compare(
        (long) c.get("installs") + (long) c.get("signups"), (long) a.get("installs") + (long) a.get("signups")));
    return out;
  }

  private static Object parseProps(String props) {
    return props == null ? null : Json.toPlain(Json.parse(props));
  }

  public Map<String, Object> features(Map<String, String> q) {
    Range r = range(q.get("days"));
    Filter w = filterSql("e", q.get("platform"), q.get("channel"));
    List<Row> rows = db.all(
        "SELECT e.name, COUNT(*) AS n, COUNT(DISTINCT COALESCE(e.device_id, e.phone)) AS actors FROM analytics_events e"
            + " WHERE e.day >= ? AND e.day <= ?" + w.sql() + " GROUP BY e.name ORDER BY n DESC",
        args(r.from(), r.to(), w.params()));
    List<Row> screens = db.all(
        "SELECT e.props, COUNT(*) AS n FROM analytics_events e WHERE e.name = 'screen_view' AND e.day >= ? AND e.day <= ?"
            + w.sql() + " GROUP BY e.props ORDER BY n DESC LIMIT 20",
        args(r.from(), r.to(), w.params()));
    List<Map<String, Object>> events = new ArrayList<>();
    for (Row e : rows) events.add(obj("name", e.str("name"), "label", label(e.str("name")), "count", e.n("n"), "users", e.n("actors")));
    List<Map<String, Object>> sc = new ArrayList<>();
    for (Row s : screens) {
      Object screen = "?";
      if (s.str("props") != null) {
        JsonNode p = Json.parse(s.str("props"));
        JsonNode v = p.isObject() ? p.get("screen") : null;
        screen = v == null || v.isNull() ? "?" : Json.toPlain(v);
      }
      sc.add(obj("screen", screen, "count", s.n("n")));
    }
    return obj("from", r.from(), "to", r.to(), "events", events, "screens", sc);
  }

  static Long median(List<Long> xs) {
    if (xs.isEmpty()) return null;
    List<Long> s = new ArrayList<>(xs);
    s.sort(null);
    return s.get(s.size() / 2);
  }

  /**
   * Strictly ordered: a step counts only after the step before it, by the same
   * person (device, or account when there's no device id).
   */
  public Map<String, Object> funnel(Map<String, String> q) {
    Range r = range(q.get("days"));
    List<String> list = new ArrayList<>();
    for (String s : (q.get("steps") == null ? "" : q.get("steps")).split(",")) {
      String t = s.strip();
      if (t.matches("[a-z][a-z0-9_]{1,40}") && list.size() < 8) list.add(t);
    }
    List<String> chosen = list.size() >= 2 ? list : DEFAULT_FUNNEL;
    double wd = numOr(q.get("windowDays"), 30);
    long windowMs = (long) (wd * DAY_MS);
    Filter w = filterSql("e", q.get("platform"), q.get("channel"));
    String marks = String.join(",", java.util.Collections.nCopies(chosen.size(), "?"));
    List<Row> rows = db.all(
        "SELECT e.name, e.at, e.device_id, e.phone FROM analytics_events e WHERE e.day >= ? AND e.day <= ? AND e.name IN (" + marks + ")"
            + w.sql() + " ORDER BY e.at",
        args(r.from(), r.to(), chosen, w.params()));
    // A device that signs in links its pre-login steps to the account.
    Map<String, String> deviceOfPhone = new HashMap<>();
    for (Row row : rows) {
      if (row.str("phone") != null && row.str("device_id") != null) deviceOfPhone.putIfAbsent(row.str("phone"), row.str("device_id"));
    }
    Map<String, int[]> step = new HashMap<>();
    Map<String, List<Long>> times = new HashMap<>();
    List<List<Long>> reachedAt = new ArrayList<>();
    for (int i = 0; i < chosen.size(); i++) reachedAt.add(new ArrayList<>());
    for (Row row : rows) {
      String actor = row.str("device_id") != null ? row.str("device_id")
          : deviceOfPhone.containsKey(row.str("phone")) ? deviceOfPhone.get(row.str("phone")) : "p:" + row.str("phone");
      int[] st = step.computeIfAbsent(actor, k -> new int[1]);
      List<Long> tm = times.computeIfAbsent(actor, k -> new ArrayList<>());
      int i = chosen.indexOf(row.str("name"));
      long at = row.n("at");
      if (i == st[0] && (i == 0 || at - tm.get(0) <= windowMs)) {
        tm.add(at);
        reachedAt.get(i).add(i == 0 ? 0 : at - tm.get(i - 1));
        st[0]++;
      }
    }
    List<Map<String, Object>> steps = new ArrayList<>();
    for (int i = 0; i < chosen.size(); i++) {
      long c = reachedAt.get(i).size();
      long first = reachedAt.get(0).size();
      long prev = i == 0 ? 0 : reachedAt.get(i - 1).size();
      steps.add(obj(
          "name", chosen.get(i),
          "label", label(chosen.get(i)),
          "count", c,
          "ofFirst", first != 0 ? (Object) (c / (double) first) : null,
          "ofPrevious", i == 0 ? null : prev != 0 ? (Object) (c / (double) prev) : null,
          "medianMs", i == 0 ? null : median(reachedAt.get(i))));
    }
    List<Map<String, Object>> available = new ArrayList<>();
    EVENT_LABELS.forEach((name, l) -> available.add(obj("name", name, "label", l)));
    return obj("from", r.from(), "to", r.to(), "steps", steps, "available", available);
  }

  /** Signup cohorts (by week or day) and the share active in each later period. */
  public Map<String, Object> cohorts(Map<String, String> q) {
    String unit = q.get("unit") == null ? "week" : q.get("unit");
    int size = unit.equals("day") ? 1 : 7;
    int count = (int) Math.min(Math.max(numOr(q.get("periods"), 8), 2), 26);
    String today = tehranDay(now.getAsLong());
    String start = addDays(today, -(count * size - 1));
    Filter uw = filterSql("u", q.get("platform"), q.get("channel"));
    List<Row> users = db.all("SELECT u.phone, u.first_day FROM users u WHERE u.first_day >= ?" + uw.sql(), args(start, uw.params()));
    List<Row> activity = db.all("SELECT DISTINCT phone, day FROM analytics_events WHERE phone IS NOT NULL AND day >= ?", start);
    Map<String, Set<Long>> activeIn = new HashMap<>();
    for (Row a : activity) activeIn.computeIfAbsent(a.str("phone"), k -> new HashSet<>()).add(Math.floorDiv(daysBetween(start, a.str("day")), size));
    long[] sizes = new long[count];
    long[][] retained = new long[count][];
    for (int i = 0; i < count; i++) retained[i] = new long[count - i];
    for (Row u : users) {
      long c = Math.floorDiv(daysBetween(start, u.str("first_day")), size);
      if (c < 0 || c >= count) continue;
      int ci = (int) c;
      sizes[ci]++;
      Set<Long> seen = activeIn.getOrDefault(u.str("phone"), Set.of());
      for (int k = 0; k < retained[ci].length; k++) if (k == 0 || seen.contains(c + k)) retained[ci][k]++;
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      List<Long> ret = new ArrayList<>();
      List<Object> rates = new ArrayList<>();
      for (long n : retained[i]) {
        ret.add(n);
        rates.add(sizes[i] != 0 ? (Object) (n / (double) sizes[i]) : null);
      }
      rows.add(obj("start", addDays(start, (long) i * size), "size", sizes[i], "retained", ret, "rates", rates));
    }
    return obj("unit", unit, "rows", rows);
  }

  public Map<String, Object> subscriptions(Map<String, String> q) {
    Range r = range(q.get("days"));
    long fromT = dayStart(r.from());
    String platform = q.get("platform"), channel = q.get("channel");
    Filter uw = filterSql("u", platform, channel);
    boolean filtered = (platform != null && !platform.isEmpty()) || (channel != null && !channel.isEmpty());
    String join = filtered ? "JOIN users u ON u.phone = c.phone" : "LEFT JOIN users u ON u.phone = c.phone";
    List<Row> rows = db.all(
        "SELECT c.id, c.phone, c.plan_id, c.amount, c.status, c.created_at, u.first_at, u.platform, u.channel FROM checkouts c "
            + join + " WHERE c.kind = 'entry' AND c.created_at >= ?" + uw.sql() + " ORDER BY c.created_at",
        args(fromT, uw.params()));
    List<Row> paid = rows.stream().filter(x -> "paid".equals(x.str("status")) || "refunded".equals(x.str("status"))).toList();
    Map<String, Map<String, Object>> perDay = new LinkedHashMap<>();
    for (String d : dayList(r.from(), r.to())) perDay.put(d, obj("day", d, "count", 0L, "revenue", 0L, "refunds", 0L));
    Map<String, Map<String, Object>> plans = new LinkedHashMap<>();
    for (Row x : paid) {
      boolean refunded = "refunded".equals(x.str("status"));
      Map<String, Object> d = perDay.get(tehranDay(x.n("created_at")));
      if (d != null) {
        if (refunded) d.put("refunds", (long) d.get("refunds") + 1);
        else {
          d.put("count", (long) d.get("count") + 1);
          d.put("revenue", (long) d.get("revenue") + x.n("amount"));
        }
      }
      String planId = x.str("plan_id");
      Map<String, Object> p = plans.computeIfAbsent(planId, k -> {
        Plans.Plan plan = Plans.byId(k);
        return obj("id", k, "title", plan != null ? plan.title() : k, "count", 0L, "revenue", 0L, "refunded", 0L);
      });
      if (refunded) p.put("refunded", (long) p.get("refunded") + 1);
      else {
        p.put("count", (long) p.get("count") + 1);
        p.put("revenue", (long) p.get("revenue") + x.n("amount"));
      }
    }
    List<Row> kept = paid.stream().filter(x -> "paid".equals(x.str("status"))).toList();
    Map<String, Integer> byBuyer = new LinkedHashMap<>();
    for (Row x : kept) byBuyer.merge(x.str("phone"), 1, Integer::sum);
    List<Long> toFirst = new ArrayList<>();
    Set<String> firstSeen = new HashSet<>();
    for (Row x : kept) {
      if (firstSeen.contains(x.str("phone")) || x.lng("first_at") == null || x.n("first_at") == 0) continue;
      firstSeen.add(x.str("phone"));
      toFirst.add(x.n("created_at") - x.n("first_at"));
    }
    toFirst.sort(null);
    long started = rows.size();
    long signups = count("SELECT COUNT(*) AS n FROM users u WHERE u.first_day >= ? AND u.first_day <= ?" + uw.sql(),
        args(r.from(), r.to(), uw.params()));
    long revenue = kept.stream().mapToLong(x -> x.n("amount")).sum();
    List<Map<String, Object>> recent = new ArrayList<>();
    for (int i = kept.size() - 1; i >= Math.max(0, kept.size() - 20); i--) {
      Row x = kept.get(i);
      recent.add(obj("phone", x.str("phone"), "planId", x.str("plan_id"), "amount", x.lng("amount"), "at", x.lng("created_at"),
          "platform", x.str("platform"), "channel", x.str("channel")));
    }
    Map<String, Object> totals = obj(
        "purchases", (long) kept.size(),
        "revenue", revenue,
        "buyers", (long) byBuyer.size(),
        "repeatBuyers", byBuyer.values().stream().filter(n -> n > 1).count(),
        "refunded", (long) (paid.size() - kept.size()),
        "checkoutsStarted", started,
        "checkoutSuccess", started != 0 ? (Object) (paid.size() / (double) started) : null,
        "abandoned", rows.stream().filter(x -> "pending".equals(x.str("status")) || "cancelled".equals(x.str("status"))).count(),
        "signups", signups,
        "medianTimeToFirstMs", toFirst.isEmpty() ? null : toFirst.get(toFirst.size() / 2));
    return obj("from", r.from(), "to", r.to(), "series", new ArrayList<>(perDay.values()), "plans", new ArrayList<>(plans.values()),
        "totals", totals, "recent", recent);
  }

  // ---------- users ----------

  private static final Map<String, String> USER_SORTS = Map.of(
      "last", "t.last_at DESC",
      "first", "t.first_at DESC",
      "oldest", "t.first_at ASC",
      "spent", "t.spent DESC, t.last_at DESC",
      "debt", "t.debt DESC, t.last_at DESC",
      "logins", "t.logins DESC, t.last_at DESC");

  private static final String USERS_SQL =
      " SELECT u.*, COALESCE(s.subs, 0) AS subs, COALESCE(s.spent, 0) AS spent, s.last_purchase,"
          + " COALESCE(d.debt, 0) AS debt, COALESCE(f.funds, 0) AS funds, COALESCE(m.circles, 0) AS circles"
          + " FROM users u"
          + " LEFT JOIN (SELECT phone, COUNT(*) AS subs, SUM(amount) AS spent, MAX(created_at) AS last_purchase"
          + "   FROM checkouts WHERE kind = 'entry' AND status = 'paid' GROUP BY phone) s ON s.phone = u.phone"
          + " LEFT JOIN (SELECT m.phone, SUM(x.amount) AS debt FROM contributions x JOIN circle_members m ON m.id = x.member_id"
          + "   WHERE x.status = 'covered' AND x.settled_at IS NULL GROUP BY m.phone) d ON d.phone = u.phone"
          + " LEFT JOIN (SELECT owner_phone AS phone, COUNT(*) AS funds FROM funds GROUP BY owner_phone) f ON f.phone = u.phone"
          + " LEFT JOIN (SELECT m.phone, COUNT(*) AS circles FROM circle_members m JOIN circles c ON c.id = m.circle_id"
          + "   WHERE c.status IN ('forming', 'active') GROUP BY m.phone) m ON m.phone = u.phone ";

  static String latinDigits(String s) {
    StringBuilder out = new StringBuilder();
    for (char ch : s.toCharArray()) {
      if (ch >= '۰' && ch <= '۹') out.append((char) ('0' + (ch - '۰')));
      else if (ch >= '٠' && ch <= '٩') out.append((char) ('0' + (ch - '٠')));
      else out.append(ch);
    }
    return out.toString();
  }

  public Map<String, Object> users(Map<String, String> q) {
    List<String> where = new ArrayList<>();
    List<Object> params = new ArrayList<>();
    // Persian digits, spaces, +98 or a leading 0: whatever was typed.
    String digits = latinDigits(q.getOrDefault("q", "")).replaceAll("\\D", "").replaceFirst("^(0098|98)", "0");
    if (!digits.isEmpty()) {
      where.add("t.phone LIKE ?");
      params.add("%" + digits.replaceFirst("^0", "") + "%");
    }
    for (String f : List.of("platform", "channel")) {
      String v = q.get(f);
      if (v != null && !v.isEmpty() && !v.equals("all")) {
        where.add("t." + f + " = ?");
        params.add(v);
      }
    }
    long t = now.getAsLong();
    Map<String, String> segments = Map.of(
        "subscriber", "t.subs > 0",
        "free", "t.subs = 0",
        "debtor", "t.debt > 0",
        "fund_owner", "t.funds > 0",
        "in_circle", "t.circles > 0",
        "new", "t.first_at >= " + (t - 7 * DAY_MS),
        "active", "t.last_at >= " + (t - 7 * DAY_MS),
        "dormant", "t.last_at < " + (t - 14 * DAY_MS));
    for (String s : q.getOrDefault("segment", "").split(",")) if (segments.containsKey(s)) where.add(segments.get(s));
    double joined = num(q.get("joined"));
    if (joined > 0) {
      where.add("t.first_at >= ?");
      params.add(t - (long) (Math.min(joined, 3650) * DAY_MS));
    }
    if (q.get("from") != null && !q.get("from").isEmpty()) {
      where.add("t.first_day >= ?");
      params.add(clip(q.get("from"), 10));
    }
    if (q.get("to") != null && !q.get("to").isEmpty()) {
      where.add("t.first_day <= ?");
      params.add(clip(q.get("to"), 10));
    }
    String whereSql = where.isEmpty() ? "" : "WHERE " + String.join(" AND ", where);
    long limit = (long) Math.min(Math.max(numOr(q.get("limit"), 25), 1), 200);
    long page = (long) Math.max(numOr(q.get("page"), 1), 1);
    String order = USER_SORTS.getOrDefault(q.getOrDefault("sort", ""), USER_SORTS.get("last"));
    long total = count("SELECT COUNT(*) AS n FROM (" + USERS_SQL + ") t " + whereSql, params.toArray());
    List<Row> rows = db.all("SELECT * FROM (" + USERS_SQL + ") t " + whereSql + " ORDER BY " + order + " LIMIT ? OFFSET ?",
        args(params, limit, (page - 1) * limit));
    List<Map<String, Object>> platforms = new ArrayList<>(), channels = new ArrayList<>();
    for (Row r : db.all("SELECT platform AS k, COUNT(*) AS n FROM users GROUP BY platform ORDER BY n DESC")) platforms.add(obj("key", r.str("k"), "count", r.n("n")));
    for (Row r : db.all("SELECT channel AS k, COUNT(*) AS n FROM users GROUP BY channel ORDER BY n DESC")) channels.add(obj("key", r.str("k"), "count", r.n("n")));
    return obj(
        "total", total,
        "page", page,
        "pages", Math.max(1, (long) Math.ceil(total / (double) limit)),
        "facets", obj("platforms", platforms, "channels", channels),
        "users", rows.stream().map(Analytics::userView).toList());
  }

  static Map<String, Object> userView(Row u) {
    return obj(
        "phone", u.str("phone"),
        "firstAt", u.lng("first_at"),
        "lastAt", u.lng("last_at"),
        "platform", u.str("platform"),
        "channel", u.str("channel"),
        "campaign", u.str("campaign"),
        "appVersion", u.str("app_version"),
        "logins", u.lng("logins"),
        "subscriptions", u.lng("subs"),
        "spent", u.lng("spent"),
        "lastPurchase", u.lng("last_purchase"),
        "debt", u.lng("debt"),
        "funds", u.lng("funds"),
        "circles", u.lng("circles"));
  }

  public Map<String, Object> user(String phone) {
    Row row = db.get("SELECT * FROM (" + USERS_SQL + ") t WHERE t.phone = ?", phone);
    if (row == null) throw new HttpError(404, "این کاربر پیدا نشد.");
    List<Map<String, Object>> devices = new ArrayList<>();
    for (Row d : db.all("SELECT * FROM devices WHERE phone = ? ORDER BY last_at DESC", phone)) {
      devices.add(obj("id", d.str("id"), "platform", d.str("platform"), "osVersion", d.str("os_version"), "appVersion", d.str("app_version"),
          "channel", d.str("channel"), "firstAt", d.lng("first_at"), "lastAt", d.lng("last_at")));
    }
    List<Map<String, Object>> purchases = new ArrayList<>();
    for (Row p : db.all("SELECT id, kind, plan_id, amount, status, created_at FROM checkouts WHERE phone = ? ORDER BY created_at DESC LIMIT 50", phone)) {
      purchases.add(obj("id", p.str("id"), "kind", p.str("kind"), "planId", p.str("plan_id"), "amount", p.lng("amount"),
          "status", p.str("status"), "at", p.lng("created_at")));
    }
    List<Map<String, Object>> circles = new ArrayList<>();
    for (Row m : db.all(
        "SELECT c.id, c.plan_id, c.status, c.current_month, c.months, m.position, m.won_month,"
            + " (SELECT COALESCE(SUM(x.amount), 0) FROM contributions x WHERE x.member_id = m.id AND x.status = 'covered' AND x.settled_at IS NULL) AS debt"
            + " FROM circle_members m JOIN circles c ON c.id = m.circle_id WHERE m.phone = ? ORDER BY m.joined_at DESC",
        phone)) {
      circles.add(obj("id", m.str("id"), "planId", m.str("plan_id"), "status", m.str("status"), "month", m.lng("current_month"),
          "months", m.lng("months"), "position", m.lng("position"), "wonMonth", m.lng("won_month"), "debt", m.lng("debt")));
    }
    List<Map<String, Object>> events = new ArrayList<>();
    for (Row e : db.all("SELECT at, name, platform, props FROM analytics_events WHERE phone = ? ORDER BY at DESC LIMIT 100", phone)) {
      events.add(obj("at", e.lng("at"), "name", e.str("name"), "label", label(e.str("name")), "platform", e.str("platform"),
          "props", parseProps(e.str("props"))));
    }
    List<Row> errors = db.all(
        "SELECT fingerprint, at, message, platform, app_version FROM error_logs WHERE phone = ? ORDER BY at DESC LIMIT 30", phone);
    return obj("user", userView(row), "devices", devices, "purchases", purchases, "circles", circles, "events", events, "errors", errors);
  }

  // ---------- errors ----------

  public Map<String, Object> errorGroups(Map<String, String> q) {
    Range r = range(q.getOrDefault("days", "30"));
    String status = q.get("status"), source = q.get("source"), text = q.get("q"), platform = q.get("platform");
    List<String> where = new ArrayList<>(List.of("g.last_at >= ?"));
    List<Object> params = new ArrayList<>(List.of(dayStart(r.from())));
    if (status != null && !status.isEmpty() && !status.equals("all")) {
      if (status.equals("open")) where.add("g.status IN ('open', 'regressed')");
      else {
        where.add("g.status = ?");
        params.add(status);
      }
    }
    if (source != null && !source.isEmpty() && !source.equals("all")) {
      where.add("g.source = ?");
      params.add(source);
    }
    if (text != null && !text.isEmpty()) {
      where.add("(LOWER(g.message) LIKE ? OR LOWER(COALESCE(g.url, '')) LIKE ?)");
      params.add("%" + text.toLowerCase() + "%");
      params.add("%" + text.toLowerCase() + "%");
    }
    boolean byPlatform = platform != null && !platform.isEmpty() && !platform.equals("all");
    String pw = byPlatform ? " AND l.platform = ?" : "";
    List<Object> pp = byPlatform ? List.of(platform) : List.of();
    List<Row> groups = db.all(
        "SELECT g.*,"
            + " (SELECT COUNT(*) FROM error_logs l WHERE l.fingerprint = g.fingerprint AND l.day >= ?" + pw + ") AS recent,"
            + " (SELECT COUNT(DISTINCT COALESCE(l.device_id, l.phone)) FROM error_logs l WHERE l.fingerprint = g.fingerprint AND l.day >= ?" + pw + ") AS affected"
            + " FROM error_groups g WHERE " + String.join(" AND ", where) + " ORDER BY g.last_at DESC LIMIT 200",
        args(r.from(), pp, r.from(), pp, params));
    Map<String, Long> perDay = byKey(db.all("SELECT day, COUNT(*) AS n FROM error_logs l WHERE day >= ?" + pw + " GROUP BY day",
        args(r.from(), pp)), "day");
    List<Map<String, Object>> series = new ArrayList<>();
    for (String day : dayList(r.from(), r.to())) series.add(obj("day", day, "count", perDay.getOrDefault(day, 0L)));
    List<Map<String, Object>> platforms = new ArrayList<>();
    for (Row x : db.all("SELECT COALESCE(platform, 'server') AS k, COUNT(*) AS n FROM error_logs WHERE day >= ? GROUP BY COALESCE(platform, 'server')", r.from())) {
      platforms.add(obj("key", x.str("k"), "count", x.n("n")));
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (Row g : groups) {
      if (byPlatform && g.n("recent") <= 0) continue;
      out.add(obj("fingerprint", g.str("fingerprint"), "source", g.str("source"), "message", g.str("message"), "url", g.str("url"),
          "status", g.str("status"), "count", g.lng("count"), "recent", g.lng("recent"), "affected", g.lng("affected"),
          "firstAt", g.lng("first_at"), "lastAt", g.lng("last_at"), "note", g.str("note")));
    }
    return obj("from", r.from(), "to", r.to(), "series", series, "platforms", platforms, "groups", out);
  }

  private List<Map<String, Object>> facet(String col, String fingerprint) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Row r : db.all("SELECT COALESCE(" + col + ", '—') AS k, COUNT(*) AS n FROM error_logs WHERE fingerprint = ? GROUP BY COALESCE("
        + col + ", '—') ORDER BY n DESC LIMIT 10", fingerprint)) {
      out.add(obj("key", r.str("k"), "count", r.n("n")));
    }
    return out;
  }

  public Map<String, Object> errorGroup(String fingerprint) {
    Row g = db.get("SELECT * FROM error_groups WHERE fingerprint = ?", fingerprint);
    if (g == null) throw new HttpError(404, "این خطا پیدا نشد.");
    List<Map<String, Object>> occurrences = new ArrayList<>();
    for (Row l : db.all("SELECT * FROM error_logs WHERE fingerprint = ? ORDER BY at DESC LIMIT 50", fingerprint)) {
      occurrences.add(obj("id", l.str("id"), "at", l.lng("at"), "level", l.str("level"), "url", l.str("url"), "phone", l.str("phone"),
          "platform", l.str("platform"), "appVersion", l.str("app_version"), "stack", l.str("stack"), "context", parseProps(l.str("context"))));
    }
    return obj(
        "group", obj("fingerprint", g.str("fingerprint"), "source", g.str("source"), "message", g.str("message"), "stack", g.str("stack"),
            "url", g.str("url"), "status", g.str("status"), "count", g.lng("count"), "firstAt", g.lng("first_at"), "lastAt", g.lng("last_at"),
            "note", g.str("note")),
        "platforms", facet("platform", fingerprint),
        "versions", facet("app_version", fingerprint),
        "urls", facet("url", fingerprint),
        "occurrences", occurrences);
  }

  public Map<String, Object> updateErrorGroup(String fingerprint, JsonNode body) {
    JsonNode s = body != null && body.isObject() ? body.get("status") : null;
    String status = s == null || s.isNull() ? null : textOf(s);
    if (status != null && !status.isEmpty() && !List.of("open", "resolved", "ignored").contains(status)) {
      throw new HttpError(400, "وضعیت نامعتبر است.");
    }
    if (status != null && status.isEmpty()) status = null;
    JsonNode n = body != null && body.isObject() ? body.get("note") : null;
    String note = n == null ? null : clip(textOf(n), 1000);
    int changes = db.run("UPDATE error_groups SET status = COALESCE(?, status), note = COALESCE(?, note) WHERE fingerprint = ?",
        status, note, fingerprint);
    if (changes == 0) throw new HttpError(404, "این خطا پیدا نشد.");
    return obj("ok", true);
  }
}
