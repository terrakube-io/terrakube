import { WorkspaceListItem } from "../types";

export enum WorkspaceStatusFilter {
  All = "All",
  NeverExecuted = "NeverExecuted",
}

export enum PolicyComplianceFilter {
  All = "All",
  Compliant = "COMPLIANT",
  NonCompliant = "NON_COMPLIANT",
  Exempted = "EXEMPTED",
  Unknown = "UNKNOWN",
}

export type WorkspaceFilterValues = {
  status: string;
  policyStatus?: string;
  search: string;
  tagIds: string[];
  projectId: string | null;
};

export function filterWorkspaces(workspaces: WorkspaceListItem[], filters: WorkspaceFilterValues): WorkspaceListItem[] {
  let filtered =
    filters.status === WorkspaceStatusFilter.All
      ? workspaces
      : filters.status === WorkspaceStatusFilter.NeverExecuted
        ? workspaces.filter(
            (x) =>
              !x.lastStatus ||
              x.lastStatus === WorkspaceStatusFilter.NeverExecuted ||
              x.lastStatus.toLowerCase() === "neverexecuted"
          )
        : workspaces.filter(
            (x) => x.lastStatus === filters.status || x.lastStatus?.toLowerCase() === filters.status?.toLowerCase()
          );

  if (filters.policyStatus && filters.policyStatus !== PolicyComplianceFilter.All) {
    filtered = filtered.filter((workspace) => {
      if (filters.policyStatus === PolicyComplianceFilter.Unknown) {
        return !workspace.policyComplianceStatus || workspace.policyComplianceStatus.toUpperCase() === "UNKNOWN";
      }
      return workspace.policyComplianceStatus?.toUpperCase() === filters.policyStatus?.toUpperCase();
    });
  }

  filtered = filtered.filter((workspace) => {
    if (workspace.description) {
      return workspace.name.includes(filters.search) || workspace.description.includes(filters.search);
    }
    return workspace.name.includes(filters.search);
  });

  filtered = filtered.filter((workspace) => {
    if (filters.tagIds.length === 0) return true;
    return workspace.tags?.some((tag) => filters.tagIds.includes(tag));
  });

  filtered = filtered.filter((workspace) => {
    if (!filters.projectId) return true;
    if (filters.projectId === "__unassigned__") return !workspace.projectId;
    return workspace.projectId === filters.projectId;
  });

  return filtered;
}
