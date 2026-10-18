import { useCallback, useRef, useState } from "react";

const STORAGE_PREFIX = "terrakube.workspaceCollapsedGroups";

type Scope = { storageKey?: string; collapsed: ReadonlySet<string> };

/** One entry per user and organization, so a shared browser tab never leaks choices between them. */
export function collapsedGroupsStorageKey(userId: string, organizationId: string): string {
  return `${STORAGE_PREFIX}:${userId}:${organizationId}`;
}

function readCollapsed(storageKey?: string): ReadonlySet<string> {
  if (!storageKey) return new Set();
  try {
    const parsed: unknown = JSON.parse(sessionStorage.getItem(storageKey) ?? "[]");
    return new Set(Array.isArray(parsed) ? parsed.filter((value): value is string => typeof value === "string") : []);
  } catch {
    // Storage can be blocked or hold something unreadable; the groups then simply start expanded.
    return new Set();
  }
}

function writeCollapsed(storageKey: string, collapsed: ReadonlySet<string>) {
  try {
    sessionStorage.setItem(storageKey, JSON.stringify([...collapsed]));
  } catch {
    // The choice then only lasts until the page is left.
  }
}

/**
 * Which project groups of the workspace list the user has collapsed.
 *
 * Groups are identified by project id (`__unassigned__` for the unassigned group), so renaming a project
 * keeps its state and a project that shows on several pages is collapsed on all of them. Only collapsed
 * groups are stored: everything else is expanded.
 */
export function useCollapsedProjectGroups(userId: string | undefined, organizationId: string | undefined) {
  const storageKey = userId && organizationId ? collapsedGroupsStorageKey(userId, organizationId) : undefined;
  const [scope, setScope] = useState<Scope>(() => ({ storageKey, collapsed: readCollapsed(storageKey) }));

  // Another user or organization has its own choices, so load them instead of carrying the last ones over.
  let current = scope;
  if (scope.storageKey !== storageKey) {
    current = { storageKey, collapsed: readCollapsed(storageKey) };
    setScope(current);
  }

  // Lets several changes made before the next render build on each other.
  const latest = useRef(current);
  latest.current = current;

  const update = useCallback((change: (next: Set<string>) => void) => {
    const { storageKey: key, collapsed } = latest.current;
    const next = new Set(collapsed);
    change(next);
    latest.current = { storageKey: key, collapsed: next };
    if (key) writeCollapsed(key, next);
    setScope(latest.current);
  }, []);

  const toggle = useCallback(
    (groupKey: string) => update((next) => (next.has(groupKey) ? next.delete(groupKey) : next.add(groupKey))),
    [update]
  );

  /** Collapses or expands the given groups and leaves every other stored choice as it was. */
  const setCollapsed = useCallback(
    (groupKeys: string[], collapse: boolean) =>
      update((next) => groupKeys.forEach((groupKey) => (collapse ? next.add(groupKey) : next.delete(groupKey)))),
    [update]
  );

  return { collapsed: current.collapsed, toggle, setCollapsed };
}
