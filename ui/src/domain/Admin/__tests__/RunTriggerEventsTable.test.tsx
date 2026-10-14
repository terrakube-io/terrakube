import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { RunTriggerEventsTable } from "../RunTriggerEventsTable";

const listMock = jest.fn();
const replayMock = jest.fn();

jest.mock("@/modules/admin/runTriggerEventService", () => ({
  listRunTriggerEvents: (...args: unknown[]) => listMock(...args),
  replayRunTriggerEvent: (...args: unknown[]) => replayMock(...args),
  getAdminErrorMessage: () => "error",
}));

const event = {
  id: "event-1",
  jobId: 900,
  workspaceName: "network",
  status: "FAILED",
  attemptCount: 5,
  lastError: "boom",
  organizationId: "org-1",
};

describe("RunTriggerEventsTable", () => {
  beforeEach(() => {
    listMock.mockReset();
    replayMock.mockReset();
  });

  it("renders each failed event's job, workspace, attempts and last error", async () => {
    listMock.mockResolvedValue([event]);
    render(<RunTriggerEventsTable canReplay />);

    expect(await screen.findByText("#900 (network)")).toBeInTheDocument();
    expect(screen.getByText("5")).toBeInTheDocument();
    expect(screen.getByText("boom")).toBeInTheDocument();
  });

  it("asks only for one organization's events when given an organization, and hides the organization column", async () => {
    listMock.mockResolvedValue([event]);
    render(<RunTriggerEventsTable organizationId="org-1" canReplay />);

    await screen.findByText("#900 (network)");
    expect(listMock).toHaveBeenCalledWith(undefined, "org-1");
    expect(screen.queryByText("Organization")).toBeNull();
  });

  it("says so when there is nothing to replay", async () => {
    listMock.mockResolvedValue([]);
    render(<RunTriggerEventsTable canReplay />);

    expect(await screen.findByText("No failed run trigger events.")).toBeInTheDocument();
  });

  it("shows an access-denied alert on a 403, not a generic error toast", async () => {
    listMock.mockRejectedValue({ response: { status: 403 } });
    render(<RunTriggerEventsTable canReplay />);

    expect(await screen.findByText("Access Denied")).toBeInTheDocument();
  });

  it("replays an event on confirm and reloads the list", async () => {
    listMock.mockResolvedValueOnce([event]).mockResolvedValueOnce([]);
    replayMock.mockResolvedValue({ eventId: "event-1", rearmed: true });
    render(<RunTriggerEventsTable canReplay />);

    await screen.findByText("#900 (network)");
    await userEvent.click(screen.getByRole("button", { name: "Replay event for job 900" }));
    await userEvent.click(await screen.findByRole("button", { name: "Replay" }));

    await waitFor(() => expect(replayMock).toHaveBeenCalledWith("org-1", "event-1"));
    expect(await screen.findByText("No failed run trigger events.")).toBeInTheDocument();
  });

  it("disables replay for someone without the permission", async () => {
    listMock.mockResolvedValue([event]);
    render(<RunTriggerEventsTable organizationId="org-1" canReplay={false} />);

    await screen.findByText("#900 (network)");
    expect(screen.getByRole("button", { name: "Replay event for job 900" })).toBeDisabled();
  });
});
