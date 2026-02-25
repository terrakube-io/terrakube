import "github-markdown-css/github-markdown-light.css";
import "./styles/variables.css";
import "./styles/global.css";
import React from "react";
import { createRoot } from "react-dom/client";
import { AuthProvider } from "react-oidc-context";
import { oidcConfig } from "./config/authConfig";
import App from "./domain/Home/App";
import "./index.css";
import reportWebVitals from "./reportWebVitals";

// Get the root element from the DOM
const container = document.getElementById("root");

// Create a root
const root = createRoot(container!);

// Initial render
root.render(
  <React.StrictMode>
    <AuthProvider {...oidcConfig}>
      <App />
    </AuthProvider>
  </React.StrictMode>
);

reportWebVitals();
