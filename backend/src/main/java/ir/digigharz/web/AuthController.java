package ir.digigharz.web;

import static ir.digigharz.Json.obj;

import ir.digigharz.HttpError;
import ir.digigharz.db.Row;
import ir.digigharz.lib.Fairness;
import ir.digigharz.lib.Phone;
import ir.digigharz.sms.SmsSender;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sign-in (SMS code or the Digipay launch token), the session, health and the admin sign-in. */
@RestController
public class AuthController {
  private static final long MINUTE = 60 * 1000;
  private static final long CODE_TTL = 2 * MINUTE;
  private static final long RESEND_GAP = MINUTE;
  private static final long SEND_WINDOW = 60 * MINUTE;
  private static final int MAX_SENDS_PER_WINDOW = 5;
  private static final int MAX_CODE_ATTEMPTS = 5;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final Server s;

  public AuthController(Server s) {
    this.s = s;
  }

  private static String newCode() {
    return String.format("%05d", RANDOM.nextInt(100000));
  }

  @PostMapping("/api/auth/request-code")
  public Map<String, Object> requestCode(HttpServletRequest req) {
    String phone = Phone.normalize(Body.of(req).str("phone"));
    if (phone == null) throw new HttpError(400, "شماره موبایل معتبر نیست.");

    long t = s.now();
    Row previous = s.db.get("SELECT * FROM otp_codes WHERE phone = ?", phone);
    boolean sameWindow = previous != null && t - previous.n("window_start") < SEND_WINDOW;
    long sentInWindow = sameWindow ? previous.n("sent_in_window") : 0;
    if (sentInWindow >= MAX_SENDS_PER_WINDOW) {
      throw new HttpError(429, "تعداد درخواست‌ها زیاد است. یک ساعت دیگر تلاش کنید.");
    }
    if (previous != null && previous.n("expires_at") - CODE_TTL + RESEND_GAP > t) {
      throw new HttpError(429, "لطفاً یک دقیقه صبر کنید و دوباره تلاش کنید.");
    }
    SmsSender sms = s.o.sendCode;
    if (sms == null && s.o.production && !s.o.demo) throw new HttpError(500, "سرویس پیامک تنظیم نشده است.");

    String code = newCode();
    s.db.run(
        "INSERT INTO otp_codes (phone, code_hash, expires_at, attempts, window_start, sent_in_window)"
            + " VALUES (?, ?, ?, 0, ?, ?)"
            + " ON CONFLICT (phone) DO UPDATE SET code_hash = excluded.code_hash, expires_at = excluded.expires_at,"
            + " attempts = 0, window_start = excluded.window_start, sent_in_window = excluded.sent_in_window",
        phone, Fairness.sha256Hex(code), t + CODE_TTL, sameWindow ? previous.n("window_start") : t, sentInWindow + 1);

    if (sms != null) {
      try {
        sms.send(phone, code);
      } catch (RuntimeException e) {
        System.err.println("SMS send failed: " + e.getMessage());
        throw new HttpError(502, "ارسال پیامک ناموفق بود. دوباره تلاش کنید.");
      }
      // SMS.ir's sandbox accepts the request but delivers nothing, so the
      // code is shown on screen as in dev mode.
      return sms.sandbox() ? obj("ok", true, "devCode", code) : obj("ok", true);
    }
    System.out.printf("[dev] login code for %s: %s%n", phone, code);
    return obj("ok", true, "devCode", code);
  }

  @PostMapping("/api/auth/verify")
  public Map<String, Object> verify(HttpServletRequest req, HttpServletResponse res) {
    Body body = Body.of(req);
    String phone = Phone.normalize(body.str("phone"));
    String code = Phone.toLatinDigits(body.str("code")).strip();
    Row row = phone == null ? null : s.db.get("SELECT * FROM otp_codes WHERE phone = ?", phone);
    if (row == null || row.n("expires_at") <= s.now()) {
      throw new HttpError(400, "کد منقضی شده است. دوباره درخواست کنید.");
    }
    if (row.n("attempts") >= MAX_CODE_ATTEMPTS) {
      throw new HttpError(429, "تعداد تلاش‌ها زیاد بود. کد جدید درخواست کنید.");
    }
    if (!Fairness.sha256Hex(code).equals(row.str("code_hash"))) {
      s.db.run("UPDATE otp_codes SET attempts = attempts + 1 WHERE phone = ?", phone);
      throw new HttpError(400, "کد وارد شده اشتباه است.");
    }
    // Keep the send counter so verifying doesn't reset the rate limit.
    // Spend the code in the same step that checks it, so it works only once.
    int changes = s.db.run("UPDATE otp_codes SET code_hash = '', expires_at = 0 WHERE phone = ? AND code_hash = ?",
        phone, row.str("code_hash"));
    if (changes == 0) throw new HttpError(400, "کد منقضی شده است. دوباره درخواست کنید.");
    s.startSession(res, phone);
    return obj("phone", phone);
  }

  // Inside the Digipay app the user is already signed in; the host hands us
  // a launch token that Digipay vouches for, so no SMS code is needed.
  @PostMapping("/api/auth/digipay")
  public Map<String, Object> digipay(HttpServletRequest req, HttpServletResponse res) {
    String identity = s.o.digipay.verifyLaunchToken(Body.of(req).str("token"));
    String phone = identity == null ? null : Phone.normalize(identity);
    if (phone == null) throw new HttpError(401, "ورود از طریق دیجی‌پی تأیید نشد.");
    s.startSession(res, phone);
    return obj("phone", phone);
  }

  @PostMapping("/api/auth/logout")
  public Map<String, Object> logout(HttpServletRequest req, HttpServletResponse res) {
    String token = Cookies.parse(req.getHeader("cookie")).get("sid");
    if (token != null) s.db.run("DELETE FROM sessions WHERE token_hash = ?", Fairness.sha256Hex(token));
    s.clearSession(res);
    return obj("ok", true);
  }

  @GetMapping("/api/me")
  public Map<String, Object> me(HttpServletRequest req) {
    return obj("phone", s.requireLogin(req));
  }

  // The first-visit tour: shown once per account, right after signing in.
  @GetMapping("/api/me/tour")
  public Map<String, Object> tour(HttpServletRequest req) {
    String phone = s.requireLogin(req);
    return obj("seen", s.db.get("SELECT 1 AS x FROM tour_seen WHERE phone = ?", phone) != null);
  }

  @PostMapping("/api/me/tour")
  public Map<String, Object> tourSeen(HttpServletRequest req) {
    String phone = s.requireLogin(req);
    s.db.run("INSERT INTO tour_seen (phone, seen_at) VALUES (?, ?) ON CONFLICT (phone) DO NOTHING", phone, s.now());
    return obj("ok", true);
  }

  // For the hosting platform's health checks, and to see which build is live.
  @GetMapping("/api/health")
  public Map<String, Object> health() {
    s.db.get("SELECT 1 AS ok");
    Map<String, Object> out = obj("ok", true, "demo", s.o.demo, "builtAt", s.o.builtAt, "persistentStorage", s.o.persistentStorage);
    if (s.o.demo || !s.o.production) out.put("storage", s.o.storageInfo);
    return out;
  }

  // ---------- admin panel sign-in ----------

  @GetMapping("/api/admin/me")
  public Map<String, Object> adminMe(HttpServletRequest req) {
    String username = s.admin.currentAdmin(req);
    String phone = username != null ? null : s.currentPhone(req);
    return obj(
        "configured", s.admin.configured(),
        "username", username,
        // Without an admin account, a phone with ops access gets in too.
        "allowed", username != null || (!s.admin.configured() && phone != null && s.isOps(phone)));
  }

  @PostMapping("/api/admin/login")
  public Map<String, Object> adminLogin(HttpServletRequest req, HttpServletResponse res) {
    Body body = Body.of(req);
    return s.admin.login(s.clientIp(req), body.str("username"), body.str("password"), res);
  }

  @PostMapping("/api/admin/logout")
  public Map<String, Object> adminLogout(HttpServletRequest req, HttpServletResponse res) {
    s.admin.logout(req, res);
    return obj("ok", true);
  }

  // ---------- SMS status (admin panel) ----------

  @GetMapping("/api/ops/sms")
  public Map<String, Object> smsStatus(HttpServletRequest req) {
    s.requireAdmin(req);
    SmsSender sms = s.o.sendCode;
    if (sms == null) return obj("configured", false);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("configured", true);
    out.putAll(sms.info());
    try {
      out.putAll(sms.status());
    } catch (RuntimeException e) {
      out.put("error", e.getMessage());
    }
    return out;
  }

  // Sends a real code message to a number, so the operator can see the whole
  // path work (or read the provider's exact error).
  @PostMapping("/api/ops/sms/test")
  public Map<String, Object> smsTest(HttpServletRequest req) {
    s.requireAdmin(req);
    SmsSender sms = s.o.sendCode;
    if (sms == null) throw new HttpError(400, "سرویس پیامک تنظیم نشده است.");
    String phone = Phone.normalize(Body.of(req).str("phone"));
    if (phone == null) throw new HttpError(400, "شماره موبایل معتبر نیست.");
    String code = newCode();
    try {
      sms.send(phone, code);
    } catch (RuntimeException e) {
      System.err.println("SMS test failed: " + e.getMessage());
      return obj("ok", false, "error", e.getMessage());
    }
    return obj("ok", true, "code", code, "sandbox", sms.sandbox());
  }
}
