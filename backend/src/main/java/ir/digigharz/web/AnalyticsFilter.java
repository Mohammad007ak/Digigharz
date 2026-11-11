package ir.digigharz.web;

import ir.digigharz.analytics.Analytics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Product analytics: successful API calls become events. Everything the
 * event needs is read here; the database work happens after the response,
 * on the analytics thread, so tracking never slows or breaks a request.
 */
public class AnalyticsFilter extends org.springframework.web.filter.OncePerRequestFilter {
  /** Set by the sign-in routes: who just signed in. */
  public static final String SIGNED_IN = "digigharz.signedInPhone";

  private final Server s;

  public AnalyticsFilter(Server s) {
    this.s = s;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    return !req.getRequestURI().startsWith("/api/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String path = req.getRequestURI();
    Analytics.RouteEvent match = Analytics.match(req.getMethod(), path);
    if (match == null) {
      chain.doFilter(req, res);
      return;
    }
    Analytics.Ctx ctx = Analytics.Ctx.of(req);
    chain.doFilter(req, res);
    if (res.getStatus() >= 400) return;
    try {
      Object signed = req.getAttribute(SIGNED_IN);
      String signedIn = signed instanceof String p ? p : null;
      String phone = match.name().equals("login") ? null : s.currentPhone(req);
      String planId = Body.of(req).get("planId") == null ? null : Body.of(req).str("planId");
      s.analytics.later(() -> s.analytics.recordRequest(match, path, ctx, signedIn, phone, planId));
    } catch (RuntimeException e) {
      System.err.println("analytics: " + e.getMessage());
    }
  }
}
