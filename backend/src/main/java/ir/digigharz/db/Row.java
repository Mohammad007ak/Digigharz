package ir.digigharz.db;

import java.util.LinkedHashMap;

/** One result row, keyed by column name. Whole numbers are always Long. */
public class Row extends LinkedHashMap<String, Object> {
  public String str(String key) {
    Object v = get(key);
    return v == null ? null : v.toString();
  }

  public Long lng(String key) {
    Object v = get(key);
    return v == null ? null : ((Number) v).longValue();
  }

  /** The value as a long, 0 when null (like COUNT/COALESCE results). */
  public long n(String key) {
    Long v = lng(key);
    return v == null ? 0 : v;
  }

  /** SQLite and Postgres flags are 0/1 integers. */
  public boolean flag(String key) {
    return n(key) != 0;
  }
}
