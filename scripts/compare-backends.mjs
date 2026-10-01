// Runs the same scenario against the original Node backend and this Java
// backend and reports every difference in their answers (status codes,
// fields, types and values), ignoring what is random by design: ids, hashes,
// timestamps, payment references and who won each lottery.
//
//   npm run build && (cd backend && mvn -q package -DskipTests)
//   node scripts/compare-backends.mjs <checkout of the Node version>
//
// The Node checkout needs its own `npm install`.
import { spawn } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const root = new URL("..", import.meta.url).pathname;
const nodeRoot = process.argv[2];
if (!nodeRoot) {
  console.error("usage: node scripts/compare-backends.mjs <checkout of the Node version>");
  process.exit(2);
}
const NODE_PORT = 3101;
const JAVA_PORT = 3102;

function launch(name, command, args, env, cwd = root) {
  const dir = mkdtempSync(join(tmpdir(), `compare-${name}-`));
  const child = spawn(command, args, {
    cwd,
    env: { ...process.env, DB_PATH: join(dir, "db.sqlite"), DIST_DIR: join(root, "dist"), ...env },
    stdio: ["ignore", "ignore", "inherit"],
  });
  return child;
}

async function waitFor(port) {
  for (let i = 0; i < 120; i++) {
    try {
      if ((await fetch(`http://127.0.0.1:${port}/api/health`)).ok) return;
    } catch {
      // Not up yet.
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`nothing answered on port ${port}`);
}

function client(port) {
  const cookies = new Map();
  return async (method, path, body, { raw = false, headers = {} } = {}) => {
    const response = await fetch(`http://127.0.0.1:${port}${path}`, {
      method,
      redirect: "manual",
      headers: {
        "content-type": "application/json",
        cookie: [...cookies].map(([k, v]) => `${k}=${v}`).join("; "),
        ...headers,
      },
      body: body === undefined ? undefined : typeof body === "string" ? body : JSON.stringify(body),
    });
    for (const c of response.headers.getSetCookie()) {
      const [kv] = c.split(";");
      const [k, v] = kv.split("=");
      if (v) cookies.set(k, v);
      else cookies.delete(k);
    }
    const text = await response.text();
    if (raw) {
      return {
        status: response.status,
        type: (response.headers.get("content-type") ?? "").split(";")[0],
        location: response.headers.get("location"),
        length: text.length,
      };
    }
    let json;
    try {
      json = JSON.parse(text);
    } catch {
      json = `<non-JSON ${response.headers.get("content-type")}>`;
    }
    return { status: response.status, body: json };
  };
}

// Values that differ between any two runs, by design.
const VOLATILE_KEYS = new Set([
  "id", "circleId", "checkoutId", "memberId", "winner", "winnerId", "anchor", "nonceDigest", "reveal", "previous",
  "seed", "nonce", "createdAt", "startedAt", "deadline", "at", "nextDrawAt", "joinedAt", "payoutRef", "claimBy",
  "dueAt", "drawAt", "lastPayDay", "ref", "fillMs", "averageMs", "devCode", "since", "storage", "builtAt", "drawId",
]);
// Who won which lottery is random, and so is everything that follows from it.
const LOTTERY_KEYS = new Set(["wonMonth", "winnerPosition", "position", "eligible", "owing"]);

function normalize(value, key, parent) {
  // Whose phone is on a lottery's draw and payout events depends on who won.
  if (key === "phone" && ["draw", "payout"].includes(parent?.kind)) return "<lottery>";
  if (LOTTERY_KEYS.has(key)) return "<lottery>";
  if (VOLATILE_KEYS.has(key)) return value === null ? null : `<${Array.isArray(value) ? "array" : typeof value}>`;
  if (Array.isArray(value)) return value.map((v) => normalize(v));
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((k) => [k, normalize(value[k], k, value)]));
  }
  return value;
}

const differences = [];
function compare(label, a, b, path = "") {
  if (typeof a !== typeof b || Array.isArray(a) !== Array.isArray(b) || (a === null) !== (b === null)) {
    differences.push(`${label}${path}: node ${JSON.stringify(a)} ≠ java ${JSON.stringify(b)}`);
    return;
  }
  if (Array.isArray(a)) {
    if (a.length !== b.length) differences.push(`${label}${path}: node has ${a.length} items, java ${b.length}`);
    for (let i = 0; i < Math.min(a.length, b.length); i++) compare(label, a[i], b[i], `${path}[${i}]`);
  } else if (a && typeof a === "object") {
    for (const k of new Set([...Object.keys(a), ...Object.keys(b)])) {
      if (!(k in a)) differences.push(`${label}${path}.${k}: only java has it (${JSON.stringify(b[k])})`);
      else if (!(k in b)) differences.push(`${label}${path}.${k}: only node has it (${JSON.stringify(a[k])})`);
      else compare(label, a[k], b[k], `${path}.${k}`);
    }
  } else if (a !== b) {
    differences.push(`${label}${path}: node ${JSON.stringify(a)} ≠ java ${JSON.stringify(b)}`);
  }
}

const phone = (i) => `0912000${String(i).padStart(3, "0")}9`;

// One scenario, written once, run against a backend; returns every answer.
async function scenario(port) {
  const answers = [];
  const record = async (label, promise) => answers.push([label, normalize(await promise)]);
  const anon = client(port);
  const users = Array.from({ length: 12 }, () => client(port));

  await record("health", anon("GET", "/api/health"));
  await record("me (signed out)", anon("GET", "/api/me"));
  await record("not json", anon("POST", "/api/auth/logout", "x", { headers: { "content-type": "text/plain" } }));
  await record("broken json", anon("POST", "/api/auth/logout", "{nope"));
  await record("unknown api", anon("GET", "/api/nothing"));
  await record("bad phone", anon("POST", "/api/auth/request-code", { phone: "123" }));
  await record("code", anon("POST", "/api/auth/request-code", { phone: "۰۹۱۲۰۰۰۰۰۸۸" }));
  await record("resend too soon", anon("POST", "/api/auth/request-code", { phone: "09120000088" }));
  await record("wrong code", anon("POST", "/api/auth/verify", { phone: "09120000088", code: "abcde" }));
  await record("bad token", anon("POST", "/api/auth/digipay", { token: "nope" }));
  for (let i = 0; i < 12; i++) await record(`sign in ${i}`, users[i]("POST", "/api/auth/digipay", { token: `sim-${phone(i + 1)}` }));
  const [u1] = users;
  await record("me", u1("GET", "/api/me"));
  await record("tour", u1("GET", "/api/me/tour"));
  await record("tour seen", u1("POST", "/api/me/tour", {}));
  await record("tour again", u1("GET", "/api/me/tour"));
  await record("plans", u1("GET", "/api/plans"));
  await record("eligibility", u1("GET", "/api/eligibility"));
  await record("join without accepting", u1("POST", "/api/circles/join", { planId: "p6-5" }));
  await record("join retired", u1("POST", "/api/circles/join", { planId: "p24-10", accept: true }));
  await record("join unknown", u1("POST", "/api/circles/join", { planId: "zzz", accept: true }));

  // Five members fill a six-seat plan (Digipay holds seat 1).
  let circleId;
  for (let i = 0; i < 5; i++) {
    const started = await users[i]("POST", "/api/circles/join", { planId: "p6-5", accept: true });
    answers.push([`join ${i}`, normalize(started)]);
    await record(`checkout ${i}`, users[i]("GET", `/api/checkouts/${started.body.checkoutId}`));
    const paid = await users[i]("POST", `/api/checkouts/${started.body.checkoutId}/complete`, { action: "pay" });
    answers.push([`pay ${i}`, normalize(paid)]);
    circleId = paid.body.circleId;
    if (i === 0) {
      await record("joined twice", users[0]("POST", "/api/circles/join", { planId: "p6-5", accept: true }));
      await record("forming view", u1("GET", `/api/circles/${circleId}`));
    }
  }
  // A sixth joins another plan and leaves while it forms.
  const other = await users[5]("POST", "/api/circles/join", { planId: "p12-10", accept: true });
  const otherCircle = (await users[5]("POST", `/api/checkouts/${other.body.checkoutId}/complete`, { action: "pay" })).body.circleId;
  await record("leave", users[5]("POST", `/api/circles/${otherCircle}/leave`, {}));
  // A cancelled payment seats nobody.
  const cancelled = await users[6]("POST", "/api/circles/join", { planId: "p12-5", accept: true });
  await record("cancel", users[6]("POST", `/api/checkouts/${cancelled.body.checkoutId}/complete`, { action: "cancel" }));
  await record("cancel again", users[6]("POST", `/api/checkouts/${cancelled.body.checkoutId}/complete`, { action: "pay" }));

  await record("active view", u1("GET", `/api/circles/${circleId}`));
  await record("stranger view", users[11]("GET", `/api/circles/${circleId}`));
  // Months 2–6: user 3 never pays; the others pay through the gateway.
  for (let m = 2; m <= 6; m++) {
    for (let i = 0; i < 5; i++) {
      if (i === 2) continue;
      const due = await users[i]("POST", `/api/circles/${circleId}/pay`, {});
      if (due.status === 201) {
        await record(`m${m} checkout ${i}`, users[i]("GET", `/api/checkouts/${due.body.checkoutId}`));
        await record(`m${m} pay ${i}`, users[i]("POST", `/api/checkouts/${due.body.checkoutId}/complete`, { action: "pay" }));
      } else answers.push([`m${m} nothing due ${i}`, normalize(due)]);
    }
    await record(`close month ${m}`, u1("POST", `/api/ops/circles/${circleId}/close-month`, {}));
    await record(`view after ${m}`, users[2]("GET", `/api/circles/${circleId}`));
  }
  await record("seen", u1("POST", `/api/circles/${circleId}/seen`, { month: 3 }));
  await record("my circles", u1("GET", "/api/circles"));
  await record("debtor's circles", users[2]("GET", "/api/circles"));
  const settle = await users[2]("POST", `/api/circles/${circleId}/pay`, {});
  answers.push(["settle", normalize(settle)]);
  await record("settle checkout", users[2]("GET", `/api/checkouts/${settle.body.checkoutId}`));
  await record("settle pay", users[2]("POST", `/api/checkouts/${settle.body.checkoutId}/complete`, { action: "pay" }));
  await record("final view", users[2]("GET", `/api/circles/${circleId}`));

  // A demo circle filled with simulated members.
  const demo = await users[7]("POST", "/api/circles/join", { planId: "p6-10", accept: true });
  const demoCircle = (await users[7]("POST", `/api/checkouts/${demo.body.checkoutId}/complete`, { action: "pay" })).body.circleId;
  await record("fill", users[7]("POST", `/api/ops/circles/${demoCircle}/fill`, {}));
  await record("fill again", users[7]("POST", `/api/ops/circles/${demoCircle}/fill`, {}));
  for (let m = 2; m <= 4; m++) await record(`demo close ${m}`, users[7]("POST", `/api/ops/circles/${demoCircle}/close-month`, {}));
  await record("demo view", users[7]("GET", `/api/circles/${demoCircle}`));

  // The admin panel (open to everyone in development).
  await record("admin me", u1("GET", "/api/admin/me"));
  await record("admin login (none set)", u1("POST", "/api/admin/login", { username: "a", password: "b" }));
  await record("overview", u1("GET", "/api/ops/overview"));
  await record("ops circles", u1("GET", "/api/ops/circles"));
  await record("ops circle", u1("GET", `/api/ops/circles/${circleId}`));
  await record("ops demo circle", u1("GET", `/api/ops/circles/${demoCircle}`));
  await record("ops missing circle", u1("GET", "/api/ops/circles/nope"));
  await record("debtors", u1("GET", "/api/ops/debtors"));
  await record("events", u1("GET", "/api/ops/events?limit=1000"));
  await record("draw events", u1("GET", "/api/ops/events?kind=draw&limit=3"));
  await record("sms status", u1("GET", "/api/ops/sms"));
  await record("sms test", u1("POST", "/api/ops/sms/test", { phone: phone(1) }));

  // A family fund. Member 2 already had this cycle's loan, so the draw can
  // only pick member 1 (the draw itself is random).
  const { currentMonthKey, addMonths } = await import(new URL("../src/lib/jalali.js", import.meta.url));
  const month = currentMonthKey();
  const data = {
    version: 1,
    fund: { name: "صندوق", contribution: 1000, loanAmount: 2000, installments: 3, startMonth: month },
    members: [
      { id: "m1", name: "یک", phone: phone(1), shares: 1, joinMonth: month },
      { id: "m2", name: "دو", phone: `+98 ${phone(2).slice(1)}`, shares: 1, joinMonth: month },
    ],
    payments: [
      { id: "p1", memberId: "m1", type: "contribution", month, amount: 1000 },
      { id: "p2", memberId: "m2", type: "contribution", month, amount: 3000 },
    ],
    loans: [
      { id: "l0", memberId: "m2", amount: 2000, installments: 3, cycle: 1, drawMonth: month, firstInstallmentMonth: addMonths(month, 1) },
    ],
  };
  const created = await u1("POST", "/api/funds", { data });
  answers.push(["fund created", normalize(created)]);
  const fundId = created.body.id;
  await record("invalid fund", u1("POST", "/api/funds", { data: { members: [] } }));
  await record("funds", u1("GET", "/api/funds"));
  await record("member's funds", users[1]("GET", "/api/funds"));
  await record("fund", u1("GET", `/api/funds/${fundId}`));
  await record("fund as member", users[1]("GET", `/api/funds/${fundId}`));
  await record("save", u1("PUT", `/api/funds/${fundId}`, { data, version: 1 }));
  await record("save stale", u1("PUT", `/api/funds/${fundId}`, { data, version: 1 }));
  const draw = await u1("POST", `/api/funds/${fundId}/draws`, {});
  answers.push(["draw", normalize(draw)]);
  await record("confirm", u1("POST", `/api/funds/${fundId}/draws/${draw.body.draw.id}/confirm`, {}));
  await record("confirm again", u1("POST", `/api/funds/${fundId}/draws/${draw.body.draw.id}/confirm`, {}));
  await record("member view", users[1]("GET", `/api/funds/${fundId}/view`));
  await record("manager view", u1("GET", `/api/funds/${fundId}/view`));
  await record("stranger fund view", users[11]("GET", `/api/funds/${fundId}/view`));
  await record("no more draws", u1("POST", `/api/funds/${fundId}/draws`, {}));
  await record("delete", u1("DELETE", `/api/funds/${fundId}`, {}));
  await record("logout", u1("POST", "/api/auth/logout", {}));
  await record("me after logout", u1("GET", "/api/me"));

  // The website.
  for (const path of ["/", "/app", "/app/x?y=1", "/welcome", "/welcome/", "/terms", "/guide/family-loan-fund",
    "/guide/family-loan-fund/", "/robots.txt", "/icon.svg", "/nope", "/.env"]) {
    await record(`page ${path}`, anon("GET", path, undefined, { raw: true }));
  }
  return answers;
}

const node = launch("node", "node", ["server/index.js"], { PORT: NODE_PORT }, nodeRoot);
const java = launch("java", "java", ["-jar", "backend/target/digigharz.jar"], { PORT: JAVA_PORT });
try {
  await Promise.all([waitFor(NODE_PORT), waitFor(JAVA_PORT)]);
  const [a, b] = [await scenario(NODE_PORT), await scenario(JAVA_PORT)];
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    if (a[i]?.[0] !== b[i]?.[0]) throw new Error(`scenarios diverged at step ${i}`);
    compare(a[i][0], a[i][1], b[i][1]);
  }
  console.log(`${a.length} requests compared.`);
  if (differences.length) {
    console.log(`${differences.length} differences:\n  ${differences.join("\n  ")}`);
    process.exitCode = 1;
  } else console.log("No differences.");
} finally {
  node.kill();
  java.kill();
}
