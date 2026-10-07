import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { FailedRunTriggerEvents } from "../FailedRunTriggerEvents";

const listMock = jest.fn();
const replayMock = jest.fn();

jest.mock("@/modules/admin/runTriggerEventService", () => ({
  listRunTriggerEvents: (...args: unknown[]) => listMock(...args),
  replayRunTriggerEvent: (...args: unknown[]) => replayMock(...args),
  getAdminErrorMessage: () => "error",
}));

describe("FailedRunTriggerEvents", () => {
  beforeEach(() => {
    listMock.mockReset();
    replayMock.mockReset();
  });

  it("renders each failed event's job, workspace and attempt count", async () => {
    listMock.mockResolvedValue([
      { id: "event-1", jobId: 900, workspaceName: "network", status: "FAILED", attemptCount: 5, lastError: "boom" },
    ]);
    render(<FailedRunTriggerEvents />);

    expect(await screen.findByText("#900 (network)")).toBeInTheDocument();
    expect(screen.getByText("5")).toBeInTheDocument();
    expect(screen.getByText("boom")).toBeInTheDocument();
  });

  it("says so when there is nothing to replay", async () => {
    listMock.mockResolvedValue([]);
    render(<FailedRunTriggerEvents />);

    expect(await screen.findByText("No failed run trigger events.")).toBeInTheDocument();
  });

  /** A non-admin gets the controller's own 403 rendered as the existing access-denied screen. */
  it("shows an access-denied alert on a 403, not a generic error toast", async () => {
    listMock.mockRejectedValue({ response: { status: 403 } });
    render(<FailedRunTriggerEvents />);

    expect(await screen.findByText("Access Denied")).toBeInTheDocument();
  });

  it("replays an event on confirm and reloads the list", async () => {
    listMock.mockResolvedValueOnce([
      { id: "event-1", jobId: 900, workspaceName: "network", status: "FAILED", attemptCount: 5 },
    ]);
    replayMock.mockResolvedValue({ eventId: "event-1", rearmed: true });
    listMock.mockResolvedValueOnce([]);

    render(<FailedRunTriggerEvents />);

    await screen.findByText("#900 (network)");
    await userEvent.click(screen.getByRole("button", { name: /replay/i }));
    await userEvent.click(screen.getByRole("button", { name: /yes/i }));

    await waitFor(() => expect(replayMock).toHaveBeenCalledWith("event-1"));
    expect(await screen.findByText("No failed run trigger events.")).toBeInTheDocument();
  });
});
