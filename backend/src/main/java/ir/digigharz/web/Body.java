package ir.digigharz.web;

import ir.digigharz.Json;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;

/** The parsed JSON body of an API request (an empty object when there was none). */
public record Body(JsonNode node) {
  static final String ATTRIBUTE = "digigharz.body";

  public static Body of(HttpServletRequest req) {
    Object b = req.getAttribute(ATTRIBUTE);
    return new Body(b instanceof JsonNode n ? n : Json.object());
  }

  /** The field, or null when missing or JSON null. */
  public JsonNode get(String key) {
    JsonNode v = node.isObject() ? node.get(key) : null;
    return v == null || v.isNull() ? null : v;
  }

  /** JavaScript's String(value ?? ""). */
  public String str(String key) {
    JsonNode v = get(key);
    if (v == null) return "";
    if (v.isString()) return v.asString();
    if (v.isNumber()) {
      double d = v.asDouble();
      return d == Math.rint(d) && Math.abs(d) < 1e21 ? Long.toString((long) d) : Double.toString(d);
    }
    if (v.isObject()) return "[object Object]";
    if (v.isArray()) {
      StringBuilder s = new StringBuilder();
      for (int i = 0; i < v.size(); i++) s.append(i > 0 ? "," : "").append(v.get(i).isNull() ? "" : v.get(i).isString() ? v.get(i).asString() : v.get(i).toString());
      return s.toString();
    }
    return v.toString();
  }

  /** The field if it is a string, else null. */
  public String text(String key) {
    JsonNode v = get(key);
    return v != null && v.isString() ? v.asString() : null;
  }

  /** Strictly the JSON value true. */
  public boolean isTrue(String key) {
    JsonNode v = get(key);
    return v != null && v.isBoolean() && v.asBoolean();
  }
}
