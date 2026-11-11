import { useEffect, useState } from "react";
import { CheckCircle2, EyeOff, RotateCcw, Search, Sparkles } from "lucide-react";
import AppBar from "../../../ui/AppBar.jsx";
import { EmptyState, Spinner } from "../../../ui/bits.jsx";
import { useToast } from "../../../ui/feedback.jsx";
import { api } from "../../../lib/api.js";
import { formatNumber } from "../../../lib/format.js";
import { formatWhen } from "../shared.jsx";
import {
  COLORS,
  LineChart,
  Markdown,
  Report,
  SplitBar,
  dayLabel,
  platformLabel,
  useFilters,
  useReport,
} from "./ui.jsx";

const STATUS = {
  open: { label: "باز", badge: "danger" },
  regressed: { label: "برگشته", badge: "danger" },
  resolved: { label: "رفع‌شده", badge: "success" },
  ignored: { label: "نادیده", badge: "" },
};
const SOURCE = { app: "اپ", server: "سرور" };

// Every crash and failure in the app and on the server, grouped into one
// row per bug, newest first. Mark fixed; if it happens again it reopens.
export default function Errors({ open }) {
  const [filters, setFilters] = useFilters("errors", {
    days: 30,
    status: "open",
    source: "all",
    platform: "all",
    q: "",
  });
  const [typed, setTyped] = useState(filters.q);
  useEffect(() => {
    const t = setTimeout(() => setFilters((f) => ({ ...f, q: typed })), 300);
    return () => clearTimeout(t);
  }, [typed, setFilters]);
  const { data, error } = useReport("/api/ops/errors", filters);
  const set = (k) => (e) => setFilters((f) => ({ ...f, [k]: e.target.value }));

  return (
    <div className="stack">
      <div className="filters">
        <div className="chip-group">
          {[7, 30, 90].map((d) => (
            <button
              key={d}
              className={`chip ${Number(filters.days) === d ? "active" : ""}`}
              onClick={() => setFilters((f) => ({ ...f, days: d }))}
            >
              {formatNumber(d)} روز
            </button>
          ))}
        </div>
        <div className="input sm">
          <select value={filters.status} onChange={set("status")} aria-label="وضعیت">
            <option value="open">باز و برگشته</option>
            <option value="resolved">رفع‌شده</option>
            <option value="ignored">نادیده</option>
            <option value="all">همه</option>
          </select>
        </div>
        <div className="input sm">
          <select value={filters.source} onChange={set("source")} aria-label="منبع">
            <option value="all">اپ و سرور</option>
            <option value="app">فقط اپ</option>
            <option value="server">فقط سرور</option>
          </select>
        </div>
        <div className="input sm">
          <select value={filters.platform} onChange={set("platform")} aria-label="پلتفرم">
            <option value="all">همه‌ی پلتفرم‌ها</option>
            <option value="android">اندروید</option>
            <option value="ios">iOS</option>
            <option value="web">وب</option>
          </select>
        </div>
        <div className="input sm search-input">
          <Search size={16} className="muted" />
          <input value={typed} onChange={(e) => setTyped(e.target.value)} placeholder="جستجو در متن خطا یا مسیر" />
        </div>
      </div>
      <Report data={data} error={error}>
        {data && (
          <div className="admin-grid">
            <section className="card">
              <div className="section-title">
                <h2>خطا در روز</h2>
              </div>
              <LineChart data={data.series} series={[{ key: "count", label: "خطا", color: COLORS[3] }]} height={160} />
            </section>
            <section className="card">
              <div className="section-title">
                <h2>به تفکیک پلتفرم</h2>
              </div>
              <SplitBar
                rows={data.platforms.map((p) => ({ key: p.key, label: platformLabel(p.key), value: p.count }))}
                colors={COLORS}
              />
            </section>
            <section className="card flush span-2">
              <div className="section-title">
                <h2>{formatNumber(data.groups.length)} مشکل</h2>
                <span className="muted small">هر ردیف یک باگ؛ تکرارهای یکسان با هم شمرده می‌شوند</span>
              </div>
              {data.groups.length === 0 ? (
                <EmptyState title="خطایی نیست" text="در این بازه و با این فیلترها مشکلی ثبت نشده." />
              ) : (
                <div className="table-scroll">
                  <table className="data-table clickable-rows">
                    <thead>
                      <tr>
                        <th>خطا</th>
                        <th>وضعیت</th>
                        <th>تکرار در بازه</th>
                        <th>کاربر درگیر</th>
                        <th>آخرین بار</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.groups.map((g) => (
                        <tr key={g.fingerprint} onClick={() => open("ops", "errors", g.fingerprint)}>
                          <td className="error-cell">
                            <strong dir="ltr">{g.message}</strong>
                            <span className="cell-sub">
                              {SOURCE[g.source]} {g.url ? <span dir="ltr">· {g.url}</span> : null}
                            </span>
                          </td>
                          <td>
                            <span className={`badge ${STATUS[g.status]?.badge}`}>{STATUS[g.status]?.label}</span>
                          </td>
                          <td className="num">{formatNumber(g.recent)}</td>
                          <td className="num">{formatNumber(g.affected)}</td>
                          <td>{formatWhen(g.lastAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>
          </div>
        )}
      </Report>
    </div>
  );
}

export function ErrorDetail({ fingerprint, back, open }) {
  const { data, error, reload } = useReport(`/api/ops/errors/${fingerprint}`);
  const [busy, setBusy] = useState(null);
  const [note, setNote] = useState(null);
  const toast = useToast();
  const g = data?.group;

  const update = async (body, message) => {
    setBusy(body.status ?? "note");
    try {
      await api("PATCH", `/api/ops/errors/${fingerprint}`, body);
      toast(message);
      reload();
    } catch (e) {
      toast(e.message, { tone: "error" });
    } finally {
      setBusy(null);
    }
  };

  return (
    <>
      <AppBar
        onBack={back}
        title="جزئیات خطا"
        sub={g ? `${formatNumber(g.count)} بار از ${formatWhen(g.firstAt)}` : ""}
      />
      <div className="page">
        <Report data={data} error={error}>
          {g && (
            <div className="admin-grid">
              <section className="card span-2">
                <div className="section-title">
                  <span className={`badge ${STATUS[g.status]?.badge}`}>{STATUS[g.status]?.label}</span>
                  <div className="actions-row">
                    {g.status !== "resolved" && (
                      <button
                        className="btn sm primary"
                        disabled={Boolean(busy)}
                        onClick={() => update({ status: "resolved" }, "به‌عنوان رفع‌شده علامت خورد")}
                      >
                        {busy === "resolved" ? <Spinner /> : <CheckCircle2 size={15} />} رفع شد
                      </button>
                    )}
                    {g.status !== "ignored" && (
                      <button
                        className="btn sm outline"
                        disabled={Boolean(busy)}
                        onClick={() => update({ status: "ignored" }, "نادیده گرفته شد")}
                      >
                        <EyeOff size={15} /> نادیده
                      </button>
                    )}
                    {(g.status === "resolved" || g.status === "ignored") && (
                      <button
                        className="btn sm outline"
                        disabled={Boolean(busy)}
                        onClick={() => update({ status: "open" }, "دوباره باز شد")}
                      >
                        <RotateCcw size={15} /> باز کردن
                      </button>
                    )}
                  </div>
                </div>
                <h3 dir="ltr" className="error-title">
                  {g.message}
                </h3>
                <p className="muted small" dir="ltr">
                  {SOURCE[g.source]} {g.url}
                </p>
                {g.stack && (
                  <pre className="stack-trace" dir="ltr">
                    {g.stack}
                  </pre>
                )}
                <label className="field">
                  <span>یادداشت (علت، تیکت، مسئول)</span>
                  <textarea
                    className="input"
                    rows={2}
                    value={note ?? g.note ?? ""}
                    onChange={(e) => setNote(e.target.value)}
                  />
                </label>
                {note !== null && note !== (g.note ?? "") && (
                  <button className="btn sm outline" onClick={() => update({ note }, "یادداشت ذخیره شد")}>
                    ذخیره‌ی یادداشت
                  </button>
                )}
              </section>
              {[
                ["پلتفرم", data.platforms.map((p) => ({ ...p, key: platformLabel(p.key) }))],
                ["نسخه‌ی اپ", data.versions],
                ["صفحه / مسیر", data.urls],
              ].map(([title, rows]) => (
                <section className="card" key={title}>
                  <div className="section-title">
                    <h2>{title}</h2>
                  </div>
                  <SplitBar rows={rows.map((r) => ({ key: r.key, label: r.key, value: r.count }))} colors={COLORS} />
                </section>
              ))}
              <section className="card span-2">
                <div className="section-title">
                  <h2>آخرین رخدادها</h2>
                </div>
                <ul className="event-list">
                  {data.occurrences.map((o) => (
                    <li key={o.id} className={o.level === "warning" ? "gold" : "danger"}>
                      <span className="event-dot" />
                      <div>
                        <strong>
                          {formatWhen(o.at)}، {platformLabel(o.platform ?? "server")}
                          {o.appVersion ? `، نسخه‌ی ${o.appVersion}` : ""}
                        </strong>
                        <span>
                          {o.phone ? (
                            <button className="link-btn" dir="ltr" onClick={() => open("ops", "users", o.phone)}>
                              {o.phone}
                            </button>
                          ) : (
                            "کاربر ناشناس"
                          )}{" "}
                          <span dir="ltr">{o.url}</span>
                        </span>
                      </div>
                    </li>
                  ))}
                </ul>
              </section>
            </div>
          )}
        </Report>
      </div>
    </>
  );
}

// The daily AI briefing: one report per day, newest first.
export function Insights() {
  const { data, error, reload } = useReport("/api/ops/insights");
  const [index, setIndex] = useState(0);
  const [busy, setBusy] = useState(false);
  const toast = useToast();

  const generate = async (day) => {
    setBusy(true);
    try {
      await api("POST", "/api/ops/insights", day ? { day } : {});
      toast("تحلیل آماده شد");
      setIndex(0);
      reload();
    } catch (e) {
      toast(e.message, { tone: "error" });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Report data={data} error={error}>
      {data && (
        <div className="stack">
          {!data.configured && (
            <section className="card notice gold">
              <strong>هوش مصنوعی هنوز وصل نیست.</strong>
              <p className="small">
                مقدار <code dir="ltr">ANTHROPIC_API_KEY</code> را در تنظیمات سرور بگذارید. از آن به بعد هر روز ساعت ۷
                صبح (به وقت تهران) داده‌های دیروز تحلیل و اینجا گزارش می‌شود.
              </p>
            </section>
          )}
          <div className="filters">
            {data.insights.length > 0 && (
              <div className="input sm">
                <select value={index} onChange={(e) => setIndex(Number(e.target.value))} aria-label="روز">
                  {data.insights.map((r, i) => (
                    <option key={r.day} value={i}>
                      گزارش {dayLabel(r.day)}
                    </option>
                  ))}
                </select>
              </div>
            )}
            <button className="btn sm primary" disabled={busy || !data.configured} onClick={() => generate()}>
              {busy ? <Spinner /> : <Sparkles size={15} />} تحلیل دیروز (همین الان)
            </button>
          </div>
          {data.insights.length === 0 ? (
            <section className="card">
              <EmptyState title="هنوز گزارشی نیست" text="اولین گزارش فردا صبح ساخته می‌شود، یا همین الان بسازید." />
            </section>
          ) : (
            <InsightCard insight={data.insights[index]} />
          )}
        </div>
      )}
    </Report>
  );
}

function InsightCard({ insight }) {
  const y = insight.metrics?.yesterday;
  return (
    <section className="card insight">
      <div className="section-title">
        <h2>
          <Sparkles size={18} /> اتفاقات {dayLabel(insight.day)}
        </h2>
        <span className="muted small">
          {formatWhen(insight.createdAt)} · {insight.model}
        </span>
      </div>
      {y && (
        <div className="admin-stats">
          <div>
            <span>نصب</span>
            <strong>{formatNumber(y.installs)}</strong>
          </div>
          <div>
            <span>ثبت‌نام</span>
            <strong>{formatNumber(y.signups)}</strong>
          </div>
          <div>
            <span>فعال</span>
            <strong>{formatNumber(y.activeUsers)}</strong>
          </div>
          <div>
            <span>خرید</span>
            <strong>{formatNumber(y.subscriptions.purchases)}</strong>
          </div>
          <div>
            <span>خطا</span>
            <strong>{formatNumber(y.errors)}</strong>
          </div>
        </div>
      )}
      {insight.status === "failed" ? (
        <p className="error-text">تحلیل ناموفق بود: {insight.error}</p>
      ) : (
        <Markdown text={insight.content} />
      )}
    </section>
  );
}
