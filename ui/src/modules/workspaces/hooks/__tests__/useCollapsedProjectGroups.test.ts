import { renderHook, act } from "@testing-library/react";
import { collapsedGroupsStorageKey, useCollapsedProjectGroups } from "../useCollapsedProjectGroups";

describe("useCollapsedProjectGroups", () => {
  beforeEach(() => {
    sessionStorage.clear();
  });

  it("starts with every group expanded", () => {
    const { result } = renderHook(() => useCollapsedProjectGroups("user-1", "org-1"));
    expect(result.current.collapsed.size).toBe(0);
  });

  it("toggles a group and stores it under the user and organization", () => {
    const { result } = renderHook(() => useCollapsedProjectGroups("user-1", "org-1"));

    act(() => result.current.toggle("proj-1"));
    expect(result.current.collapsed.has("proj-1")).toBe(true);
    expect(JSON.parse(sessionStorage.getItem(collapsedGroupsStorageKey("user-1", "org-1"))!)).toEqual(["proj-1"]);

    act(() => result.current.toggle("proj-1"));
    expect(result.current.collapsed.has("proj-1")).toBe(false);
    expect(JSON.parse(sessionStorage.getItem(collapsedGroupsStorageKey("user-1", "org-1"))!)).toEqual([]);
  });

  it("restores the choices after the page is left and reopened", () => {
    const first = renderHook(() => useCollapsedProjectGroups("user-1", "org-1"));
    act(() => first.result.current.toggle("proj-1"));
    first.unmount();

    const second = renderHook(() => useCollapsedProjectGroups("user-1", "org-1"));
    expect(second.result.current.collapsed.has("proj-1")).toBe(true);
  });

  it("keeps users and organizations apart", () => {
    const { result } = renderHook(() => useCollapsedProjectGroups("user-1", "org-1"));
    act(() => result.current.toggle("proj-1"));

    expect(renderHook(() => useCollapsedProjectGroups("user-2", "org-1")).result.current.collapsed.size).toBe(0);
    expect(renderHook(() => useCollapsedProjectGroups("user-1", "org-2")).result.current.collapsed.size).toBe(0);
  });

  it("loads the other scope's choices when the organization changes", () => {
    sessionStorage.setItem(collapsedGroupsStorageKey("user-1", "org-2"), JSON.stringify(["proj-9"]));
    const { result, rerender } = renderHook(({ org }) => useCollapsedProjectGroups("user-1", org), {
      initialProps: { org: "org-1" },
    });
    act(() => result.current.toggle("proj-1"));

    rerender({ org: "org-2" });
    expect([...result.current.collapsed]).toEqual(["proj-9"]);

    rerender({ org: "org-1" });
    expect([...result.current.collapsed]).toEqual(["proj-1"]);
  });

  it("collapses and expands several groups and leaves the other stored choices alone", () => {
    const { result } = renderHook(() => useCollapsedProjectGroups("user-1", "org-1"));
    act(() => result.current.toggle("on-another-page"));

    act(() => result.current.setCollapsed(["proj-1", "__unassigned__"], true));
    expect([...result.current.collapsed].sort()).toEqual(["__unassigned__", "on-another-page", "proj-1"]);

    act(() => result.current.setCollapsed(["proj-1", "__unassigned__"], false));
    expect([...result.current.collapsed]).toEqual(["on-another-page"]);
  });

  it("applies changes made back to back before the next render", () => {
    const { result } = renderHook(() => useCollapsedProjectGroups("user-1", "org-1"));
    act(() => {
      result.current.toggle("proj-1");
      result.current.toggle("proj-2");
    });
    expect([...result.current.collapsed].sort()).toEqual(["proj-1", "proj-2"]);
  });

  it("ignores stored data it cannot read", () => {
    sessionStorage.setItem(collapsedGroupsStorageKey("user-1", "org-1"), "{not json");
    expect(renderHook(() => useCollapsedProjectGroups("user-1", "org-1")).result.current.collapsed.size).toBe(0);

    sessionStorage.setItem(collapsedGroupsStorageKey("user-1", "org-1"), JSON.stringify({ a: 1 }));
    expect(renderHook(() => useCollapsedProjectGroups("user-1", "org-1")).result.current.collapsed.size).toBe(0);

    sessionStorage.setItem(collapsedGroupsStorageKey("user-1", "org-1"), JSON.stringify(["proj-1", 3, null]));
    expect([...renderHook(() => useCollapsedProjectGroups("user-1", "org-1")).result.current.collapsed]).toEqual([
      "proj-1",
    ]);
  });

  it("still works for the current view when storage is unavailable", () => {
    const setItem = jest.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("quota");
    });
    const { result } = renderHook(() => useCollapsedProjectGroups("user-1", "org-1"));

    act(() => result.current.toggle("proj-1"));
    expect(result.current.collapsed.has("proj-1")).toBe(true);
    setItem.mockRestore();
  });

  it("does not store anything before the user and organization are known", () => {
    const { result } = renderHook(() => useCollapsedProjectGroups(undefined, "org-1"));
    act(() => result.current.toggle("proj-1"));
    expect(result.current.collapsed.has("proj-1")).toBe(true);
    expect(sessionStorage.length).toBe(0);
  });
});
