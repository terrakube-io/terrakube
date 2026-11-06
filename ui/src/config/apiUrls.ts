export function getPublicApiOrigin(): string {
  return new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin;
}

/** "/api" on a single-domain deployment (REACT_APP_TERRAKUBE_PUBLIC_URL set), otherwise "". */
export function apiPrefix(): string {
  const publicUrl = window._env_.REACT_APP_TERRAKUBE_PUBLIC_URL;
  return typeof publicUrl === "string" && publicUrl.trim() !== "" ? "/api" : "";
}

/** Backend URL for a legacy path (must start with "/"). */
export function apiUrl(path: string): string {
  return getPublicApiOrigin() + apiPrefix() + path;
}

export function graphqlUrl(): string {
  return apiUrl("/graphql/api/v1");
}

export function subscriptionsUrl(): string {
  return apiUrl("/subscriptions").replace(/^http/, "ws");
}

/** Hostname shown in CLI snippets. */
export function publicHostname(): string {
  const publicUrl = window._env_.REACT_APP_TERRAKUBE_PUBLIC_URL;
  return new URL(apiPrefix() ? (publicUrl as string) : window._env_.REACT_APP_TERRAKUBE_API_URL).hostname;
}
