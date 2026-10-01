package ir.digigharz.web;

import ir.digigharz.Json;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;

/**
 * Every /api request: mutating requests must be JSON (a cross-site form post
 * can't send that without a CORS preflight, which together with SameSite
 * cookies blocks CSRF), and the body is parsed once, up to 2 MB.
 */
public class ApiFilter extends OncePerRequestFilter {
  private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
  private static final int LIMIT = 2 * 1024 * 1024;

  static boolean isJson(String contentType) {
    if (contentType == null) return false;
    String type = contentType.split(";")[0].trim().toLowerCase();
    return type.equals("application/json");
  }

  static void send(HttpServletResponse res, int status, Map<String, Object> body) throws IOException {
    res.setStatus(status);
    res.setContentType("application/json; charset=utf-8");
    res.getOutputStream().write(Json.stringify(body).getBytes(StandardCharsets.UTF_8));
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    return !req.getRequestURI().startsWith("/api/") && !req.getRequestURI().equals("/api");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    boolean json = isJson(req.getContentType());
    if (MUTATING.contains(req.getMethod()) && !json) {
      send(res, 415, Map.of("error", "درخواست نامعتبر است."));
      return;
    }
    if (json) {
      if (req.getContentLengthLong() > LIMIT) {
        send(res, 413, Map.of("error", "درخواست بیش از حد بزرگ است."));
        return;
      }
      byte[] raw;
      try (InputStream in = req.getInputStream()) {
        raw = in.readNBytes(LIMIT + 1);
      }
      if (raw.length > LIMIT) {
        send(res, 413, Map.of("error", "درخواست بیش از حد بزرگ است."));
        return;
      }
      if (raw.length > 0) {
        // Like Express's JSON parser: only an object or an array is a body.
        String text = new String(raw, StandardCharsets.UTF_8).strip();
        JsonNode node = null;
        if (text.startsWith("{") || text.startsWith("[")) {
          try {
            node = Json.parse(text);
          } catch (RuntimeException e) {
            node = null;
          }
        }
        if (node == null) {
          send(res, 400, Map.of("error", "درخواست نامعتبر است."));
          return;
        }
        req.setAttribute(Body.ATTRIBUTE, node);
      }
    }
    chain.doFilter(req, res);
  }
}
