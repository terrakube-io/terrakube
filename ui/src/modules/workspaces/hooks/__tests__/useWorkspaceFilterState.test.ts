import { renderHook, act } from "@testing-library/react";
import { useWorkspaceFilterState } from "../useWorkspaceFilterState";

describe("useWorkspaceFilterState", () => {
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
  });

  it("defaults status to 'All', search to '', tagIds to [], projectId to null, groupByProject to true", () => {
    const { result } = renderHook(() => useWorkspaceFilterState());
    expect(result.current.status).toBe("All");
    expect(result.current.search).toBe("");
    expect(result.current.tagIds).toEqual([]);
    expect(result.current.projectId).toBeNull();
    expect(result.current.groupByProject).toBe(true);
  });

  it("initializes status/search from sessionStorage", () => {
    sessionStorage.setItem("filterValue", "running");
    sessionStorage.setItem("searchValue", "billing");
    const { result } = renderHook(() => useWorkspaceFilterState());
    expect(result.current.status).toBe("running");
    expect(result.current.search).toBe("billing");
  });

  it("initializes projectId from sessionStorage and groupByProject from localStorage", () => {
    sessionStorage.setItem("projectFilter", "proj-1");
    localStorage.setItem("groupByProject", "false");
    const { result } = renderHook(() => useWorkspaceFilterState());
    expect(result.current.projectId).toBe("proj-1");
    expect(result.current.groupByProject).toBe(false);
  });

  it("setProjectId updates state and persists to sessionStorage", () => {
    const { result } = renderHook(() => useWorkspaceFilterState());
    act(() => result.current.setProjectId("proj-2"));
    expect(result.current.projectId).toBe("proj-2");
    expect(sessionStorage.getItem("projectFilter")).toBe("proj-2");
  });

  it("setGroupByProject updates state and persists to localStorage", () => {
    const { result } = renderHook(() => useWorkspaceFilterState());
    act(() => result.current.setGroupByProject(false));
    expect(result.current.groupByProject).toBe(false);
    expect(localStorage.getItem("groupByProject")).toBe("false");
  });

  it("setTagIds updates state", () => {
    const { result } = renderHook(() => useWorkspaceFilterState());
    act(() => result.current.setTagIds(["tag-1", "tag-2"]));
    expect(result.current.tagIds).toEqual(["tag-1", "tag-2"]);
  });
});
