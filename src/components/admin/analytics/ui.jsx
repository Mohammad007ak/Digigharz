import { useEffect, useState } from "react";
import { ArrowDownRight, ArrowUpRight } from "lucide-react";
import { api } from "../../../lib/api.js";
import { formatNumber } from "../../../lib/format.js";
import { formatDay } from "../../../lib/jalali.js";

export const PLATFORM_LABELS = { android: "اندروید", ios: "iOS", web: "وب", unknown: "نامشخص", server: "سرور" };
export const platformLabel = (p) => PLATFORM_LABELS[p] ?? p;

const CHANNEL_LABELS = {
  direct: "مستقیم",
  digipay: "دیجی‌پی",
  google: "گوگل",
  instagram: "اینستاگرام",
  telegram: "تلگرام",
  unknown: "نامشخص (قبل از آمارگیری)",
};
export const channelLabel = (c) => CHANNEL_LABELS[c] ?? c;

// A Tehran day key ("2026-10-21") as "۲۹ مهر".
export const dayLabel = (day) => formatDay(Date.parse(`${day}T12:00:00Z`));
export const percent = (r, digits = 0) =>
  r === null || r === undefined
    ? "—"
    : `${(Math.round(r * 100 * 10 ** digits) / 10 ** digits).toLocaleString("fa-IR")}٪`;

// Fetches `path` with the given query; refetches when the query changes.
export function useReport(path, query = {}) {
  const qs = new URLSearchParams(
    Object.entries(query).filter(([, v]) => v !== undefined && v !== "" && v !== "all"),
  ).toString();
  const url = qs ? `${path}?${qs}` : path;
  const [state, setState] = useState({ data: null, error: null, url: null });
  const [tick, setTick] = useState(0);
  useEffect(() => {
    let live = true;
    api("GET", url).then(
      (data) => live && setState({ data, error: null, url }),
      (error) => live && setState({ data: null, error, url }),
    );
    return () => {
      live = false;
    };
  }, [url, tick]);
  return {
    data: state.data,
    error: state.error,
    loading: state.url !== url,
    reload: () => setTick((t) => t + 1),
  };
}

export const RANGES = [
  { value: 7, label: "۷ روز" },
  { value: 30, label: "۳۰ روز" },
  { value: 90, label: "۹۰ روز" },
];

// Date range plus the platform/channel filters every report shares.
export function FilterBar({ filters, setFilters, channels = [], showRange = true, children }) {
  const set = (k) => (e) => setFilters((f) => ({ ...f, [k]: e.target.value }));
  return (
    <div className="filters an-filters">
      {showRange && (
        <div className="chip-group" role="group" aria-label="بازه">
          {RANGES.map((r) => (
            <button
              key={r.value}
              className={`chip ${Number(filters.days) === r.value ? "active" : ""}`}
              onClick={() => setFilters((f) => ({ ...f, days: r.value }))}
            >
              {r.label}
            </button>
          ))}
        </div>
      )}
      <div className="input sm">
        <select value={filters.platform ?? "all"} onChange={set("platform")} aria-label="پلتفرم">
          <option value="all">همه‌ی پلتفرم‌ها</option>
          <option value="android">اندروید</option>
          <option value="ios">iOS</option>
          <option value="web">وب</option>
        </select>
      </div>
      <div className="input sm">
        <select value={filters.channel ?? "all"} onChange={set("channel")} aria-label="کانال جذب">
          <option value="all">همه‌ی کانال‌ها</option>
          {channels.map((c) => (
            <option key={c} value={c}>
              {channelLabel(c)}
            </option>
          ))}
        </select>
      </div>
      {children}
    </div>
  );
}

// Filters live in the URL-free component state but survive switching tabs.
const memory = {};
export function useFilters(key, initial) {
  const [filters, setFilters] = useState(() => memory[key] ?? initial);
  useEffect(() => {
    memory[key] = filters;
  }, [key, filters]);
  return [filters, setFilters];
}

// The channels any report has seen, for the channel filter.
let knownChannels = [];
export function useChannels(rows) {
  const keys = rows?.map((r) => r.key) ?? [];
  knownChannels = [...new Set([...knownChannels, ...keys])];
  return knownChannels;
}

export function Delta({ now, before, invert = false }) {
  if (!before) return null;
  const change = (now - before) / before;
  if (!Number.isFinite(change) || Math.abs(change) < 0.005) return <span className="delta">بدون تغییر</span>;
  const up = change > 0;
  const good = invert ? !up : up;
  return (
    <span className={`delta ${good ? "good" : "bad"}`} title="نسبت به بازه‌ی قبل">
      {up ? <ArrowUpRight size={13} /> : <ArrowDownRight size={13} />}
      {percent(Math.abs(change))}
    </span>
  );
}

export function Stat({ label, value, sub, delta, tone = "" }) {
  return (
    <div className={`kpi ${tone}`}>
      <div className="kpi-label">{label}</div>
      <strong className="kpi-value">{value}</strong>
      <span className="kpi-sub">
        {delta} {sub}
      </span>
    </div>
  );
}

// Several daily series on one chart; hover (or tap) a day to read it.
export function LineChart({ data, series, height = 200, xKey = "day", format = formatNumber }) {
  const [hover, setHover] = useState(null);
  const [hidden, setHidden] = useState(() => new Set());
  const width = 720;
  const pad = { top: 12, right: 8, bottom: 22, left: 36 };
  const shown = series.filter((s) => !hidden.has(s.key));
  const max = Math.max(1, ...data.flatMap((d) => shown.map((s) => d[s.key] ?? 0)));
  const nice = niceMax(max);
  const w = width - pad.left - pad.right;
  const h = height - pad.top - pad.bottom;
  // RTL: the newest day is on the left.
  const x = (i) => pad.left + (data.length <= 1 ? w / 2 : w - (i / (data.length - 1)) * w);
  const y = (v) => pad.top + h - (v / nice) * h;
  const ticks = [0, nice / 2, nice];
  const labelEvery = Math.max(1, Math.ceil(data.length / 7));

  const onMove = (e) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const px = ((e.clientX - rect.left) / rect.width) * width;
    const i = Math.round(((w - (px - pad.left)) / w) * (data.length - 1));
    setHover(Math.max(0, Math.min(data.length - 1, i)));
  };

  return (
    <div className="line-chart">
      <div className="chart-legend">
        {series.map((s) => (
          <button
            key={s.key}
            className={`legend-item ${hidden.has(s.key) ? "off" : ""}`}
            onClick={() =>
              setHidden((prev) => {
                const next = new Set(prev);
                next.has(s.key) ? next.delete(s.key) : next.add(s.key);
                return next;
              })
            }
          >
            <i style={{ background: s.color }} />
            {s.label}
            {hover !== null && data[hover] && <b>{format(data[hover][s.key] ?? 0)}</b>}
          </button>
        ))}
        {hover !== null && data[hover] && <span className="muted small">{dayLabel(data[hover][xKey])}</span>}
      </div>
      <svg
        viewBox={`0 0 ${width} ${height}`}
        role="img"
        onMouseMove={onMove}
        onMouseLeave={() => setHover(null)}
        onTouchStart={(e) =>
          onMove(e.touches[0] ? { ...e, clientX: e.touches[0].clientX, currentTarget: e.currentTarget } : e)
        }
      >
        {ticks.map((t) => (
          <g key={t}>
            <line x1={pad.left} x2={width - pad.right} y1={y(t)} y2={y(t)} className="grid" />
            <text x={pad.left - 6} y={y(t) + 4} className="axis" textAnchor="end">
              {formatCompactAxis(t)}
            </text>
          </g>
        ))}
        {data.map((d, i) =>
          i % labelEvery === 0 ? (
            <text key={d[xKey]} x={x(i)} y={height - 4} className="axis" textAnchor="middle">
              {dayLabel(d[xKey])}
            </text>
          ) : null,
        )}
        {shown.map((s) => (
          <g key={s.key}>
            <polyline
              fill="none"
              stroke={s.color}
              strokeWidth="2.25"
              strokeLinejoin="round"
              strokeLinecap="round"
              points={data.map((d, i) => `${x(i)},${y(d[s.key] ?? 0)}`).join(" ")}
            />
            {data.length <= 31 &&
              data.map((d, i) => (
                <circle key={i} cx={x(i)} cy={y(d[s.key] ?? 0)} r={hover === i ? 4 : 2} fill={s.color} />
              ))}
          </g>
        ))}
        {hover !== null && <line x1={x(hover)} x2={x(hover)} y1={pad.top} y2={pad.top + h} className="cursor" />}
      </svg>
    </div>
  );
}

function niceMax(v) {
  const p = 10 ** Math.floor(Math.log10(v));
  return [1, 2, 2.5, 5, 10].map((m) => m * p).find((m) => m >= v) ?? v;
}
const formatCompactAxis = (v) =>
  v >= 1e9
    ? `${formatNumber(v / 1e9)}B`
    : v >= 1e6
      ? `${formatNumber(v / 1e6)}M`
      : v >= 1e3
        ? `${formatNumber(v / 1e3)}K`
        : formatNumber(Math.round(v * 10) / 10);

// Shares of a whole as one stacked bar, with a legend.
export function SplitBar({ rows, colors }) {
  const total = rows.reduce((t, r) => t + r.value, 0);
  if (!total) return <p className="muted small">هنوز داده‌ای نیست.</p>;
  return (
    <div className="share-split">
      <div className="split-bar">
        {rows
          .filter((r) => r.value)
          .map((r, i) => (
            <span
              key={r.key}
              style={{ width: `${(r.value / total) * 100}%`, background: colors[i % colors.length] }}
              title={`${r.label}: ${formatNumber(r.value)}`}
            />
          ))}
      </div>
      <ul className="split-legend">
        {rows.map((r, i) => (
          <li key={r.key}>
            <i style={{ background: colors[i % colors.length] }} />
            {r.label}
            <b>{formatNumber(r.value)}</b>
            <span className="muted">{percent(r.value / total)}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

export const COLORS = ["var(--brand)", "var(--success)", "var(--gold)", "var(--danger)", "#8b5cf6", "#06b6d4"];

export function Report({ error, data, children }) {
  if (error) return <p className="error-text">{error.message}</p>;
  if (!data) return <div className="skeleton" style={{ height: 240, borderRadius: 16 }} />;
  return children;
}

// The daily briefing is Markdown: headings, bullets, bold. Rendered as
// elements (never as HTML) so nothing in it can run.
export function Markdown({ text }) {
  const blocks = [];
  let list = null;
  const inline = (s, k) =>
    s
      .split(/(\*\*[^*]+\*\*)/g)
      .map((part, i) =>
        part.startsWith("**") && part.endsWith("**") ? <strong key={`${k}-${i}`}>{part.slice(2, -2)}</strong> : part,
      );
  String(text ?? "")
    .split("\n")
    .forEach((line, i) => {
      const t = line.trim();
      const bullet = t.match(/^[-*•]\s+(.*)$/) ?? t.match(/^\d+[.)]\s+(.*)$/);
      if (bullet) {
        list ??= [];
        list.push(<li key={i}>{inline(bullet[1], i)}</li>);
        return;
      }
      if (list) {
        blocks.push(<ul key={`l${i}`}>{list}</ul>);
        list = null;
      }
      if (!t) return;
      const h = t.match(/^(#{1,4})\s+(.*)$/);
      if (h) blocks.push(h[1].length <= 2 ? <h3 key={i}>{inline(h[2], i)}</h3> : <h4 key={i}>{inline(h[2], i)}</h4>);
      else blocks.push(<p key={i}>{inline(t, i)}</p>);
    });
  if (list) blocks.push(<ul key="last">{list}</ul>);
  return <div className="markdown">{blocks}</div>;
}
