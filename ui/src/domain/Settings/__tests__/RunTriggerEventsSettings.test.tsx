import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { RunTriggerEventsSettings } from "../RunTriggerEvents";

const listMock = jest.fn();
const capabilitiesMock = jest.fn();

jest.mock("@/modules/admin/runTriggerEventService", () => ({
  listRunTriggerEvents: (...args: unknown[]) => listMock(...args),
  replayRunTriggerEvent: jest.fn(),
  getAdminErrorMessage: () => "error",
}));
jest.mock("@/modules/cascades/cascadeService", () => ({
  getCascadeCapabilities: (...args: unknown[]) => capabilitiesMock(...args),
}));

const renderPage = () =>
  render(
    <MemoryRouter initialEntries={["/organizations/org-1/settings/trigger-events"]}>
      <Routes>
        <Route path="/organizations/:orgid/settings/trigger-events" element={<RunTriggerEventsSettings />} />
      </Routes>
    </MemoryRouter>
  );

describe("RunTriggerEventsSettings", () => {
  beforeEach(() => {
    listMock.mockReset();
    capabilitiesMock.mockReset();
    listMock.mockResolvedValue([
      { id: "event-1", jobId: 900, workspaceName: "network", status: "FAILED", attemptCount: 5 },
    ]);
  });

  it("lists only this organization's events", async () => {
    capabilitiesMock.mockResolvedValue({ operate: true, instanceAdmin: false });
    renderPage();

    expect(await screen.findByText("#900 (network)")).toBeInTheDocument();
    expect(listMock).toHaveBeenCalledWith(undefined, "org-1");
    await waitFor(() => expect(screen.getByRole("button", { name: "Replay event for job 900" })).toBeEnabled());
  });

  it("leaves replay disabled without the manage job permission", async () => {
    capabilitiesMock.mockResolvedValue({ operate: false, instanceAdmin: false });
    renderPage();

    await screen.findByText("#900 (network)");
    expect(screen.getByRole("button", { name: "Replay event for job 900" })).toBeDisabled();
  });
});
