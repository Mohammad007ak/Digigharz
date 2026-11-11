package ir.digigharz.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ir.digigharz.Json;
import ir.digigharz.analytics.Analytics;
import ir.digigharz.analytics.Insights;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;

/** Product analytics, the error log and the daily AI briefing, as in server/analytics.test.js. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnalyticsTest {
  static final HttpClient HTTP = HttpClient.newHttpClient();
  static final AtomicLong clock = new AtomicLong(Instant.parse("2026-10-20T08:00:00Z").toEpochMilli());
  static final List<JsonNode> aiCalls = Collections.synchronizedList(new ArrayList<>());
  static final List<ConfigurableApplicationContext> servers = new ArrayList<>();
  static String baseUrl;
  static Server server;

  @BeforeAll
  static void startServer() {
    baseUrl = ApiTest.start(o -> {
      o.now = clock::get;
      o.random = () -> 0;
      o.adminUsername = "admin";
      o.adminPassword = "secret-password";
      o.insightsApiKey = "test-key";
      o.insightsFetch = (url, headers, body) -> {
        aiCalls.add(Json.parse(body));
        return new Insights.Response(200, Json.stringify(Map.of("content",
            List.of(Map.of("type", "text", "text", "## خلاصه‌ی دیروز\n- همه چیز خوب بود")))));
      };
    }, servers);
    server = servers.get(0).getBean(Server.class);
  }

  @AfterAll
  static void stop() {
    servers.forEach(ConfigurableApplicationContext::close);
  }

  record Res(int status, JsonNode body) {}

  /** A browser on one device: keeps its session cookie and sends its device headers. */
  static final class Client {
    final Map<String, String> headers;
    String cookie = "";

    Client(Map<String, String> headers) {
      this.headers = headers;
    }

    Res call(String method, String path, Object body) {
      clock.addAndGet(1000);
      try {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + path)).header("content-type", "application/json");
        headers.forEach(req::header);
        if (!cookie.isEmpty()) req.header("cookie", cookie);
        req.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(Json.stringify(body)));
        HttpResponse<String> res = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
        res.headers().firstValue("set-cookie").ifPresent(c -> cookie = c.split(";")[0]);
        return new Res(res.statusCode(), Json.parse(res.body()));
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    Res call(String method, String path) {
      return call(method, path, null);
    }
  }

  static void settle() {
    server.analytics.settle();
  }

  static void signIn(Client c, String phone) {
    Res sent = c.call("POST", "/api/auth/request-code", Map.of("phone", phone));
    c.call("POST", "/api/auth/verify", Map.of("phone", phone, "code", sent.body().get("devCode").asString()));
    settle();
  }

  static Client admin() {
    Client a = new Client(Map.of());
    assertEquals(200, a.call("POST", "/api/admin/login", Map.of("username", "admin", "password", "secret-password")).status());
    return a;
  }

  @Test
  @Order(1)
  void helpers() {
    assertEquals("ios", Analytics.platformFromAgent("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0)"));
    assertEquals("android", Analytics.platformFromAgent("Mozilla/5.0 (Linux; Android 14)"));
    assertEquals("web", Analytics.platformFromAgent("Mozilla/5.0 (Windows NT 10.0)"));
    assertEquals("instagram", Analytics.normalizeChannel(" Instagram "));
    assertEquals("direct", Analytics.normalizeChannel(""));
    // 23:00 UTC is already the next day in Tehran.
    assertEquals("2026-10-21", Analytics.tehranDay(Instant.parse("2026-10-20T23:00:00Z").toEpochMilli()));
  }

  @Test
  @Order(2)
  void installsSignupsPlatformsChannelsFunnelUsersAndErrors() {
    Client android = new Client(Map.of("x-device-id", "device-android-1", "x-platform", "android", "x-channel", "instagram"));
    Client ios = new Client(Map.of("x-device-id", "device-ios-0001", "x-platform", "ios", "x-channel", "google"));
    Client admin = admin();

    android.call("POST", "/api/collect", Map.of("events", List.of(Map.of("name", "app_open"))));
    ios.call("POST", "/api/collect", Map.of("events", List.of(Map.of("name", "app_open"))));
    settle();
    signIn(android, "09121111111");
    signIn(ios, "09352222222");
    android.call("GET", "/api/plans");
    settle();

    JsonNode growth = admin.call("GET", "/api/ops/analytics/growth?days=7").body();
    assertEquals(2, growth.get("totals").get("devices").asInt());
    assertEquals(2, growth.get("totals").get("users").asInt());
    assertEquals(2, growth.get("totals").get("dau").asInt());
    List<String> platforms = new ArrayList<>();
    growth.get("platforms").forEach(p -> platforms.add(p.get("key").asString()));
    Collections.sort(platforms);
    assertEquals(List.of("android", "ios"), platforms);
    JsonNode instagram = null;
    for (JsonNode c : growth.get("channels")) if (c.get("key").asString().equals("instagram")) instagram = c;
    assertEquals(1, instagram.get("signups").asInt());

    JsonNode funnel = admin.call("GET", "/api/ops/analytics/funnel?days=7").body();
    List<Integer> counts = new ArrayList<>();
    for (int i = 0; i < 4; i++) counts.add(funnel.get("steps").get(i).get("count").asInt());
    assertEquals(List.of(2, 2, 2, 1), counts);

    // Search with Persian digits and a partial number; filter by platform.
    JsonNode users = admin.call("GET", "/api/ops/users?q=" + URLEncoder.encode("۲۲۲۲", StandardCharsets.UTF_8)).body();
    assertEquals(1, users.get("total").asInt());
    assertEquals("09352222222", users.get("users").get(0).get("phone").asString());
    users = admin.call("GET", "/api/ops/users?platform=android").body();
    assertEquals("instagram", users.get("users").get(0).get("channel").asString());
    JsonNode detail = admin.call("GET", "/api/ops/users/09121111111").body();
    boolean sawPlans = false;
    for (JsonNode e : detail.get("events")) if (e.get("name").asString().equals("plans_viewed")) sawPlans = true;
    assertTrue(sawPlans);

    // The other reports answer too.
    assertEquals(200, admin.call("GET", "/api/ops/analytics/features?days=7").status());
    assertEquals(200, admin.call("GET", "/api/ops/analytics/cohorts?unit=day").status());
    assertEquals(200, admin.call("GET", "/api/ops/analytics/subscriptions?days=30&platform=all").status());
    // Members can't read them.
    assertEquals(401, android.call("GET", "/api/ops/users").status());

    // App errors are grouped; a fixed bug that comes back is a regression.
    Map<String, Object> crash = Map.of("message", "TypeError: x is undefined (id 123)", "stack", "at Foo (app.js:1:2)", "url", "/#/home");
    android.call("POST", "/api/log-error", Map.of("errors", List.of(crash)));
    ios.call("POST", "/api/log-error", Map.of("errors", List.of(Map.of(
        "message", "TypeError: x is undefined (id 456)", "stack", "at Foo (app.js:1:2)", "url", "/#/home"))));
    JsonNode errors = admin.call("GET", "/api/ops/errors").body();
    assertEquals(1, errors.get("groups").size());
    assertEquals(2, errors.get("groups").get(0).get("affected").asInt());
    String fp = errors.get("groups").get(0).get("fingerprint").asString();
    assertEquals(200, admin.call("PATCH", "/api/ops/errors/" + fp, Map.of("status", "resolved")).status());
    android.call("POST", "/api/log-error", Map.of("errors", List.of(crash)));
    errors = admin.call("GET", "/api/ops/errors").body();
    assertEquals("regressed", errors.get("groups").get(0).get("status").asString());
    assertEquals(200, admin.call("GET", "/api/ops/errors/" + fp).status());
  }

  @Test
  @Order(3)
  void theDailyAiBriefingIsWrittenOnceAfterTheMorningHour() {
    clock.set(Instant.parse("2026-10-21T01:00:00Z").toEpochMilli()); // 04:30 Tehran: too early
    server.insights.runDaily();
    assertEquals(0, aiCalls.size());
    clock.set(Instant.parse("2026-10-21T05:00:00Z").toEpochMilli()); // 08:30 Tehran
    server.insights.runDaily();
    server.insights.runDaily();
    assertEquals(1, aiCalls.size());
    assertTrue(aiCalls.get(0).get("messages").get(0).get("content").asString().contains("2026-10-20"));
    JsonNode list = admin().call("GET", "/api/ops/insights").body();
    JsonNode first = list.get("insights").get(0);
    assertEquals("2026-10-20", first.get("day").asString());
    assertEquals("done", first.get("status").asString());
    assertEquals(2, first.get("metrics").get("yesterday").get("signups").asInt());
  }
}
