package ir.digigharz;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** JSON helpers; values stored as JSON text in the database go through here. */
public final class Json {
  private Json() {}

  public static final JsonMapper MAPPER = JsonMapper.builder().build();
  public static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  public static String stringify(Object value) {
    return MAPPER.writeValueAsString(value);
  }

  public static JsonNode parse(String text) {
    return MAPPER.readTree(text);
  }

  public static ObjectNode object() {
    return NODES.objectNode();
  }

  public static List<Object> parseList(String text) {
    List<Object> out = new ArrayList<>();
    for (JsonNode n : parse(text)) out.add(n.isIntegralNumber() ? (Object) n.asLong() : n.isNumber() ? (Object) n.asDouble() : n.asString());
    return out;
  }

  /** A JSON object as a plain map (for the "detail" of audit events). */
  public static Object toPlain(JsonNode node) {
    return MAPPER.convertValue(node, Object.class);
  }

  /** An insertion-ordered map from alternating keys and values. */
  public static Map<String, Object> obj(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }
}
