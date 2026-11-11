// What the app tells the admin panel about itself: which device this is,
// its platform and version, where the user came from (first touch), that
// the app was opened, which screen is showing, and every error it hits.
// Logins, purchases and feature use are recorded by the server from the API
// calls themselves; these headers go with every call so they're attributed.
import pkg from "../../package.json";

const DEVICE_KEY = "dg:device";
const SOURCE_KEY = "dg:source";

const read = (key) => {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
};
const write = (key, value) => {
  try {
    localStorage.setItem(key, value);
  } catch {
    // Private mode: this visit is counted on its own.
  }
};

function deviceId() {
  let id = read(DEVICE_KEY);
  if (!id) {
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    id = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
    write(DEVICE_KEY, id);
  }
  return id;
}

export function detectPlatform(ua = navigator.userAgent, touch = navigator.maxTouchPoints) {
  // iPadOS says it's a Mac; a Mac with a touch screen is an iPad.
  if (/iPhone|iPad|iPod/i.test(ua) || (/Macintosh/.test(ua) && touch > 1)) return "ios";
  if (/Android/i.test(ua)) return "android";
  return "web";
}

function osVersion(ua = navigator.userAgent) {
  return ua.match(/Android\s([\d.]+)/)?.[1] ?? ua.match(/OS (\d+[_\d]*) like Mac/)?.[1]?.replace(/_/g, ".") ?? null;
}

// First touch wins: utm_source / ref in the link, else the Digipay launch,
// else the referring site, else "direct". Kept for the device's lifetime.
function source() {
  const saved = read(SOURCE_KEY);
  if (saved) {
    try {
      return JSON.parse(saved);
    } catch {
      // fall through
    }
  }
  const params = new URLSearchParams(window.location.search);
  let channel = params.get("utm_source") || params.get("ref") || params.get("source");
  if (!channel && params.get("dp_token")) channel = "digipay";
  if (!channel && document.referrer) {
    try {
      const host = new URL(document.referrer).hostname.replace(/^www\./, "");
      if (host !== window.location.hostname) {
        channel = /google\./.test(host)
          ? "google"
          : /instagram\./.test(host)
            ? "instagram"
            : /t\.me|telegram/.test(host)
              ? "telegram"
              : host.split(".").slice(-2, -1)[0] || host;
      }
    } catch {
      // ignore
    }
  }
  const value = {
    channel: (channel || "direct").toLowerCase().slice(0, 40),
    campaign: params.get("utm_campaign")?.slice(0, 60) || null,
  };
  write(SOURCE_KEY, JSON.stringify(value));
  return value;
}

let context = null;
export function analyticsHeaders() {
  if (!context) {
    const s = source();
    context = {
      "x-device-id": deviceId(),
      "x-platform": detectPlatform(),
      "x-app-version": pkg.version,
      "x-channel": s.channel,
      ...(s.campaign ? { "x-campaign": encodeURIComponent(s.campaign) } : {}),
      ...(osVersion() ? { "x-os-version": osVersion() } : {}),
    };
  }
  return context;
}

function send(path, body) {
  return fetch(path, {
    method: "POST",
    headers: { "content-type": "application/json", ...analyticsHeaders() },
    body: JSON.stringify(body),
    keepalive: true,
  }).catch(() => {});
}

let queue = [];
let timer = null;
export function track(name, props) {
  queue.push({ name, props });
  clearTimeout(timer);
  timer = setTimeout(flush, 1500);
}
function flush() {
  if (!queue.length) return;
  const events = queue;
  queue = [];
  send("/api/collect", { events });
}

let lastScreen = null;
export function trackScreen(screen) {
  if (screen === lastScreen) return;
  lastScreen = screen;
  track("screen_view", { screen });
}

// Errors: grouped on the server; the same one is reported at most 5 times
// per visit so a render loop can't flood the log.
const reported = new Map();
export function reportError(error, extra = {}) {
  const message = String(error?.message ?? error ?? "Unknown error").slice(0, 500);
  const n = reported.get(message) ?? 0;
  if (n >= 5) return;
  reported.set(message, n + 1);
  send("/api/log-error", {
    errors: [
      {
        message: error?.name && !message.startsWith(error.name) ? `${error.name}: ${message}` : message,
        stack: error?.stack ? String(error.stack).slice(0, 4000) : null,
        url: window.location.hash || window.location.pathname,
        level: extra.level ?? "error",
        context: { ...extra.context, ua: navigator.userAgent.slice(0, 200) },
      },
    ],
  });
}

let started = false;
export function startAnalytics() {
  if (started) return;
  started = true;
  window.addEventListener("error", (e) => {
    // A failed <script>/<img> has no error object.
    if (e.error || e.message) reportError(e.error ?? e.message, { context: { file: e.filename, line: e.lineno } });
  });
  window.addEventListener("unhandledrejection", (e) => reportError(e.reason ?? "Unhandled rejection"));
  document.addEventListener("visibilitychange", () => document.visibilityState === "hidden" && flush());
  track("app_open", { standalone: window.matchMedia?.("(display-mode: standalone)").matches || undefined });
}
