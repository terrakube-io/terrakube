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
                        workspaceTag: { edges: [{ node: { tagId: "tag-1", value: "prod" } }] },
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
      tagFilters: [{ tagId: "tag-1", value: "prod" }],
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
            '(name=ini="*platform*",description=ini="*platform*");workspaceTag.tagId=="tag-1";workspaceTag.value=="prod";project.id=="project-1";lastJobStatus=="running";policyComplianceStatus=="NON_COMPLIANT"',
          sort: "-lastJobDate,-id",
          statusAll:
            '(name=ini="*platform*",description=ini="*platform*");workspaceTag.tagId=="tag-1";workspaceTag.value=="prod";project.id=="project-1";policyComplianceStatus=="NON_COMPLIANT"',
          policyAll:
            '(name=ini="*platform*",description=ini="*platform*");workspaceTag.tagId=="tag-1";workspaceTag.value=="prod";project.id=="project-1";lastJobStatus=="running"',
          policy_UNKNOWN:
            '(name=ini="*platform*",description=ini="*platform*");workspaceTag.tagId=="tag-1";workspaceTag.value=="prod";project.id=="project-1";lastJobStatus=="running";(policyComplianceStatus=isnull=true,policyComplianceStatus==UNKNOWN)',
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
        tags: [{ tagId: "tag-1", value: "prod" }],
        projectId: "project-1",
      })
    );
  });

  // Elide resolves every predicate on workspaceTag against one join alias, so an AND of two tag filters
  // cannot be written as a single RSQL expression. The service resolves the ids of each filter first.
  function tagMatchResponse(matches: string[][]) {
    return {
      isError: false,
      responseCode: 200,
      data: {
        organization: {
          edges: [
            {
              node: Object.fromEntries(
                matches.map((ids, index) => [
                  `p${index}`,
                  { edges: ids.map((id) => ({ node: { id } })), pageInfo: { totalRecords: ids.length } },
                ])
              ),
            },
          ],
        },
      },
    };
  }

  const emptyPageResponse = {
    isError: false,
    responseCode: 200,
    data: {
      organization: {
        edges: [
          { node: { name: "Acme", workspace: { edges: [], pageInfo: { hasNextPage: false, totalRecords: 0 } } } },
        ],
      },
    },
  };

  it("ands several tag filters by intersecting the ids each one matches", async () => {
    mockApiPost
      .mockResolvedValueOnce(
        tagMatchResponse([
          ["ws-1", "ws-2"],
          ["ws-2", "ws-3"],
        ])
      )
      .mockResolvedValueOnce(emptyPageResponse);

    await workspaceService.listWorkspacePage(
      {
        organizationId: "org-and",
        first: 20,
        after: 0,
        tagFilters: [{ tagId: "tag-env", value: "prod" }, { tagId: "tag-team" }],
        sort: "name_asc",
      },
      false
    );

    const [, idQuery] = mockApiPost.mock.calls[0];
    expect(idQuery.variables.p0).toBe('workspaceTag.tagId=="tag-env";workspaceTag.value=="prod"');
    expect(idQuery.variables.p1).toBe('workspaceTag.tagId=="tag-team"');

    const [, pageQuery] = mockApiPost.mock.calls[1];
    expect(pageQuery.variables.filter).toBe('id=in=("ws-2")');
  });

  it("asks for nothing when the tag filters have no workspace in common", async () => {
    mockApiPost.mockResolvedValueOnce(tagMatchResponse([["ws-1"], ["ws-2"]])).mockResolvedValueOnce(emptyPageResponse);

    const result = await workspaceService.listWorkspacePage(
      {
        organizationId: "org-disjoint",
        first: 20,
        after: 0,
        search: "api",
        tagFilters: [
          { tagId: "tag-env", value: "prod" },
          { tagId: "tag-team", value: "infra" },
        ],
        sort: "name_asc",
      },
      false
    );

    const [, pageQuery] = mockApiPost.mock.calls[1];
    expect(pageQuery.variables.filter).toBe('(name=ini="*api*",description=ini="*api*");id=isnull=true');
    expect(result.data?.workspaces).toEqual([]);
    expect(result.data?.organizationName).toBe("Acme");
  });

  it("reuses the resolved ids across page turns instead of resolving them again", async () => {
    mockApiPost
      .mockResolvedValueOnce(tagMatchResponse([["ws-1", "ws-2"], ["ws-2"]]))
      .mockResolvedValue(emptyPageResponse);

    const request = {
      organizationId: "org-cache",
      first: 20,
      after: 0,
      tagFilters: [{ tagId: "tag-env", value: "prod" }, { tagId: "tag-team" }],
      sort: "name_asc" as const,
    };
    await workspaceService.listWorkspacePage(request, false);
    await workspaceService.listWorkspacePage({ ...request, after: 20 }, false);

    // One resolution, two page requests.
    expect(mockApiPost).toHaveBeenCalledTimes(3);
    expect(mockApiPost.mock.calls[2][1].variables.filter).toBe('id=in=("ws-2")');
  });

  const andRequest = (organizationId: string) => ({
    organizationId,
    first: 20,
    after: 0,
    tagFilters: [{ tagId: "tag-env", value: "prod" }, { tagId: "tag-team" }],
    sort: "name_asc" as const,
  });

  it("fails the page instead of showing no workspaces when resolving the ids fails", async () => {
    mockApiPost.mockResolvedValueOnce({
      isError: true,
      responseCode: 200,
      error: { status: "GraphQL request failed", message: "boom" },
    });

    const result = await workspaceService.listWorkspacePage(andRequest("org-error"), false);

    expect(result.isError).toBe(true);
    expect(result.error.message).toBe("boom");
    // The page itself is not requested with a filter that would match nothing
    expect(mockApiPost).toHaveBeenCalledTimes(1);
  });

  it("does not keep a failed resolution, so the next request tries again", async () => {
    mockApiPost
      .mockResolvedValueOnce({ isError: true, responseCode: 500, error: { message: "boom" } })
      .mockResolvedValueOnce(tagMatchResponse([["ws-1"], ["ws-1"]]))
      .mockResolvedValueOnce(emptyPageResponse);

    await workspaceService.listWorkspacePage(andRequest("org-retry"), false);
    await workspaceService.listWorkspacePage(andRequest("org-retry"), false);

    expect(mockApiPost.mock.calls[2][1].variables.filter).toBe('id=in=("ws-1")');
  });

  it("refuses an AND of tag filters that matches more workspaces than can be sent back", async () => {
    const many = Array.from({ length: 1001 }, (_, index) => `ws-${index}`);
    mockApiPost.mockResolvedValueOnce(tagMatchResponse([many, many]));

    const result = await workspaceService.listWorkspacePage(andRequest("org-many"), false);

    expect(result.isError).toBe(true);
    expect(result.error.status).toBe("Too many matching workspaces");
    expect(mockApiPost).toHaveBeenCalledTimes(1);
  });

  it("refuses to intersect a tag filter whose matches did not fit in one page", async () => {
    const truncated = tagMatchResponse([["ws-1"], ["ws-1"]]);
    truncated.data.organization.edges[0].node.p0.pageInfo.totalRecords = 10001;
    mockApiPost.mockResolvedValueOnce(truncated);

    const result = await workspaceService.listWorkspacePage(andRequest("org-truncated"), false);

    expect(result.isError).toBe(true);
    expect(mockApiPost).toHaveBeenCalledTimes(1);
  });

  it("resolves the ids again once the cache is cleared", async () => {
    mockApiPost
      .mockResolvedValueOnce(tagMatchResponse([["ws-1"], ["ws-1"]]))
      .mockResolvedValueOnce(emptyPageResponse)
      .mockResolvedValueOnce(tagMatchResponse([["ws-2"], ["ws-2"]]))
      .mockResolvedValueOnce(emptyPageResponse);

    await workspaceService.listWorkspacePage(andRequest("org-clear"), false);
    workspaceService.clearTagFilterCache();
    await workspaceService.listWorkspacePage(andRequest("org-clear"), false);

    expect(mockApiPost.mock.calls[3][1].variables.filter).toBe('id=in=("ws-2")');
  });

  it("sends a single tag filter as one query, without resolving ids", async () => {
    mockApiPost.mockResolvedValueOnce(emptyPageResponse);

    await workspaceService.listWorkspacePage(
      {
        organizationId: "org-single",
        first: 20,
        after: 0,
        tagFilters: [{ tagId: "tag-env", value: "prod" }],
        sort: "name_asc",
      },
      false
    );

    expect(mockApiPost).toHaveBeenCalledTimes(1);
    expect(mockApiPost.mock.calls[0][1].variables.filter).toBe(
      'workspaceTag.tagId=="tag-env";workspaceTag.value=="prod"'
    );
  });
});
