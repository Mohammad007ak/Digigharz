package ir.digigharz.analytics;

import static ir.digigharz.Json.obj;
import static ir.digigharz.analytics.Analytics.DAY_MS;
import static ir.digigharz.analytics.Analytics.TEHRAN_OFFSET;
import static ir.digigharz.analytics.Analytics.addDays;
import static ir.digigharz.analytics.Analytics.dayStart;
import static ir.digigharz.analytics.Analytics.tehranDay;

import ir.digigharz.Json;
import ir.digigharz.circles.OpsReports;
import ir.digigharz.db.Db;
import ir.digigharz.db.Row;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import tools.jackson.databind.JsonNode;

/**
 * Every morning (Tehran time) the previous day's numbers go to Claude, which
 * writes the operator a short report: what happened, what changed, what
 * broke, and what to do about it. Stored per day in ai_insights.
 *
 * <p>Needs ANTHROPIC_API_KEY. INSIGHTS_MODEL picks the model; INSIGHTS_HOUR the
 * Tehran hour after which yesterday's report is written (default 7).
 */
public class Insights {
  static final String API_URL = "https://api.anthropic.com/v1/messages";
  public static final String DEFAULT_MODEL = "claude-opus-5-5";

  static final String SYSTEM = """
      You are the head of growth and product analytics for "Digi Gharz" (دیجی قرض), a Persian mini-app inside Digipay.
      It has two products: free family loan funds (صندوق قرض‌الحسنه خانوادگی), and paid guaranteed rotating-savings plans (طرح‌های تضمینی):
      members join a 12-person circle by paying the first monthly installment (that payment is the "subscription purchase"), a provably fair lottery picks who receives the pot each month, and Digipay guarantees unpaid installments (members who don't pay become debtors).
      Every morning you get yesterday's metrics as JSON, compared with the day before and the 7-day average, plus the error log.
      Write the operator's daily briefing in fluent, concise Persian (Markdown). Structure:
      ## خلاصه‌ی دیروز  (3–5 bullets, the most important things first, with numbers)
      ## رشد و جذب  (installs, signups, channels, Android vs iOS)
      ## فعالیت و قیف  (active users, funnel drop-offs, feature use)
      ## درآمد و اشتراک  (purchases, revenue, plans, refunds, debt)
      ## خطاها و مشکلات فنی  (new or growing errors, who they hit, likely cause and what to check)
      ## پیشنهادهای اقدام  (3–5 concrete, prioritized actions)
      Rules: only state what the data supports; call out sharp changes (±30% or more) and anomalies; when numbers are too small to conclude anything, say so briefly instead of over-interpreting. Use Persian digits for numbers. Money is in toman.""";

  /** One HTTP POST; swapped out in tests. */
  public interface Fetch {
    Response post(String url, Map<String, String> headers, String body) throws Exception;
  }

  public record Response(int status, String body) {}

  static Fetch httpFetch() {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    return (url, headers, body) -> {
      HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
          .POST(HttpRequest.BodyPublishers.ofString(body));
      headers.forEach(req::header);
      HttpResponse<String> res = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
      return new Response(res.statusCode(), res.body());
    };
  }

  private final Db db;
  private final Analytics analytics;
  private final OpsReports ops;
  private final LongSupplier now;
  private final String apiKey;
  public final String model;
  private final int hour;
  private final Fetch fetch;
  private final AtomicBoolean running = new AtomicBoolean(false);

  public Insights(Db db, Analytics analytics, OpsReports ops, LongSupplier now, String apiKey, String model, Integer hour, Fetch fetch) {
    this.db = db;
    this.analytics = analytics;
    this.ops = ops;
    this.now = now;
    this.apiKey = apiKey == null || apiKey.isEmpty() ? null : apiKey;
    this.model = model == null || model.isEmpty() ? DEFAULT_MODEL : model;
    this.hour = hour == null ? 7 : hour;
    this.fetch = fetch == null ? httpFetch() : fetch;
  }

  public boolean configured() {
    return apiKey != null;
  }

  private long one(String sql, Object... p) {
    Row r = db.get(sql, p);
    return r == null ? 0 : r.n("n");
  }

  private Map<String, Object> split(String table, String col, String dayCol, String day) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Row r : db.all("SELECT " + col + " AS k, COUNT(*) AS n FROM " + table + " WHERE " + dayCol + " = ? GROUP BY " + col, day)) {
      out.put(r.str("k") == null ? "unknown" : r.str("k"), r.n("n"));
    }
    return out;
  }

  Map<String, Object> dayMetrics(String day) {
    long from = dayStart(day);
    long to = from + DAY_MS;
    Map<String, Object> events = new LinkedHashMap<>();
    for (Row e : db.all(
        "SELECT name, COUNT(*) AS n, COUNT(DISTINCT COALESCE(device_id, phone)) AS u FROM analytics_events WHERE day = ? GROUP BY name", day)) {
      Map<String, Object> v = obj("count", e.n("n"), "users", e.n("u"));
      String meaning = Analytics.EVENT_LABELS.get(e.str("name"));
      if (meaning != null) v.put("meaning", meaning);
      events.put(e.str("name"), v);
    }
    Row purchases = db.get(
        "SELECT COUNT(*) AS n, COALESCE(SUM(amount), 0) AS revenue FROM checkouts WHERE kind = 'entry' AND status = 'paid' AND created_at >= ? AND created_at < ?",
        from, to);
    Map<String, Object> opsEvents = new LinkedHashMap<>();
    for (Row r : db.all(
        "SELECT kind, COUNT(*) AS n, COALESCE(SUM(amount), 0) AS amount FROM ops_events WHERE at >= ? AND at < ? GROUP BY kind", from, to)) {
      opsEvents.put(r.str("kind"), obj("count", r.n("n"), "amount", r.n("amount")));
    }
    return obj(
        "day", day,
        "installs", one("SELECT COUNT(*) AS n FROM devices WHERE first_day = ?", day),
        "signups", one("SELECT COUNT(*) AS n FROM users WHERE first_day = ?", day),
        "activeUsers", one("SELECT COUNT(DISTINCT COALESCE(device_id, phone)) AS n FROM analytics_events WHERE day = ?", day),
        "installsByPlatform", split("devices", "platform", "first_day", day),
        "installsByChannel", split("devices", "channel", "first_day", day),
        "signupsByPlatform", split("users", "platform", "first_day", day),
        "signupsByChannel", split("users", "channel", "first_day", day),
        "events", events,
        "subscriptions", obj("purchases", purchases.n("n"), "revenue", purchases.n("revenue")),
        "circleOperations", opsEvents,
        "errors", one("SELECT COUNT(*) AS n FROM error_logs WHERE day = ?", day));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> gather(String day) {
    Map<String, Object> yesterday = dayMetrics(day);
    Map<String, Object> before = dayMetrics(addDays(day, -1));
    List<Map<String, Object>> week = new ArrayList<>();
    for (int i = 1; i <= 7; i++) week.add(dayMetrics(addDays(day, -i)));
    java.util.function.Function<String, Double> avg = key -> {
      long sum = 0;
      for (Map<String, Object> d : week) sum += d.get(key) == null ? 0 : (Long) d.get(key);
      return Math.round(sum / 7.0 * 10) / 10.0;
    };
    long from = dayStart(day);
    List<Map<String, Object>> errors = new ArrayList<>();
    for (Row e : db.all(
        "SELECT g.fingerprint, g.source, g.message, g.url, g.status, g.first_at, COUNT(*) AS n,"
            + " COUNT(DISTINCT COALESCE(l.device_id, l.phone)) AS affected"
            + " FROM error_logs l JOIN error_groups g ON g.fingerprint = l.fingerprint"
            + " WHERE l.day = ? GROUP BY g.fingerprint, g.source, g.message, g.url, g.status, g.first_at ORDER BY n DESC LIMIT 15",
        day)) {
      errors.add(obj("message", e.str("message"), "source", e.str("source"), "where", e.str("url"), "occurrences", e.n("n"),
          "usersAffected", e.n("affected"), "isNew", e.n("first_at") >= from, "status", e.str("status")));
    }
    Map<String, Object> overview = ops.overview();
    Map<String, Object> money = (Map<String, Object>) overview.get("money");
    Map<String, Object> collection = (Map<String, Object>) overview.get("collection");
    Map<String, Object> fill = (Map<String, Object>) overview.get("fill");
    Map<String, Object> funnel = analytics.funnel(Map.of("days", "7"));
    List<Map<String, Object>> funnelSteps = new ArrayList<>();
    for (Map<String, Object> s : (List<Map<String, Object>>) funnel.get("steps")) {
      funnelSteps.add(obj("step", s.get("label"), "count", s.get("count"), "ofPrevious", s.get("ofPrevious")));
    }
    return obj(
        "yesterday", yesterday,
        "dayBefore", before,
        "sevenDayAverage", obj("installs", avg.apply("installs"), "signups", avg.apply("signups"),
            "activeUsers", avg.apply("activeUsers"), "errors", avg.apply("errors")),
        "funnelLast7Days", funnelSteps,
        "errorsYesterday", errors,
        "plansNow", obj(
            "circles", overview.get("circles"),
            "openDebt", money.get("openDebt"),
            "debtors", money.get("debtors"),
            "paidWithoutGuaranteeRate", collection.get("paidRate"),
            "averageGroupFillMs", fill.get("averageMs")),
        "totals", obj("users", one("SELECT COUNT(*) AS n FROM users")));
  }

  private void save(String day, String status, String content, Map<String, Object> metrics, String error) {
    db.run(
        "INSERT INTO ai_insights (day, created_at, model, status, content, metrics, error) VALUES (?, ?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT (day) DO UPDATE SET created_at = excluded.created_at, model = excluded.model, status = excluded.status,"
            + " content = excluded.content, metrics = excluded.metrics, error = excluded.error",
        day, now.getAsLong(), model, status, content, Json.stringify(metrics), error);
  }

  public Map<String, Object> generate(String day, boolean force) {
    if (apiKey == null) throw new IllegalStateException("ANTHROPIC_API_KEY تنظیم نشده است.");
    Row existing = db.get("SELECT status FROM ai_insights WHERE day = ?", day);
    if (existing != null && "done".equals(existing.str("status")) && !force) return get(day);
    Map<String, Object> metrics = gather(day);
    try {
      String prompt = "گزارش روز " + day + " (تقویم میلادی، به وقت تهران). داده‌ها:\n\n```json\n"
          + Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(metrics) + "\n```";
      String request = Json.stringify(obj(
          "model", model,
          "max_tokens", 4000,
          "system", SYSTEM,
          "messages", List.of(obj("role", "user", "content", prompt))));
      Response res = fetch.post(API_URL,
          Map.of("content-type", "application/json", "x-api-key", apiKey, "anthropic-version", "2023-06-01"), request);
      JsonNode body;
      try {
        body = Json.parse(res.body() == null || res.body().isBlank() ? "{}" : res.body());
      } catch (RuntimeException e) {
        body = Json.object();
      }
      if (res.status() < 200 || res.status() >= 300) {
        JsonNode m = body.path("error").path("message");
        throw new IllegalStateException(m.isString() ? m.asString() : "HTTP " + res.status());
      }
      StringBuilder text = new StringBuilder();
      for (JsonNode b : body.path("content")) {
        if ("text".equals(b.path("type").asString(""))) {
          if (!text.isEmpty()) text.append('\n');
          text.append(b.path("text").asString(""));
        }
      }
      save(day, "done", text.toString().strip(), metrics, null);
    } catch (Exception e) {
      save(day, "failed", null, metrics, e.getMessage());
      throw e instanceof RuntimeException r ? r : new IllegalStateException(e.getMessage(), e);
    }
    return get(day);
  }

  public Map<String, Object> get(String day) {
    Row r = db.get("SELECT * FROM ai_insights WHERE day = ?", day);
    return r == null ? null : view(r);
  }

  private static Map<String, Object> view(Row r) {
    return obj(
        "day", r.str("day"),
        "createdAt", r.lng("created_at"),
        "model", r.str("model"),
        "status", r.str("status"),
        "content", r.str("content"),
        "error", r.str("error"),
        "metrics", r.str("metrics") == null ? null : Json.toPlain(Json.parse(r.str("metrics"))));
  }

  public List<Map<String, Object>> list() {
    return db.all("SELECT * FROM ai_insights ORDER BY day DESC LIMIT ?", 30L).stream().map(Insights::view).toList();
  }

  /** Called every minute by the scheduler; writes yesterday's report once it's past the configured hour in Tehran. */
  public void runDaily() {
    if (apiKey == null || !running.compareAndSet(false, true)) return;
    try {
      long t = now.getAsLong();
      long tehranHour = Math.floorMod((t + TEHRAN_OFFSET) / (60 * 60 * 1000), 24);
      if (tehranHour < hour) return;
      String day = addDays(tehranDay(t), -1);
      Row existing = db.get("SELECT status, created_at FROM ai_insights WHERE day = ?", day);
      // A failed attempt is retried after an hour.
      if (existing != null && ("done".equals(existing.str("status")) || t - existing.n("created_at") < 60 * 60 * 1000)) return;
      try {
        generate(day, false);
      } catch (RuntimeException e) {
        System.err.println("AI insight for " + day + " failed: " + e.getMessage());
      }
    } finally {
      running.set(false);
    }
  }
}
