import { useState } from "react";
import { WorkspaceTagFilter } from "@/modules/workspaces/types";

export function useWorkspaceFilterState() {
  const [status, setStatus] = useState<string>(sessionStorage.getItem("filterValue") || "All");
  const [policyStatus, setPolicyStatusState] = useState<string>(sessionStorage.getItem("policyFilter") || "All");
  const [search, setSearch] = useState<string>(sessionStorage.getItem("searchValue") || "");
  const [tagFilters, setTagFilters] = useState<WorkspaceTagFilter[]>([]);
  const [projectId, setProjectIdState] = useState<string | null>(sessionStorage.getItem("projectFilter") || null);
  const [groupByProject, setGroupByProjectState] = useState<boolean>(
    localStorage.getItem("groupByProject") !== "false"
  );

  const setPolicyStatus = (value: string) => {
    setPolicyStatusState(value);
    sessionStorage.setItem("policyFilter", value);
  };

  const setProjectId = (value: string | null) => {
    setProjectIdState(value);
    sessionStorage.setItem("projectFilter", value ?? "");
  };

  const setGroupByProject = (value: boolean) => {
    setGroupByProjectState(value);
    localStorage.setItem("groupByProject", String(value));
  };

  return {
    status,
    setStatus,
    policyStatus,
    setPolicyStatus,
    search,
    setSearch,
    tagFilters,
    setTagFilters,
    projectId,
    setProjectId,
    groupByProject,
    setGroupByProject,
  };
}
