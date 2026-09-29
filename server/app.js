import express from "express";
import { readFileSync } from "node:fs";
import { createHash, randomBytes, randomInt } from "node:crypto";
import { HttpError } from "./errors.js";
import { createCircleService } from "./circles.js";
import { mountCircleRoutes } from "./circle-routes.js";
import { createOpsReports } from "./ops.js";
import { createAdminAuth } from "./admin-auth.js";
import { createDigipaySimulator } from "./digipay/simulator.js";
import { normalizePhone } from "../src/lib/phone.js";
import { currentMonthKey } from "../src/lib/jalali.js";
import {
  createLoan,
  fundBalance,
  isValidState,
  listDues,
  loanProgress,
  lotteryEntries,
  newId,
  overdueDues,
  pickWinner,
  sum,
} from "../src/lib/fund.js";

const MINUTE = 60 * 1000;
const CODE_TTL = 2 * MINUTE;
const RESEND_GAP = MINUTE;
const SEND_WINDOW = 60 * MINUTE;
const MAX_SENDS_PER_WINDOW = 5;
const MAX_CODE_ATTEMPTS = 5;
const SESSION_TTL = 30 * 24 * 60 * MINUTE;
const MAX_FUNDS_PER_OWNER = 20;

const sha256 = (value) => createHash("sha256").update(value).digest("hex");
const secureRandom = () => randomInt(0, 2 ** 47) / 2 ** 47;
const toLatinDigits = (value) => String(value ?? "").replace(/[۰-۹]/g, (d) => "۰۱۲۳۴۵۶۷۸۹".indexOf(d));

function parseCookies(header = "") {
  return Object.fromEntries(
    header
      .split(";")
      .map((part) => part.trim().split("="))
      .filter(([key, value]) => key && value)
      .map(([key, value]) => [key, decodeURIComponent(value)]),
  );
}

export function createApp({
  db,
  sendCode = null,
  production = false,
  // A public demo: production hosting, but login codes are shown on screen,
  // simulator logins work and anyone can use the ops tools. Never for real users.
  demo = false,
  now = Date.now,
  random = secureRandom,
  digipay = createDigipaySimulator(),
  opsPhones = [],
  // Admin panel sign-in; without them the panel falls back to the phone
  // rules below (open in development and demos, OPS_PHONES in production).
  adminUsername = null,
  adminPassword = null,
  formTimeoutMs,
  // Debit wallets for unpaid shares (WALLET_DEBIT=true); off for now.
  walletDebit = false,
  // Gate joining on the credit score (SCORING_CHECK=true); off for now.
  scoringCheck = false,
  // Whether the database sits on a mounted disk (null when unknown).
  persistentStorage = null,
  // Where the database is and which disks are mounted (shown in demos only).
  storageInfo = null,
}) {
  // The simulator signs anyone in as any phone ("sim-<phone>") and moves no
  // real money, so a production server may only run it as an explicit demo.
  if (production && !demo && digipay.name === "simulator") {
    throw new Error(
      "Refusing to start: production needs DIGIPAY_MODE=live, or DEMO_MODE=true for a public demo on the simulator.",
    );
  }

  if (production && !demo && sendCode?.sandbox) {
    throw new Error(
      "Refusing to start: an SMS sandbox key shows login codes on screen; use it only with DEMO_MODE=true.",
    );
  }

  const app = express();
  // Behind the hosting platform's HTTPS proxy.
  if (production) app.set("trust proxy", 1);
  app.use(express.json({ limit: "2mb" }));

  // Mutating requests must be JSON: a cross-site form post can't send that
  // without a CORS preflight, which together with SameSite cookies blocks CSRF.
  app.use("/api", (req, res, next) => {
    if (["POST", "PUT", "PATCH", "DELETE"].includes(req.method) && !req.is("application/json")) {
      return res.status(415).json({ error: "درخواست نامعتبر است." });
    }
    next();
  });

  const cookieOptions = {
    httpOnly: true,
    sameSite: "lax",
    secure: production,
    path: "/",
  };

  async function currentPhone(req) {
    const token = parseCookies(req.headers.cookie).sid;
    if (!token) return null;
    const session = await db.get(
      "SELECT phone FROM sessions WHERE token_hash = ? AND expires_at > ?",
      sha256(token),
      now(),
    );
    return session?.phone ?? null;
  }

  async function requireLogin(req, res, next) {
    try {
      req.phone = await currentPhone(req);
    } catch (error) {
      return next(error);
    }
    if (!req.phone) return res.status(401).json({ error: "ابتدا وارد شوید." });
    next();
  }

  async function loadFund(id) {
    const row = await db.get("SELECT * FROM funds WHERE id = ?", id);
    if (!row) throw new HttpError(404, "صندوق پیدا نشد.");
    return { ...row, data: JSON.parse(row.data) };
  }

  // Report "not found" to non-managers so fund ids can't be probed.
  async function loadManagedFund(req) {
    const fund = await loadFund(req.params.id);
    if (fund.owner_phone !== req.phone) throw new HttpError(404, "صندوق پیدا نشد.");
    return fund;
  }

  async function syncMembers(tx, fundId, data) {
    await tx.run("DELETE FROM fund_members WHERE fund_id = ?", fundId);
    if (data.demo) return;
    for (const member of data.members) {
      const phone = normalizePhone(member.phone);
      if (phone)
        await tx.run("INSERT INTO fund_members (fund_id, member_id, phone) VALUES (?, ?, ?)", fundId, member.id, phone);
    }
  }

  // Saves only if nobody else saved since `version` was read.
  async function writeFund(tx, id, data, version) {
    const { changes } = await tx.run(
      "UPDATE funds SET data = ?, version = ?, updated_at = ? WHERE id = ? AND version = ?",
      JSON.stringify(data),
      version + 1,
      now(),
      id,
      version,
    );
    if (!changes) throw new HttpError(409, "این صندوق در جای دیگری تغییر کرده است.");
    await syncMembers(tx, id, data);
  }

  function validData(data) {
    if (!isValidState(data)) throw new HttpError(400, "اطلاعات صندوق معتبر نیست.");
    return { ...data, fund: { cycle: 1, ...data.fund } };
  }

  async function startSession(res, phone) {
    const token = randomBytes(32).toString("base64url");
    await db.transaction(async (tx) => {
      await tx.run("DELETE FROM sessions WHERE expires_at <= ?", now());
      await tx.run(
        "INSERT INTO sessions (token_hash, phone, expires_at) VALUES (?, ?, ?)",
        sha256(token),
        phone,
        now() + SESSION_TTL,
      );
    });
    res.cookie("sid", token, { ...cookieOptions, maxAge: SESSION_TTL });
  }

  const route = (handler) => async (req, res, next) => {
    try {
      await handler(req, res);
    } catch (error) {
      next(error);
    }
  };

  // ---------- auth ----------

  app.post(
    "/api/auth/request-code",
    route(async (req, res) => {
      const phone = normalizePhone(req.body.phone);
      if (!phone) throw new HttpError(400, "شماره موبایل معتبر نیست.");

      const t = now();
      const previous = await db.get("SELECT * FROM otp_codes WHERE phone = ?", phone);
      const sameWindow = previous && t - previous.window_start < SEND_WINDOW;
      const sentInWindow = sameWindow ? previous.sent_in_window : 0;
      if (sentInWindow >= MAX_SENDS_PER_WINDOW) {
        throw new HttpError(429, "تعداد درخواست‌ها زیاد است. یک ساعت دیگر تلاش کنید.");
      }
      if (previous && previous.expires_at - CODE_TTL + RESEND_GAP > t) {
        throw new HttpError(429, "لطفاً یک دقیقه صبر کنید و دوباره تلاش کنید.");
      }
      if (!sendCode && production && !demo) throw new HttpError(500, "سرویس پیامک تنظیم نشده است.");

      const code = String(randomInt(0, 100000)).padStart(5, "0");
      await db.run(
        `INSERT INTO otp_codes (phone, code_hash, expires_at, attempts, window_start, sent_in_window)
         VALUES (?, ?, ?, 0, ?, ?)
         ON CONFLICT (phone) DO UPDATE SET code_hash = excluded.code_hash, expires_at = excluded.expires_at,
           attempts = 0, window_start = excluded.window_start, sent_in_window = excluded.sent_in_window`,
        phone,
        sha256(code),
        t + CODE_TTL,
        sameWindow ? previous.window_start : t,
        sentInWindow + 1,
      );

      if (sendCode) {
        try {
          await sendCode(phone, code);
        } catch (error) {
          console.error("SMS send failed:", error.message);
          throw new HttpError(502, "ارسال پیامک ناموفق بود. دوباره تلاش کنید.");
        }
        // SMS.ir's sandbox accepts the request but delivers nothing, so the
        // code is shown on screen as in dev mode.
        return res.json(sendCode.sandbox ? { ok: true, devCode: code } : { ok: true });
      }
      console.log(`[dev] login code for ${phone}: ${code}`);
      res.json({ ok: true, devCode: code });
    }),
  );

  app.post(
    "/api/auth/verify",
    route(async (req, res) => {
      const phone = normalizePhone(req.body.phone);
      const code = toLatinDigits(req.body.code).trim();
      const row = phone && (await db.get("SELECT * FROM otp_codes WHERE phone = ?", phone));
      if (!row || row.expires_at <= now()) {
        throw new HttpError(400, "کد منقضی شده است. دوباره درخواست کنید.");
      }
      if (row.attempts >= MAX_CODE_ATTEMPTS) {
        throw new HttpError(429, "تعداد تلاش‌ها زیاد بود. کد جدید درخواست کنید.");
      }
      if (sha256(code) !== row.code_hash) {
        await db.run("UPDATE otp_codes SET attempts = attempts + 1 WHERE phone = ?", phone);
        throw new HttpError(400, "کد وارد شده اشتباه است.");
      }

      // Keep the send counter so verifying doesn't reset the rate limit.
      // Spend the code in the same step that checks it, so it works only once.
      const { changes } = await db.run(
        "UPDATE otp_codes SET code_hash = '', expires_at = 0 WHERE phone = ? AND code_hash = ?",
        phone,
        row.code_hash,
      );
      if (!changes) throw new HttpError(400, "کد منقضی شده است. دوباره درخواست کنید.");
      await startSession(res, phone);
      res.json({ phone });
    }),
  );

  // Inside the Digipay app the user is already signed in; the host hands us
  // a launch token that Digipay vouches for, so no SMS code is needed.
  app.post(
    "/api/auth/digipay",
    route(async (req, res) => {
      const identity = await digipay.identity.verifyLaunchToken(req.body.token);
      const phone = identity && normalizePhone(identity.phone);
      if (!phone) throw new HttpError(401, "ورود از طریق دیجی‌پی تأیید نشد.");
      await startSession(res, phone);
      res.json({ phone });
    }),
  );

  app.post(
    "/api/auth/logout",
    route(async (req, res) => {
      const token = parseCookies(req.headers.cookie).sid;
      if (token) await db.run("DELETE FROM sessions WHERE token_hash = ?", sha256(token));
      res.clearCookie("sid", cookieOptions);
      res.json({ ok: true });
    }),
  );

  app.get("/api/me", requireLogin, (req, res) => res.json({ phone: req.phone }));

  // The first-visit tour: shown once per account, right after signing in.
  app.get(
    "/api/me/tour",
    requireLogin,
    route(async (req, res) =>
      res.json({ seen: Boolean(await db.get("SELECT 1 AS x FROM tour_seen WHERE phone = ?", req.phone)) }),
    ),
  );
  app.post(
    "/api/me/tour",
    requireLogin,
    route(async (req, res) => {
      await db.run(
        "INSERT INTO tour_seen (phone, seen_at) VALUES (?, ?) ON CONFLICT (phone) DO NOTHING",
        req.phone,
        now(),
      );
      res.json({ ok: true });
    }),
  );

  // For the hosting platform's health checks, and to see which build is live.
  let builtAt = null;
  try {
    builtAt = readFileSync(new URL("../BUILT_AT", import.meta.url), "utf8").trim();
  } catch {
    // Not a Docker build.
  }
  app.get(
    "/api/health",
    route(async (req, res) => {
      await db.get("SELECT 1 AS ok");
      res.json({
        ok: true,
        demo,
        builtAt,
        persistentStorage,
        ...(demo || !production ? { storage: storageInfo } : {}),
      });
    }),
  );

  // ---------- SMS status (admin panel) ----------

  // Who may use the admin panel and the simulator tools:
  // - a signed-in admin (username/password), always;
  // - with no admin account configured, members by phone as before: anyone
  //   in development and demos, OPS_PHONES in production.
  // The simulator buttons members see in a demo (filling a waiting group,
  // running a draw early) stay open to them either way.
  const isOps = (phone) => (production && !demo ? opsPhones.includes(phone) : true);
  const admin = createAdminAuth({
    db,
    username: adminUsername,
    password: adminPassword,
    production,
    now,
    parseCookies,
  });
  const allow = (check) => async (req, res, next) => {
    try {
      req.admin = await admin.currentAdmin(req);
      if (!req.admin) req.phone = await currentPhone(req);
    } catch (error) {
      return next(error);
    }
    if (req.admin || check(req.phone)) return next();
    res.status(401).json({ error: "ورود مدیر لازم است.", adminLogin: true });
  };
  const requireAdmin = allow((phone) => !admin.configured && phone && isOps(phone));
  const requireSimulatorOps = allow((phone) => phone && isOps(phone));

  app.get(
    "/api/admin/me",
    route(async (req, res) => {
      const username = await admin.currentAdmin(req);
      const phone = username ? null : await currentPhone(req);
      res.json({
        configured: admin.configured,
        username,
        // Without an admin account, a phone with ops access gets in too.
        allowed: Boolean(username || (!admin.configured && phone && isOps(phone))),
      });
    }),
  );

  app.post(
    "/api/admin/login",
    route(async (req, res) => res.json(await admin.login(req, res))),
  );

  app.post(
    "/api/admin/logout",
    route(async (req, res) => {
      await admin.logout(req, res);
      res.json({ ok: true });
    }),
  );

  app.get(
    "/api/ops/sms",
    requireAdmin,
    route(async (req, res) => {
      if (!sendCode) return res.json({ configured: false });
      const info = sendCode.info ?? {};
      try {
        res.json({ configured: true, ...info, ...(await sendCode.status()) });
      } catch (error) {
        res.json({ configured: true, ...info, error: error.message });
      }
    }),
  );

  // Sends a real code message to a number, so the operator can see the
  // whole path work (or read the provider's exact error).
  app.post(
    "/api/ops/sms/test",
    requireAdmin,
    route(async (req, res) => {
      if (!sendCode) throw new HttpError(400, "سرویس پیامک تنظیم نشده است.");
      const phone = normalizePhone(req.body.phone);
      if (!phone) throw new HttpError(400, "شماره موبایل معتبر نیست.");
      const code = String(randomInt(0, 100000)).padStart(5, "0");
      try {
        await sendCode(phone, code);
      } catch (error) {
        console.error("SMS test failed:", error.message);
        return res.json({ ok: false, error: error.message });
      }
      res.json({ ok: true, code, sandbox: Boolean(sendCode.sandbox) });
    }),
  );

  // ---------- funds (manager) ----------

  app.get(
    "/api/funds",
    requireLogin,
    route(async (req, res) => {
      const managed = (
        await db.all("SELECT id, data FROM funds WHERE owner_phone = ? ORDER BY created_at", req.phone)
      ).map((row) => {
        const data = JSON.parse(row.data);
        const late = new Set(overdueDues(data, currentMonthKey(new Date(now()))).map((d) => d.memberId));
        return {
          id: row.id,
          name: data.fund.name,
          members: data.members.length,
          balance: fundBalance(data),
          lateMembers: late.size,
        };
      });
      const member = (
        await db.all(
          `SELECT DISTINCT f.id, f.data, f.created_at FROM fund_members m JOIN funds f ON f.id = m.fund_id
           WHERE m.phone = ? AND f.owner_phone != ? ORDER BY f.created_at`,
          req.phone,
          req.phone,
        )
      ).map((row) => ({ id: row.id, name: JSON.parse(row.data).fund.name }));
      res.json({ managed, member });
    }),
  );

  app.post(
    "/api/funds",
    requireLogin,
    route(async (req, res) => {
      const data = validData(req.body.data);
      const { count } = await db.get("SELECT COUNT(*) AS count FROM funds WHERE owner_phone = ?", req.phone);
      if (count >= MAX_FUNDS_PER_OWNER) throw new HttpError(400, "به سقف تعداد صندوق رسیده‌اید.");

      const id = newId();
      await db.transaction(async (tx) => {
        await tx.run(
          "INSERT INTO funds (id, owner_phone, data, version, created_at, updated_at) VALUES (?, ?, ?, 1, ?, ?)",
          id,
          req.phone,
          JSON.stringify(data),
          now(),
          now(),
        );
        await syncMembers(tx, id, data);
      });
      res.status(201).json({ id, data, version: 1 });
    }),
  );

  app.get(
    "/api/funds/:id",
    requireLogin,
    route(async (req, res) => {
      const fund = await loadManagedFund(req);
      res.json({ id: fund.id, data: fund.data, version: fund.version });
    }),
  );

  app.put(
    "/api/funds/:id",
    requireLogin,
    route(async (req, res) => {
      const fund = await loadManagedFund(req);
      if (req.body.version !== fund.version) {
        throw new HttpError(409, "این صندوق در جای دیگری تغییر کرده است.", {
          data: fund.data,
          version: fund.version,
        });
      }
      const data = validData(req.body.data);
      try {
        await db.transaction((tx) => writeFund(tx, fund.id, data, fund.version));
      } catch (error) {
        if (error.status !== 409) throw error;
        const latest = await loadFund(fund.id);
        throw new HttpError(409, error.message, { data: latest.data, version: latest.version });
      }
      res.json({ version: fund.version + 1 });
    }),
  );

  app.delete(
    "/api/funds/:id",
    requireLogin,
    route(async (req, res) => {
      const fund = await loadManagedFund(req);
      await db.transaction(async (tx) => {
        await tx.run("DELETE FROM draws WHERE fund_id = ?", fund.id);
        await tx.run("DELETE FROM fund_members WHERE fund_id = ?", fund.id);
        await tx.run("DELETE FROM funds WHERE id = ?", fund.id);
      });
      res.json({ ok: true });
    }),
  );

  // ---------- lottery ----------
  // Draws happen on the server with a secure random source and are logged,
  // so a manager can't quietly re-roll until a friend wins.

  app.post(
    "/api/funds/:id/draws",
    requireLogin,
    route(async (req, res) => {
      const fund = await loadManagedFund(req);
      const entries = lotteryEntries(fund.data);
      if (!entries.length) throw new HttpError(400, "همه‌ی اعضا در این دور وام گرفته‌اند.");
      if (fundBalance(fund.data) < fund.data.fund.loanAmount) {
        throw new HttpError(400, "موجودی صندوق برای یک وام کافی نیست.");
      }

      const winner = pickWinner(entries, random);
      const draw = {
        id: newId(),
        month: currentMonthKey(new Date(now())),
        winnerId: winner.id,
        winnerName: winner.name,
      };
      await db.transaction(async (tx) => {
        await tx.run(
          "UPDATE draws SET status = 'cancelled', resolved_at = ? WHERE fund_id = ? AND status = 'pending'",
          now(),
          fund.id,
        );
        await tx.run(
          `INSERT INTO draws (id, fund_id, month, winner_member_id, winner_name, entries, status, created_at)
           VALUES (?, ?, ?, ?, ?, ?, 'pending', ?)`,
          draw.id,
          fund.id,
          draw.month,
          winner.id,
          winner.name,
          JSON.stringify(entries.map((e) => ({ memberId: e.member.id, name: e.member.name, tickets: e.tickets }))),
          now(),
        );
      });
      res.status(201).json({ draw });
    }),
  );

  async function loadPendingDraw(fundId, drawId) {
    const draw = await db.get("SELECT * FROM draws WHERE id = ? AND fund_id = ?", drawId, fundId);
    if (!draw) throw new HttpError(404, "قرعه پیدا نشد.");
    if (draw.status !== "pending") throw new HttpError(400, "این قرعه قبلاً نهایی شده است.");
    return draw;
  }

  app.post(
    "/api/funds/:id/draws/:drawId/confirm",
    requireLogin,
    route(async (req, res) => {
      const fund = await loadManagedFund(req);
      const draw = await loadPendingDraw(fund.id, req.params.drawId);
      if (!fund.data.members.some((m) => m.id === draw.winner_member_id)) {
        throw new HttpError(400, "برنده دیگر عضو صندوق نیست.");
      }
      const loan = { ...createLoan(fund.data.fund, draw.winner_member_id, draw.month), drawId: draw.id };
      const data = { ...fund.data, loans: [...fund.data.loans, loan] };
      await db.transaction(async (tx) => {
        await writeFund(tx, fund.id, data, fund.version);
        const { changes } = await tx.run(
          "UPDATE draws SET status = 'confirmed', resolved_at = ? WHERE id = ? AND status = 'pending'",
          now(),
          draw.id,
        );
        if (!changes) throw new HttpError(400, "این قرعه قبلاً نهایی شده است.");
      });
      res.json({ data, version: fund.version + 1 });
    }),
  );

  app.post(
    "/api/funds/:id/draws/:drawId/cancel",
    requireLogin,
    route(async (req, res) => {
      const fund = await loadManagedFund(req);
      const draw = await loadPendingDraw(fund.id, req.params.drawId);
      await db.run(
        "UPDATE draws SET status = 'cancelled', resolved_at = ? WHERE id = ? AND status = 'pending'",
        now(),
        draw.id,
      );
      res.json({ ok: true });
    }),
  );

  // ---------- member view ----------
  // What an ordinary member may see: their own ledger, plus the fund-wide
  // facts that make the fund trustworthy (balance, loans, every draw).

  app.get(
    "/api/funds/:id/view",
    requireLogin,
    route(async (req, res) => {
      const fund = await loadFund(req.params.id);
      const isManager = fund.owner_phone === req.phone;
      const membership = await db.get(
        "SELECT member_id FROM fund_members WHERE fund_id = ? AND phone = ?",
        fund.id,
        req.phone,
      );
      if (!isManager && !membership) throw new HttpError(404, "صندوق پیدا نشد.");

      const { data } = fund;
      const month = currentMonthKey(new Date(now()));
      const nameOf = new Map(data.members.map((m) => [m.id, m.name]));
      const member = membership && data.members.find((m) => m.id === membership.member_id);

      let me = null;
      if (member) {
        const dues = listDues(data, month).filter((d) => d.memberId === member.id);
        const overdue = dues.filter((d) => !d.paid && d.month < month);
        me = {
          name: member.name,
          shares: member.shares,
          overdueAmount: sum(overdue),
          dues: dues
            .map(({ type, month: m, amount, paid }) => ({ type, month: m, amount, paid }))
            .sort((a, b) => b.month.localeCompare(a.month) || a.type.localeCompare(b.type)),
          loans: data.loans
            .filter((l) => l.memberId === member.id)
            .map((l) => ({
              amount: l.amount,
              installments: l.installments,
              firstInstallmentMonth: l.firstInstallmentMonth,
              ...loanProgress(data, l),
            })),
        };
      }

      const draws = (
        await db.all(
          "SELECT month, winner_name, status, created_at FROM draws WHERE fund_id = ? ORDER BY created_at DESC",
          fund.id,
        )
      ).map((d) => ({ month: d.month, winnerName: d.winner_name, status: d.status, createdAt: d.created_at }));

      res.json({
        fund: {
          name: data.fund.name,
          contribution: data.fund.contribution,
          loanAmount: data.fund.loanAmount,
          installments: data.fund.installments,
          cycle: data.fund.cycle,
          cardNumber: data.fund.cardNumber ?? "",
          cardHolder: data.fund.cardHolder ?? "",
        },
        isManager,
        currentMonth: month,
        balance: fundBalance(data),
        memberCount: data.members.length,
        totalShares: sum(data.members, (m) => m.shares),
        me,
        loans: [...data.loans]
          .sort((a, b) => b.drawMonth.localeCompare(a.drawMonth))
          .map((l) => ({
            memberName: nameOf.get(l.memberId) ?? "عضو سابق",
            amount: l.amount,
            drawMonth: l.drawMonth,
            viaDraw: Boolean(l.drawId),
          })),
        draws,
      });
    }),
  );

  const circles = createCircleService({ db, digipay, now, formTimeoutMs, walletDebit, scoringCheck });
  // The hosting process calls this on a timer so draws happen on their day
  // even when nobody opens the app.
  app.locals.runScheduledJobs = () => circles.runDueDraws();

  mountCircleRoutes(app, {
    service: circles,
    reports: createOpsReports({ db, now }),
    requireLogin,
    route,
    // Ops tools (simulating months, filling circles) are open to everyone
    // in development and to OPS_PHONES in production.
    isOps,
    requireAdmin,
    requireSimulatorOps,
    simulator: digipay.name === "simulator",
  });

  app.use("/api", (req, res) => res.status(404).json({ error: "مسیر پیدا نشد." }));

  // eslint-disable-next-line no-unused-vars
  app.use((error, req, res, next) => {
    if (error instanceof HttpError) {
      return res.status(error.status).json({ error: error.message, ...error.extra });
    }
    if (error.type === "entity.parse.failed") return res.status(400).json({ error: "درخواست نامعتبر است." });
    console.error(error);
    res.status(500).json({ error: "خطای داخلی سرور." });
  });

  return app;
}
