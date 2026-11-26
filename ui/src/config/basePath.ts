const trimTrailingSlash = (value: string): string => value.replace(/\/+$/, "");

export const normalizeBasePath = (value?: string): string => {
  const raw = (value ?? "").trim();

  if (!raw || raw === "/") {
    return "/";
  }

  const normalized = raw.replace(/^\/+|\/+$/g, "");
  return normalized ? `/${normalized}/` : "/";
};

export const getBasePath = (): string => normalizeBasePath(window._env_.REACT_APP_BASE_PATH);

export const getUiRedirectUri = (): string => {
  const configured = (window._env_.REACT_APP_REDIRECT_URI ?? "").trim();

  if (configured) {
    // Keep trailing slash so the redirect_uri matches the router basename (/ui/)
    return configured;
  }

  const basePath = getBasePath();
  return basePath === "/" ? window.location.origin : `${window.location.origin}${basePath}`;
};

export const withBasePath = (path: string): string => {
  if (!path) {
    return path;
  }

  // Keep fully-qualified and data URLs untouched.
  if (/^(?:[a-z]+:)?\/\//i.test(path) || path.startsWith("data:")) {
    return path;
  }

  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  const basePath = getBasePath();

  if (basePath === "/") {
    return normalizedPath;
  }

  const baseNoTrailingSlash = trimTrailingSlash(basePath);

  // Prevent double-prefixing if caller already passed an already-prefixed path.
  if (normalizedPath === baseNoTrailingSlash || normalizedPath.startsWith(`${baseNoTrailingSlash}/`)) {
    return normalizedPath;
  }

  return `${baseNoTrailingSlash}${normalizedPath}`;
};

// Query parameters an OIDC redirect appends; a target carrying them would replay the callback.
const OIDC_CALLBACK_PARAMS = ["code", "state", "session_state", "iss", "error", "error_description", "error_uri"];

// The in-app location to come back to after signing in: path, query and fragment of the current URL.
export const getPostLoginTarget = (): string =>
  `${window.location.pathname}${window.location.search}${window.location.hash}`;

// Resolves the sign-in state round-tripped through the identity provider into a same-origin path
// under the UI base path, falling back to the base path for anything else (including the absolute
// redirect URI older sessions stored as state), so the callback can never navigate off the UI.
export const resolvePostLoginTarget = (state: unknown): string => {
  const basePath = getBasePath();

  if (typeof state !== "string" || !state.startsWith("/") || state.startsWith("//") || state.startsWith("/\\")) {
    return basePath;
  }

  let target: URL;
  try {
    target = new URL(state, window.location.origin);
  } catch {
    return basePath;
  }

  // Dot segments can normalize a path into a protocol-relative one ("/..//host" -> "//host").
  if (target.origin !== window.location.origin || target.pathname.startsWith("//")) {
    return basePath;
  }

  // The bare base path ("/ui") is outside the router's "/ui/" basename and would render a 404.
  if (target.pathname === trimTrailingSlash(basePath)) {
    target.pathname = basePath;
  } else if (!target.pathname.startsWith(basePath)) {
    return basePath;
  }

  OIDC_CALLBACK_PARAMS.forEach((param) => target.searchParams.delete(param));
  return `${target.pathname}${target.search}${target.hash}`;
};
