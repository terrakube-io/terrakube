import { renderHook } from "@testing-library/react";
import {
  inboxOrigin,
  listOrigin,
  projectOrigin,
  rememberWorkspaceOrigin,
  useWorkspaceOrigin,
  workspaceOriginOf,
} from "../workspaceOrigin";

describe("workspaceOrigin", () => {
  beforeEach(() => sessionStorage.clear());

  it("knows nothing for a workspace opened without an entry point", () => {
    expect(workspaceOriginOf("ws-1")).toBeNull();
  });

  it("returns the origin of the workspace that was opened, and nothing for another one", () => {
    rememberWorkspaceOrigin("ws-1", projectOrigin("org-1", "p-1"));
    expect(workspaceOriginOf("ws-1")).toEqual({ kind: "project", path: "/organizations/org-1/projects/p-1" });
    expect(workspaceOriginOf("ws-2")).toBeNull();
  });

  it("is replaced by the next entry point, so the list wins after opening from the list", () => {
    rememberWorkspaceOrigin("ws-1", projectOrigin("org-1", "p-1"));
    rememberWorkspaceOrigin("ws-1", listOrigin("org-1"));
    expect(workspaceOriginOf("ws-1")?.kind).toBe("list");
    rememberWorkspaceOrigin("ws-1", inboxOrigin());
    expect(workspaceOriginOf("ws-1")).toEqual({ kind: "inbox", path: "/inbox" });
  });

  it("ignores a broken stored value", () => {
    sessionStorage.setItem("terrakube.workspaceOrigin", "{");
    expect(workspaceOriginOf("ws-1")).toBeNull();
  });

  it("renders without an origin, and does not crash, when storage cannot be read", () => {
    const spy = jest.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    try {
      const { result } = renderHook(() => useWorkspaceOrigin("ws-1"));
      expect(result.current).toBeNull();
    } finally {
      spy.mockRestore();
    }
  });
});
