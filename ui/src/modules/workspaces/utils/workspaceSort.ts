import { JobStatus } from "../../../domain/types";
import { WorkspaceListItem } from "../types";

/** Combined sort option: field + direction in one value for the UI */
export type WorkspaceSortOption =
  | "name_asc"
  | "name_desc"
  | "lastRun_desc"
  | "lastRun_asc"
  | "status"
  | "source_asc"
  | "source_desc"
  | "terraformVersion_asc"
  | "terraformVersion_desc";

const SORT_STORAGE_KEY = "workspaceSortValue";

/** Order for "sort by status" (lower index = earlier in list when ascending) */
const STATUS_ORDER: (JobStatus | "NeverExecuted")[] = [
  JobStatus.Running,
  JobStatus.Queue,
  JobStatus.WaitingApproval,
  JobStatus.Failed,
  JobStatus.Rejected,
  JobStatus.Cancelled,
  JobStatus.Completed,
  JobStatus.NoChanges,
  JobStatus.NotExecuted,
  JobStatus.Approved,
  JobStatus.Pending,
  JobStatus.Unknown,
  "NeverExecuted",
];

function statusRank(s: JobStatus | undefined): number {
  const idx = STATUS_ORDER.indexOf(s ?? ("NeverExecuted" as const));
  return idx === -1 ? STATUS_ORDER.length : idx;
}

function parseDate(iso: string | undefined): number {
  if (!iso) return 0;
  const t = new Date(iso).getTime();
  return Number.isNaN(t) ? 0 : t;
}

function str(a: string | undefined, b: string | undefined): number {
  const A = (a ?? "").toLowerCase();
  const B = (b ?? "").toLowerCase();
  return A.localeCompare(B, undefined, { sensitivity: "base" });
}

export function sortWorkspaces(workspaces: WorkspaceListItem[], option: WorkspaceSortOption): WorkspaceListItem[] {
  const list = [...workspaces];

  switch (option) {
    case "name_asc":
      return list.sort((a, b) => str(a.name, b.name));
    case "name_desc":
      return list.sort((a, b) => str(b.name, a.name));
    case "lastRun_desc":
      return list.sort((a, b) => parseDate(b.lastRun) - parseDate(a.lastRun));
    case "lastRun_asc":
      return list.sort((a, b) => parseDate(a.lastRun) - parseDate(b.lastRun));
    case "status":
      return list.sort((a, b) => statusRank(a.lastStatus) - statusRank(b.lastStatus));
    case "source_asc":
      return list.sort((a, b) => str(a.source ?? a.normalizedSource, b.source ?? b.normalizedSource));
    case "source_desc":
      return list.sort((a, b) => str(b.source ?? b.normalizedSource, a.source ?? a.normalizedSource));
    case "terraformVersion_asc":
      return list.sort((a, b) => str(a.terraformVersion, b.terraformVersion));
    case "terraformVersion_desc":
      return list.sort((a, b) => str(b.terraformVersion, a.terraformVersion));
    default:
      return list;
  }
}

export function getStoredWorkspaceSortOption(): WorkspaceSortOption {
  const stored = sessionStorage.getItem(SORT_STORAGE_KEY);
  const valid: WorkspaceSortOption[] = [
    "name_asc",
    "name_desc",
    "lastRun_desc",
    "lastRun_asc",
    "status",
    "source_asc",
    "source_desc",
    "terraformVersion_asc",
    "terraformVersion_desc",
  ];
  if (stored && valid.includes(stored as WorkspaceSortOption)) {
    return stored as WorkspaceSortOption;
  }
  return "name_asc";
}

export function setStoredWorkspaceSortOption(option: WorkspaceSortOption): void {
  sessionStorage.setItem(SORT_STORAGE_KEY, option);
}

/** Options for the Sort by dropdown (combined field + direction) */
export const WORKSPACE_SORT_OPTIONS: { label: string; value: WorkspaceSortOption }[] = [
  { label: "Name (A → Z)", value: "name_asc" },
  { label: "Name (Z → A)", value: "name_desc" },
  { label: "Last run (newest first)", value: "lastRun_desc" },
  { label: "Last run (oldest first)", value: "lastRun_asc" },
  { label: "Job status", value: "status" },
  { label: "Repository (A → Z)", value: "source_asc" },
  { label: "Repository (Z → A)", value: "source_desc" },
  { label: "Terraform version (A → Z)", value: "terraformVersion_asc" },
  { label: "Terraform version (Z → A)", value: "terraformVersion_desc" },
];
