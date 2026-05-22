import { UserManager, WebStorageStateStore } from "oidc-client-ts";
import React from "react";
import { AuthContext, AuthContextProps } from "react-oidc-context";
import { getUiRedirectUri } from "./basePath";

const redirectUri = getUiRedirectUri();

export const oidcConfig = {
  authority: window._env_.REACT_APP_AUTHORITY,
  client_id: window._env_.REACT_APP_CLIENT_ID,
  redirect_uri: redirectUri,
  // After Dex returns the auth code, oidc-client-ts resolves the callback and
  // then navigates to the URL stored in the state parameter.  When no state was
  // preserved (e.g. first visit hits /ui directly) the library falls back to
  // redirect_uri itself, which lands on root if the URI ends without a subpath.
  // Explicitly setting post_logout_redirect_uri and using the same base keeps
  // the user inside the subdir after login.
  post_logout_redirect_uri: redirectUri,
  scope: window._env_.REACT_APP_SCOPE,
  userStore: new WebStorageStateStore({ store: window.localStorage }),
};

export const mgr = new UserManager(oidcConfig);

export const useAuth = (): AuthContextProps => {
  const context = React.useContext(AuthContext);

  if (!context) {
    throw new Error(
      "AuthProvider context is undefined, please verify you are calling useAuth() as child of a <AuthProvider> component."
    );
  }

  return context;
};
