import { apiPost } from "@/modules/api/apiWrapper";
import projectService, { chipFilter } from "../projectService";

jest.mock("@/modules/api/apiWrapper", () => ({ apiPost: jest.fn() }));
jest.mock("@/config/axiosConfig", () => ({ __esModule: true, default: {} }));

const post = apiPost as jest.Mock;
const sent = () => post.mock.calls[0][1] as { query: string; variables: Record<string, unknown> };

describe("chipFilter", () => {
  it("escapes a team name instead of dropping its quote, so the team still matches itself", () => {
    expect(chipFilter("mine", ["O'Reilly Ops", 'say "hi"'])).toBe(
      `projectAccess.name=in=("O'Reilly Ops","say \\"hi\\"")`
    );
  });

  it("matches nothing, not everything, for My projects without any team", () => {
    expect(chipFilter("mine", [])).toBe("workspaceCount<0");
  });

  it("has a filter for each of the other chips and none for All", () => {
    expect(chipFilter("attention")).toBe("attentionCount=gt=0");
    expect(chipFilter("empty")).toBe("workspaceCount==0");
    expect(chipFilter("stale")).toMatch(/^lastRunAt=lt=\d{4}-\d{2}-\d{2}T/);
    expect(chipFilter("all")).toBeUndefined();
  });
});

describe("getProjectIndexCounts", () => {
  beforeEach(() => jest.clearAllMocks());

  it("sends the filters as variables and reads each total from its alias", async () => {
    post.mockResolvedValue({
      isError: false,
      responseCode: 200,
      data: {
        organization: {
          edges: [
            {
              node: {
                all: { pageInfo: { totalRecords: 50 } },
                attention: { pageInfo: { totalRecords: 3 } },
                mine: { pageInfo: { totalRecords: 2 } },
                empty: { pageInfo: { totalRecords: 1 } },
                stale: { pageInfo: { totalRecords: 4 } },
              },
            },
          ],
        },
      },
    });

    const result = await projectService.getProjectIndexCounts("org", ["O'Reilly Ops"]);

    expect(result.data).toEqual({ all: 50, attention: 3, mine: 2, empty: 1, stale: 4 });
    expect(sent().query).not.toContain("O'Reilly");
    expect(sent().variables.mine).toBe(`projectAccess.name=in=("O'Reilly Ops")`);
    expect(sent().variables.attention).toBe("attentionCount=gt=0");
  });

  it("asks nothing about My projects when the person is in no team", async () => {
    post.mockResolvedValue({ isError: false, responseCode: 200, data: { organization: { edges: [{ node: {} }] } } });

    const result = await projectService.getProjectIndexCounts("org", []);

    expect(sent().query).not.toContain("mine: projectSummary");
    expect(result.data?.mine).toBe(0);
  });
});

describe("getUnassignedSummary", () => {
  beforeEach(() => jest.clearAllMocks());

  it("counts a workspace with no status as never run and reads the last run from one that ran", async () => {
    post.mockResolvedValue({
      isError: false,
      responseCode: 200,
      data: {
        organization: {
          edges: [
            {
              node: {
                total: { pageInfo: { totalRecords: 9 } },
                never: { pageInfo: { totalRecords: 4 } },
                latest: { edges: [{ node: { lastJobDate: "2026-11-01T10:00:00Z" } }] },
              },
            },
          ],
        },
      },
    });

    const result = await projectService.getUnassignedSummary("org");

    expect(sent().query).toContain("lastJobStatus=isnull=true");
    expect(sent().query).toContain("lastJobDate=isnull=false");
    expect(result.data).toMatchObject({ id: "unassigned", workspaceCount: 9, neverRunCount: 4 });
    expect(result.data?.lastRunAt).toBe("2026-11-01T10:00:00Z");
  });
});

describe("listAttentionWorkspaces", () => {
  beforeEach(() => jest.clearAllMocks());

  it("quotes the project id", async () => {
    post.mockResolvedValue({ isError: false, responseCode: 200, data: { organization: { edges: [{ node: {} }] } } });

    await projectService.listAttentionWorkspaces("org", 'p"1');

    expect(sent().variables.filter).toContain(`project.id=="p\\"1"`);
  });
});
