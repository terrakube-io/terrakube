import { useState } from "react";

export function useWorkspaceFilterState() {
  const [status, setStatus] = useState<string>(sessionStorage.getItem("filterValue") || "All");
  const [policyStatus, setPolicyStatusState] = useState<string>(sessionStorage.getItem("policyFilter") || "All");
  const [search, setSearch] = useState<string>(sessionStorage.getItem("searchValue") || "");
  const [tagIds, setTagIds] = useState<string[]>([]);
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
    tagIds,
    setTagIds,
    projectId,
    setProjectId,
    groupByProject,
    setGroupByProject,
  };
}
