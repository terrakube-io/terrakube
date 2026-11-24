import { approveRuns, findWaitingRuns } from "@/modules/projects/bulkWorkspaceActions";
import { approveWaiting } from "../confirmApprove";
import { InboxItem } from "../inboxModel";

jest.mock("@/modules/projects/bulkWorkspaceActions", () => ({ approveRuns: jest.fn(), findWaitingRuns: jest.fn() }));

const item = (orgId: string, workspaceId: string): InboxItem => ({
  key: `waiting:${workspaceId}`,
  kind: "waiting",
  orgId,
  orgName: orgId,
  workspaceId,
  name: workspaceId,
});

describe("approveWaiting", () => {
  beforeEach(() => jest.resetAllMocks());

  it("approves the waiting runs of each organization and counts the ones no longer waiting", async () => {
    (findWaitingRuns as jest.Mock).mockImplementation(async (orgId: string) =>
      orgId === "a" ? [{ jobId: "1", workspaceId: "w1" }] : []
    );
    (approveRuns as jest.Mock).mockResolvedValue({ succeeded: ["1"], failed: [] });

    const outcome = await approveWaiting([item("a", "w1"), item("a", "w2"), item("b", "w3")]);

    expect(findWaitingRuns).toHaveBeenCalledWith("a", ["w1", "w2"]);
    expect(approveRuns).toHaveBeenCalledTimes(1);
    expect(outcome).toEqual({ approved: 1, skipped: 2, heldBack: 0, failures: [] });
  });

  it("holds back a run with a policy violation and approves the rest", async () => {
    (findWaitingRuns as jest.Mock).mockResolvedValue([
      { jobId: "1", workspaceId: "w1", hardViolations: 0, softViolations: 0 },
      { jobId: "2", workspaceId: "w2", hardViolations: 1, softViolations: 0 },
      { jobId: "3", workspaceId: "w3", hardViolations: 0, softViolations: 2 },
    ]);
    (approveRuns as jest.Mock).mockResolvedValue({ succeeded: ["1"], failed: [] });

    const outcome = await approveWaiting([item("a", "w1"), item("a", "w2"), item("a", "w3")]);

    expect(approveRuns).toHaveBeenCalledWith("a", ["1"]);
    expect(outcome).toEqual({ approved: 1, skipped: 0, heldBack: 2, failures: [] });
  });

  it("approves nothing when every run has a violation", async () => {
    (findWaitingRuns as jest.Mock).mockResolvedValue([
      { jobId: "2", workspaceId: "w2", hardViolations: 1, softViolations: 0 },
    ]);

    const outcome = await approveWaiting([item("a", "w2")]);

    expect(approveRuns).not.toHaveBeenCalled();
    expect(outcome.heldBack).toBe(1);
  });

  it("reports a refused approval and an organization that could not be read, and carries on", async () => {
    (findWaitingRuns as jest.Mock).mockImplementation(async (orgId: string) => {
      if (orgId === "bad") throw new Error("down");
      return [{ jobId: "7", workspaceId: "w1" }];
    });
    (approveRuns as jest.Mock).mockResolvedValue({ succeeded: [], failed: [{ id: "7", reason: "Not allowed" }] });

    const outcome = await approveWaiting([item("bad", "w9"), item("a", "w1")]);

    expect(outcome.approved).toBe(0);
    expect(outcome.failures).toEqual(["down", "Not allowed"]);
  });
});
