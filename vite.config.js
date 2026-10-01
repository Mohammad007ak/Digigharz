import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";

const page = (path) => fileURLToPath(new URL(path, import.meta.url));

// Public pages that belong in the sitemap, with their relative priority.
const SITEMAP = [
  { path: "/welcome/", priority: "1.0", changefreq: "weekly" },
  { path: "/guide/family-loan-fund/", priority: "0.8", changefreq: "monthly" },
  { path: "/terms/", priority: "0.3", changefreq: "yearly" },
];

// In development the API is the Java backend (backend/, `npm run backend`),
// on API_URL (default http://localhost:3000); Vite forwards /api to it, so
// the app still runs on a single address.
function devPages() {
  return {
    name: "sandogh-pages",
    configureServer(server) {
      // Same URLs as production: the app at the root, the landing page at /welcome/.
      server.middlewares.use((req, res, next) => {
        const [path, query] = req.url.split("?");
        if (path === "/") req.url = `/app/${query ? `?${query}` : ""}`;
        else if (path === "/welcome" || path === "/welcome/") req.url = "/welcome/index.html";
        next();
      });
    },
  };
}

// Canonical URLs, Open Graph tags and the sitemap need the real domain.
// It comes from SITE_URL (see .env.example) and is baked in at build time.
function seo(siteUrl) {
  return {
    name: "sandogh-seo",
    transformIndexHtml: (html) => html.replaceAll("__SITE_URL__", siteUrl),
    generateBundle() {
      const today = new Date().toISOString().slice(0, 10);
      const urls = SITEMAP.map(
        (p) =>
          `  <url>\n    <loc>${siteUrl}${p.path}</loc>\n    <lastmod>${today}</lastmod>\n    <changefreq>${p.changefreq}</changefreq>\n    <priority>${p.priority}</priority>\n  </url>`,
      ).join("\n");
      this.emitFile({
        type: "asset",
        fileName: "sitemap.xml",
        source: `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls}\n</urlset>\n`,
      });
      this.emitFile({
        type: "asset",
        fileName: "robots.txt",
        source: `User-agent: *\nAllow: /\nDisallow: /api/\n\nSitemap: ${siteUrl}/sitemap.xml\n`,
      });
    },
  };
}

export default defineConfig(({ command, mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const siteUrl = (env.SITE_URL || (command === "build" ? "" : "http://localhost:5173")).replace(/\/$/, "");
  if (!siteUrl) {
    console.warn("\n⚠  SITE_URL is not set; canonical links and the sitemap will use http://localhost:3000.\n");
  }

  return {
    plugins: [react(), devPages(), seo(siteUrl || "http://localhost:3000")],
    server: { proxy: { "/api": env.API_URL || "http://localhost:3000" } },
    build: {
      rollupOptions: {
        input: {
          landing: page("./welcome/index.html"),
          app: page("./app/index.html"),
          guide: page("./guide/family-loan-fund/index.html"),
          terms: page("./terms/index.html"),
          notFound: page("./404.html"),
        },
      },
    },
  };
});
