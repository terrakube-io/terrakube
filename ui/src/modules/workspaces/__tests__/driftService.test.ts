import { apiPost } from "@/modules/api/apiWrapper";
import { listDriftChecks } from "../driftService";

jest.mock("@/modules/api/apiWrapper", () => ({ apiPost: jest.fn() }));
const post = apiPost as jest.Mock;

describe("listDriftChecks", () => {
  beforeEach(() => jest.resetAllMocks());

  it("asks for the runs the API marked as drift checks, newest first, a bounded number of them", async () => {
    post.mockResolvedValue({ isError: false, data: { organization: { edges: [{ node: { job: { edges: [] } } }] } } });

    await listDriftChecks("org-1", "ws-1", 6);

    const request = post.mock.calls[0][1];
    expect(request.variables.orgId).toBe("org-1");
    expect(request.variables.filter).toMatch(
      /^workspace\.id=='ws-1';driftResult=isnull=false;createdDate=ge=\d{4}-\d{2}-\d{2}T[\d:.]+Z$/
    );
    expect(request.query).toContain('sort: "-createdDate"');
    // Job cannot be paginated by Elide ("Cannot paginate job"), so the page size is applied after reading.
    expect(request.query).not.toContain("first");
  });

  it("reads each check with its result and counts, and keeps missing counts as null", async () => {
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
                        id: 9,
                        createdDate: "2026-11-14T02:00:00Z",
                        driftResult: "drift",
                        planAdd: 0,
                        planChange: 2,
                        planDestroy: 0,
                      },
                    },
                    { node: { id: 8, driftResult: "clean" } },
                  ],
                },
              },
            },
          ],
        },
      },
    });

    const response = await listDriftChecks("org-1", "ws-1");

    expect(response.data).toEqual([
      { id: "9", createdDate: "2026-11-14T02:00:00Z", result: "drift", add: 0, change: 2, destroy: 0 },
      { id: "8", createdDate: undefined, result: "clean", add: null, change: null, destroy: null },
    ]);
  });

  it("passes a failed request on", async () => {
    post.mockResolvedValue({ isError: true, error: { message: "denied" } });

    expect((await listDriftChecks("org-1", "ws-1")).isError).toBe(true);
  });
});
