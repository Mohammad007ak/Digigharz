package ir.digigharz.web;

import ir.digigharz.HttpError;
import ir.digigharz.circles.CircleService;
import ir.digigharz.circles.OpsReports;
import ir.digigharz.db.Db;
import ir.digigharz.db.Row;
import ir.digigharz.lib.Fairness;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.function.Predicate;

/** The services behind the API, and who is asking. */
public class Server {
  static final long MINUTE = 60 * 1000;
  static final long SESSION_TTL = 30L * 24 * 60 * MINUTE;
  private static final SecureRandom RANDOM = new SecureRandom();

  public final AppOptions o;
  public final Db db;
  public final CircleService circles;
  public final OpsReports reports;
  public final AdminAuth admin;

  public Server(AppOptions o) {
    // The simulator signs anyone in as any phone ("sim-<phone>") and moves no
    // real money, so a production server may only run it as an explicit demo.
    if (o.production && !o.demo && "simulator".equals(o.digipay.name())) {
      throw new IllegalStateException(
          "Refusing to start: production needs DIGIPAY_MODE=live, or DEMO_MODE=true for a public demo on the simulator.");
    }
    if (o.production && !o.demo && o.sendCode != null && o.sendCode.sandbox()) {
      throw new IllegalStateException(
          "Refusing to start: an SMS sandbox key shows login codes on screen; use it only with DEMO_MODE=true.");
    }
    this.o = o;
    this.db = o.db;
    this.circles = new CircleService(new CircleService.Options(
        o.db, o.digipay, o.now, o.formTimeoutMs, 0.05, 30L * 24 * 60 * MINUTE, o.walletDebit, o.scoringCheck));
    this.reports = new OpsReports(o.db, o.now);
    this.admin = new AdminAuth(o.db, o.adminUsername, o.adminPassword, o.production, o.now);
  }

  long now() {
    return o.now.getAsLong();
  }

  public String currentPhone(HttpServletRequest req) {
    String token = Cookies.parse(req.getHeader("cookie")).get("sid");
    if (token == null) return null;
    Row session = db.get("SELECT phone FROM sessions WHERE token_hash = ? AND expires_at > ?", Fairness.sha256Hex(token), now());
    return session == null ? null : session.str("phone");
  }

  public String requireLogin(HttpServletRequest req) {
    String phone = currentPhone(req);
    if (phone == null) throw new HttpError(401, "ابتدا وارد شوید.");
    return phone;
  }

  public void startSession(HttpServletResponse res, String phone) {
    byte[] raw = new byte[32];
    RANDOM.nextBytes(raw);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    long t = now();
    db.transaction(tx -> {
      tx.run("DELETE FROM sessions WHERE expires_at <= ?", t);
      tx.run("INSERT INTO sessions (token_hash, phone, expires_at) VALUES (?, ?, ?)", Fairness.sha256Hex(token), phone, t + SESSION_TTL);
      return null;
    });
    Cookies.set(res, "sid", token, Duration.ofMillis(SESSION_TTL), "Lax", o.production);
  }

  public void clearSession(HttpServletResponse res) {
    Cookies.clear(res, "sid", "Lax", o.production);
  }

  // Who may use the admin panel and the simulator tools:
  // - a signed-in admin (username/password), always;
  // - with no admin account configured, members by phone: anyone in
  //   development and demos, OPS_PHONES in production.
  // The simulator buttons members see in a demo (filling a waiting group,
  // running a draw early) stay open to them either way.
  public boolean isOps(String phone) {
    return o.production && !o.demo ? o.opsPhones.contains(phone) : true;
  }

  private void allow(HttpServletRequest req, Predicate<String> check) {
    if (admin.currentAdmin(req) != null) return;
    if (check.test(currentPhone(req))) return;
    throw new HttpError(401, "ورود مدیر لازم است.", java.util.Map.of("adminLogin", true));
  }

  public void requireAdmin(HttpServletRequest req) {
    allow(req, phone -> !admin.configured() && phone != null && isOps(phone));
  }

  public void requireSimulatorOps(HttpServletRequest req) {
    allow(req, phone -> phone != null && isOps(phone));
  }

  /** The client's address; behind the hosting platform's proxy in production. */
  public String clientIp(HttpServletRequest req) {
    String forwarded = req.getHeader("x-forwarded-for");
    if (o.production && forwarded != null && !forwarded.isBlank()) {
      String[] hops = forwarded.split(",");
      return hops[hops.length - 1].trim();
    }
    return req.getRemoteAddr();
  }
}
