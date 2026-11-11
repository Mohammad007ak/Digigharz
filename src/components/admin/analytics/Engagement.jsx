import { Plus, X } from "lucide-react";
import { formatCompact, formatNumber } from "../../../lib/format.js";
import { formatDuration, planTitle } from "../shared.jsx";
import {
  COLORS,
  Delta,
  FilterBar,
  LineChart,
  Report,
  Stat,
  channelLabel,
  dayLabel,
  percent,
  platformLabel,
  useChannels,
  useFilters,
  useReport,
} from "./ui.jsx";

const DEFAULT_STEPS = ["install", "otp_requested", "login", "plans_viewed", "join_started", "subscription_purchased"];

// Ordered feature funnel: pick the steps; see where people drop off and how
// long each step takes. Below it, how much each feature is used.
export function Funnel() {
  const [filters, setFilters] = useFilters("funnel", { days: 30, steps: DEFAULT_STEPS.join(",") });
  const { data, error } = useReport("/api/ops/analytics/funnel", filters);
  const features = useReport("/api/ops/analytics/features", {
    days: filters.days,
    platform: filters.platform,
    channel: filters.channel,
  });
  const channels = useChannels(useReport("/api/ops/analytics/growth", { days: filters.days }).data?.channels);
  const steps = filters.steps.split(",");
  const setSteps = (list) => setFilters((f) => ({ ...f, steps: list.join(",") }));

  return (
    <div className="stack">
      <FilterBar filters={filters} setFilters={setFilters} channels={channels}>
        <button className="link-btn" onClick={() => setSteps(DEFAULT_STEPS)}>
          قیف پیش‌فرض
        </button>
      </FilterBar>
      <Report data={data} error={error}>
        {data && (
          <section className="card">
            <div className="section-title">
              <h2>قیف استفاده</h2>
              <span className="muted small">هر مرحله فقط بعد از مرحله‌ی قبل، و تا ۳۰ روز بعد از اولی</span>
            </div>
            <ol className="funnel">
              {data.steps.map((s, i) => (
                <li key={s.name}>
                  <div className="funnel-head">
                    <span className="funnel-n">{formatNumber(i + 1)}</span>
                    <strong>{s.label}</strong>
                    {steps.length > 2 && (
                      <button
                        className="icon-btn sm"
                        aria-label="حذف مرحله"
                        onClick={() => setSteps(steps.filter((x) => x !== s.name))}
                      >
                        <X size={14} />
                      </button>
                    )}
                  </div>
                  <div className="funnel-bar">
                    <span style={{ width: `${(s.ofFirst ?? 0) * 100}%` }} />
                  </div>
                  <div className="funnel-meta">
                    <b>{formatNumber(s.count)}</b>
                    <span>{percent(s.ofFirst)} از شروع</span>
                    {i > 0 && (
                      <span className={s.ofPrevious !== null && s.ofPrevious < 0.4 ? "danger-text" : ""}>
                        {percent(s.ofPrevious)} از مرحله‌ی قبل
                        {data.steps[i - 1].count > s.count &&
                          `، ریزش ${formatNumber(data.steps[i - 1].count - s.count)} نفر`}
                      </span>
                    )}
                    {s.medianMs !== null && <span className="muted">میانه‌ی زمان: {formatDuration(s.medianMs)}</span>}
                  </div>
                </li>
              ))}
            </ol>
            {steps.length < 8 && (
              <div className="input sm add-step">
                <Plus size={15} />
                <select value="" onChange={(e) => e.target.value && setSteps([...steps, e.target.value])}>
                  <option value="">افزودن مرحله…</option>
                  {data.available
                    .filter((a) => !steps.includes(a.name))
                    .map((a) => (
                      <option key={a.name} value={a.name}>
                        {a.label}
                      </option>
                    ))}
                </select>
              </div>
            )}
          </section>
        )}
      </Report>
      <Report data={features.data} error={features.error}>
        {features.data && (
          <div className="admin-grid">
            <section className="card flush">
              <div className="section-title">
                <h2>استفاده از فیچرها</h2>
              </div>
              <div className="table-scroll">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>رویداد</th>
                      <th>تعداد</th>
                      <th>کاربر یکتا</th>
                    </tr>
                  </thead>
                  <tbody>
                    {features.data.events.map((e) => (
                      <tr key={e.name}>
                        <td>
                          <strong>{e.label}</strong>
                          <span className="cell-sub" dir="ltr">
                            {e.name}
                          </span>
                        </td>
                        <td className="num">{formatNumber(e.count)}</td>
                        <td className="num">{formatNumber(e.users)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
            <section className="card flush">
              <div className="section-title">
                <h2>پربازدیدترین صفحه‌ها</h2>
              </div>
              <div className="table-scroll">
                <table className="data-table">
                  <tbody>
                    {features.data.screens.map((s) => (
                      <tr key={s.screen}>
                        <td dir="ltr">{s.screen}</td>
                        <td className="num">{formatNumber(s.count)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          </div>
        )}
      </Report>
    </div>
  );
}

// Of the users who signed up in a week (or day), the share still active in
// each following one. Darker is better.
export function Cohorts() {
  const [filters, setFilters] = useFilters("cohorts", { unit: "week", periods: 8 });
  const { data, error } = useReport("/api/ops/analytics/cohorts", filters);
  const channels = useChannels(useReport("/api/ops/analytics/growth", { days: 90 }).data?.channels);
  const unitLabel = filters.unit === "day" ? "روز" : "هفته";
  return (
    <div className="stack">
      <FilterBar filters={filters} setFilters={setFilters} channels={channels} showRange={false}>
        <div className="chip-group">
          {[
            ["week", 8, "هفتگی"],
            ["day", 14, "روزانه"],
          ].map(([unit, periods, label]) => (
            <button
              key={unit}
              className={`chip ${filters.unit === unit ? "active" : ""}`}
              onClick={() => setFilters((f) => ({ ...f, unit, periods }))}
            >
              {label}
            </button>
          ))}
        </div>
      </FilterBar>
      <Report data={data} error={error}>
        {data && (
          <section className="card flush">
            <div className="section-title">
              <h2>ماندگاری کاربران (کوهورت)</h2>
              <span className="muted small">درصد کاربرانی که در {unitLabel}‌های بعد از ثبت‌نام برگشته‌اند</span>
            </div>
            <div className="table-scroll">
              <table className="data-table cohort">
                <thead>
                  <tr>
                    <th>ثبت‌نام</th>
                    <th>کاربر</th>
                    {data.rows.map((_, i) => (
                      <th key={i}>
                        {unitLabel} {formatNumber(i)}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((r) => (
                    <tr key={r.start}>
                      <td>{dayLabel(r.start)}</td>
                      <td className="num">{formatNumber(r.size)}</td>
                      {data.rows.map((_, i) => {
                        const rate = r.rates[i];
                        if (rate === undefined) return <td key={i} />;
                        return (
                          <td
                            key={i}
                            className="num heat"
                            style={{ "--heat": rate ?? 0, color: rate > 0.45 ? "#fff" : undefined }}
                            title={`${formatNumber(r.retained[i])} از ${formatNumber(r.size)}`}
                          >
                            {r.size ? percent(rate) : "—"}
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        )}
      </Report>
    </div>
  );
}

export function Subscriptions() {
  const [filters, setFilters] = useFilters("subs", { days: 30 });
  const { data, error } = useReport("/api/ops/analytics/subscriptions", filters);
  const channels = useChannels(useReport("/api/ops/analytics/growth", { days: filters.days }).data?.channels);
  return (
    <div className="stack">
      <FilterBar filters={filters} setFilters={setFilters} channels={channels} />
      <Report data={data} error={error}>
        {data && (
          <div className="admin-grid">
            <div className="kpi-grid">
              <Stat
                label="خرید اشتراک"
                value={formatNumber(data.totals.purchases)}
                sub={`${formatNumber(data.totals.buyers)} خریدار`}
              />
              <Stat label="درآمد (قسط اول)" value={formatCompact(data.totals.revenue)} sub="تومان" />
              <Stat
                label="تبدیل ثبت‌نام به خرید"
                value={percent(data.totals.signups ? data.totals.buyers / data.totals.signups : null)}
                sub={`از ${formatNumber(data.totals.signups)} ثبت‌نام`}
              />
              <Stat
                label="موفقیت پرداخت"
                value={percent(data.totals.checkoutSuccess)}
                sub={`${formatNumber(data.totals.abandoned)} پرداخت نیمه‌کاره/لغو`}
                tone={data.totals.checkoutSuccess !== null && data.totals.checkoutSuccess < 0.7 ? "gold" : ""}
              />
              <Stat label="خرید تکراری" value={formatNumber(data.totals.repeatBuyers)} sub="بیش از یک طرح" />
              <Stat label="برگشت وجه" value={formatNumber(data.totals.refunded)} sub="خروج از صف یا انقضا" />
              <Stat
                label="زمان تا اولین خرید"
                value={formatDuration(data.totals.medianTimeToFirstMs)}
                sub="میانه، از ثبت‌نام"
              />
              <Stat
                label="میانگین هر خرید"
                value={data.totals.purchases ? formatCompact(data.totals.revenue / data.totals.purchases) : "—"}
                sub="تومان"
              />
            </div>
            <section className="card span-2">
              <div className="section-title">
                <h2>خرید روزانه</h2>
              </div>
              <LineChart
                data={data.series}
                series={[
                  { key: "count", label: "خرید", color: COLORS[0] },
                  { key: "refunds", label: "برگشت", color: COLORS[2] },
                ]}
              />
            </section>
            <section className="card flush">
              <div className="section-title">
                <h2>به تفکیک طرح</h2>
              </div>
              <div className="table-scroll">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>طرح</th>
                      <th>خرید</th>
                      <th>درآمد</th>
                      <th>برگشت</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.plans.map((p) => (
                      <tr key={p.id}>
                        <td>
                          <strong>{p.title}</strong>
                        </td>
                        <td className="num">{formatNumber(p.count)}</td>
                        <td className="num">{formatCompact(p.revenue)}</td>
                        <td className="num">{formatNumber(p.refunded)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
            <section className="card flush">
              <div className="section-title">
                <h2>آخرین خریدها</h2>
              </div>
              <div className="table-scroll">
                <table className="data-table">
                  <tbody>
                    {data.recent.map((r, i) => (
                      <tr key={i}>
                        <td dir="ltr" className="mono">
                          {r.phone}
                        </td>
                        <td>{planTitle(r.planId)}</td>
                        <td>
                          {platformLabel(r.platform ?? "unknown")}، {channelLabel(r.channel ?? "unknown")}
                        </td>
                        <td className="num">{formatCompact(r.amount)}</td>
                        <td>{dayLabel(new Date(r.at + 3.5 * 3600e3).toISOString().slice(0, 10))}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          </div>
        )}
      </Report>
    </div>
  );
}

export { Delta };
