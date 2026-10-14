import axiosInstance from "@/config/axiosConfig";
import { getOrganizationDependencyGraph } from "../dependencyGraphService";

jest.mock("@/config/axiosConfig", () => ({
  __esModule: true,
  default: { get: jest.fn() },
  getErrorMessage: () => "fallback error",
}));

const mockGet = axiosInstance.get as jest.Mock;

beforeEach(() => {
  mockGet.mockReset();
});

describe("getOrganizationDependencyGraph", () => {
  it("filters run triggers by the source workspace's organization, not a single workspace", async () => {
    mockGet.mockResolvedValueOnce({ data: { data: [] } }); // workspaces
    mockGet.mockResolvedValueOnce({ data: { data: [] } }); // triggers

    await getOrganizationDependencyGraph("org-1");

    expect(mockGet).toHaveBeenCalledWith("organization/org-1/workspace");
    expect(mockGet).toHaveBeenCalledWith("runTrigger", {
      params: { "filter[runTrigger]": "sourceWorkspace.organization.id==org-1" },
    });
  });

  it("maps workspaces and edges from both responses", async () => {
    mockGet.mockResolvedValueOnce({
      data: { data: [{ id: "ws-1", attributes: { name: "network" } }] },
    });
    mockGet.mockResolvedValueOnce({
      data: {
        data: [
          {
            id: "edge-1",
            attributes: { synchronizationMode: "ALL", enabled: true, onDestroy: undefined },
            relationships: {
              sourceWorkspace: { data: { type: "workspace", id: "ws-1" } },
              destinationWorkspace: { data: { type: "workspace", id: "ws-2" } },
            },
          },
        ],
      },
    });

    const result = await getOrganizationDependencyGraph("org-1");

    expect(result.workspaces).toEqual([{ id: "ws-1", name: "network" }]);
    expect(result.edges).toEqual([
      {
        id: "edge-1",
        sourceWorkspaceId: "ws-1",
        destinationWorkspaceId: "ws-2",
        synchronizationMode: "ALL",
        enabled: true,
        onDestroy: undefined,
      },
    ]);
  });

  /** A dangling relationship (either end missing) must be dropped, not crash the whole graph. */
  it("drops an edge missing either end instead of throwing", async () => {
    mockGet.mockResolvedValueOnce({ data: { data: [] } });
    mockGet.mockResolvedValueOnce({
      data: {
        data: [
          {
            id: "edge-dangling",
            attributes: { synchronizationMode: "EACH" },
            relationships: { sourceWorkspace: { data: { type: "workspace", id: "ws-1" } } },
          },
        ],
      },
    });

    const result = await getOrganizationDependencyGraph("org-1");

    expect(result.edges).toEqual([]);
  });
});

it("fetches later workspace pages so large organizations never lose topology", async () => {
  mockGet.mockImplementation((url: string) =>
    Promise.resolve(
      url === "organization/org-1/workspace"
        ? { data: { data: [{ id: "one", attributes: { name: "first" } }], links: { next: "next-workspaces" } } }
        : url === "next-workspaces"
          ? { data: { data: [{ id: "two", attributes: { name: "second" } }] } }
          : { data: { data: [] } }
    )
  );
  expect((await getOrganizationDependencyGraph("org-1")).workspaces.map((workspace) => workspace.id)).toEqual([
    "one",
    "two",
  ]);
});
