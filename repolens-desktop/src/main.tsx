import React from "react";
import ReactDOM from "react-dom/client";
import "@vscode/codicons/dist/codicon.css";
// Self-host Monaco: must be imported before any Editor component mounts.
import "./lib/monacoLoader";
import App from "./App";
import { I18nProvider } from "./i18n/I18nProvider";

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    <I18nProvider>
      <App />
    </I18nProvider>
  </React.StrictMode>,
);
