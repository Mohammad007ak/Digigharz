package ir.digigharz.web;

import ir.digigharz.HttpError;
import ir.digigharz.analytics.Analytics;
import jakarta.servlet.http.HttpServletRequest;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Errors as {error, ...extra}, in Persian, the way the app expects them. */
@RestControllerAdvice
public class ErrorAdvice {
  private final Server s;

  public ErrorAdvice(Server s) {
    this.s = s;
  }

  @ExceptionHandler(HttpError.class)
  public ResponseEntity<Map<String, Object>> http(HttpError e) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", e.getMessage());
    body.putAll(e.extra());
    return ResponseEntity.status(e.status()).body(body);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> other(Exception e, HttpServletRequest req) {
    e.printStackTrace();
    // Every server crash lands in the admin panel's error log.
    try {
      StringWriter stack = new StringWriter();
      e.printStackTrace(new PrintWriter(stack));
      Object pattern = req.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
      String where = req.getMethod() + " " + (pattern != null ? pattern : req.getRequestURI());
      String phone;
      try {
        phone = s.currentPhone(req);
      } catch (RuntimeException ignored) {
        phone = null;
      }
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      Analytics.Ctx ctx = Analytics.Ctx.of(req);
      String who = phone;
      s.analytics.later(() -> s.analytics.logError("server", "error", message, stack.toString(), where, null, ctx, who));
    } catch (RuntimeException ignored) {
      // Logging must never hide the original error.
    }
    return ResponseEntity.status(500).body(Map.of("error", "خطای داخلی سرور."));
  }
}
