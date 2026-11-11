import { useEffect, useState } from "react";
import {
  AlertOctagon,
  BarChart3,
  CalendarClock,
  CreditCard,
  FlaskConical,
  Grid3x3,
  LayoutDashboard,
  ListTree,
  LogOut,
  ScrollText,
  Sparkles,
  TrendingUp,
  Users as UsersIcon,
  Wallet,
} from "lucide-react";
import AppBar from "../../ui/AppBar.jsx";
import { PageSkeleton } from "../../ui/bits.jsx";
import { api } from "../../lib/api.js";
import AdminLogin from "./AdminLogin.jsx";
import Dashboard from "./Dashboard.jsx";
import Circles from "./Circles.jsx";
import CircleDetail from "./CircleDetail.jsx";
import Debtors from "./Debtors.jsx";
import Events from "./Events.jsx";
import Growth from "./analytics/Growth.jsx";
import Users, { UserDetail } from "./analytics/Users.jsx";
import { Cohorts, Funnel, Subscriptions } from "./analytics/Engagement.jsx";
import Errors, { ErrorDetail, Insights } from "./analytics/Errors.jsx";

const GROUPS = [
  {
    label: "تحلیل محصول",
    sections: [
      { value: "growth", label: "رشد، نصب و جذب", icon: TrendingUp, Component: Growth },
      { value: "users", label: "کاربران", icon: UsersIcon, Component: Users },
      { value: "funnel", label: "قیف و فیچرها", icon: ListTree, Component: Funnel },
      { value: "cohorts", label: "کوهورت", icon: Grid3x3, Component: Cohorts },
      { value: "subscriptions", label: "خرید اشتراک", icon: CreditCard, Component: Subscriptions },
    ],
  },
  {
    label: "پایش",
    sections: [
      { value: "insights", label: "بینش روزانه‌ی AI", icon: Sparkles, Component: Insights },
      { value: "errors", label: "لاگ خطاها", icon: AlertOctagon, Component: Errors },
    ],
  },
  {
    label: "عملیات طرح‌ها",
    sections: [
      { value: "dashboard", label: "داشبورد طرح‌ها", icon: LayoutDashboard, Component: Dashboard },
      { value: "circles", label: "دوره‌ها", icon: CalendarClock, Component: Circles },
      { value: "debtors", label: "بدهکاران", icon: Wallet, Component: Debtors },
      { value: "events", label: "رویدادهای مالی", icon: ScrollText, Component: Events },
    ],
  },
];
const SECTIONS = GROUPS.flatMap((g) => g.sections);

// The operator's admin panel: #/ops/<section>[/<circle id>].
export default function AdminPanel({ section, itemId, back, go, goBack }) {
  const current = SECTIONS.find((s) => s.value === section) ?? SECTIONS[0];
  // { configured, username, allowed } from the server; null while loading.
  const [auth, setAuth] = useState(null);

  useEffect(() => {
    api("GET", "/api/admin/me").then(setAuth, () => setAuth({ configured: false, allowed: false }));
  }, []);

  const logout = async () => {
    await api("POST", "/api/admin/logout").catch(() => {});
    setAuth((a) => ({ ...a, username: null, allowed: false }));
  };

  if (!auth) {
    return (
      <div className="home admin">
        <PageSkeleton />
      </div>
    );
  }
  if (!auth.allowed) {
    return (
      <div className="home admin">
        <AdminLogin
          configured={auth.configured}
          back={back}
          onLogin={(username) => setAuth({ configured: true, username, allowed: true })}
        />
      </div>
    );
  }

  if (current.value === "circles" && itemId) {
    return (
      <div className="home admin">
        <CircleDetail id={itemId} back={() => goBack("ops", "circles")} />
      </div>
    );
  }
  if (current.value === "users" && itemId) {
    return (
      <div className="home admin">
        <UserDetail phone={decodeURIComponent(itemId)} back={() => goBack("ops", "users")} open={go} />
      </div>
    );
  }
  if (current.value === "errors" && itemId) {
    return (
      <div className="home admin">
        <ErrorDetail fingerprint={itemId} back={() => goBack("ops", "errors")} open={go} />
      </div>
    );
  }

  const { Component } = current;
  return (
    <div className="home admin">
      <AppBar
        onBack={back}
        title="پنل مدیریت دیجی قرض"
        sub={
          <>
            <FlaskConical size={13} /> داده‌های شبیه‌ساز دیجی‌پی
            {auth.username && <>، {auth.username}</>}
          </>
        }
      >
        {auth.username && (
          <button className="icon-btn" onClick={logout} aria-label="خروج مدیر" title="خروج مدیر">
            <LogOut size={20} />
          </button>
        )}
      </AppBar>
      <div className="page admin-layout">
        <nav className="admin-nav" aria-label="بخش‌های پنل">
          {GROUPS.map((g) => (
            <div key={g.label} className="admin-nav-group">
              <span className="admin-nav-label">{g.label}</span>
              {g.sections.map(({ value, label, icon: Icon }) => (
                <button
                  key={value}
                  className={value === current.value ? "active" : ""}
                  aria-current={value === current.value ? "page" : undefined}
                  onClick={() =>
                    go("ops", value === SECTIONS[0].value ? undefined : value, undefined, { replace: true })
                  }
                >
                  <Icon size={17} />
                  {label}
                </button>
              ))}
            </div>
          ))}
        </nav>
        <main className="admin-main">
          <h1 className="admin-heading">
            <BarChart3 size={20} /> {current.label}
          </h1>
          <Component key={current.value} open={go} />
        </main>
      </div>
    </div>
  );
}
