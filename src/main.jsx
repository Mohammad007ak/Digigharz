import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "@fontsource-variable/vazirmatn";
import "@fontsource-variable/estedad";
import "./styles.css";
import App from "./App.jsx";
import ErrorBoundary from "./ui/ErrorBoundary.jsx";
import { startAnalytics } from "./lib/analytics.js";

startAnalytics();

createRoot(document.getElementById("root")).render(
  <StrictMode>
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
  </StrictMode>,
);
