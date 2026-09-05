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

  it("uses Elide pagination, RSQL filtering, sorting, and page totals", async () => {
    mockApiPost.mockResolvedValue({
      isError: false,
      responseCode: 200,
      data: {
        organization: {
          edges: [
            {
              node: {
                name: "Acme",
                workspace: {
                  edges: [
                    {
                      node: {
                        id: "ws-1",
                        name: "platform",
                        source: "git@github.com:acme/platform.git",
                        iacType: "terraform",
                        lastJobStatus: "running",
                        lastJobDate: "2026-09-03T12:00:00Z",
                        locked: false,
                        workspaceTag: { edges: [{ node: { tagId: "tag-1" } }] },
                        project: { edges: [{ node: { id: "project-1", name: "Platform" } }] },
                      },
                    },
                  ],
                  pageInfo: { endCursor: "40", hasNextPage: true, totalRecords: 42 },
                },
                all: { pageInfo: { totalRecords: 42 } },
                waitingApproval: { pageInfo: { totalRecords: 1 } },
                failed: { pageInfo: { totalRecords: 2 } },
                pending: { pageInfo: { totalRecords: 3 } },
                queue: { pageInfo: { totalRecords: 4 } },
                running: { pageInfo: { totalRecords: 5 } },
                completed: { pageInfo: { totalRecords: 6 } },
                neverExecuted: { pageInfo: { totalRecords: 7 } },
              },
            },
          ],
        },
      },
    });

    const result = await workspaceService.listWorkspacePage({
      organizationId: "org-1",
      first: 20,
      after: 20,
      search: "platform",
      status: "running",
      tagIds: ["tag-1"],
      projectId: "project-1",
      sort: "lastRun_desc",
    });

    expect(mockApiPost).toHaveBeenCalledWith(
      "/graphql/api/v1",
      expect.objectContaining({
        variables: expect.objectContaining({
          organizationIds: ["org-1"],
          first: "20",
          after: "20",
          filter:
            '(name=ini="*platform*",description=ini="*platform*");workspaceTag.tagId=in=("tag-1");project.id=="project-1";lastJobStatus=="running"',
          sort: "-lastJobDate,-id",
          allFilter:
            '(name=ini="*platform*",description=ini="*platform*");workspaceTag.tagId=in=("tag-1");project.id=="project-1"',
        }),
      }),
      { dataWrapped: true, contentType: "application/json" }
    );
    expect(result.data?.organizationName).toBe("Acme");
    expect(result.data?.pageInfo).toEqual({ endCursor: "40", hasNextPage: true, totalRecords: 42 });
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
