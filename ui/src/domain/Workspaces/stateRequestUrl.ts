import { apiUrl } from "@/config/apiUrls";

// State URLs keep the API host they were written with, so after a move to a single-domain host they
// can still name the old API host. Rebuild Terrakube state URLs on the current API instead.
export function stateRequestUrl(url: string): string {
  try {
    const { pathname, search } = new URL(url);
    const legacyPath = pathname.replace(/^\/api(?=\/tfstate\/v1\/)/, "");
    if (legacyPath.startsWith("/tfstate/v1/")) return apiUrl(legacyPath + search);
  } catch {
    // Not an absolute URL: leave it alone.
  }
  return url;
}
