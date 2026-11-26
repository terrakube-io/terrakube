import { apiPost } from "@/modules/api/apiWrapper";
import { listRunPolicyResults, worstResult } from "../runPolicyService";

jest.mock("@/modules/api/apiWrapper");
const post = apiPost as jest.Mock;

describe("worstResult", () => {
  it("puts a failure before a warning, and anything before a pass", () => {
    expect(worstResult(["PASSED", "FAILED", "WARNING"])).toBe("FAILED");
    expect(worstResult(["PASSED", "WARNING"])).toBe("WARNING");
    expect(worstResult(["PASSED", "OVERRIDDEN"])).toBe("OVERRIDDEN");
  });

  it("is null when there is nothing to report", () => {
    expect(worstResult([])).toBeNull();
    expect(worstResult(["SOMETHING_NEW"])).toBeNull();
  });
});

describe("listRunPolicyResults", () => {
  beforeEach(() => jest.resetAllMocks());

  it("asks for the runs of the workspace without paging them, and reads the worst result of each run", async () => {
    post.mockResolvedValue({
      isError: false,
      data: {
        organization: {
          edges: [
            {
              node: {
                job: {
                  edges: [
                    {
                      node: {
                        id: "1",
                        policyEvaluation: { edges: [{ node: { status: "PASSED" } }, { node: { status: "FAILED" } }] },
                      },
                    },
                    { node: { id: "2", policyEvaluation: { edges: [{ node: { status: "PASSED" } }] } } },
                    { node: { id: "3", policyEvaluation: { edges: [] } } },
                  ],
                },
              },
            },
          ],
        },
      },
    });

    const results = await listRunPolicyResults("org-1", "ws-1");

    const request = post.mock.calls[0][1];
    expect(request.variables.orgId).toBe("org-1");
    // Only the last 90 days of runs are read, because Job cannot be paged and a workspace can have thousands.
    expect(request.variables.filter).toMatch(/^workspace\.id=="ws-1";createdDate=ge=\d{4}-\d{2}-\d{2}T[\d:.]+Z$/);
    expect(request.query).not.toContain("first");
    expect(results).toEqual({ "1": "FAILED", "2": "PASSED" });
  });

  it("reads an evaluation that has an override as overridden, not as the PASSED the API stores after it", async () => {
    post.mockResolvedValue({
      isError: false,
      data: {
        organization: {
          edges: [
            {
              node: {
                job: {
                  edges: [
                    {
                      node: {
                        id: "5",
                        policyEvaluation: {
                          edges: [{ node: { status: "PASSED", override: { edges: [{ node: { id: "o1" } }] } } }],
                        },
                      },
                    },
                    {
                      node: {
                        id: "6",
                        policyEvaluation: { edges: [{ node: { status: "PASSED", override: { edges: [] } } }] },
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

    const results = await listRunPolicyResults("org-1", "ws-1");

    expect(post.mock.calls[0][1].query).toContain("override { edges { node { id } } }");
    expect(results).toEqual({ "5": "OVERRIDDEN", "6": "PASSED" });
  });

  it("quotes the workspace id, so a malformed one cannot add to the filter", async () => {
    post.mockResolvedValue({ isError: false, data: { organization: { edges: [{ node: { job: { edges: [] } } }] } } });

    await listRunPolicyResults("org-1", "x';name=='y");

    expect(post.mock.calls[0][1].variables.filter).toContain(`workspace.id=="x';name=='y"`);
  });

  it("fails when the request fails, so the page can leave the column out", async () => {
    post.mockResolvedValue({ isError: true, error: { message: "boom" } });

    await expect(listRunPolicyResults("org-1", "ws-1")).rejects.toThrow("boom");
  });
});
