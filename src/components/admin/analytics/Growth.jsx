import { formatCompact, formatNumber } from "../../../lib/format.js";
import {
  COLORS,
  Delta,
  FilterBar,
  LineChart,
  Report,
  SplitBar,
  Stat,
  channelLabel,
  percent,
  platformLabel,
  useChannels,
  useFilters,
  useReport,
} from "./ui.jsx";

// Installs, signups and active users over time, Android vs iOS, and which
// acquisition channels bring users who end up paying.
export default function Growth() {
  const [filters, setFilters] = useFilters("growth", { days: 30 });
  const { data, error } = useReport("/api/ops/analytics/growth", filters);
  const channels = useChannels(data?.channels);

  return (
    <div className="stack">
      <FilterBar filters={filters} setFilters={setFilters} channels={channels} />
      <Report data={data} error={error}>
        {data && <GrowthBody data={data} />}
      </Report>
    </div>
  );
}

function GrowthBody({ data }) {
  const { totals, previous } = data;
  const stickiness = totals.mau ? totals.dau / totals.mau : null;
  return (
    <div className="admin-grid">
      <div className="kpi-grid">
        <Stat
          label="نصب جدید"
          value={formatNumber(totals.installs)}
          delta={<Delta now={totals.installs} before={previous.installs} />}
          sub="در این بازه"
        />
        <Stat
          label="ثبت‌نام جدید"
          value={formatNumber(totals.signups)}
          delta={<Delta now={totals.signups} before={previous.signups} />}
          sub={totals.installs ? `${percent(totals.signups / totals.installs)} از نصب‌ها` : ""}
        />
        <Stat
          label="کاربر فعال امروز (DAU)"
          value={formatNumber(totals.dau)}
          delta={<Delta now={totals.dau} before={totals.dauYesterday} />}
          sub={`دیروز ${formatNumber(totals.dauYesterday)}`}
        />
        <Stat
          label="کاربر فعال ۷ روز (WAU)"
          value={formatNumber(totals.wau)}
          sub={`ماهانه (MAU): ${formatNumber(totals.mau)}`}
        />
        <Stat label="چسبندگی (DAU/MAU)" value={percent(stickiness)} sub="هر چه بیشتر، استفاده‌ی روزانه‌تر" />
        <Stat
          label="خرید اشتراک"
          value={formatNumber(totals.purchases)}
          delta={<Delta now={totals.purchases} before={previous.purchases} />}
          sub="در این بازه"
        />
        <Stat label="کل کاربران ثبت‌نامی" value={formatNumber(totals.users)} />
        <Stat label="کل دستگاه‌ها (نصب)" value={formatNumber(totals.devices)} />
      </div>

      <section className="card span-2">
        <div className="section-title">
          <h2>روند روزانه</h2>
          <span className="muted small">روی هر سری بزنید تا پنهان یا نمایان شود</span>
        </div>
        <LineChart
          data={data.series}
          series={[
            { key: "active", label: "کاربر فعال", color: COLORS[0] },
            { key: "installs", label: "نصب", color: COLORS[1] },
            { key: "signups", label: "ثبت‌نام", color: COLORS[2] },
            { key: "purchases", label: "خرید", color: COLORS[3] },
          ]}
        />
      </section>

      <section className="card">
        <div className="section-title">
          <h2>اندروید و iOS</h2>
          <span className="muted small">نصب‌ها در این بازه</span>
        </div>
        <SplitBar
          rows={data.platforms.map((p) => ({ key: p.key, label: platformLabel(p.key), value: p.installs }))}
          colors={COLORS}
        />
        <div className="table-scroll" style={{ marginTop: 12 }}>
          <table className="data-table">
            <thead>
              <tr>
                <th>پلتفرم</th>
                <th>نصب</th>
                <th>ثبت‌نام</th>
                <th>فعال</th>
                <th>خریدار</th>
                <th>تبدیل</th>
              </tr>
            </thead>
            <tbody>
              {data.platforms.map((p) => (
                <tr key={p.key}>
                  <td>
                    <strong>{platformLabel(p.key)}</strong>
                  </td>
                  <td className="num">{formatNumber(p.installs)}</td>
                  <td className="num">{formatNumber(p.signups)}</td>
                  <td className="num">{formatNumber(p.active)}</td>
                  <td className="num">{formatNumber(p.buyers)}</td>
                  <td className="num">{percent(p.conversion)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="card">
        <div className="section-title">
          <h2>کانال‌های جذب</h2>
          <span className="muted small">اولین منبع ورود (utm_source)</span>
        </div>
        <SplitBar
          rows={data.channels.map((c) => ({ key: c.key, label: channelLabel(c.key), value: c.signups }))}
          colors={COLORS}
        />
        <div className="table-scroll" style={{ marginTop: 12 }}>
          <table className="data-table">
            <thead>
              <tr>
                <th>کانال</th>
                <th>نصب</th>
                <th>ثبت‌نام</th>
                <th>خریدار</th>
                <th>تبدیل به خرید</th>
                <th>درآمد</th>
              </tr>
            </thead>
            <tbody>
              {data.channels.map((c) => (
                <tr key={c.key}>
                  <td>
                    <strong>{channelLabel(c.key)}</strong>
                  </td>
                  <td className="num">{formatNumber(c.installs)}</td>
                  <td className="num">{formatNumber(c.signups)}</td>
                  <td className="num">{formatNumber(c.buyers)}</td>
                  <td className="num">{percent(c.conversion)}</td>
                  <td className="num">{formatCompact(c.revenue)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="muted small" style={{ marginTop: 10 }}>
          برای هر کمپین لینک را با <code dir="ltr">?utm_source=instagram&amp;utm_campaign=…</code> بسازید تا اینجا جدا
          دیده شود.
        </p>
      </section>
    </div>
  );
}
