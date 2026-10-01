package ir.digigharz.web;

import ir.digigharz.HttpError;
import ir.digigharz.db.Db;
import ir.digigharz.db.Row;
import ir.digigharz.lib.Fairness;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Username/password sign-in for the admin panel, separate from members' phone
 * login. The credentials come from ADMIN_USERNAME / ADMIN_PASSWORD; only slow
 * hashes of them are kept in memory, sessions live in the database, and
 * repeated wrong passwords lock the address out for a while.
 */
public class AdminAuth {
  private static final long MINUTE = 60 * 1000;
  private static final long SESSION_TTL = 12 * 60 * MINUTE;
  private static final int MAX_FAILURES = 5;
  private static final long LOCKOUT = 15 * MINUTE;
  private static final int MIN_PASSWORD_LENGTH = 10;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final Db db;
  private final String username;
  private final boolean configured;
  private final boolean production;
  private final LongSupplier now;
  private final byte[] salt = new byte[16];
  private final byte[] userHash;
  private final byte[] passHash;

  private record Failures(int count, long until) {}

  // Failed attempts per client address.
  private final Map<String, Failures> failures = new ConcurrentHashMap<>();

  public AdminAuth(Db db, String username, String password, boolean production, LongSupplier now) {
    this.db = db;
    this.username = username;
    this.configured = username != null && !username.isEmpty() && password != null && !password.isEmpty();
    this.production = production;
    this.now = now;
    if (configured && production && password.length() < MIN_PASSWORD_LENGTH) {
      throw new IllegalStateException(
          "Refusing to start: ADMIN_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters.");
    }
    RANDOM.nextBytes(salt);
    userHash = configured ? slowHash(username) : null;
    passHash = configured ? slowHash(password) : null;
  }

  public boolean configured() {
    return configured;
  }

  private byte[] slowHash(String value) {
    try {
      KeySpec spec = new PBEKeySpec(value.toCharArray(), salt, 60_000, 256);
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public String currentAdmin(HttpServletRequest req) {
    String token = Cookies.parse(req.getHeader("cookie")).get("asid");
    if (token == null) return null;
    Row row = db.get("SELECT username FROM admin_sessions WHERE token_hash = ? AND expires_at > ?",
        Fairness.sha256Hex(token), now.getAsLong());
    return row == null ? null : row.str("username");
  }

  public Map<String, Object> login(String clientIp, String user, String pass, HttpServletResponse res) {
    if (!configured) throw new HttpError(404, "ورود مدیر تنظیم نشده است.");
    String key = clientIp == null ? "unknown" : clientIp;
    long t = now.getAsLong();
    Failures f = failures.get(key);
    if (f != null && f.count() >= MAX_FAILURES && f.until() > t) {
      throw new HttpError(429, "تلاش‌های ناموفق زیاد بود. ۱۵ دقیقه‌ی دیگر دوباره امتحان کنید.");
    }
    // Hash both fields every time, so a wrong username takes as long as a wrong password.
    boolean userOk = MessageDigest.isEqual(slowHash(user), userHash);
    boolean passOk = MessageDigest.isEqual(slowHash(pass), passHash);
    if (!userOk || !passOk) {
      int count = (f != null && f.until() > t ? f.count() : 0) + 1;
      failures.put(key, new Failures(count, t + LOCKOUT));
      throw new HttpError(401, "نام کاربری یا رمز عبور اشتباه است.");
    }
    failures.remove(key);
    byte[] raw = new byte[32];
    RANDOM.nextBytes(raw);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    db.transaction(tx -> {
      tx.run("DELETE FROM admin_sessions WHERE expires_at <= ?", t);
      tx.run("INSERT INTO admin_sessions (token_hash, username, expires_at) VALUES (?, ?, ?)",
          Fairness.sha256Hex(token), username, t + SESSION_TTL);
      return null;
    });
    Cookies.set(res, "asid", token, Duration.ofMillis(SESSION_TTL), "Strict", production);
    return Map.of("username", username);
  }

  public void logout(HttpServletRequest req, HttpServletResponse res) {
    String token = Cookies.parse(req.getHeader("cookie")).get("asid");
    if (token != null) db.run("DELETE FROM admin_sessions WHERE token_hash = ?", Fairness.sha256Hex(token));
    Cookies.clear(res, "asid", "Strict", production);
  }

  static String utf8(byte[] b) {
    return new String(b, StandardCharsets.UTF_8);
  }
}
