import { apiPost } from "@/modules/api/apiWrapper";
import { complianceRate, loadOrgOverview } from "../orgOverviewService";

jest.mock("@/modules/api/apiWrapper");
const post = apiPost as jest.Mock;

describe("loadOrgOverview", () => {
  beforeEach(() => jest.resetAllMocks());

  it("reads the totals and the last runs, asking for a few of them", async () => {
    post.mockResolvedValue({
      isError: false,
      data: {
        organization: {
          edges: [
            {
              node: {
                all: { pageInfo: { totalRecords: 29 } },
                completed: { pageInfo: { totalRecords: 16 } },
                failed: { pageInfo: { totalRecords: 4 } },
                waiting: { pageInfo: { totalRecords: 1 } },
                running: { pageInfo: { totalRecords: 2 } },
                never: { pageInfo: { totalRecords: 6 } },
                compliant: { pageInfo: { totalRecords: 20 } },
                nonCompliant: { pageInfo: { totalRecords: 4 } },
                projects: { pageInfo: { totalRecords: 6 } },
                recent: {
                  edges: [
                    {
                      node: {
                        id: "w1",
                        name: "svc-02",
                        lastJobStatus: "failed",
                        lastJobDate: "2026-11-14T10:00:00Z",
                        project: { edges: [{ node: { name: "Payments" } }] },
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

    const overview = await loadOrgOverview("org-1");

    expect(post.mock.calls[0][1].variables).toEqual({ orgId: "org-1", first: "6" });
    expect(overview.counts).toEqual({
      workspaces: 29,
      status: { completed: 16, failed: 4, waiting: 1, running: 2, never: 6 },
      projects: 6,
      compliant: 20,
      nonCompliant: 4,
    });
    expect(overview.recent).toEqual([
      { workspaceId: "w1", name: "svc-02", status: "failed", at: "2026-11-14T10:00:00Z", projectName: "Payments" },
    ]);
  });

  it("counts the same statuses as the project health bars, and asks for one row per count", async () => {
    post.mockResolvedValue({ isError: false, data: { organization: { edges: [{ node: {} }] } } });

    await loadOrgOverview("org-1");

    const query = post.mock.calls[0][1].query as string;
    // noChanges is a completed run, and a workspace with no status has never run.
    expect(query).toContain("lastJobStatus=in=('completed','noChanges')");
    expect(query).toContain("lastJobStatus=in=('running','pending','approved','queue')");
    expect(query).toContain("(lastJobStatus=='NeverExecuted',lastJobStatus=isnull=true)");
    // Without first, each alias asks for a whole page of rows to read one total.
    const aliases = [
      "all",
      "completed",
      "failed",
      "waiting",
      "running",
      "never",
      "compliant",
      "nonCompliant",
      "projects",
    ];
    aliases.forEach((alias) =>
      expect(query).toMatch(new RegExp(`${alias}: (workspace|project)\\(first: "1", after: "0"`))
    );
  });

  it("fails when the request fails, so the page can say so", async () => {
    post.mockResolvedValue({ isError: true, error: { message: "boom" } });

    await expect(loadOrgOverview("org-1")).rejects.toThrow("boom");
  });
});

describe("complianceRate", () => {
  const status = { completed: 0, failed: 0, waiting: 0, running: 0, never: 0 };

  it("is the share of evaluated workspaces that comply", () => {
    expect(complianceRate({ workspaces: 29, status, projects: 6, compliant: 20, nonCompliant: 4 })).toBe(83);
  });

  it("is null when nothing was evaluated, instead of 0%", () => {
    expect(complianceRate({ workspaces: 3, status, projects: 1, compliant: 0, nonCompliant: 0 })).toBeNull();
  });
});
