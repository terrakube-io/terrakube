import { apiPost } from "@/modules/api/apiWrapper";
import workspaceService from "../workspaceService";

jest.mock("@/modules/api/apiWrapper", () => ({
  __esModule: true,
  apiPost: jest.fn(),
}));

const mockApiPost = apiPost as jest.Mock;

describe("workspaceService.listWorkspaces", () => {
  beforeEach(() => mockApiPost.mockReset());

  it("maps the locked field from the GraphQL response", async () => {
    mockApiPost.mockResolvedValue({
      isError: false,
      responseCode: 200,
      data: {
        organization: {
          edges: [
            {
              node: {
                id: "org-1",
                name: "acme",
                workspace: {
                  edges: [
                    {
                      node: {
                        id: "ws-1",
                        name: "locked-ws",
                        description: null,
                        source: "",
                        branch: "main",
                        terraformVersion: "1.9.2",
                        iacType: "terraform",
                        lastJobStatus: null,
                        lastJobDate: null,
                        locked: true,
                        workspaceTag: { edges: [] },
                        project: { edges: [] },
                      },
                    },
                    {
                      node: {
                        id: "ws-2",
                        name: "unlocked-ws",
                        description: null,
                        source: "",
                        branch: "main",
                        terraformVersion: "1.9.2",
                        iacType: "terraform",
                        lastJobStatus: null,
                        lastJobDate: null,
                        locked: false,
                        workspaceTag: { edges: [] },
                        project: { edges: [] },
                      },
                    },
                  ],
                },
              },
            },
          ],
        },
      },
    });

    const result = await workspaceService.listWorkspaces("org-1");

    expect(result.data!.workspaces.find((w) => w.id === "ws-1")?.locked).toBe(true);
    expect(result.data!.workspaces.find((w) => w.id === "ws-2")?.locked).toBe(false);
  });
});

describe("workspaceService.listWorkspacePage", () => {
  beforeEach(() => mockApiPost.mockReset());

  it("sends cursor, filters, and sorting as GraphQL variables and maps the page", async () => {
    mockApiPost.mockResolvedValue({
      isError: false,
      responseCode: 200,
      data: {
        workspacePage: {
          nodes: [
            {
              id: "ws-1",
              name: "platform",
              source: "git@github.com:acme/platform.git",
              iacType: "terraform",
              lastJobStatus: "running",
              lastJobDate: "2026-09-03T12:00:00Z",
              locked: false,
              tagIds: ["tag-1"],
              projectId: "project-1",
              projectName: "Platform",
            },
          ],
          pageInfo: { endCursor: "opaque-next", hasNextPage: true, totalRecords: 42 },
          statusCounts: {
            all: 42,
            waitingApproval: 1,
            failed: 2,
            pending: 3,
            queue: 4,
            running: 5,
            completed: 6,
            neverExecuted: 7,
          },
        },
      },
    });

    const result = await workspaceService.listWorkspacePage({
      organizationId: "org-1",
      first: 20,
      after: "opaque-current",
      search: "platform",
      status: "running",
      tagIds: ["tag-1"],
      projectId: "project-1",
      sort: "lastRun_desc",
    });

    expect(mockApiPost).toHaveBeenCalledWith(
      "/graphql",
      expect.objectContaining({
        variables: {
          organizationId: "org-1",
          first: 20,
          after: "opaque-current",
          search: "platform",
          status: "running",
          tagIds: ["tag-1"],
          projectId: "project-1",
          sort: "LAST_RUN_DESC",
        },
      }),
      { dataWrapped: true, contentType: "application/json" }
    );
    expect(result.data?.pageInfo).toEqual({ endCursor: "opaque-next", hasNextPage: true, totalRecords: 42 });
    expect(result.data?.statusCounts.running).toBe(5);
    expect(result.data?.workspaces[0]).toEqual(
      expect.objectContaining({
        id: "ws-1",
        normalizedSource: "https://github.com/acme/platform",
        tags: ["tag-1"],
        projectId: "project-1",
      })
    );
  });
});
