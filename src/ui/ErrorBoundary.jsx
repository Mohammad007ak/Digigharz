import { Component } from "react";
import { reportError } from "../lib/analytics.js";

// A crash in rendering is reported to the admin panel's error log, and the
// user gets a way out instead of a blank screen.
export default class ErrorBoundary extends Component {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(error, info) {
    reportError(error, { level: "fatal", context: { componentStack: info?.componentStack?.slice(0, 1500) } });
  }

  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <div className="home" style={{ padding: 32, textAlign: "center" }}>
        <h2>مشکلی پیش آمد</h2>
        <p className="muted">خطا ثبت شد و بررسی می‌کنیم. لطفاً دوباره تلاش کنید.</p>
        <button className="btn primary" onClick={() => window.location.reload()}>
          بارگذاری دوباره
        </button>
      </div>
    );
  }
}
