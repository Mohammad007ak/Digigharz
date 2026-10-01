package ir.digigharz.web;

import jakarta.servlet.http.HttpServletResponse;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.ResponseCookie;

final class Cookies {
  private Cookies() {}

  static Map<String, String> parse(String header) {
    Map<String, String> cookies = new HashMap<>();
    if (header == null) return cookies;
    for (String part : header.split(";")) {
      String[] kv = part.trim().split("=", -1);
      if (kv.length < 2 || kv[0].isEmpty() || kv[1].isEmpty()) continue;
      try {
        cookies.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
      } catch (IllegalArgumentException e) {
        // A malformed cookie is no cookie.
      }
    }
    return cookies;
  }

  static void set(HttpServletResponse res, String name, String value, Duration maxAge, String sameSite, boolean secure) {
    res.addHeader("Set-Cookie", ResponseCookie.from(name, value)
        .httpOnly(true).sameSite(sameSite).secure(secure).path("/").maxAge(maxAge).build().toString());
  }

  static void clear(HttpServletResponse res, String name, String sameSite, boolean secure) {
    res.addHeader("Set-Cookie", ResponseCookie.from(name, "")
        .httpOnly(true).sameSite(sameSite).secure(secure).path("/").maxAge(0).build().toString());
  }
}
