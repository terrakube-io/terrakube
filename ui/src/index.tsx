import "github-markdown-css/github-markdown-light.css";
import "./styles/variables.css";
import "./styles/global.css";
import React from "react";
import { createRoot } from "react-dom/client";
import { QueryClientProvider } from "@tanstack/react-query";
import { AuthProvider } from "react-oidc-context";
import { mgr } from "./config/authConfig";
import type { User } from "oidc-client-ts";
import { resolvePostLoginTarget } from "./config/basePath";
import App from "./domain/Home/App";
import { clearQueriesOnSignOut, queryClient } from "./modules/api/queryClient";
import "./index.css";

// Get the root element from the DOM
const container = document.getElementById("root");

// Create a root
const root = createRoot(container!);

// By default react-oidc-context calls window.history.replaceState({}, "", "/")
// after handling the auth callback, which strips any subpath prefix from the URL
// and drops the page the user was opening. Overriding onSigninCallback returns to
// the page recorded as sign-in state (falling back to the base path). App holds
// back the router until this has rewritten the callback URL.
const onSigninCallback = (user: User | undefined): void => {
  window.history.replaceState({}, document.title, resolvePostLoginTarget(user?.state));
};

clearQueriesOnSignOut(queryClient, mgr);

// Shares the UserManager with axiosConfig/apiWrapper so removeUser() there updates this provider immediately.
root.render(
  <React.StrictMode>
    <AuthProvider userManager={mgr} onSigninCallback={onSigninCallback}>
      <QueryClientProvider client={queryClient}>
        <App />
      </QueryClientProvider>
    </AuthProvider>
  </React.StrictMode>
);
