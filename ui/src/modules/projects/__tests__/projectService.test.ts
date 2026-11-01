import { parse } from "graphql";
import { apiPost } from "@/modules/api/apiWrapper";
import projectService from "../projectService";

jest.mock("@/config/axiosConfig", () => ({ __esModule: true, default: {} }));
jest.mock("@/modules/api/apiWrapper", () => ({ apiPost: jest.fn() }));

const mockApiPost = apiPost as jest.Mock;
const listProjectsWithCounts = (organizationId: string) => projectService.listProjects(organizationId, true);
const teamEdges = (names: string[]) => names.map((name) => ({ node: { name } }));
const projectNode = (id: string, names: string[] = []) => ({
  id,
  name: `Project ${id}`,
  description: "Description",
  projectAccess: { edges: teamEdges(names) },
});
const projectResponse = (nodes: ReturnType<typeof projectNode>[]) => ({
  isError: false,
  responseCode: 200,
  data: { organization: { edges: [{ node: { project: { edges: nodes.map((node) => ({ node })) } } }] } },
});
const countResponse = (counts: number[]) => ({
  isError: false,
  responseCode: 200,
  data: {
    organization: {
      edges: [
        {
          node: Object.fromEntries(counts.map((totalRecords, index) => [`p${index}`, { pageInfo: { totalRecords } }])),
        },
      ],
    },
  },
});

describe("projectService.listProjects counts", () => {
  beforeEach(() => mockApiPost.mockReset());

  it("uses permission-filtered API totals and counts each team only once", async () => {
    mockApiPost
      .mockResolvedValueOnce(projectResponse([projectNode("production", ["ops", "ops", "developers"])]))
      .mockResolvedValueOnce(countResponse([12345]));

    const response = await listProjectsWithCounts("org-1");

    expect(response.data).toEqual([
      {
        id: "production",
        name: "Project production",
        description: "Description",
        workspaceCount: 12345,
        teamCount: 2,
      },
    ]);
    const [path, { query, variables }, options] = mockApiPost.mock.calls[1];
    expect(path).toBe("/graphql/api/v1");
    expect(query).toContain('workspace(first: "1", filter: $p0)');
    expect(query).toContain("pageInfo { totalRecords }");
    expect(query).not.toContain("edges { node { id");
    expect(variables).toEqual({ organizationIds: ["org-1"], p0: 'project.id=="production"' });
    expect(options).toEqual({ dataWrapped: true, contentType: "application/json" });
    for (const [, body] of mockApiPost.mock.calls) expect(() => parse(body.query)).not.toThrow();
  });

  it("returns zero for empty or permission-filtered collections", async () => {
    mockApiPost
      .mockResolvedValueOnce(projectResponse([projectNode("empty")]))
      .mockResolvedValueOnce(countResponse([0]));

    expect((await listProjectsWithCounts("org-1")).data?.[0]).toMatchObject({
      workspaceCount: 0,
      teamCount: 0,
    });
  });

  it("does not fetch counts when there are no visible projects", async () => {
    mockApiPost.mockResolvedValueOnce(projectResponse([]));

    expect((await listProjectsWithCounts("org-1")).data).toEqual([]);
    expect(mockApiPost).toHaveBeenCalledTimes(1);
  });

  it("batches totals instead of issuing a separate request per project", async () => {
    const nodes = Array.from({ length: 51 }, (_, index) => projectNode(`project-${index}`));
    mockApiPost
      .mockResolvedValueOnce(projectResponse(nodes))
      .mockResolvedValueOnce(countResponse(Array.from({ length: 50 }, (_, index) => index)))
      .mockResolvedValueOnce(countResponse([100]));

    const response = await listProjectsWithCounts("org-1");

    expect(response.data).toHaveLength(51);
    expect(response.data?.[49].workspaceCount).toBe(49);
    expect(response.data?.[50].workspaceCount).toBe(100);
    expect(mockApiPost).toHaveBeenCalledTimes(3);
    expect(mockApiPost.mock.calls[2][1].variables).toEqual({
      organizationIds: ["org-1"],
      p0: 'project.id=="project-50"',
    });
  });

  it("counts teams beyond Elide's implicit assignment limit without counting boundary duplicates twice", async () => {
    const names = Array.from({ length: 499 }, (_, index) => `team-${String(index).padStart(3, "0")}`);
    const boundary = 'team-499 "operations"';
    mockApiPost
      .mockResolvedValueOnce(projectResponse([projectNode("large", [...names, boundary])]))
      .mockResolvedValueOnce({
        isError: false,
        responseCode: 200,
        data: { project: { edges: [{ node: { projectAccess: { edges: teamEdges(["team-500", "team-500"]) } } }] } },
      })
      .mockResolvedValueOnce(countResponse([0]));

    expect((await listProjectsWithCounts("org-1")).data?.[0].teamCount).toBe(501);
    const { query, variables } = mockApiPost.mock.calls[1][1];
    expect(variables).toEqual({
      projectIds: ["large"],
      filter: 'name=gt="team-499 \\"operations\\""',
    });
    expect(query).toContain('projectAccess(sort: "name", filter: $filter)');
    expect(() => parse(query)).not.toThrow();
  });

  it.each(["projects", "teams", "totals"])(
    "surfaces %s request failures instead of showing zero counts",
    async (stage) => {
      const failure = { isError: true, responseCode: 403, error: { status: "Forbidden" } };
      if (stage !== "projects") {
        mockApiPost.mockResolvedValueOnce(
          projectResponse([projectNode("p1", stage === "teams" ? Array(500).fill("ops") : [])])
        );
      }
      mockApiPost.mockResolvedValueOnce(failure);

      expect(await listProjectsWithCounts("org-1")).toEqual({ ...failure, data: [] });
    }
  );

  it("rejects missing totals rather than silently presenting them as zero", async () => {
    mockApiPost.mockResolvedValueOnce(projectResponse([projectNode("p1")])).mockResolvedValueOnce(countResponse([]));

    expect(await listProjectsWithCounts("org-1")).toMatchObject({
      isError: true,
      error: { status: "Failed to load project counts" },
      data: [],
    });
  });

  it("fetches current totals and team assignments again on every call", async () => {
    mockApiPost
      .mockResolvedValueOnce(projectResponse([projectNode("p1", ["ops"])]))
      .mockResolvedValueOnce(countResponse([1]))
      .mockResolvedValueOnce(projectResponse([projectNode("p1", ["ops", "devs"])]))
      .mockResolvedValueOnce(countResponse([2]));

    expect((await listProjectsWithCounts("org-1")).data?.[0]).toMatchObject({ workspaceCount: 1, teamCount: 1 });
    expect((await listProjectsWithCounts("org-1")).data?.[0]).toMatchObject({ workspaceCount: 2, teamCount: 2 });
  });

  it("keeps other project selectors lightweight without requesting counts or permissions", async () => {
    mockApiPost.mockResolvedValueOnce(projectResponse([projectNode("p1")]));

    expect((await projectService.listProjects("org-1")).data).toEqual([
      { id: "p1", name: "Project p1", description: "Description" },
    ]);
    expect(mockApiPost).toHaveBeenCalledTimes(1);
    expect(mockApiPost.mock.calls[0][1].query).not.toContain("projectAccess");
  });
});
