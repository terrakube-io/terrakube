import { DependencyGraphData } from "../dependencyGraphService";
import { filterToNeighborhood } from "../utils/graphFilter";

// a -> b -> c -> d, a chain long enough to prove hop limiting actually stops somewhere.
const CHAIN: DependencyGraphData = {
  workspaces: [
    { id: "a", name: "a" },
    { id: "b", name: "b" },
    { id: "c", name: "c" },
    { id: "d", name: "d" },
  ],
  edges: [
    { id: "e1", sourceWorkspaceId: "a", destinationWorkspaceId: "b", synchronizationMode: "EACH" as never },
    { id: "e2", sourceWorkspaceId: "b", destinationWorkspaceId: "c", synchronizationMode: "EACH" as never },
    { id: "e3", sourceWorkspaceId: "c", destinationWorkspaceId: "d", synchronizationMode: "EACH" as never },
  ],
};

describe("filterToNeighborhood", () => {
  it("includes the focus workspace even with zero edges", () => {
    const isolated: DependencyGraphData = { workspaces: [{ id: "lonely", name: "lonely" }], edges: [] };

    const result = filterToNeighborhood(isolated, "lonely", 2);

    expect(result.workspaces.map((w) => w.id)).toEqual(["lonely"]);
  });

  it("stops at the configured hop count", () => {
    const result = filterToNeighborhood(CHAIN, "a", 1);

    expect(result.workspaces.map((w) => w.id).sort()).toEqual(["a", "b"]);
    expect(result.edges.map((e) => e.id)).toEqual(["e1"]);
  });

  it("expands further with a larger hop count", () => {
    const result = filterToNeighborhood(CHAIN, "a", 3);

    expect(result.workspaces.map((w) => w.id).sort()).toEqual(["a", "b", "c", "d"]);
  });

  /** A dependency graph scoped to one workspace must show what it depends on too, not just its dependents. */
  it("traverses backwards as well as forwards", () => {
    const result = filterToNeighborhood(CHAIN, "c", 1);

    expect(result.workspaces.map((w) => w.id).sort()).toEqual(["b", "c", "d"]);
  });

  it("returns nothing for a workspace id that isn't in the graph at all", () => {
    const result = filterToNeighborhood(CHAIN, "does-not-exist", 2);

    expect(result.workspaces).toEqual([]);
    expect(result.edges).toEqual([]);
  });

  /** A diamond's join must only be counted (and included) once, same as the backend's snapshot. */
  it("does not include the same workspace twice when two paths reach it", () => {
    const diamond: DependencyGraphData = {
      workspaces: [
        { id: "root", name: "root" },
        { id: "left", name: "left" },
        { id: "right", name: "right" },
        { id: "join", name: "join" },
      ],
      edges: [
        { id: "e1", sourceWorkspaceId: "root", destinationWorkspaceId: "left", synchronizationMode: "EACH" as never },
        { id: "e2", sourceWorkspaceId: "root", destinationWorkspaceId: "right", synchronizationMode: "EACH" as never },
        { id: "e3", sourceWorkspaceId: "left", destinationWorkspaceId: "join", synchronizationMode: "ALL" as never },
        { id: "e4", sourceWorkspaceId: "right", destinationWorkspaceId: "join", synchronizationMode: "ALL" as never },
      ],
    };

    const result = filterToNeighborhood(diamond, "root", 2);

    expect(result.workspaces.map((w) => w.id).sort()).toEqual(["join", "left", "right", "root"]);
  });
});
