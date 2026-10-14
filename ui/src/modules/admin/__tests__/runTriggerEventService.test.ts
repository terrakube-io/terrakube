import { axiosAdmin } from "@/config/axiosConfig";
import { listRunTriggerEvents, replayRunTriggerEvent, RunTriggerEventStatus } from "../runTriggerEventService";

jest.mock("@/config/axiosConfig", () => ({
  __esModule: true,
  axiosAdmin: { get: jest.fn(), post: jest.fn() },
  getErrorMessage: () => "fallback error",
}));

jest.mock("@/modules/cascades/cascadeService", () => ({
  getAdminErrorMessage: () => "admin error",
}));

const mockGet = axiosAdmin.get as jest.Mock;
const mockPost = axiosAdmin.post as jest.Mock;

beforeEach(() => {
  mockGet.mockReset();
  mockPost.mockReset();
});

describe("listRunTriggerEvents", () => {
  it("defaults to FAILED and lists every organization's events when none is given", async () => {
    mockGet.mockResolvedValue({ data: [] });

    await listRunTriggerEvents();

    expect(mockGet).toHaveBeenCalledWith("/cascade/v1/trigger-event", {
      params: { status: RunTriggerEventStatus.Failed },
    });
  });

  it("lists one organization's events through its own endpoint", async () => {
    mockGet.mockResolvedValue({ data: [] });

    await listRunTriggerEvents(undefined, "org-1");

    expect(mockGet).toHaveBeenCalledWith("/cascade/v1/organization/org-1/trigger-event", {
      params: { status: RunTriggerEventStatus.Failed },
    });
  });

  it("returns the summaries as-is", async () => {
    const summaries = [{ id: "event-1", jobId: 900, workspaceName: "network", status: "FAILED", attemptCount: 5 }];
    mockGet.mockResolvedValue({ data: summaries });

    const result = await listRunTriggerEvents();

    expect(result).toEqual(summaries);
  });
});

describe("replayRunTriggerEvent", () => {
  it("posts to the organization's replay endpoint", async () => {
    mockPost.mockResolvedValue({ data: { eventId: "event-1", rearmed: true } });

    const result = await replayRunTriggerEvent("org-1", "event-1");

    expect(mockPost).toHaveBeenCalledWith("/cascade/v1/organization/org-1/trigger-event/event-1/replay", {});
    expect(result).toEqual({ eventId: "event-1", rearmed: true });
  });
});
