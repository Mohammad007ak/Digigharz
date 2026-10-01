package ir.digigharz.web;

import ir.digigharz.db.Db;
import ir.digigharz.digipay.Digipay;
import ir.digigharz.digipay.Simulator;
import ir.digigharz.sms.SmsSender;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/** Everything the server is built from; the production values come from {@link EnvConfig}. */
public class AppOptions {
  private static final SecureRandom SECURE = new SecureRandom();

  public Db db;
  /** Null: no SMS provider (dev mode shows codes on screen). */
  public SmsSender sendCode;
  public boolean production;
  /**
   * A public demo: production hosting, but login codes are shown on screen,
   * simulator logins work and anyone can use the ops tools. Never for real users.
   */
  public boolean demo;
  public LongSupplier now = System::currentTimeMillis;
  public DoubleSupplier random = () -> SECURE.nextLong(1L << 47) / (double) (1L << 47);
  public Digipay digipay = new Simulator();
  public List<String> opsPhones = List.of();
  /** Admin panel sign-in; without them the panel falls back to the phone rules. */
  public String adminUsername;
  public String adminPassword;
  public long formTimeoutMs = 60 * 60 * 1000;
  /** Debit wallets for unpaid shares (WALLET_DEBIT=true); off for now. */
  public boolean walletDebit;
  /** Gate joining on the credit score (SCORING_CHECK=true); off for now. */
  public boolean scoringCheck;
  /** Whether the database sits on a mounted disk (null when unknown). */
  public Boolean persistentStorage;
  /** Where the database is and which disks are mounted (shown in demos only). */
  public Map<String, Object> storageInfo;
  /** The built frontend (vite build's dist folder); null serves the API only. */
  public String dist;
  /** CONTACT_* settings for the terms page. */
  public Map<String, String> env = Map.of();
  /** When the image was built (the BUILT_AT file), shown by /api/health. */
  public String builtAt;
  public int port;
  /** Run due draws every minute, so they happen even when nobody opens the app. */
  public boolean scheduler;
}
