import { apiPost } from "@/modules/api/apiWrapper";
import { buildProjectSummaryFilter, getProjectIndexCounts } from "../projectSummaryService";

jest.mock("@/modules/api/apiWrapper", () => ({ apiPost: jest.fn() }));

const post = apiPost as jest.Mock;

describe("getProjectIndexCounts", () => {
  beforeEach(() => {
    jest.resetAllMocks();
    post.mockResolvedValue({
      isError: false,
      responseCode: 200,
      data: {
        organization: {
          edges: [
            {
              node: {
                all: { pageInfo: { totalRecords: 60 } },
                attention: { pageInfo: { totalRecords: 55 } },
                mine: { pageInfo: { totalRecords: 3 } },
                empty: { pageInfo: { totalRecords: 1 } },
                stale: { pageInfo: { totalRecords: 0 } },
              },
            },
          ],
        },
      },
    });
  });

  it("reads every chip count in one request", async () => {
    const result = await getProjectIndexCounts("org-1", ["payments-devs"]);

    expect(post).toHaveBeenCalledTimes(1);
    expect(result.data).toEqual({ all: 60, attention: 55, mine: 3, empty: 1, stale: 0 });
  });

  it("sends the filters as variables, so a group name can never change the query text", async () => {
    await getProjectIndexCounts("org-1", ['evil" ) { id } #', "payments-devs"]);

    const { query, variables } = post.mock.calls[0][1];
    expect(query).not.toContain("evil");
    expect(query).toContain("filter: $mine");
    expect(variables.mine).toBe('projectAccess.name=in=("evil\\" ) { id } #","payments-devs")');
    expect(variables.attention).toBe("attentionCount=gt=0");
    expect(variables.orgId).toBe("org-1");
  });

  it("leaves out the My projects count for a person in no team", async () => {
    await getProjectIndexCounts("org-1", []);

    const { query, variables } = post.mock.calls[0][1];
    expect(query).not.toContain("mine:");
    expect(variables).not.toHaveProperty("mine");
  });
});

describe("buildProjectSummaryFilter", () => {
  it("drops characters that would change the meaning of the search", () => {
    expect(buildProjectSummaryFilter({ search: 'pay*"ments;(x)' })).toBe(
      "(name=ini=*pay  ments  x*,description=ini=*pay  ments  x*)"
    );
  });
});
