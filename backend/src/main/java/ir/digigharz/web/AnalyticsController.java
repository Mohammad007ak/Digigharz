package ir.digigharz.web;

import static ir.digigharz.Json.obj;

import ir.digigharz.HttpError;
import ir.digigharz.analytics.Analytics;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** What the app reports (opens, screens, errors) and the admin panel's analytics, users, error log and AI briefing. */
@RestController
public class AnalyticsController {
  private final Server s;

  public AnalyticsController(Server s) {
    this.s = s;
  }

  private String phoneOrNull(HttpServletRequest req) {
    try {
      return s.currentPhone(req);
    } catch (RuntimeException e) {
      return null;
    }
  }

  // Public, like any analytics beacon: the app reports opens and screens.
  @PostMapping("/api/collect")
  public Map<String, Object> collect(HttpServletRequest req) {
    return s.analytics.collect(Analytics.Ctx.of(req), s.currentPhone(req), Body.of(req).node());
  }

  @PostMapping("/api/log-error")
  public Map<String, Object> logError(HttpServletRequest req) {
    return s.analytics.collectErrors(Analytics.Ctx.of(req), phoneOrNull(req), Body.of(req).node());
  }

  @GetMapping("/api/ops/analytics/growth")
  public Map<String, Object> growth(HttpServletRequest req, @RequestParam Map<String, String> q) {
    s.requireAdmin(req);
    return s.analytics.growth(q);
  }

  @GetMapping("/api/ops/analytics/features")
  public Map<String, Object> features(HttpServletRequest req, @RequestParam Map<String, String> q) {
    s.requireAdmin(req);
    return s.analytics.features(q);
  }

  @GetMapping("/api/ops/analytics/funnel")
  public Map<String, Object> funnel(HttpServletRequest req, @RequestParam Map<String, String> q) {
    s.requireAdmin(req);
    return s.analytics.funnel(q);
  }

  @GetMapping("/api/ops/analytics/cohorts")
  public Map<String, Object> cohorts(HttpServletRequest req, @RequestParam Map<String, String> q) {
    s.requireAdmin(req);
    return s.analytics.cohorts(q);
  }

  @GetMapping("/api/ops/analytics/subscriptions")
  public Map<String, Object> subscriptions(HttpServletRequest req, @RequestParam Map<String, String> q) {
    s.requireAdmin(req);
    return s.analytics.subscriptions(q);
  }

  @GetMapping("/api/ops/users")
  public Map<String, Object> users(HttpServletRequest req, @RequestParam Map<String, String> q) {
    s.requireAdmin(req);
    return s.analytics.users(q);
  }

  @GetMapping("/api/ops/users/{phone}")
  public Map<String, Object> user(HttpServletRequest req, @PathVariable String phone) {
    s.requireAdmin(req);
    return s.analytics.user(phone);
  }

  @GetMapping("/api/ops/errors")
  public Map<String, Object> errors(HttpServletRequest req, @RequestParam Map<String, String> q) {
    s.requireAdmin(req);
    return s.analytics.errorGroups(q);
  }

  @GetMapping("/api/ops/errors/{fp}")
  public Map<String, Object> error(HttpServletRequest req, @PathVariable String fp) {
    s.requireAdmin(req);
    return s.analytics.errorGroup(fp);
  }

  @PatchMapping("/api/ops/errors/{fp}")
  public Map<String, Object> updateError(HttpServletRequest req, @PathVariable String fp) {
    s.requireAdmin(req);
    return s.analytics.updateErrorGroup(fp, Body.of(req).node());
  }

  @GetMapping("/api/ops/insights")
  public Map<String, Object> insights(HttpServletRequest req) {
    s.requireAdmin(req);
    return obj("configured", s.insights.configured(), "model", s.insights.model, "insights", s.insights.list());
  }

  @PostMapping("/api/ops/insights")
  public Map<String, Object> generate(HttpServletRequest req) {
    s.requireAdmin(req);
    String requested = Body.of(req).text("day");
    String day = requested != null && requested.matches("\\d{4}-\\d{2}-\\d{2}")
        ? requested
        : Analytics.tehranDay(s.now() - Analytics.DAY_MS);
    try {
      return obj("insight", s.insights.generate(day, true));
    } catch (RuntimeException e) {
      throw new HttpError(502, "تحلیل هوش مصنوعی ناموفق بود: " + e.getMessage());
    }
  }
}
