package ir.digigharz.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ir.digigharz.DigiGharz;
import ir.digigharz.Json;
import ir.digigharz.TestSupport;
import ir.digigharz.digipay.Simulator;
import ir.digigharz.lib.Jalali;
import ir.digigharz.sms.SmsSender;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;

/** The HTTP API, end to end, as in server/app.test.js. */
class ApiTest {
  static final String MANAGER = "09120000001";
  static final String MEMBER = "09120000002";
  static final String STRANGER = "09120000003";
  static final HttpClient HTTP = HttpClient.newHttpClient();

  static final AtomicLong clock = new AtomicLong(System.currentTimeMillis());
  static ConfigurableApplicationContext app;
  static String baseUrl;

  record Res(int status, JsonNode body) {}

  static String start(Consumer<AppOptions> configure, List<ConfigurableApplicationContext> started) {
    AppOptions o = new AppOptions();
    o.db = TestSupport.testDatabase();
    configure.accept(o);
    ConfigurableApplicationContext ctx = DigiGharz.start(o);
    started.add(ctx);
    return "http://127.0.0.1:" + ((WebServerApplicationContext) ctx).getWebServer().getPort();
  }

  static final List<ConfigurableApplicationContext> servers = new ArrayList<>();

  @BeforeAll
  static void startServer() {
    baseUrl = start(o -> {
      o.now = clock::get;
      o.random = () -> 0;
    }, servers);
  }

  @AfterAll
  static void stop() {
    servers.forEach(ConfigurableApplicationContext::close);
  }

  /** A browser: keeps its session cookie between calls. */
  static final class Client {
    final String base;
    String cookie = "";

    Client(String base) {
      this.base = base;
    }

    Res call(String method, String path, Object body) {
      try {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + path)).header("content-type", "application/json");
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

  static Client login(String phone) {
    Client c = new Client(baseUrl);
    Res sent = c.call("POST", "/api/auth/request-code", Map.of("phone", phone));
    Res verified = c.call("POST", "/api/auth/verify", Map.of("phone", phone, "code", sent.body().get("devCode").asString()));
    assertEquals(200, verified.status());
    return c;
  }

  static JsonNode sampleData() {
    String month = Jalali.currentMonthKey(System.currentTimeMillis());
    return Json.parse("""
        {"version":1,
         "fund":{"name":"صندوق تست","contribution":1000,"loanAmount":2000,"installments":2,"startMonth":"%1$s","cycle":1},
         "members":[{"id":"m1","name":"مدیر","phone":"09120000001","shares":1,"joinMonth":"%1$s"},
                    {"id":"m2","name":"عضو","phone":"+98 912 000 0002","shares":1,"joinMonth":"%1$s"}],
         "payments":[{"id":"p1","memberId":"m1","type":"contribution","month":"%1$s","amount":1000},
                     {"id":"p2","memberId":"m2","type":"contribution","month":"%1$s","amount":1000}],
         "loans":[]}""".formatted(month));
  }

  @Test
  void loginRequiresTheCodeThatWasSent() {
    Client call = new Client(baseUrl);
    assertEquals(401, call.call("GET", "/api/me").status());

    Res requested = call.call("POST", "/api/auth/request-code", Map.of("phone", "۰۹۱۲۰۰۰۰۰۰۹"));
    assertEquals(200, requested.status());
    assertTrue(requested.body().get("devCode").asString().matches("\\d{5}"));

    assertEquals(400, call.call("POST", "/api/auth/verify", Map.of("phone", "09120000009", "code", "abcde")).status());
    String code = requested.body().get("devCode").asString();
    assertEquals(200, call.call("POST", "/api/auth/verify", Map.of("phone", "09120000009", "code", code)).status());
    assertEquals(Json.parse("{\"phone\":\"09120000009\"}"), call.call("GET", "/api/me").body());
    // The code is single-use.
    assertEquals(400, call.call("POST", "/api/auth/verify", Map.of("phone", "09120000009", "code", code)).status());
  }

  @Test
  void rejectsInvalidPhonesAndRapidResends() {
    Client call = new Client(baseUrl);
    assertEquals(400, call.call("POST", "/api/auth/request-code", Map.of("phone", "12345")).status());
    assertEquals(200, call.call("POST", "/api/auth/request-code", Map.of("phone", "09120000010")).status());
    assertEquals(429, call.call("POST", "/api/auth/request-code", Map.of("phone", "09120000010")).status());
    clock.addAndGet(61 * 1000);
    assertEquals(200, call.call("POST", "/api/auth/request-code", Map.of("phone", "09120000010")).status());
  }

  @Test
  void locksACodeAfterTooManyWrongGuesses() {
    Client call = new Client(baseUrl);
    Res sent = call.call("POST", "/api/auth/request-code", Map.of("phone", "09120000011"));
    for (int i = 0; i < 5; i++) call.call("POST", "/api/auth/verify", Map.of("phone", "09120000011", "code", "00000"));
    Res locked = call.call("POST", "/api/auth/verify", Map.of("phone", "09120000011", "code", sent.body().get("devCode").asString()));
    assertEquals(429, locked.status());
  }

  @Test
  void managerOwnsTheFundMembersGetAReadOnlyViewStrangersGetNothing() {
    Client manager = login(MANAGER);
    Client member = login(MEMBER);
    Client stranger = login(STRANGER);

    Res created = manager.call("POST", "/api/funds", Map.of("data", sampleData()));
    assertEquals(201, created.status());
    String id = created.body().get("id").asString();

    // (Other tests' funds may be listed too: JUnit doesn't run them in file order.)
    assertTrue(member.call("GET", "/api/funds").body().get("member").valueStream()
        .anyMatch(f -> f.equals(Json.parse("{\"id\":\"" + id + "\",\"name\":\"صندوق تست\"}"))));
    assertEquals(404, member.call("GET", "/api/funds/" + id).status());
    assertEquals(404, member.call("PUT", "/api/funds/" + id, Map.of("data", sampleData(), "version", 1)).status());
    assertEquals(404, stranger.call("GET", "/api/funds/" + id + "/view").status());

    Res view = member.call("GET", "/api/funds/" + id + "/view");
    assertEquals(200, view.status());
    assertEquals("عضو", view.body().get("me").get("name").asString());
    assertEquals(2000, view.body().get("balance").asLong());
    assertEquals(false, view.body().get("isManager").asBoolean());
  }

  @Test
  void savingWithAStaleVersionIsRejected() {
    Client manager = login(MANAGER);
    String id = manager.call("POST", "/api/funds", Map.of("data", sampleData())).body().get("id").asString();

    assertEquals(Json.parse("{\"version\":2}"), manager.call("PUT", "/api/funds/" + id, Map.of("data", sampleData(), "version", 1)).body());
    Res stale = manager.call("PUT", "/api/funds/" + id, Map.of("data", sampleData(), "version", 1));
    assertEquals(409, stale.status());
    assertEquals(2, stale.body().get("version").asLong());
    assertEquals(400, manager.call("PUT", "/api/funds/" + id, Map.of("data", Map.of("members", List.of()), "version", 2)).status());
  }

  @Test
  void drawsAreChosenOnTheServerAndEveryRerollIsVisibleToMembers() {
    Client manager = login(MANAGER);
    Client member = login(MEMBER);
    String id = manager.call("POST", "/api/funds", Map.of("data", sampleData())).body().get("id").asString();

    Res first = manager.call("POST", "/api/funds/" + id + "/draws");
    assertEquals(201, first.status());
    assertEquals("m1", first.body().get("draw").get("winnerId").asString()); // random() => 0 picks the first ticket

    // Drawing again silently cancels the pending draw, but it stays on record.
    clock.addAndGet(1000);
    Res second = manager.call("POST", "/api/funds/" + id + "/draws");
    String secondId = second.body().get("draw").get("id").asString();
    Res confirmed = manager.call("POST", "/api/funds/" + id + "/draws/" + secondId + "/confirm");
    assertEquals(200, confirmed.status());
    assertEquals(1, confirmed.body().get("data").get("loans").size());
    assertEquals(secondId, confirmed.body().get("data").get("loans").get(0).get("drawId").asString());

    assertEquals(400, manager.call("POST", "/api/funds/" + id + "/draws/" + first.body().get("draw").get("id").asString() + "/confirm").status());

    JsonNode view = member.call("GET", "/api/funds/" + id + "/view").body();
    assertEquals(Json.parse("[\"confirmed\",\"cancelled\"]"), Json.MAPPER.valueToTree(
        view.get("draws").valueStream().map(d -> d.get("status").asString()).toList()));
    assertTrue(view.get("loans").get(0).get("viaDraw").asBoolean());
    assertEquals(0, view.get("balance").asLong());

    // Balance is now below one loan, so no more draws.
    assertEquals(400, manager.call("POST", "/api/funds/" + id + "/draws").status());
  }

  @Test
  void mutatingRequestsMustBeJson() throws Exception {
    HttpResponse<String> res = HTTP.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/logout")).POST(HttpRequest.BodyPublishers.ofString("x")).build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(415, res.statusCode());
    HttpResponse<String> broken = HTTP.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/logout")).header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{nope")).build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(400, broken.statusCode());
  }

  @Test
  void unknownApiPathsAnswerJson404() {
    Res res = new Client(baseUrl).call("GET", "/api/nothing-here");
    assertEquals(404, res.status());
    assertEquals("مسیر پیدا نشد.", res.body().get("error").asString());
  }

  @Test
  void insideTheDigipayMiniAppALaunchTokenSignsTheUserInWithoutSms() {
    Client call = new Client(baseUrl);
    assertEquals(401, call.call("POST", "/api/auth/digipay", Map.of("token", "bogus")).status());
    assertEquals(200, call.call("POST", "/api/auth/digipay", Map.of("token", "sim-09120000055")).status());
    assertEquals(Json.parse("{\"phone\":\"09120000055\"}"), call.call("GET", "/api/me").body());
  }

  @Test
  void productionRefusesTheSimulatorUnlessItsAnExplicitDemo() {
    AppOptions o = new AppOptions();
    o.db = TestSupport.testDatabase();
    o.production = true;
    assertTrue(assertThrows(IllegalStateException.class, () -> new Server(o)).getMessage().contains("DEMO_MODE"));
    o.demo = true;
    assertDoesNotThrow(() -> new Server(o));

    AppOptions live = new AppOptions();
    live.db = o.db;
    live.production = true;
    live.digipay = new Simulator() {
      @Override
      public String name() {
        return "live";
      }
    };
    live.sendCode = sandboxSender(new ArrayList<>());
    assertTrue(assertThrows(IllegalStateException.class, () -> new Server(live)).getMessage().contains("sandbox"));
  }

  static SmsSender sandboxSender(List<String> sent) {
    return new SmsSender() {
      public void send(String phone, String code) {
        sent.add(code);
      }

      public boolean sandbox() {
        return true;
      }

      public Map<String, Object> info() {
        return Map.of();
      }

      public Map<String, Object> status() {
        return Map.of();
      }
    };
  }

  @Test
  void withAnSmsSandboxKeyTheCodeIsSentAndAlsoShownOnScreen() {
    List<String> sent = new ArrayList<>();
    String base = start(o -> {
      o.sendCode = sandboxSender(sent);
      o.demo = true;
    }, servers);
    Res res = new Client(base).call("POST", "/api/auth/request-code", Map.of("phone", "09121234567"));
    assertEquals(sent.get(0), res.body().get("devCode").asString());
  }

  @Test
  void joiningAGuaranteedPlanNeedsExplicitAcceptanceAndTheFirstShareThenShowsUpInMyCircles() {
    Client call = new Client(baseUrl);
    call.call("POST", "/api/auth/digipay", Map.of("token", "sim-09120000066"));
    assertEquals(4, call.call("GET", "/api/plans").body().get("plans").size());

    assertEquals(400, call.call("POST", "/api/circles/join", Map.of("planId", "p12-5")).status());
    Res started = call.call("POST", "/api/circles/join", Map.of("planId", "p12-5", "accept", true));
    assertEquals(201, started.status());
    String checkoutId = started.body().get("checkoutId").asString();
    JsonNode checkout = call.call("GET", "/api/checkouts/" + checkoutId).body();
    assertEquals("entry", checkout.get("kind").asString());
    assertEquals(5_000_000, checkout.get("amount").asLong());
    assertEquals(0, call.call("GET", "/api/circles").body().get("circles").size());

    JsonNode paid = call.call("POST", "/api/checkouts/" + checkoutId + "/complete", Map.of("action", "pay")).body();
    assertTrue(paid.get("ok").asBoolean());
    JsonNode mine = call.call("GET", "/api/circles").body().get("circles");
    assertEquals(paid.get("circleId"), mine.get(0).get("id"));
    assertEquals("forming", mine.get(0).get("status").asString());

    JsonNode view = call.call("GET", "/api/circles/" + paid.get("circleId").asString()).body();
    JsonNode me = view.get("members").valueStream().filter(m -> m.get("isMe").asBoolean()).findFirst().orElseThrow();
    assertEquals(2, me.get("position").asLong());
  }

  @Test
  void theAdminPanelNeedsTheAdminUsernameAndPasswordMembersKeepTheDemoSimulatorButtons() {
    String base = start(o -> {
      o.demo = true;
      o.adminUsername = "boss";
      o.adminPassword = "a-long-secret";
    }, servers);
    Client member = new Client(base);
    member.call("POST", "/api/auth/digipay", Map.of("token", "sim-09120000019"));
    assertEquals(401, member.call("GET", "/api/ops/overview").status());
    assertEquals(false, member.call("GET", "/api/admin/me").body().get("allowed").asBoolean());

    // A member in the demo can still fill their waiting group.
    String checkoutId = member.call("POST", "/api/circles/join", Map.of("planId", "p12-5", "accept", true)).body().get("checkoutId").asString();
    String circleId = member.call("POST", "/api/checkouts/" + checkoutId + "/complete", Map.of("action", "pay")).body().get("circleId").asString();
    assertEquals(200, member.call("POST", "/api/ops/circles/" + circleId + "/fill").status());

    Client boss = new Client(base);
    assertEquals(401, boss.call("POST", "/api/admin/login", Map.of("username", "boss", "password", "wrong")).status());
    assertEquals(200, boss.call("POST", "/api/admin/login", Map.of("username", "boss", "password", "a-long-secret")).status());
    assertEquals(Json.parse("{\"configured\":true,\"username\":\"boss\",\"allowed\":true}"), boss.call("GET", "/api/admin/me").body());
    assertEquals(1, boss.call("GET", "/api/ops/overview").body().get("circles").get("active").asLong());
    boss.call("POST", "/api/admin/logout");
    assertEquals(401, boss.call("GET", "/api/ops/overview").status());

    // Five wrong passwords lock the address out, even for the right one.
    Client guesser = new Client(base);
    for (int i = 0; i < 5; i++) guesser.call("POST", "/api/admin/login", Map.of("username", "boss", "password", "x" + i));
    assertEquals(429, guesser.call("POST", "/api/admin/login", Map.of("username", "boss", "password", "a-long-secret")).status());
  }

  @Test
  void productionRefusesAShortAdminPassword() {
    AppOptions o = new AppOptions();
    o.db = TestSupport.testDatabase();
    o.production = true;
    o.demo = true;
    o.adminUsername = "boss";
    o.adminPassword = "short";
    assertTrue(assertThrows(IllegalStateException.class, () -> new Server(o)).getMessage().contains("ADMIN_PASSWORD"));
  }

  @Test
  void eachAccountSeesTheFirstVisitTourOnceWhateverTheDevice() {
    Client call = login("09120000019");
    assertEquals(Json.parse("{\"seen\":false}"), call.call("GET", "/api/me/tour").body());
    assertEquals(200, call.call("POST", "/api/me/tour", Map.of()).status());
    assertEquals(200, call.call("POST", "/api/me/tour", Map.of()).status()); // twice is fine
    // Another sign-in (another phone or browser) already knows.
    assertEquals(Json.parse("{\"seen\":true}"), login("09120000019").call("GET", "/api/me/tour").body());
    assertEquals(Json.parse("{\"seen\":false}"), login("09120000029").call("GET", "/api/me/tour").body());
  }
}
