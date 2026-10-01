package ir.digigharz.web;

import static ir.digigharz.Json.obj;

import ir.digigharz.HttpError;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Guaranteed plans: joining, paying, the circle view, and the admin panel's reports and simulator tools. */
@RestController
public class CircleController {
  private final Server s;

  public CircleController(Server s) {
    this.s = s;
  }

  private boolean simulator() {
    return "simulator".equals(s.o.digipay.name());
  }

  @GetMapping("/api/plans")
  public Map<String, Object> plans() {
    return obj("plans", s.circles.planSummaries(), "simulator", simulator(), "walletDebit", s.circles.walletDebit());
  }

  @GetMapping("/api/eligibility")
  public Map<String, Object> eligibility(HttpServletRequest req) {
    return s.circles.eligibility(s.requireLogin(req));
  }

  @GetMapping("/api/circles")
  public Map<String, Object> mine(HttpServletRequest req) {
    String phone = s.requireLogin(req);
    return obj("circles", s.circles.myCircles(phone), "ops", s.isOps(phone));
  }

  @PostMapping("/api/circles/join")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> join(HttpServletRequest req) {
    String phone = s.requireLogin(req);
    Body body = Body.of(req);
    if (!body.isTrue("accept")) throw new HttpError(400, "برای عضویت باید شرایط طرح را بپذیرید.");
    return s.circles.join(phone, body.text("planId"), body.text("nonce"));
  }

  @GetMapping("/api/circles/{id}")
  public Map<String, Object> view(HttpServletRequest req, @PathVariable String id) {
    String phone = s.requireLogin(req);
    boolean ops = s.isOps(phone);
    Map<String, Object> view = s.circles.circleView(phone, id, ops);
    view.put("ops", ops);
    return view;
  }

  @PostMapping("/api/circles/{id}/seen")
  public Map<String, Object> seen(HttpServletRequest req, @PathVariable String id) {
    String phone = s.requireLogin(req);
    s.circles.markSeen(phone, id, monthOf(Body.of(req).get("month")));
    return obj("ok", true);
  }

  // Number(month) || 1
  private static long monthOf(JsonNode v) {
    if (v == null) return 1;
    double d;
    if (v.isNumber()) d = v.asDouble();
    else if (v.isString()) {
      try {
        d = v.asString().isBlank() ? 0 : Double.parseDouble(v.asString().trim());
      } catch (NumberFormatException e) {
        d = Double.NaN;
      }
    } else if (v.isBoolean()) d = v.asBoolean() ? 1 : 0;
    else d = Double.NaN;
    return Double.isNaN(d) || d == 0 ? 1 : (long) d;
  }

  @PostMapping("/api/circles/{id}/leave")
  public Map<String, Object> leave(HttpServletRequest req, @PathVariable String id) {
    s.circles.leave(s.requireLogin(req), id);
    return obj("ok", true);
  }

  @PostMapping("/api/circles/{id}/pay")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> pay(HttpServletRequest req, @PathVariable String id) {
    return s.circles.startCheckout(s.requireLogin(req), id);
  }

  @GetMapping("/api/checkouts/{id}")
  public Map<String, Object> checkout(HttpServletRequest req, @PathVariable String id) {
    return s.circles.getCheckout(s.requireLogin(req), id);
  }

  // With the real gateway Digipay confirms the payment server-to-server; the
  // simulator lets the mock payment page do it.
  @PostMapping("/api/checkouts/{id}/complete")
  public Map<String, Object> complete(HttpServletRequest req, @PathVariable String id) {
    String phone = s.requireLogin(req);
    if (!simulator()) throw new HttpError(404, "مسیر پیدا نشد.");
    String action = "pay".equals(Body.of(req).text("action")) ? "pay" : "cancel";
    return s.circles.completeCheckout(phone, id, action);
  }

  // ---------- admin panel ----------

  @GetMapping("/api/ops/overview")
  public Map<String, Object> overview(HttpServletRequest req) {
    s.requireAdmin(req);
    return s.reports.overview();
  }

  @GetMapping("/api/ops/circles")
  public Map<String, Object> circles(HttpServletRequest req) {
    s.requireAdmin(req);
    return obj("circles", s.reports.circles());
  }

  @GetMapping("/api/ops/circles/{id}")
  public Map<String, Object> circle(HttpServletRequest req, @PathVariable String id) {
    s.requireAdmin(req);
    return s.reports.circle(id);
  }

  @GetMapping("/api/ops/debtors")
  public Map<String, Object> debtors(HttpServletRequest req) {
    s.requireAdmin(req);
    return obj("debtors", s.reports.debtors());
  }

  @GetMapping("/api/ops/events")
  public Map<String, Object> events(
      HttpServletRequest req,
      @RequestParam(required = false) String limit,
      @RequestParam(required = false) String kind) {
    s.requireAdmin(req);
    return obj("events", s.reports.events(limit, kind == null || kind.isEmpty() ? null : kind));
  }

  @PostMapping("/api/ops/circles/{id}/fill")
  public Map<String, Object> fill(HttpServletRequest req, @PathVariable String id) {
    s.requireSimulatorOps(req);
    s.circles.fillWithBots(id);
    return obj("ok", true);
  }

  @PostMapping("/api/ops/circles/{id}/close-month")
  public Map<String, Object> closeMonth(HttpServletRequest req, @PathVariable String id) {
    s.requireSimulatorOps(req);
    return s.circles.closeMonth(id);
  }
}
