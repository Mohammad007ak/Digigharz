import { existsSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { openDatabase } from "./db.js";

// In a container, only a mounted disk survives a redeploy. Linux lists
// mounts in /proc/mounts; the database is safe if its folder, or a folder
// above it (other than /), is one of them. null when we can't tell.
function mountPoints() {
  try {
    return readFileSync("/proc/mounts", "utf8")
      .split("\n")
      .map((line) => line.split(" ")[1])
      .filter(Boolean);
  } catch {
    return null;
  }
}

function onMountedDisk(dbPath, mounts) {
  const dataDir = dirname(resolve(dbPath));
  // A folder on a different filesystem than / is a mounted disk, whether or
  // not the runtime lists it in /proc/mounts.
  try {
    if (statSync(dataDir).dev !== statSync("/").dev) return true;
  } catch {
    // Fall back to the mount list.
  }
  if (!mounts) return null;
  for (let dir = dataDir; dir !== "/"; dir = dirname(dir)) {
    if (mounts.includes(dir)) return true;
  }
  return false;
}

// When the data folder was first used. If this stays the same across a
// redeploy, the data survives it, whatever the mount checks say.
function dataCreatedAt(dbPath) {
  const marker = `${dirname(resolve(dbPath))}/.created-at`;
  try {
    if (!existsSync(marker)) writeFileSync(marker, new Date().toISOString());
    return readFileSync(marker, "utf8").trim();
  } catch {
    return null;
  }
}

// Mounts that could be a data disk (not the system's own), to show where a
// disk actually landed when it isn't where the database is.
const SYSTEM_MOUNTS = /^\/(proc|sys|dev|run|etc|var\/run)(\/|$)|^\/$/;
const dataMounts = (mounts) => (mounts ?? []).filter((m) => !SYSTEM_MOUNTS.test(m));
import { createSmsSender } from "./sms.js";
import { createApp } from "./app.js";
import { createDigipay } from "./digipay/index.js";

// Shared by the production server and the Vite dev middleware.
//
// DATABASE_URL (postgres://...) puts the data in PostgreSQL, which lives
// outside the app's container and survives every deploy. Without it the
// data is a SQLite file at DB_PATH, which only survives a deploy on a
// mounted disk.
export async function createAppFromEnv(env = process.env) {
  const production = env.NODE_ENV === "production";
  const postgres = Boolean(env.DATABASE_URL);
  const dbPath = env.DB_PATH ?? "data/sandogh.db";
  const db = await openDatabase(postgres ? env.DATABASE_URL : dbPath);
  const mounts = postgres ? null : mountPoints();
  const persistentStorage = postgres ? true : production ? onMountedDisk(dbPath, mounts) : null;
  if (persistentStorage === false) {
    console.warn(
      `⚠ The database (${resolve(dbPath)}) is not on a mounted disk: all data will be lost on the next deploy. ` +
        `Mount a persistent disk at ${dirname(resolve(dbPath))}, or point DB_PATH at the disk you mounted.`,
    );
  }
  const sendCode = createSmsSender(env);
  const opsPhones = (env.OPS_PHONES ?? "")
    .split(",")
    .map((p) => p.trim())
    .filter(Boolean);
  // How long a circle may wait to fill. The goal is under five minutes; the
  // default gives early launches an hour.
  const formTimeoutMs = Number(env.CIRCLE_FORM_TIMEOUT_MIN ?? 60) * 60 * 1000;
  const demo = env.DEMO_MODE === "true";
  return createApp({
    db,
    sendCode,
    production,
    demo,
    digipay: createDigipay(env),
    opsPhones,
    adminUsername: env.ADMIN_USERNAME || null,
    adminPassword: env.ADMIN_PASSWORD || null,
    formTimeoutMs,
    walletDebit: env.WALLET_DEBIT === "true",
    scoringCheck: env.SCORING_CHECK === "true",
    persistentStorage,
    storageInfo: postgres
      ? { database: "postgres", startedAt: new Date().toISOString() }
      : {
          database: "sqlite",
          dbPath: resolve(dbPath),
          mounts: dataMounts(mounts),
          dataCreatedAt: dataCreatedAt(dbPath),
          startedAt: new Date().toISOString(),
        },
  });
}
