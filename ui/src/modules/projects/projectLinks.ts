import { ProjectSummaryModel } from "@/domain/types";
import { HealthKey } from "./projectHealth";

export type WorkspaceListFilter = { status?: string; policy?: string };

// The workspace status the list filters on for each part of the health bar. The list filters on one status, but the
// summary counts Completed as completed plus noChanges and Queued as pending, approved and queue. A link there would
// open fewer rows than the number it sits on, so those two have no link until the list can filter on the whole set.
const STATUS_FOR_HEALTH: Record<HealthKey, string | null> = {
  completed: null,
  failed: "failed",
  running: "running",
  queued: null,
  waiting: "waitingApproval",
  never: "NeverExecuted",
};

export function statusForHealth(key: HealthKey): string | null {
  return STATUS_FOR_HEALTH[key];
}

/** The workspaces tab of a project, optionally already filtered, so a number can link to the rows behind it. */
export function projectWorkspacesLink(orgid: string, projectId: string, filter: WorkspaceListFilter = {}): string {
  const params = new URLSearchParams();
  if (filter.status) params.set("status", filter.status);
  if (filter.policy) params.set("policy", filter.policy);
  const query = params.toString();
  return `/organizations/${orgid}/projects/${projectId}/workspaces${query ? `?${query}` : ""}`;
}

/**
 * Where "needs attention" should lead. Attention mixes failed runs, runs waiting for a person and policy violations,
 * which one list filter cannot express together, so the link goes to the worst one that is present.
 */
export function attentionFilter(summary: ProjectSummaryModel): WorkspaceListFilter {
  if (summary.failedCount > 0) return { status: "failed" };
  if (summary.waitingApprovalCount > 0) return { status: "waitingApproval" };
  if (summary.nonCompliantCount > 0) return { policy: "NON_COMPLIANT" };
  return {};
}

type Allowed = readonly string[];

/** Reads one query parameter, accepting only known values so a hand-edited link cannot break the page. */
export function readChoice<T extends string>(params: URLSearchParams, name: string, allowed: Allowed, fallback: T): T {
  const value = params.get(name);
  return value && allowed.includes(value) ? (value as T) : fallback;
}

export function readPositiveInt(params: URLSearchParams, name: string, fallback: number): number {
  const value = Number.parseInt(params.get(name) ?? "", 10);
  return Number.isFinite(value) && value >= 1 ? value : fallback;
}

/**
 * Writes the view state into the query string, leaving out anything at its default so a plain link stays plain.
 * Returns the existing params untouched when nothing changed, so the caller can skip a pointless navigation.
 */
export function withViewState(
  current: URLSearchParams,
  values: Record<string, string | number | undefined | null>,
  defaults: Record<string, string | number>
): URLSearchParams {
  const next = new URLSearchParams(current);
  Object.keys(values).forEach((name) => next.delete(name));
  Object.entries(values).forEach(([name, value]) => {
    if (value === undefined || value === null || value === "" || value === defaults[name]) return;
    next.set(name, String(value));
  });
  return next.toString() === current.toString() ? current : next;
}
