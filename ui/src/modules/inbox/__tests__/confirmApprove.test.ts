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
    expect(outcome).toEqual({ approved: 1, skipped: 2, failures: [] });
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
