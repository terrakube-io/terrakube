import { useSyncExternalStore } from "react";

/** Where a person was before opening a workspace, so the way back leads there and not always to the list. */
export type WorkspaceOrigin = { kind: "list" | "project" | "inbox"; path: string };

const KEY = "terrakube.workspaceOrigin";
const listeners = new Set<() => void>();

type Stored = { workspaceId: string; origin: WorkspaceOrigin };

function read(): Stored | null {
  try {
    return JSON.parse(sessionStorage.getItem(KEY) ?? "null");
  } catch {
    return null;
  }
}

/** Remembers the origin for the workspace about to open. Call it from the link or row that opens it. */
export function rememberWorkspaceOrigin(workspaceId: string, origin: WorkspaceOrigin) {
  try {
    sessionStorage.setItem(KEY, JSON.stringify({ workspaceId, origin }));
  } catch {
    return; // storage is unavailable, the way back falls back to the list
  }
  listeners.forEach((listener) => listener());
}

export function workspaceOriginOf(workspaceId?: string): WorkspaceOrigin | null {
  const stored = read();
  return stored && stored.workspaceId === workspaceId ? stored.origin : null;
}

const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};

// Reading storage can throw, for example when it is blocked, and a snapshot that throws breaks the render.
const snapshot = () => {
  try {
    return sessionStorage.getItem(KEY);
  } catch {
    return null;
  }
};

export function useWorkspaceOrigin(workspaceId?: string): WorkspaceOrigin | null {
  const raw = useSyncExternalStore(subscribe, snapshot);
  return raw ? workspaceOriginOf(workspaceId) : null;
}

export const listOrigin = (organizationId: string): WorkspaceOrigin => ({
  kind: "list",
  path: `/organizations/${organizationId}/workspaces`,
});

export const projectOrigin = (organizationId: string, projectId: string): WorkspaceOrigin => ({
  kind: "project",
  path: `/organizations/${organizationId}/projects/${projectId}`,
});

/** The inbox, with the filters it had, so going back lands on the same list. */
export const inboxOrigin = (search = ""): WorkspaceOrigin => ({ kind: "inbox", path: `/inbox${search}` });
