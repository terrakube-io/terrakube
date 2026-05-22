import "github-markdown-css/github-markdown-light.css";
import "./styles/variables.css";
import "./styles/global.css";
import React from "react";
import { createRoot } from "react-dom/client";
import { AuthProvider } from "react-oidc-context";
import { oidcConfig } from "./config/authConfig";
import { getUiRedirectUri } from "./config/basePath";
import App from "./domain/Home/App";
import "./index.css";
import reportWebVitals from "./reportWebVitals";

// Get the root element from the DOM
const container = document.getElementById("root");

// Create a root
const root = createRoot(container!);

// By default react-oidc-context calls window.history.replaceState({}, "", "/")
// after handling the auth callback, which strips any subpath prefix from the URL.
// Overriding onSigninCallback keeps the browser on the correct base path instead.
const onSigninCallback = (): void => {
  const base = getUiRedirectUri();
  window.history.replaceState({}, document.title, base);
};

// Initial render
root.render(
  <React.StrictMode>
    <AuthProvider {...oidcConfig} onSigninCallback={onSigninCallback}>
      <App />
    </AuthProvider>
  </React.StrictMode>
);

reportWebVitals();
