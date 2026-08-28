const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isOrgId(value: string | null | undefined): value is string {
  return !!value && UUID_PATTERN.test(value);
}

export function getOrgIdFromPathname(pathname: string): string | null {
  const segments = pathname.split("/").filter(Boolean);
  const index = segments.indexOf("organizations");
  const candidate = index >= 0 ? segments[index + 1] : null;
  return isOrgId(candidate) ? candidate : null;
}
