package ir.digigharz.web;

import ir.digigharz.db.Db;
import ir.digigharz.digipay.Digipay;
import ir.digigharz.sms.SmsSender;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Builds the server from environment variables (the same ones as the Node
 * version), plus an optional .env file for local runs. Secrets (SMS keys, the
 * admin password, the database URL) belong only there or in the hosting
 * platform's environment settings, never in the code.
 *
 * <p>DATABASE_URL (postgres://...) puts the data in PostgreSQL, which lives
 * outside the app's container and survives every deploy. Without it the data
 * is a SQLite file at DB_PATH, which only survives a deploy on a mounted disk.
 */
public final class EnvConfig {
  private EnvConfig() {}

  /** The process environment over the values in .env (here or one folder up), if there is one. */
  public static Map<String, String> environment() {
    Map<String, String> env = new HashMap<>();
    Path file = Files.isRegularFile(Path.of(".env")) ? Path.of(".env") : Path.of("../.env");
    if (Files.isRegularFile(file)) {
      try {
        for (String line : Files.readAllLines(file)) {
          String l = line.strip();
          if (l.isEmpty() || l.startsWith("#") || !l.contains("=")) continue;
          if (l.startsWith("export ")) l = l.substring(7).strip();
          String key = l.substring(0, l.indexOf('=')).strip();
          String value = l.substring(l.indexOf('=') + 1).strip();
          if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
          }
          env.put(key, value);
        }
      } catch (IOException e) {
        System.err.println("Could not read .env: " + e.getMessage());
      }
    }
    env.putAll(System.getenv());
    return env;
  }

  public static AppOptions fromEnv(Map<String, String> env) {
    AppOptions o = new AppOptions();
    o.production = "production".equals(env.get("NODE_ENV")) || "production".equals(env.get("APP_ENV"));
    String databaseUrl = env.get("DATABASE_URL");
    boolean postgres = databaseUrl != null && !databaseUrl.isEmpty();
    String dbPath = env.getOrDefault("DB_PATH", "data/sandogh.db");
    o.db = Db.open(postgres ? databaseUrl : dbPath);
    List<String> mounts = postgres ? null : mountPoints();
    o.persistentStorage = postgres ? Boolean.TRUE : o.production ? onMountedDisk(dbPath, mounts) : null;
    if (Boolean.FALSE.equals(o.persistentStorage)) {
      Path dir = Path.of(dbPath).toAbsolutePath().getParent();
      System.err.printf("⚠ The database (%s) is not on a mounted disk: all data will be lost on the next deploy. "
          + "Mount a persistent disk at %s, or point DB_PATH at the disk you mounted.%n", Path.of(dbPath).toAbsolutePath(), dir);
    }
    o.sendCode = SmsSender.fromEnv(env);
    o.opsPhones = Arrays.stream(env.getOrDefault("OPS_PHONES", "").split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    // How long a circle may wait to fill. The goal is under five minutes; the
    // default gives early launches an hour.
    o.formTimeoutMs = (long) (Double.parseDouble(env.getOrDefault("CIRCLE_FORM_TIMEOUT_MIN", "60")) * 60 * 1000);
    o.demo = "true".equals(env.get("DEMO_MODE"));
    o.digipay = Digipay.fromEnv(env);
    o.adminUsername = emptyToNull(env.get("ADMIN_USERNAME"));
    o.adminPassword = emptyToNull(env.get("ADMIN_PASSWORD"));
    o.walletDebit = "true".equals(env.get("WALLET_DEBIT"));
    o.scoringCheck = "true".equals(env.get("SCORING_CHECK"));
    Map<String, Object> storage = new LinkedHashMap<>();
    if (postgres) {
      storage.put("database", "postgres");
    } else {
      storage.put("database", "sqlite");
      storage.put("dbPath", Path.of(dbPath).toAbsolutePath().toString());
      storage.put("mounts", dataMounts(mounts));
      storage.put("dataCreatedAt", dataCreatedAt(dbPath));
    }
    storage.put("startedAt", Instant.now().toString());
    o.storageInfo = storage;
    o.dist = env.getOrDefault("DIST_DIR", "dist");
    o.env = env;
    try {
      o.builtAt = Files.readString(Path.of(env.getOrDefault("BUILT_AT_FILE", "BUILT_AT"))).strip();
    } catch (IOException e) {
      o.builtAt = null; // Not a Docker build.
    }
    o.port = Integer.parseInt(env.getOrDefault("PORT", "3000"));
    o.scheduler = true;
    return o;
  }

  private static String emptyToNull(String s) {
    return s == null || s.isEmpty() ? null : s;
  }

  // In a container, only a mounted disk survives a redeploy. Linux lists
  // mounts in /proc/mounts; the database is safe if its folder, or a folder
  // above it (other than /), is one of them. null when we can't tell.
  static List<String> mountPoints() {
    try {
      List<String> mounts = new ArrayList<>();
      for (String line : Files.readAllLines(Path.of("/proc/mounts"))) {
        String[] parts = line.split(" ");
        if (parts.length > 1 && !parts[1].isEmpty()) mounts.add(parts[1]);
      }
      return mounts;
    } catch (IOException e) {
      return null;
    }
  }

  static Boolean onMountedDisk(String dbPath, List<String> mounts) {
    Path dataDir = Path.of(dbPath).toAbsolutePath().getParent();
    // A folder on a different filesystem than / is a mounted disk, whether or
    // not the runtime lists it in /proc/mounts.
    try {
      Object dev = Files.getAttribute(dataDir, "unix:dev");
      if (!dev.equals(Files.getAttribute(Path.of("/"), "unix:dev"))) return true;
    } catch (Exception e) {
      // Fall back to the mount list.
    }
    if (mounts == null) return null;
    for (Path dir = dataDir; dir != null && !dir.toString().equals("/"); dir = dir.getParent()) {
      if (mounts.contains(dir.toString())) return true;
    }
    return false;
  }

  // When the data folder was first used. If this stays the same across a
  // redeploy, the data survives it, whatever the mount checks say.
  static String dataCreatedAt(String dbPath) {
    Path marker = Path.of(dbPath).toAbsolutePath().getParent().resolve(".created-at");
    try {
      if (!Files.exists(marker)) Files.writeString(marker, Instant.now().toString());
      return Files.readString(marker).strip();
    } catch (IOException e) {
      return null;
    }
  }

  // Mounts that could be a data disk (not the system's own).
  private static final Pattern SYSTEM_MOUNTS = Pattern.compile("^/(proc|sys|dev|run|etc|var/run)(/|$)|^/$");

  static List<String> dataMounts(List<String> mounts) {
    return mounts == null ? List.of() : mounts.stream().filter(m -> !SYSTEM_MOUNTS.matcher(m).find()).toList();
  }
}
