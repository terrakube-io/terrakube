import { apiPost } from "@/modules/api/apiWrapper";
import workspaceService from "../workspaceService";
import { Kind, parse } from "graphql";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

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
                        policyComplianceStatus: "COMPLIANT",
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
                        policyComplianceStatus: "NON_COMPLIANT",
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
    expect(result.data!.workspaces.find((w) => w.id === "ws-1")?.policyComplianceStatus).toBe("COMPLIANT");
    expect(result.data!.workspaces.find((w) => w.id === "ws-2")?.locked).toBe(false);
    expect(result.data!.workspaces.find((w) => w.id === "ws-2")?.policyComplianceStatus).toBe("NON_COMPLIANT");
  });
});

describe("workspaceService.listWorkspacePage", () => {
  beforeEach(() => mockApiPost.mockReset());

  it("matches the full request exercised by the API integration test", async () => {
    mockApiPost.mockResolvedValue({ isError: false, responseCode: 200, data: {} });
    await workspaceService.listWorkspacePage({
      organizationId: "d9b58bd3-f3fc-4056-a026-1163297e80a8",
      first: 20,
      after: 0,
      sort: "name_asc",
      status: "All",
    });
    const fixture = JSON.parse(
      readFileSync(resolve(__dirname, "../../../../../api/src/test/resources/workspace-page-request.json"), "utf8")
    );
    expect(JSON.parse(JSON.stringify(mockApiPost.mock.calls[0][1]))).toEqual(fixture);
  });

  it.each([true, false])("omits absent filter arguments with status counts %s", async (includeStatusCounts) => {
    mockApiPost.mockResolvedValue({ isError: false, responseCode: 200, data: {} });
    await workspaceService.listWorkspacePage(
      { organizationId: "org-1", first: 20, after: 0, sort: "name_asc", status: "All" },
      includeStatusCounts
    );
    const { query, variables } = JSON.parse(JSON.stringify(mockApiPost.mock.calls[0][1]));
    expect(() => parse(query)).not.toThrow();
    expect(query).not.toContain("$filter");
    expect(query).not.toContain("$statusAll");
    expect(query).not.toContain("$policyAll");
    expect(variables.filter).toBeUndefined();
    expect(variables.statusAll).toBeUndefined();
    expect(variables.policyAll).toBeUndefined();
  });

  it("omits status count queries during polling but keeps the page total", async () => {
    mockApiPost.mockResolvedValue({ isError: false, responseCode: 200, data: {} });
    await workspaceService.listWorkspacePage({ organizationId: "org-1", first: 20, after: 0, sort: "status" }, false);
    const { query, variables } = mockApiPost.mock.calls[0][1];
    const document = parse(query);
    expect(document.definitions[0].kind).toBe(Kind.OPERATION_DEFINITION);
    expect(query).toContain("pageInfo { endCursor hasNextPage totalRecords }");
    expect(query).not.toMatch(/\w+: workspace/);
    expect(query).not.toContain("$statusAll");
    expect(variables.sort).toBe("lastJobStatus,id");
  });

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
                statusAll: { pageInfo: { totalRecords: 42 } },
                status_waitingApproval: { pageInfo: { totalRecords: 1 } },
                status_failed: { pageInfo: { totalRecords: 2 } },
                status_pending: { pageInfo: { totalRecords: 3 } },
                status_queue: { pageInfo: { totalRecords: 4 } },
                status_running: { pageInfo: { totalRecords: 5 } },
                status_completed: { pageInfo: { totalRecords: 6 } },
                status_NeverExecuted: { pageInfo: { totalRecords: 7 } },
                policyAll: { pageInfo: { totalRecords: 5 } },
                policy_COMPLIANT: { pageInfo: { totalRecords: 3 } },
                policy_NON_COMPLIANT: { pageInfo: { totalRecords: 1 } },
                policy_EXEMPTED: { pageInfo: { totalRecords: 0 } },
                policy_UNKNOWN: { pageInfo: { totalRecords: 1 } },
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
      policyStatus: "NON_COMPLIANT",
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
            '(name=ini="*platform*",description=ini="*platform*");workspaceTag.tagId=in=("tag-1");project.id=="project-1";lastJobStatus=="running";policyComplianceStatus=="NON_COMPLIANT"',
          sort: "-lastJobDate,-id",
          statusAll:
            '(name=ini="*platform*",description=ini="*platform*");workspaceTag.tagId=in=("tag-1");project.id=="project-1";policyComplianceStatus=="NON_COMPLIANT"',
          policyAll:
            '(name=ini="*platform*",description=ini="*platform*");workspaceTag.tagId=in=("tag-1");project.id=="project-1";lastJobStatus=="running"',
          policy_UNKNOWN:
            '(name=ini="*platform*",description=ini="*platform*");workspaceTag.tagId=in=("tag-1");project.id=="project-1";lastJobStatus=="running";(policyComplianceStatus=isnull=true,policyComplianceStatus==UNKNOWN)',
        }),
      }),
      { dataWrapped: true, contentType: "application/json" }
    );
    expect(result.data?.organizationName).toBe("Acme");
    expect(mockApiPost.mock.calls[0][1].query).toContain("filter: $filter");
    expect(mockApiPost.mock.calls[0][1].query).toContain("filter: $statusAll");
    expect(mockApiPost.mock.calls[0][1].query).toContain("policy_COMPLIANT: workspace");
    expect(result.data?.pageInfo).toEqual({ endCursor: "40", hasNextPage: true, totalRecords: 42 });
    expect(result.data?.statusCounts.running).toBe(5);
    expect(result.data?.statusCounts.NeverExecuted).toBe(7);
    expect(result.data?.policyCounts).toEqual({ All: 5, COMPLIANT: 3, NON_COMPLIANT: 1, EXEMPTED: 0, UNKNOWN: 1 });
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
