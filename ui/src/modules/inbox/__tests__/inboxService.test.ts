import { apiPost } from "@/modules/api/apiWrapper";
import { loadInbox, loadInboxCounts, loadInboxPage, loadOrganizationCounts } from "../inboxService";

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

const answer = (waiting: any, failed: any, policy: any, drifted: any = rows(0)) => ({
  isError: false,
  data: { organization: { edges: [{ node: { waiting, failed, drifted, policy } }] } },
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

    expect(data.counts).toEqual({ waiting: 1, failed: 25, drifted: 0, policy: 1 });
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
    expect(request.variables).toEqual({
      orgId: "a",
      first: "10",
      waitingFilter: "lastJobStatus=='waitingApproval'",
      failedFilter: "lastJobStatus=='failed'",
      driftedFilter: "driftStatus=='drifted';lastJobStatus!='failed';lastJobStatus!='waitingApproval'",
      policyFilter: "policyComplianceStatus=='NON_COMPLIANT';lastJobStatus!='failed';lastJobStatus!='waitingApproval'",
    });
    expect(request.query).toContain("filter: $waitingFilter");
  });

  it("limits every kind to the projects the given teams own, quoting each name", async () => {
    post.mockResolvedValue(answer(rows(0), rows(0), rows(0)));

    await loadInbox([{ id: "a", name: "JXP" }], { ownerTeams: ["payments-devs", 'odd "name", (team)'] });

    const { variables } = post.mock.calls[0][1];
    const owner = 'project.ownerTeam=in=("payments-devs","odd \\"name\\", (team)")';
    expect(variables.waitingFilter).toBe(`lastJobStatus=='waitingApproval';${owner}`);
    expect(variables.failedFilter).toBe(`lastJobStatus=='failed';${owner}`);
    expect(variables.policyFilter).toContain(`lastJobStatus!='waitingApproval';${owner}`);
  });

  it("lists drifted workspaces from the day the drift was first seen, with the plan to open", async () => {
    post.mockResolvedValue(
      answer(
        rows(0),
        rows(0),
        rows(0),
        rows(1, {
          id: "w9",
          name: "edge-proxy",
          lastJobDate: "2026-11-14T12:00:00Z",
          driftFirstSeen: "2026-11-11T02:00:00Z",
          driftJobId: 412,
          project: { edges: [] },
        })
      )
    );

    const data = await loadInbox([{ id: "a", name: "JXP" }]);

    expect(data.counts.drifted).toBe(1);
    expect(data.items[0]).toMatchObject({
      kind: "drifted",
      name: "edge-proxy",
      at: "2026-11-11T02:00:00Z",
      runId: "412",
    });
  });

  it("asks for nothing when the person is in no team, because an empty list is not a filter", async () => {
    const data = await loadInbox([{ id: "a", name: "JXP" }], { ownerTeams: [] });

    expect(data.items).toEqual([]);
    expect(data.counts).toEqual({ waiting: 0, failed: 0, drifted: 0, policy: 0 });
    expect(post).not.toHaveBeenCalled();
  });

  it("carries the owner team of each project", async () => {
    post.mockResolvedValue(
      answer(
        rows(1, {
          id: "w1",
          name: "ledger-db",
          project: { edges: [{ node: { name: "payments", ownerTeam: "payments-devs" } }] },
        }),
        rows(0),
        rows(0)
      )
    );

    const data = await loadInbox([{ id: "a", name: "JXP" }]);

    expect(data.items[0]).toMatchObject({ projectName: "payments", ownerTeam: "payments-devs" });
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

const single = (workspace: any) => ({
  isError: false,
  data: { organization: { edges: [{ node: { workspace } }] } },
});

describe("loadInboxPage", () => {
  beforeEach(() => jest.resetAllMocks());

  it("reads one page of one kind, from the offset of that page, with the total", async () => {
    post.mockResolvedValue(single(rows(63, ws("w1", "gateway", "2026-11-20T10:00:00Z", "payments"))));

    const result = await loadInboxPage({ id: "a", name: "JXP" }, { kind: "failed", page: 3, pageSize: 25 });

    const { variables, query } = post.mock.calls[0][1];
    expect(variables).toEqual({
      orgId: "a",
      first: "25",
      after: "50",
      sort: "-lastJobDate,+name",
      filter: "lastJobStatus=='failed'",
    });
    expect(query).toContain("totalRecords");
    expect(result.total).toBe(63);
    expect(result.items).toEqual([
      expect.objectContaining({
        key: "failed:w1",
        kind: "failed",
        orgName: "JXP",
        name: "gateway",
        projectName: "payments",
      }),
    ]);
  });

  it("orders each kind the way the lists do, with the name last so a page boundary never reorders equal times", async () => {
    post.mockResolvedValue(single(rows(0)));
    const org = { id: "a", name: "JXP" };

    await loadInboxPage(org, { kind: "waiting", page: 1, pageSize: 25 });
    await loadInboxPage(org, { kind: "drifted", page: 1, pageSize: 25 });

    expect(post.mock.calls[0][1].variables.sort).toBe("+lastJobDate,+name");
    expect(post.mock.calls[1][1].variables.sort).toBe("+driftFirstSeen,+name");
  });

  it("narrows to names containing the text, and drops the characters that mean something to a filter", async () => {
    post.mockResolvedValue(single(rows(0)));

    await loadInboxPage({ id: "a", name: "JXP" }, { kind: "failed", page: 1, pageSize: 25, search: "gate;way*'" });

    expect(post.mock.calls[0][1].variables.filter).toBe("lastJobStatus=='failed';name=ini=*gate way*");
  });

  it("keeps to the projects the given teams own, and asks for nothing when there are none", async () => {
    post.mockResolvedValue(single(rows(0)));
    const org = { id: "a", name: "JXP" };

    await loadInboxPage(org, { kind: "failed", page: 1, pageSize: 25, scope: { ownerTeams: ["payments-devs"] } });
    expect(post.mock.calls[0][1].variables.filter).toBe(
      "lastJobStatus=='failed';project.ownerTeam=in=(\"payments-devs\")"
    );

    post.mockClear();
    const none = await loadInboxPage(org, { kind: "failed", page: 1, pageSize: 25, scope: { ownerTeams: [] } });
    expect(post).not.toHaveBeenCalled();
    expect(none).toEqual({ items: [], total: 0 });
  });

  it("fails the way the rest of the inbox does when the request fails", async () => {
    post.mockResolvedValue({ isError: true, error: { message: "down" } });

    await expect(loadInboxPage({ id: "a", name: "JXP" }, { kind: "failed", page: 1, pageSize: 25 })).rejects.toThrow(
      "down"
    );
  });
});

describe("loadInboxCounts", () => {
  beforeEach(() => jest.resetAllMocks());

  it("asks for totals only, one row at most of each kind", async () => {
    post.mockResolvedValue(answer(rows(2), rows(10), rows(1), rows(4)));

    const counts = await loadInboxCounts({ id: "a", name: "JXP" });

    expect(counts).toEqual({ waiting: 2, failed: 10, drifted: 4, policy: 1 });
    const { query, variables } = post.mock.calls[0][1];
    expect(query).not.toContain("lastJobDate");
    expect(query).toContain('first: "1"');
    expect(variables.failedFilter).toBe("lastJobStatus=='failed'");
  });

  it("counts only what matches the search, so the numbers on the tabs agree with the list", async () => {
    post.mockResolvedValue(answer(rows(0), rows(0), rows(0)));

    await loadInboxCounts({ id: "a", name: "JXP" }, null, "gate");

    expect(post.mock.calls[0][1].variables.waitingFilter).toBe("lastJobStatus=='waitingApproval';name=ini=*gate*");
  });
});

describe("loadOrganizationCounts", () => {
  beforeEach(() => jest.resetAllMocks());

  it("counts each organization, and names the ones it could not read instead of treating them as quiet", async () => {
    post
      .mockResolvedValueOnce(answer(rows(1), rows(0), rows(0)))
      .mockResolvedValueOnce({ isError: true, error: { message: "down" } });

    const result = await loadOrganizationCounts([
      { id: "a", name: "JXP" },
      { id: "b", name: "Acme" },
    ]);

    expect(result.rows).toEqual([
      { org: { id: "a", name: "JXP" }, counts: { waiting: 1, failed: 0, drifted: 0, policy: 0 } },
    ]);
    expect(result.failedOrganizations).toEqual(["Acme"]);
  });

  it("never has more than a few requests in flight, so a person in many organizations does not start them all", async () => {
    let inFlight = 0;
    let peak = 0;
    post.mockImplementation(async () => {
      inFlight++;
      peak = Math.max(peak, inFlight);
      await new Promise((resolve) => setTimeout(resolve, 5));
      inFlight--;
      return answer(rows(0), rows(0), rows(0));
    });

    await loadOrganizationCounts(Array.from({ length: 20 }, (_, index) => ({ id: `o${index}`, name: `Org ${index}` })));

    expect(peak).toBeLessThanOrEqual(5);
  });
});
