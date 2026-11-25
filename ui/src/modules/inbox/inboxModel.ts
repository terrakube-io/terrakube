export type InboxKind = "waiting" | "failed" | "drifted" | "policy";

export type InboxItem = {
  key: string;
  kind: InboxKind;
  orgId: string;
  orgName: string;
  workspaceId: string;
  name: string;
  projectName?: string;
  /** The team that owns the project, when it has one. */
  ownerTeam?: string;
  /** When the thing started needing attention: the last run, or for drift the day it was first seen. */
  at?: string;
  /** The run to open for this item, when there is a particular one (the plan of a drift check). */
  runId?: string;
};

export type InboxCounts = Record<InboxKind, number>;

export type InboxData = {
  items: InboxItem[];
  /** The real totals, which can be larger than the rows listed. */
  counts: InboxCounts;
  /** Organizations that could not be read, so a quiet inbox is never mistaken for a healthy one. */
  failedOrganizations: string[];
};

export const KIND_ORDER: InboxKind[] = ["waiting", "failed", "drifted", "policy"];

export const KIND_TITLES: Record<InboxKind, string> = {
  waiting: "Waiting for approval",
  failed: "Failed runs",
  drifted: "Drifted",
  policy: "Policy violations",
};

const time = (item: InboxItem) => (item.at ? new Date(item.at).getTime() : 0);

/**
 * Orders one kind of item for the person reading it. A run waiting for approval is blocking someone, and drift gets
 * worse the longer it is left, so the oldest of those come first. A failure or violation is most useful when it is
 * recent.
 */
export function sortItems(kind: InboxKind, items: InboxItem[]): InboxItem[] {
  return [...items].sort(
    (a, b) =>
      (kind === "waiting" || kind === "drifted" ? time(a) - time(b) : time(b) - time(a)) || a.name.localeCompare(b.name)
  );
}

export function emptyCounts(): InboxCounts {
  return { waiting: 0, failed: 0, drifted: 0, policy: 0 };
}

export function totalCount(counts: InboxCounts): number {
  return counts.waiting + counts.failed + counts.drifted + counts.policy;
}
