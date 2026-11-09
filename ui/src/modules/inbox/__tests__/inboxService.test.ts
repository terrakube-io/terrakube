import { apiPost } from "@/modules/api/apiWrapper";
import { loadInbox } from "../inboxService";

jest.mock("@/modules/api/apiWrapper", () => ({ apiPost: jest.fn() }));
const post = apiPost as jest.Mock;

const rows = (total: number, ...nodes: object[]) => ({
  edges: nodes.map((node) => ({ node })),
  pageInfo: { totalRecords: total },
});
const ws = (id: string, name: string, lastJobDate?: string, project?: string) => ({
  id,
  name,
  lastJobDate,
  project: { edges: project ? [{ node: { name: project } }] : [] },
});

const answer = (waiting: any, failed: any, policy: any) => ({
  isError: false,
  data: { organization: { edges: [{ node: { waiting, failed, policy } }] } },
});

describe("loadInbox", () => {
  beforeEach(() => jest.resetAllMocks());

  it("collects each kind from each organization, with the real totals and where each one is", async () => {
    post.mockImplementation(async (_url: string, body: any) =>
      body.variables.orgId === "a"
        ? answer(rows(1, ws("w1", "ledger-db", "2026-11-08T06:00:00Z", "payments")), rows(0), rows(0))
        : answer(
            rows(0),
            rows(25, ws("w2", "gateway", "2026-11-08T09:00:00Z")),
            rows(1, ws("w3", "vpc", "2026-11-08T08:00:00Z"))
          )
    );

    const data = await loadInbox([
      { id: "a", name: "JXP" },
      { id: "b", name: "Admin" },
    ]);

    expect(data.counts).toEqual({ waiting: 1, failed: 25, policy: 1 });
    expect(data.items.map((entry) => `${entry.kind}:${entry.name}`)).toEqual([
      "waiting:ledger-db",
      "failed:gateway",
      "policy:vpc",
    ]);
    expect(data.items[0]).toMatchObject({ orgName: "JXP", projectName: "payments", workspaceId: "w1" });
    expect(data.failedOrganizations).toEqual([]);
  });

  it("asks for a bounded number of rows and never for a value it cannot read", async () => {
    post.mockResolvedValue(answer(rows(0), rows(0), rows(0)));

    await loadInbox([{ id: "a", name: "JXP" }]);

    const request = post.mock.calls[0][1];
    expect(request.variables).toEqual({ orgId: "a", first: "10" });
    expect(request.query).toContain("lastJobStatus=='waitingApproval'");
    expect(request.query).toContain(
      "policyComplianceStatus=='NON_COMPLIANT';lastJobStatus!='failed';lastJobStatus!='waitingApproval'"
    );
  });

  it("names the organizations it could not read instead of treating them as healthy", async () => {
    post.mockImplementation(async (_url: string, body: any) =>
      body.variables.orgId === "bad"
        ? { isError: true, error: { message: "denied" } }
        : answer(rows(0), rows(1, ws("w1", "ok")), rows(0))
    );

    const data = await loadInbox([
      { id: "a", name: "JXP" },
      { id: "bad", name: "Cybersecurity" },
    ]);

    expect(data.failedOrganizations).toEqual(["Cybersecurity"]);
    expect(data.counts.failed).toBe(1);
  });

  it("returns an empty inbox for no organizations", async () => {
    const data = await loadInbox([]);

    expect(data.items).toEqual([]);
    expect(post).not.toHaveBeenCalled();
  });
});
