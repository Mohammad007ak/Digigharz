package ir.digigharz.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * The database, behind one small interface so the same code runs on SQLite
 * (development, tests, a single server with a disk) and on PostgreSQL
 * (hosting where the app's own disk doesn't survive a deploy). Same schema
 * and SQL as the Node version.
 *
 * <p>Inside {@link #transaction} use only the handle it passes in.
 */
public abstract class Db implements Handle, AutoCloseable {
  public abstract <T> T transaction(Function<Handle, T> fn);

  /** A postgres:// URL opens PostgreSQL; anything else is a SQLite file path (or ":memory:"). */
  public static Db open(String target) {
    return open(target, null);
  }

  public static Db open(String target, String schema) {
    return target.matches("^postgres(ql)?://.*") ? new Postgres(target, schema) : new Sqlite(target);
  }

  static List<String> schemaStatements() {
    try (InputStream in = Db.class.getResourceAsStream("/schema.sql")) {
      String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      StringBuilder code = new StringBuilder();
      for (String line : sql.split("\n")) if (!line.trim().startsWith("--")) code.append(line).append('\n');
      return Arrays.stream(code.toString().split(";")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---------- JDBC plumbing ----------

  static Object normalize(Object v) {
    if (v instanceof Integer || v instanceof Short || v instanceof Byte) return ((Number) v).longValue();
    if (v instanceof BigInteger b) return b.longValue();
    if (v instanceof BigDecimal d) {
      return d.stripTrailingZeros().scale() <= 0 ? (Object) d.longValue() : (Object) d.doubleValue();
    }
    if (v instanceof Float f) return f.doubleValue();
    if (v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 9.007199254740992E15) return d.longValue();
    return v;
  }

  static PreparedStatement prepare(Connection c, String sql, Object[] params) throws SQLException {
    PreparedStatement ps = c.prepareStatement(sql);
    for (int i = 0; i < params.length; i++) {
      Object p = params[i];
      if (p instanceof Boolean b) p = b ? 1L : 0L;
      if (p instanceof Integer n) p = n.longValue();
      if (p == null) ps.setNull(i + 1, java.sql.Types.NULL);
      else ps.setObject(i + 1, p);
    }
    return ps;
  }

  static List<Row> query(Connection c, String sql, Object[] params) {
    try (PreparedStatement ps = prepare(c, sql, params);
        ResultSet rs = ps.executeQuery()) {
      ResultSetMetaData meta = rs.getMetaData();
      List<Row> rows = new ArrayList<>();
      while (rs.next()) {
        Row row = new Row();
        for (int i = 1; i <= meta.getColumnCount(); i++) row.put(meta.getColumnLabel(i), normalize(rs.getObject(i)));
        rows.add(row);
      }
      return rows;
    } catch (SQLException e) {
      throw new DbException(e, sql);
    }
  }

  static int update(Connection c, String sql, Object[] params) {
    try (PreparedStatement ps = prepare(c, sql, params)) {
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new DbException(e, sql);
    }
  }

  public static class DbException extends RuntimeException {
    DbException(SQLException cause, String sql) {
      super(cause.getMessage() + " in: " + sql.replaceAll("\\s+", " ").trim(), cause);
    }
  }

  record OnConnection(String kind, Connection c) implements Handle {
    public Row get(String sql, Object... params) {
      List<Row> rows = query(c, sql, params);
      return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Row> all(String sql, Object... params) {
      return query(c, sql, params);
    }

    public int run(String sql, Object... params) {
      return update(c, sql, params);
    }
  }

  // ---------- SQLite ----------

  /** One connection, so statements and transactions take turns. */
  static final class Sqlite extends Db {
    private final Connection connection;
    private final Handle handle;
    private final ReentrantLock lock = new ReentrantLock(true);

    Sqlite(String path) {
      try {
        if (!path.equals(":memory:")) {
          Path parent = Path.of(path).toAbsolutePath().getParent();
          if (parent != null) Files.createDirectories(parent);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement st = connection.createStatement()) {
          st.execute("PRAGMA journal_mode = WAL");
          st.execute("PRAGMA foreign_keys = ON");
          st.execute("PRAGMA busy_timeout = 5000");
        }
        handle = new OnConnection("sqlite", connection);
        dropPreReleaseCircleTables();
        for (String sql : schemaStatements()) handle.run(sql);
      } catch (SQLException | IOException e) {
        throw new IllegalStateException("Can't open SQLite at " + path + ": " + e.getMessage(), e);
      }
    }

    // The circle tables changed shape before release. They only ever held
    // simulator data, so an old copy is dropped and rebuilt.
    private void dropPreReleaseCircleTables() {
      List<Row> columns = handle.all("PRAGMA table_info(circle_members)");
      if (columns.isEmpty() || columns.stream().anyMatch(c -> "seen_month".equals(c.str("name")))) return;
      for (String t : List.of("checkouts", "circle_draws", "contributions", "circle_members", "circles")) {
        handle.run("DROP TABLE IF EXISTS " + t);
      }
    }

    public String kind() {
      return "sqlite";
    }

    private <T> T locked(java.util.function.Supplier<T> fn) {
      lock.lock();
      try {
        return fn.get();
      } finally {
        lock.unlock();
      }
    }

    public Row get(String sql, Object... params) {
      return locked(() -> handle.get(sql, params));
    }

    public List<Row> all(String sql, Object... params) {
      return locked(() -> handle.all(sql, params));
    }

    public int run(String sql, Object... params) {
      return locked(() -> handle.run(sql, params));
    }

    public <T> T transaction(Function<Handle, T> fn) {
      return locked(() -> {
        try {
          connection.setAutoCommit(false);
          try {
            T result = fn.apply(handle);
            connection.commit();
            return result;
          } catch (RuntimeException | Error e) {
            connection.rollback();
            throw e;
          } finally {
            connection.setAutoCommit(true);
          }
        } catch (SQLException e) {
          throw new DbException(e, "BEGIN/COMMIT");
        }
      });
    }

    public void close() throws SQLException {
      connection.close();
    }
  }

  // ---------- PostgreSQL ----------

  static final class Postgres extends Db {
    private final HikariDataSource pool;

    Postgres(String url, String schema) {
      URI uri = URI.create(url);
      HikariConfig config = new HikariConfig();
      String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
      config.setJdbcUrl("jdbc:postgresql://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + uri.getRawPath() + query);
      if (uri.getRawUserInfo() != null) {
        String[] user = uri.getRawUserInfo().split(":", 2);
        config.setUsername(java.net.URLDecoder.decode(user[0], StandardCharsets.UTF_8));
        if (user.length > 1) config.setPassword(java.net.URLDecoder.decode(user[1], StandardCharsets.UTF_8));
      }
      config.setMaximumPoolSize(10);
      config.setMinimumIdle(1);
      if (schema != null) config.setSchema(schema);
      // The database may still be starting when the app does (a fresh deploy,
      // a restarted service): keep trying for about a minute before giving up.
      config.setInitializationFailTimeout(-1);
      pool = new HikariDataSource(config);
      for (int attempt = 1; ; attempt++) {
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
          if (schema != null) st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
          for (String sql : schemaStatements()) st.execute(sql);
          break;
        } catch (SQLException e) {
          if (attempt >= 12) throw new IllegalStateException("Can't reach PostgreSQL: " + e.getMessage(), e);
          System.err.printf("PostgreSQL not ready (%s); retrying in 5s (%d/12)%n", e.getMessage(), attempt);
          try {
            Thread.sleep(5000);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ie);
          }
        }
      }
    }

    DataSource dataSource() {
      return pool;
    }

    public String kind() {
      return "postgres";
    }

    private <T> T with(Function<Handle, T> fn) {
      try (Connection c = pool.getConnection()) {
        return fn.apply(new OnConnection("postgres", c));
      } catch (SQLException e) {
        throw new DbException(e, "connect");
      }
    }

    public Row get(String sql, Object... params) {
      return with(h -> h.get(sql, params));
    }

    public List<Row> all(String sql, Object... params) {
      return with(h -> h.all(sql, params));
    }

    public int run(String sql, Object... params) {
      return with(h -> h.run(sql, params));
    }

    public <T> T transaction(Function<Handle, T> fn) {
      try (Connection c = pool.getConnection()) {
        c.setAutoCommit(false);
        try {
          T result = fn.apply(new OnConnection("postgres", c));
          c.commit();
          return result;
        } catch (RuntimeException | Error e) {
          try {
            c.rollback();
          } catch (SQLException ignored) {
            // The original error matters more.
          }
          throw e;
        } finally {
          c.setAutoCommit(true);
        }
      } catch (SQLException e) {
        throw new DbException(e, "BEGIN/COMMIT");
      }
    }

    public void close() {
      pool.close();
    }
  }
}
