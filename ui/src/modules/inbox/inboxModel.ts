export type InboxKind = "waiting" | "failed" | "policy";

export type InboxItem = {
  key: string;
  kind: InboxKind;
  orgId: string;
  orgName: string;
  workspaceId: string;
  name: string;
  projectName?: string;
  /** When the workspace last ran, the moment the thing started needing attention. */
  at?: string;
};

export type InboxCounts = Record<InboxKind, number>;

export type InboxData = {
  items: InboxItem[];
  /** The real totals, which can be larger than the rows listed. */
  counts: InboxCounts;
  /** Organizations that could not be read, so a quiet inbox is never mistaken for a healthy one. */
  failedOrganizations: string[];
};

export const KIND_ORDER: InboxKind[] = ["waiting", "failed", "policy"];

export const KIND_TITLES: Record<InboxKind, string> = {
  waiting: "Waiting for approval",
  failed: "Failed runs",
  policy: "Policy violations",
};

const time = (item: InboxItem) => (item.at ? new Date(item.at).getTime() : 0);

/**
 * Orders one kind of item for the person reading it. A run waiting for approval is blocking someone, so the one that
 * has waited longest comes first. A failure or violation is most useful when it is recent.
 */
export function sortItems(kind: InboxKind, items: InboxItem[]): InboxItem[] {
  return [...items].sort(
    (a, b) => (kind === "waiting" ? time(a) - time(b) : time(b) - time(a)) || a.name.localeCompare(b.name)
  );
}

export function emptyCounts(): InboxCounts {
  return { waiting: 0, failed: 0, policy: 0 };
}

export function totalCount(counts: InboxCounts): number {
  return counts.waiting + counts.failed + counts.policy;
}
