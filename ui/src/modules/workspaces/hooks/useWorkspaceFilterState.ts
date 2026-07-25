import { useState } from "react";

export function useWorkspaceFilterState() {
  const [status, setStatus] = useState<string>(sessionStorage.getItem("filterValue") || "All");
  const [search, setSearch] = useState<string>(sessionStorage.getItem("searchValue") || "");
  const [tagIds, setTagIds] = useState<string[]>([]);
  const [projectId, setProjectIdState] = useState<string | null>(sessionStorage.getItem("projectFilter") || null);
  const [groupByProject, setGroupByProjectState] = useState<boolean>(
    sessionStorage.getItem("groupByProject") !== "false"
  );

  const setProjectId = (value: string | null) => {
    setProjectIdState(value);
    sessionStorage.setItem("projectFilter", value ?? "");
  };

  const setGroupByProject = (value: boolean) => {
    setGroupByProjectState(value);
    sessionStorage.setItem("groupByProject", String(value));
  };

  return {
    status,
    setStatus,
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
