package ir.digigharz.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The website from the frontend build (dist/), and the API's 404. The app lives
 * at the root: opened from the Digipay mini-app or by typing the bare domain.
 * Old /app/ links move to the root. The marketing page is at /welcome/.
 */
@RestController
public class SiteController {
  private static final Map<String, String> TYPES = Map.ofEntries(
      Map.entry("html", "text/html; charset=utf-8"),
      Map.entry("js", "text/javascript; charset=utf-8"),
      Map.entry("mjs", "text/javascript; charset=utf-8"),
      Map.entry("css", "text/css; charset=utf-8"),
      Map.entry("json", "application/json; charset=utf-8"),
      Map.entry("webmanifest", "application/manifest+json; charset=utf-8"),
      Map.entry("xml", "application/xml; charset=utf-8"),
      Map.entry("txt", "text/plain; charset=utf-8"),
      Map.entry("svg", "image/svg+xml"),
      Map.entry("png", "image/png"),
      Map.entry("jpg", "image/jpeg"),
      Map.entry("jpeg", "image/jpeg"),
      Map.entry("webp", "image/webp"),
      Map.entry("gif", "image/gif"),
      Map.entry("ico", "image/x-icon"),
      Map.entry("woff", "font/woff"),
      Map.entry("woff2", "font/woff2"),
      Map.entry("ttf", "font/ttf"),
      Map.entry("mp4", "video/mp4"),
      Map.entry("webm", "video/webm"),
      Map.entry("pdf", "application/pdf"));

  private final Server s;
  private String termsPage;

  public SiteController(Server s) {
    this.s = s;
  }

  @RequestMapping("/**")
  public void serve(HttpServletRequest req, HttpServletResponse res) throws IOException {
    String path = req.getRequestURI();
    if (path.equals("/api") || path.startsWith("/api/")) {
      ApiFilter.send(res, 404, Map.of("error", "مسیر پیدا نشد."));
      return;
    }
    // Plain-http visits (e.g. typing the bare domain) go to https. Only when
    // the platform's proxy says the request came in over http, so this can't loop.
    if ("http".equals(req.getHeader("x-forwarded-proto"))) {
      redirect(res, "https://" + req.getHeader("host") + originalUrl(req));
      return;
    }
    if (s.o.dist == null) {
      notFound(req, res);
      return;
    }
    if (!"GET".equals(req.getMethod()) && !"HEAD".equals(req.getMethod())) {
      notFound(req, res);
      return;
    }
    Path dist = Path.of(s.o.dist);
    if (path.equals("/")) {
      file(res, dist.resolve("app/index.html"), "public, max-age=0");
    } else if (path.equals("/app") || path.startsWith("/app/")) {
      redirect(res, "/" + (req.getQueryString() == null ? "" : "?" + req.getQueryString()));
    } else if (path.equals("/welcome") || path.equals("/welcome/")) {
      page(req, res, dist.resolve("welcome/index.html"));
    } else if (path.equals("/terms") || path.equals("/terms/")) {
      if (termsPage == null) termsPage = Files.readString(dist.resolve("terms/index.html"));
      html(res, termsPage.replace("<!--contact-->", contactHtml(s.o.env)), null);
    } else {
      staticFile(req, res, dist, path);
    }
  }

  private static String originalUrl(HttpServletRequest req) {
    return req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
  }

  // Hashed build assets never change, so browsers may keep them for a year;
  // everything else for an hour. A folder serves its index.html.
  private void staticFile(HttpServletRequest req, HttpServletResponse res, Path dist, String path) throws IOException {
    String decoded;
    try {
      decoded = java.net.URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      notFound(req, res);
      return;
    }
    List<String> segments = new ArrayList<>();
    for (String seg : decoded.split("/")) {
      if (seg.isEmpty()) continue;
      if (seg.startsWith(".") || seg.contains("\\") || seg.contains("\0")) {
        notFound(req, res);
        return;
      }
      segments.add(seg);
    }
    Path target = dist;
    for (String seg : segments) target = target.resolve(seg);
    target = target.normalize();
    if (!target.startsWith(dist.normalize())) {
      notFound(req, res);
      return;
    }
    if (Files.isDirectory(target)) {
      if (!path.endsWith("/")) {
        String location = path + "/" + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        res.setStatus(301);
        res.setHeader("Location", location);
        html(res, "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<title>Redirecting</title>\n</head>\n<body>\n<pre>Redirecting to "
            + escapeHtml(location) + "</pre>\n</body>\n</html>\n", null);
        return;
      }
      target = target.resolve("index.html");
    }
    if (!Files.isRegularFile(target)) {
      notFound(req, res);
      return;
    }
    boolean asset = path.startsWith("/assets/");
    file(res, target, asset ? "public, max-age=31536000, immutable" : "public, max-age=3600");
  }

  private static void redirect(HttpServletResponse res, String location) throws IOException {
    res.setStatus(301);
    res.setHeader("Location", location);
    byte[] body = ("Moved Permanently. Redirecting to " + location).getBytes(StandardCharsets.UTF_8);
    res.setContentType("text/plain; charset=utf-8");
    res.setContentLength(body.length);
    res.getOutputStream().write(body);
  }

  private static String typeOf(Path file) {
    String name = file.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "application/octet-stream" : TYPES.getOrDefault(name.substring(dot + 1).toLowerCase(), "application/octet-stream");
  }

  private static void file(HttpServletResponse res, Path file, String cacheControl) throws IOException {
    byte[] bytes = Files.readAllBytes(file);
    res.setContentType(typeOf(file));
    res.setHeader("Cache-Control", cacheControl);
    res.setDateHeader("Last-Modified", Files.getLastModifiedTime(file).toMillis());
    res.setContentLength(bytes.length);
    res.getOutputStream().write(bytes);
  }

  private static void html(HttpServletResponse res, String html, String cacheControl) throws IOException {
    byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
    res.setContentType("text/html; charset=utf-8");
    if (cacheControl != null) res.setHeader("Cache-Control", cacheControl);
    res.setContentLength(bytes.length);
    res.getOutputStream().write(bytes);
  }

  // Read from disk each time: this page must not silently vanish.
  private static void page(HttpServletRequest req, HttpServletResponse res, Path file) throws IOException {
    try {
      html(res, Files.readString(file), "no-cache");
    } catch (IOException e) {
      System.err.printf("Could not serve %s from %s: %s%n", req.getRequestURI(), file, e.getMessage());
      res.setStatus(500);
      res.setContentType("text/plain; charset=utf-8");
      res.getOutputStream().write("این صفحه موقتاً در دسترس نیست.".getBytes(StandardCharsets.UTF_8));
    }
  }

  private void notFound(HttpServletRequest req, HttpServletResponse res) throws IOException {
    System.err.printf("404 %s %s%n", req.getMethod(), originalUrl(req));
    res.setStatus(404);
    Path page = s.o.dist == null ? null : Path.of(s.o.dist, "404.html");
    if (page != null && Files.isRegularFile(page)) {
      html(res, Files.readString(page), null);
    } else {
      res.setContentType("text/plain; charset=utf-8");
      res.getOutputStream().write("Not Found".getBytes(StandardCharsets.UTF_8));
    }
  }

  private static final Pattern HTML_CHARS = Pattern.compile("[&<>\"']");

  static String escapeHtml(String s) {
    Matcher m = HTML_CHARS.matcher(s);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String r = switch (m.group()) {
        case "&" -> "&amp;";
        case "<" -> "&lt;";
        case ">" -> "&gt;";
        case "\"" -> "&quot;";
        default -> "&#39;";
      };
      m.appendReplacement(out, Matcher.quoteReplacement(r));
    }
    m.appendTail(out);
    return out.toString();
  }

  /**
   * The terms page names how to reach the business. The details come from the
   * environment (CONTACT_*), so they can change without a rebuild.
   */
  static String contactHtml(Map<String, String> env) {
    List<String> items = new ArrayList<>();
    String company = env.get("CONTACT_COMPANY");
    String email = env.get("CONTACT_EMAIL");
    String phone = env.get("CONTACT_PHONE");
    String address = env.get("CONTACT_ADDRESS");
    if (present(company)) items.add("<li><b>نام کسب‌وکار:</b> " + escapeHtml(company) + "</li>");
    if (present(email)) {
      items.add("<li><b>ایمیل پشتیبانی:</b> <a href=\"mailto:" + escapeHtml(email) + "\" dir=\"ltr\">" + escapeHtml(email) + "</a></li>");
    }
    if (present(phone)) {
      items.add("<li><b>تلفن پشتیبانی:</b> <a href=\"tel:" + escapeHtml(phone) + "\" dir=\"ltr\">" + escapeHtml(phone) + "</a></li>");
    }
    if (present(address)) items.add("<li><b>نشانی:</b> " + escapeHtml(address) + "</li>");
    if (items.isEmpty()) return "";
    return "<h2 id=\"contact\">تماس با ما</h2>\n          <ul>\n            "
        + String.join("\n            ", items) + "\n          </ul>";
  }

  private static boolean present(String s) {
    return s != null && !s.isEmpty();
  }
}
