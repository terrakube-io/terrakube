import type { UIMatch } from "react-router-dom";

/**
 * Route `handle` for routes that are tabs of one page. Switching between them keeps the page mounted,
 * with its loaded data, as long as the URL params stay the same.
 */
export type PageHandle = { page: string };

/** Routes that render a tab of the workspace page. */
export const workspacePage: PageHandle = { page: "workspace" };

/**
 * Key for the page element: changes when the route changes, unless both routes share a `page` handle,
 * and whenever the params change (another organization, workspace, run...). Pages without a handle
 * remount on every route change, because several of them (organization settings tabs, for example)
 * load their data on mount and expect a fresh mount after a create or edit form navigates back.
 */
export function pageKey(matches: UIMatch[]): string {
  const leaf = matches[matches.length - 1];
  if (!leaf) return "";
  const page = (leaf.handle as PageHandle | undefined)?.page ?? `route:${leaf.id}`;
  return `${page}:${JSON.stringify(leaf.params)}`;
}
