import { useEffect, useState } from "react";
import {
  ArrowRightLeft,
  CalendarCheck,
  Dices,
  Eye,
  House,
  LogOut,
  RefreshCw,
  Settings as SettingsIcon,
  Users,
  WalletCards,
} from "lucide-react";
import Dashboard from "./components/Dashboard.jsx";
import Members, { MemberForm, MemberSheet, blankMember, useMemberActions } from "./components/Members.jsx";
import Payments from "./components/Payments.jsx";
import Lottery from "./components/Lottery.jsx";
import Loans from "./components/Loans.jsx";
import Settings from "./components/Settings.jsx";
import Login from "./components/Login.jsx";
import FundList from "./components/FundList.jsx";
import MemberView from "./components/MemberView.jsx";
import CircleView from "./components/circles/CircleView.jsx";
import Checkout from "./components/circles/Checkout.jsx";
import AdminPanel from "./components/admin/AdminPanel.jsx";
import Tour from "./components/Tour.jsx";
import { Logo, LogoMark } from "./ui/Logo.jsx";
import { PageSkeleton } from "./ui/bits.jsx";
import { FeedbackProvider, useDialog } from "./ui/feedback.jsx";
import { api } from "./lib/api.js";
import { overdueDues } from "./lib/fund.js";
import { useFundSync } from "./lib/useFundSync.js";
import { currentMonthKey, toPersianDigits } from "./lib/jalali.js";
import { useRoute } from "./lib/router.js";
import { trackScreen } from "./lib/analytics.js";
import AppBar from "./ui/AppBar.jsx";

const TABS = [
  { id: "dashboard", label: "داشبورد", icon: House, Component: Dashboard },
  {
    id: "payments",
    label: "پرداخت‌ها",
    icon: CalendarCheck,
    Component: Payments,
  },
  { id: "lottery", label: "قرعه‌کشی", icon: Dices, Component: Lottery },
  { id: "members", label: "اعضا", icon: Users, Component: Members },
  { id: "loans", label: "وام‌ها", icon: WalletCards, Component: Loans },
  { id: "settings", label: "تنظیمات", icon: SettingsIcon, Component: Settings },
];
const MOBILE_TABS = TABS.filter((t) => t.id !== "settings");

const SAVE_LABEL = {
  loading: "",
  saving: "در حال ذخیره…",
  saved: "ذخیره شد",
  error: "ذخیره نشد",
};

function ManageFund({ fundId, tab: routeTab, go, back, phone, onLogout }) {
  const { state, status, error, update, flush, applyServer } = useFundSync(fundId);
  const confirm = useDialog();
  const [memberId, setMemberId] = useState(null);
  const [editing, setEditing] = useState(null);
  const currentMonth = currentMonthKey();
  const tab = TABS.some((t) => t.id === routeTab) ? routeTab : "dashboard";
  // Tabs replace each other in history, so "back" leaves the fund.
  const setTab = (id) => go("manage", fundId, id === "dashboard" ? undefined : id, { replace: true });
  const leave = async () => {
    const saved = await flush();
    if (!saved) {
      const ok = await confirm({
        title: "آخرین تغییرات ذخیره نشد",
        body: "اتصال به سرور برقرار نیست. اگر خارج شوید، این تغییرات از بین می‌رود.",
        confirmLabel: "خروج بدون ذخیره",
        tone: "danger",
      });
      if (!ok) return;
    }
    back("family");
  };
  const actions = useMemberActions({
    state: state ?? { members: [], payments: [], loans: [] },
    update,
  });

  if (error) {
    return (
      <div className="home">
        <AppBar onBack={() => back("family")} title="صندوق" />
        <div className="card">
          <p className="error-text">{error.message}</p>
          <button className="btn outline" style={{ marginTop: 12 }} onClick={() => back("family")}>
            بازگشت به صندوق‌ها
          </button>
        </div>
      </div>
    );
  }

  const { Component } = TABS.find((t) => t.id === tab);
  const lateCount = state ? new Set(overdueDues(state, currentMonth).map((d) => d.memberId)).size : 0;
  const goTo = (id, opts) =>
    id === "members" && opts?.add ? setEditing(blankMember(state.fund, currentMonth)) : setTab(id);

  return (
    <div className="shell">
      <aside className="sidebar">
        <Logo />
        <button className="fund-switch" onClick={leave} title="بازگشت به صفحه‌ی اصلی">
          <LogoMark size={30} />
          <div>
            <strong>{state?.fund.name ?? "…"}</strong>
            <span>همه‌ی صندوق‌ها و طرح‌ها</span>
          </div>
          <ArrowRightLeft size={16} className="muted" />
        </button>
        <nav aria-label="بخش‌های صندوق">
          {TABS.map((t) => {
            const Icon = t.icon;
            return (
              <button
                key={t.id}
                className={`side-link ${t.id === tab ? "active" : ""}`}
                onClick={() => setTab(t.id)}
                aria-current={t.id === tab ? "page" : undefined}
              >
                <Icon size={19} />
                {t.label}
                {t.id === "payments" && lateCount > 0 && <span className="count">{toPersianDigits(lateCount)}</span>}
              </button>
            );
          })}
        </nav>
        <div className="sidebar-foot">
          <button className="side-link" onClick={() => go("view", fundId)}>
            <Eye size={19} /> نمای اعضا
          </button>
          <button className="side-link" onClick={onLogout}>
            <LogOut size={19} /> خروج
            <span className="muted small" style={{ marginInlineStart: "auto" }} dir="ltr">
              {toPersianDigits(phone)}
            </span>
          </button>
        </div>
      </aside>

      <div className="main">
        <AppBar
          onBack={leave}
          backLabel="همه‌ی صندوق‌ها"
          title={state?.fund.name ?? ""}
          sub={
            <>
              <span className={`save-dot ${status}`} />
              {SAVE_LABEL[status]}
              {status === "error" && (
                <button className="link-btn" onClick={flush}>
                  <RefreshCw size={12} /> دوباره
                </button>
              )}
            </>
          }
        >
          <button
            className="icon-btn mobile-only"
            onClick={() => go("view", fundId)}
            aria-label="نمای اعضا"
            title="نمای اعضا"
          >
            <Eye size={20} />
          </button>
          <button
            className={`icon-btn mobile-only ${tab === "settings" ? "soft" : ""}`}
            onClick={() => setTab("settings")}
            aria-label="تنظیمات"
          >
            <SettingsIcon size={20} />
          </button>
        </AppBar>

        {state ? (
          <Component
            key={tab}
            state={state}
            update={update}
            currentMonth={currentMonth}
            goTo={goTo}
            goHome={() => go("family", undefined, undefined, { replace: true })}
            openMember={setMemberId}
            editMember={setEditing}
            server={{ fundId, flush, applyServer }}
          />
        ) : (
          <PageSkeleton />
        )}
      </div>

      <nav className="bottom-nav" aria-label="بخش‌های صندوق">
        {MOBILE_TABS.map((t) => {
          const Icon = t.icon;
          return (
            <button key={t.id} className={t.id === tab ? "active" : ""} onClick={() => setTab(t.id)}>
              <span className="nav-icon">
                <Icon size={21} strokeWidth={t.id === tab ? 2.2 : 1.8} />
              </span>
              {t.label}
              {t.id === "payments" && lateCount > 0 && <span className="dot-count">{toPersianDigits(lateCount)}</span>}
            </button>
          );
        })}
      </nav>

      {state && (
        <>
          <MemberSheet
            state={state}
            memberId={memberId}
            currentMonth={currentMonth}
            onClose={() => setMemberId(null)}
            onEdit={(member) => {
              setMemberId(null);
              setEditing(member);
            }}
            onDelete={async (member) => {
              if (await actions.remove(member)) setMemberId(null);
            }}
          />
          <MemberForm
            open={Boolean(editing)}
            initial={editing}
            onClose={() => setEditing(null)}
            onSave={(member) => {
              actions.save(member);
              setEditing(null);
            }}
          />
        </>
      )}
    </div>
  );
}

export default function App() {
  const [phone, setPhone] = useState(undefined);
  const [route, go, back] = useRoute();
  useEffect(() => {
    if (route.page !== "ops") trackScreen(route.page === "manage" ? `manage/${route.tab ?? "home"}` : route.page);
  }, [route.page, route.tab]);
  // Every new account gets the tour right after signing in (the server
  // remembers who has seen it); it can be opened again any time.
  const [touring, setTouring] = useState(false);
  const openTour = () => setTouring(true);
  useEffect(() => {
    if (!phone) return;
    api("GET", "/api/me/tour").then(
      (r) => !r.seen && setTouring(true),
      () => {},
    );
  }, [phone]);
  const closeTour = () => {
    setTouring(false);
    if (phone) api("POST", "/api/me/tour", {}).catch(() => {});
  };

  useEffect(() => {
    // Opened inside the Digipay app: sign in with the host's launch token.
    const params = new URLSearchParams(window.location.search);
    const launchToken = params.get("dp_token");
    const signIn = launchToken
      ? api("POST", "/api/auth/digipay", { token: launchToken }).finally(() => {
          params.delete("dp_token");
          const query = params.toString();
          window.history.replaceState(
            null,
            "",
            window.location.pathname + (query ? `?${query}` : "") + window.location.hash,
          );
        })
      : api("GET", "/api/me");
    signIn.then(
      (me) => setPhone(me.phone),
      () => setPhone(null),
    );
    const onLoggedOut = () => setPhone(null);
    window.addEventListener("sandogh:logged-out", onLoggedOut);
    return () => window.removeEventListener("sandogh:logged-out", onLoggedOut);
  }, []);

  const logout = async () => {
    await api("POST", "/api/auth/logout").catch(() => {});
    setPhone(null);
    go("home", undefined, undefined, { replace: true });
  };

  let content;
  // The admin panel has its own username/password sign-in.
  if (route.page === "ops")
    content = <AdminPanel section={route.id} itemId={route.tab} back={() => back("home")} go={go} goBack={back} />;
  else if (phone === undefined) content = <div className="home" />;
  else if (phone === null) content = <Login onLogin={setPhone} onTour={openTour} />;
  else if (route.page === "manage")
    content = (
      <ManageFund
        key={route.id}
        fundId={route.id}
        tab={route.tab}
        go={go}
        back={back}
        phone={phone}
        onLogout={logout}
      />
    );
  else if (route.page === "view")
    content = (
      <MemberView
        key={route.id}
        fundId={route.id}
        back={(isManager) => (isManager ? back("manage", route.id) : back("family"))}
      />
    );
  else if (route.page === "circle")
    content = <CircleView key={route.id} circleId={route.id} back={() => back("home")} open={go} />;
  else if (route.page === "checkout")
    content = (
      <Checkout
        key={route.id}
        checkoutId={route.id}
        done={({ kind, circleId }) =>
          // A paid entry lands in the waiting room in place of the payment
          // page; anything else returns to where the payment started.
          kind === "entry" && circleId
            ? go("circle", circleId, undefined, { replace: true })
            : back(circleId ? "circle" : "home", circleId)
        }
      />
    );
  else
    content = (
      <FundList
        phone={phone}
        tab={route.page === "family" ? "family" : "plans"}
        setTab={(tab) => go(tab === "family" ? "family" : "home", undefined, undefined, { replace: true })}
        open={go}
        onLogout={logout}
        onTour={openTour}
      />
    );

  return (
    <FeedbackProvider>
      {content}
      {touring && route.page !== "ops" && <Tour onClose={closeTour} />}
    </FeedbackProvider>
  );
}
