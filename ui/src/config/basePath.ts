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
