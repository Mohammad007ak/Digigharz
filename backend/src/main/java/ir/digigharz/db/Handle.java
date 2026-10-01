package ir.digigharz.db;

import java.util.List;

/** Queries on the database or inside one transaction. SQL uses "?" placeholders. */
public interface Handle {
  /** "sqlite" or "postgres". */
  String kind();

  /** The first row, or null. */
  Row get(String sql, Object... params);

  List<Row> all(String sql, Object... params);

  /** The number of rows changed. */
  int run(String sql, Object... params);
}
