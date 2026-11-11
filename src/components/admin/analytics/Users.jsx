import { useEffect, useState } from "react";
import { ChevronLeft, ChevronRight, Copy, Search, X } from "lucide-react";
import AppBar from "../../../ui/AppBar.jsx";
import { EmptyState } from "../../../ui/bits.jsx";
import { useToast } from "../../../ui/feedback.jsx";
import { copyText, formatCompact, formatNumber } from "../../../lib/format.js";
import { toPersianDigits } from "../../../lib/jalali.js";
import { StatusBadge, formatWhen, planTitle, toman } from "../shared.jsx";
import { Report, channelLabel, platformLabel, useFilters, useReport } from "./ui.jsx";

const SEGMENTS = [
  { value: "subscriber", label: "مشترک" },
  { value: "free", label: "بدون خرید" },
  { value: "in_circle", label: "در دوره‌ی جاری" },
  { value: "debtor", label: "بدهکار" },
  { value: "fund_owner", label: "مدیر صندوق" },
  { value: "new", label: "جدید (۷ روز)" },
  { value: "active", label: "فعال (۷ روز)" },
  { value: "dormant", label: "غیرفعال (+۱۴ روز)" },
];

const SORTS = [
  { value: "last", label: "آخرین فعالیت" },
  { value: "first", label: "جدیدترین ثبت‌نام" },
  { value: "oldest", label: "قدیمی‌ترین ثبت‌نام" },
  { value: "spent", label: "بیشترین خرید" },
  { value: "debt", label: "بیشترین بدهی" },
  { value: "logins", label: "بیشترین ورود" },
];

const INITIAL = { q: "", platform: "all", channel: "all", segment: "", sort: "last", page: 1, joined: "" };

// Server-side search (any part of the number, Persian or Latin digits),
// combinable filters, sort and pages: works the same with 100 or 100,000 users.
export default function Users({ open }) {
  const [filters, setFilters] = useFilters("users", INITIAL);
  const [typed, setTyped] = useState(filters.q);
  useEffect(() => {
    const t = setTimeout(() => setFilters((f) => (f.q === typed ? f : { ...f, q: typed, page: 1 })), 300);
    return () => clearTimeout(t);
  }, [typed, setFilters]);
  const { data, error, loading } = useReport("/api/ops/users", { ...filters, limit: 25 });

  const set = (k, v) => setFilters((f) => ({ ...f, [k]: v, page: k === "page" ? v : 1 }));
  const segments = filters.segment ? filters.segment.split(",") : [];
  const toggleSegment = (s) =>
    set("segment", (segments.includes(s) ? segments.filter((x) => x !== s) : [...segments, s]).join(","));
  const active = Object.entries(filters).some(([k, v]) => k !== "sort" && k !== "page" && v && v !== "all");

  return (
    <div className="stack">
      <section className="card user-filters">
        <div className="input search-input">
          <Search size={18} className="muted" />
          <input
            value={typed}
            onChange={(e) => setTyped(e.target.value)}
            placeholder="جستجوی شماره (هر بخشی از آن، فارسی یا انگلیسی)"
            inputMode="tel"
            aria-label="جستجوی شماره"
          />
          {typed && (
            <button className="icon-btn sm" onClick={() => setTyped("")} aria-label="پاک کردن">
              <X size={16} />
            </button>
          )}
        </div>
        <div className="filters">
          <div className="input sm">
            <select value={filters.platform} onChange={(e) => set("platform", e.target.value)} aria-label="پلتفرم">
              <option value="all">همه‌ی پلتفرم‌ها</option>
              {(data?.facets.platforms ?? []).map((p) => (
                <option key={p.key} value={p.key}>
                  {platformLabel(p.key)} ({toPersianDigits(p.count)})
                </option>
              ))}
            </select>
          </div>
          <div className="input sm">
            <select value={filters.channel} onChange={(e) => set("channel", e.target.value)} aria-label="کانال">
              <option value="all">همه‌ی کانال‌ها</option>
              {(data?.facets.channels ?? []).map((c) => (
                <option key={c.key} value={c.key}>
                  {channelLabel(c.key)} ({toPersianDigits(c.count)})
                </option>
              ))}
            </select>
          </div>
          <div className="input sm">
            <select value={filters.joined} onChange={(e) => set("joined", e.target.value)} aria-label="زمان ثبت‌نام">
              <option value="">ثبت‌نام: هر زمان</option>
              <option value="1">ثبت‌نام: ۲۴ ساعت اخیر</option>
              <option value="7">ثبت‌نام: ۷ روز اخیر</option>
              <option value="30">ثبت‌نام: ۳۰ روز اخیر</option>
              <option value="90">ثبت‌نام: ۹۰ روز اخیر</option>
            </select>
          </div>
          <div className="input sm">
            <select value={filters.sort} onChange={(e) => set("sort", e.target.value)} aria-label="مرتب‌سازی">
              {SORTS.map((s) => (
                <option key={s.value} value={s.value}>
                  مرتب: {s.label}
                </option>
              ))}
            </select>
          </div>
        </div>
        <div className="chip-group wrap">
          {SEGMENTS.map((s) => (
            <button
              key={s.value}
              className={`chip ${segments.includes(s.value) ? "active" : ""}`}
              onClick={() => toggleSegment(s.value)}
            >
              {s.label}
            </button>
          ))}
          {active && (
            <button
              className="link-btn"
              onClick={() => {
                setTyped("");
                setFilters(INITIAL);
              }}
            >
              پاک کردن همه‌ی فیلترها
            </button>
          )}
        </div>
      </section>

      <Report data={data} error={error}>
        {data && (
          <section className={`card flush ${loading ? "is-loading" : ""}`}>
            <div className="section-title">
              <h2>{formatNumber(data.total)} کاربر</h2>
              <span className="muted small">
                صفحه‌ی {formatNumber(data.page)} از {formatNumber(data.pages)}
              </span>
            </div>
            {data.users.length === 0 ? (
              <EmptyState title="کاربری پیدا نشد" text="فیلترها یا عبارت جستجو را تغییر دهید." />
            ) : (
              <div className="table-scroll">
                <table className="data-table clickable-rows">
                  <thead>
                    <tr>
                      <th>شماره</th>
                      <th>پلتفرم</th>
                      <th>کانال</th>
                      <th>ثبت‌نام</th>
                      <th>آخرین فعالیت</th>
                      <th>اشتراک</th>
                      <th>خرید</th>
                      <th>بدهی</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.users.map((u) => (
                      <tr key={u.phone} onClick={() => open("ops", "users", u.phone)}>
                        <td dir="ltr" className="mono">
                          {u.phone}
                        </td>
                        <td>{platformLabel(u.platform)}</td>
                        <td>{channelLabel(u.channel)}</td>
                        <td>{formatWhen(u.firstAt)}</td>
                        <td>{formatWhen(u.lastAt)}</td>
                        <td className="num">
                          {u.subscriptions ? <span className="badge brand">{formatNumber(u.subscriptions)}</span> : "—"}
                        </td>
                        <td className="num">{u.spent ? formatCompact(u.spent) : "—"}</td>
                        <td className="num">
                          {u.debt ? <span className="badge danger">{formatCompact(u.debt)}</span> : "—"}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {data.pages > 1 && (
              <div className="pager">
                <button className="btn sm outline" disabled={data.page <= 1} onClick={() => set("page", data.page - 1)}>
                  <ChevronRight size={16} /> قبلی
                </button>
                <span className="muted small">
                  {formatNumber(data.page)} / {formatNumber(data.pages)}
                </span>
                <button
                  className="btn sm outline"
                  disabled={data.page >= data.pages}
                  onClick={() => set("page", data.page + 1)}
                >
                  بعدی <ChevronLeft size={16} />
                </button>
              </div>
            )}
          </section>
        )}
      </Report>
    </div>
  );
}

export function UserDetail({ phone, back, open }) {
  const { data, error } = useReport(`/api/ops/users/${encodeURIComponent(phone)}`);
  const toast = useToast();
  const u = data?.user;
  return (
    <>
      <AppBar onBack={back} title={<span dir="ltr">{phone}</span>} sub="پرونده‌ی کاربر">
        <button
          className="icon-btn"
          aria-label="کپی شماره"
          onClick={() => copyText(phone).then(() => toast("شماره کپی شد"))}
        >
          <Copy size={18} />
        </button>
      </AppBar>
      <div className="page">
        <Report data={data} error={error}>
          {u && (
            <div className="admin-grid">
              <div className="kpi-grid">
                <div className="kpi">
                  <div className="kpi-label">پلتفرم / کانال</div>
                  <strong className="kpi-value">{platformLabel(u.platform)}</strong>
                  <span className="kpi-sub">
                    {channelLabel(u.channel)}
                    {u.campaign ? `، کمپین ${u.campaign}` : ""}
                  </span>
                </div>
                <div className="kpi">
                  <div className="kpi-label">ثبت‌نام</div>
                  <strong className="kpi-value small">{formatWhen(u.firstAt)}</strong>
                  <span className="kpi-sub">آخرین فعالیت {formatWhen(u.lastAt)}</span>
                </div>
                <div className="kpi">
                  <div className="kpi-label">خرید اشتراک</div>
                  <strong className="kpi-value">{formatNumber(u.subscriptions)}</strong>
                  <span className="kpi-sub">{toman(u.spent)}</span>
                </div>
                <div className={`kpi ${u.debt ? "danger" : ""}`}>
                  <div className="kpi-label">بدهی باز</div>
                  <strong className="kpi-value">{u.debt ? formatCompact(u.debt) : "—"}</strong>
                  <span className="kpi-sub">
                    {formatNumber(u.logins)} ورود، {formatNumber(u.funds)} صندوق
                  </span>
                </div>
              </div>

              <section className="card flush">
                <div className="section-title">
                  <h2>دوره‌ها</h2>
                </div>
                {data.circles.length === 0 ? (
                  <p className="muted small pad">عضو هیچ دوره‌ای نیست.</p>
                ) : (
                  <ul className="rows compact">
                    {data.circles.map((c) => (
                      <li key={c.id}>
                        <button className="row" onClick={() => open("ops", "circles", c.id)}>
                          <div className="row-main">
                            <strong>
                              {planTitle(c.planId)}، جایگاه {toPersianDigits(c.position)}
                            </strong>
                            <span>
                              ماه {formatNumber(c.month)} از {formatNumber(c.months)}
                              {c.wonMonth ? `، برنده‌ی ماه ${formatNumber(c.wonMonth)}` : ""}
                              {c.debt ? `، بدهی ${formatCompact(c.debt)}` : ""}
                            </span>
                          </div>
                          <StatusBadge status={c.status} />
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </section>

              <section className="card flush">
                <div className="section-title">
                  <h2>پرداخت‌ها</h2>
                </div>
                {data.purchases.length === 0 ? (
                  <p className="muted small pad">پرداختی ندارد.</p>
                ) : (
                  <div className="table-scroll">
                    <table className="data-table">
                      <tbody>
                        {data.purchases.map((p) => (
                          <tr key={p.id}>
                            <td>{p.kind === "entry" ? "اشتراک (قسط اول)" : "قسط"}</td>
                            <td>{planTitle(p.planId)}</td>
                            <td className="num">{formatCompact(p.amount)}</td>
                            <td>
                              <span
                                className={`badge ${p.status === "paid" ? "success" : p.status === "refunded" ? "gold" : ""}`}
                              >
                                {{ paid: "موفق", pending: "نیمه‌کاره", cancelled: "لغو", refunded: "برگشت" }[p.status]}
                              </span>
                            </td>
                            <td>{formatWhen(p.at)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </section>

              <section className="card">
                <div className="section-title">
                  <h2>دستگاه‌ها</h2>
                </div>
                <ul className="event-list">
                  {data.devices.map((d) => (
                    <li key={d.id}>
                      <span className="event-dot" />
                      <div>
                        <strong>
                          {platformLabel(d.platform)} {d.osVersion ?? ""}
                        </strong>
                        <span>
                          نسخه‌ی {d.appVersion ?? "—"}، {channelLabel(d.channel)}، آخرین بار {formatWhen(d.lastAt)}
                        </span>
                      </div>
                    </li>
                  ))}
                  {data.devices.length === 0 && <p className="muted small">دستگاهی ثبت نشده.</p>}
                </ul>
              </section>

              <section className="card">
                <div className="section-title">
                  <h2>خطاهایی که دیده</h2>
                </div>
                {data.errors.length === 0 ? (
                  <p className="muted small">خطایی ثبت نشده.</p>
                ) : (
                  <ul className="event-list">
                    {data.errors.map((e, i) => (
                      <li key={i} className="danger clickable" onClick={() => open("ops", "errors", e.fingerprint)}>
                        <span className="event-dot" />
                        <div>
                          <strong dir="auto">{e.message}</strong>
                          <span>
                            {formatWhen(e.at)}، {platformLabel(e.platform)}
                          </span>
                        </div>
                      </li>
                    ))}
                  </ul>
                )}
              </section>

              <section className="card span-2">
                <div className="section-title">
                  <h2>تاریخچه‌ی فعالیت</h2>
                  <span className="muted small">۱۰۰ رویداد آخر</span>
                </div>
                <ul className="event-list">
                  {data.events.map((e, i) => (
                    <li key={i} className={e.name === "subscription_purchased" ? "brand" : ""}>
                      <span className="event-dot" />
                      <div>
                        <strong>
                          {e.label}
                          {e.props?.screen ? `: ${e.props.screen}` : ""}
                          {e.props?.planId ? `، ${planTitle(e.props.planId)}` : ""}
                        </strong>
                        <span>
                          {formatWhen(e.at)}، {platformLabel(e.platform)}
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
