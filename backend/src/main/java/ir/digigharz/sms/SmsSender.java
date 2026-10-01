package ir.digigharz.sms;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * Sends login codes by SMS. Two providers are supported; the first one with an
 * API key set wins:
 *
 * <ul>
 *   <li>SMS.ir "verify" API with a template that has one parameter (the code).
 *       With SMSIR_SANDBOX=true (a Sandbox key) nothing is delivered, so the
 *       code is also shown on screen; the sandbox's built-in template is 123456.
 *   <li>Kavenegar "verify lookup" API with a template containing %token%.
 * </ul>
 *
 * With neither, the server runs in dev mode and shows the code on screen. Keys
 * come only from the environment (or .env), never from the code.
 */
public abstract class SmsSender {
  public abstract void send(String phone, String code);

  /** A sandbox key delivers nothing, so the code is shown on screen as well. */
  public boolean sandbox() {
    return false;
  }

  /** What the admin panel shows about the provider. */
  public abstract Map<String, Object> info();

  /** Is the key accepted, and how much credit is left? */
  public abstract Map<String, Object> status();

  public record Response(int status, JsonNode body) {
    boolean ok() {
      return status >= 200 && status < 300;
    }
  }

  /** How requests go out; tests replace it. */
  public interface Transport {
    Response request(String method, String url, Map<String, String> headers, String body);
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public static Transport httpTransport() {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    return (method, url, headers, body) -> {
      HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));
      headers.forEach(req::header);
      req.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
      try {
        HttpResponse<String> res = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode parsed;
        try {
          parsed = JSON.readTree(res.body());
        } catch (RuntimeException e) {
          parsed = MissingNode.getInstance();
        }
        return new Response(res.statusCode(), parsed == null ? MissingNode.getInstance() : parsed);
      } catch (IOException e) {
        throw new IllegalStateException(e.getMessage(), e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    };
  }

  public static SmsSender fromEnv(Map<String, String> env) {
    return fromEnv(env, httpTransport());
  }

  public static SmsSender fromEnv(Map<String, String> env, Transport transport) {
    if (present(env.get("SMSIR_API_KEY"))) return SmsIr.create(env, transport);
    if (present(env.get("KAVENEGAR_API_KEY"))) return new Kavenegar(env, transport);
    return null;
  }

  static boolean present(String s) {
    return s != null && !s.isEmpty();
  }

  static String text(JsonNode node, String key) {
    JsonNode v = node.path(key);
    return v.isMissingNode() || v.isNull() ? "" : v.isString() ? v.asString() : v.toString();
  }

  static final class SmsIr extends SmsSender {
    static final int SANDBOX_TEMPLATE = 123456;
    private final String key;
    private final boolean sandbox;
    private final int templateId;
    private final String parameter;
    private final Transport transport;

    private SmsIr(String key, boolean sandbox, int templateId, String parameter, Transport transport) {
      this.key = key;
      this.sandbox = sandbox;
      this.templateId = templateId;
      this.parameter = parameter;
      this.transport = transport;
    }

    static SmsSender create(Map<String, String> env, Transport transport) {
      boolean sandbox = "true".equals(env.get("SMSIR_SANDBOX"));
      int templateId = 0;
      try {
        templateId = Integer.parseInt(env.getOrDefault("SMSIR_TEMPLATE_ID", "").trim());
      } catch (NumberFormatException ignored) {
        // Not set (or not a number).
      }
      if (templateId == 0 && sandbox) templateId = SANDBOX_TEMPLATE;
      // No template yet (still waiting for SMS.ir's approval): run without SMS
      // rather than refuse to start.
      if (templateId == 0) {
        System.err.println("⚠ SMSIR_API_KEY is set but SMSIR_TEMPLATE_ID is not: SMS is off until the template id is set.");
        return null;
      }
      String parameter = present(env.get("SMSIR_TEMPLATE_PARAM")) ? env.get("SMSIR_TEMPLATE_PARAM") : "CODE";
      return new SmsIr(env.get("SMSIR_API_KEY"), sandbox, templateId, parameter, transport);
    }

    private static IllegalStateException failure(Response r) {
      return new IllegalStateException(
          ("SMS.ir: " + r.status() + " " + text(r.body(), "status") + " " + text(r.body(), "message")).trim());
    }

    public void send(String phone, String code) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("mobile", phone);
      body.put("templateId", templateId);
      body.put("parameters", List.of(Map.of("name", parameter, "value", code)));
      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("content-type", "application/json");
      headers.put("accept", "application/json");
      headers.put("x-api-key", key);
      Response r = transport.request("POST", "https://api.sms.ir/v1/send/verify", headers, JSON.writeValueAsString(body));
      // SMS.ir answers status 1 on success, anything else with a message.
      if (!r.ok() || r.body().path("status").asInt(0) != 1) throw failure(r);
    }

    public boolean sandbox() {
      return sandbox;
    }

    public Map<String, Object> info() {
      Map<String, Object> info = new LinkedHashMap<>();
      info.put("provider", "sms.ir");
      info.put("sandbox", sandbox);
      info.put("templateId", templateId);
      info.put("parameter", parameter);
      return info;
    }

    public Map<String, Object> status() {
      Response r = transport.request(
          "GET", "https://api.sms.ir/v1/credit", Map.of("accept", "application/json", "x-api-key", key), null);
      if (!r.ok() || r.body().path("status").asInt(0) != 1) throw failure(r);
      return Map.of("credit", r.body().path("data").asDouble());
    }
  }

  static final class Kavenegar extends SmsSender {
    private final String key;
    private final String template;
    private final Transport transport;

    Kavenegar(Map<String, String> env, Transport transport) {
      this.key = env.get("KAVENEGAR_API_KEY");
      this.template = env.getOrDefault("KAVENEGAR_TEMPLATE", "sandogh-login");
      this.transport = transport;
    }

    private static String enc(String s) {
      return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public void send(String phone, String code) {
      String url = "https://api.kavenegar.com/v1/" + key + "/verify/lookup.json?receptor=" + enc(phone) + "&token="
          + enc(code) + "&template=" + enc(template);
      Response r = transport.request("POST", url, Map.of(), null);
      if (!r.ok()) throw new IllegalStateException("Kavenegar responded " + r.status());
    }

    public Map<String, Object> info() {
      Map<String, Object> info = new LinkedHashMap<>();
      info.put("provider", "kavenegar");
      info.put("sandbox", false);
      info.put("template", template);
      return info;
    }

    public Map<String, Object> status() {
      Response r = transport.request("GET", "https://api.kavenegar.com/v1/" + key + "/account/info.json", Map.of(), null);
      if (!r.ok()) {
        throw new IllegalStateException(
            ("Kavenegar: " + r.status() + " " + text(r.body().path("return"), "message")).trim());
      }
      JsonNode credit = r.body().path("entries").path("remaincredit");
      return Map.of("credit", credit.isNumber() || credit.isString() ? credit.asDouble() : Double.NaN);
    }
  }
}
