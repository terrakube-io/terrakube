import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { OrganizationCascades } from "../OrganizationCascades";

const listMock = jest.fn();
const capabilitiesMock = jest.fn();
const cancelMock = jest.fn();
const eventsMock = jest.fn();

jest.mock("@/modules/cascades/cascadeService", () => ({
  listCascadesForOrganization: (...args: unknown[]) => listMock(...args),
  getCascadeCapabilities: (...args: unknown[]) => capabilitiesMock(...args),
  cancelCascade: (...args: unknown[]) => cancelMock(...args),
  getAdminErrorMessage: () => "error",
}));
jest.mock("@/modules/admin/runTriggerEventService", () => ({
  listRunTriggerEvents: (...args: unknown[]) => eventsMock(...args),
  replayRunTriggerEvent: jest.fn(),
  getAdminErrorMessage: () => "error",
}));

// PageWrapper deep-imports an ESM-only antd submodule Jest can't transform, so pages built on it mock it.
jest.mock("@/components/layout/PageWrapper/PageWrapper", () => ({
  __esModule: true,
  default: function PageWrapper({ title, children }: { title?: React.ReactNode; children?: React.ReactNode }) {
    return (
      <div>
        <h2>{title}</h2>
        {children}
      </div>
    );
  },
}));

const cascade = {
  id: "cascade-1",
  status: "BLOCKED",
  originJobId: "900",
  createdDate: "2026-01-02T00:00:00Z",
  originWorkspaceId: "ws-1",
  originWorkspaceName: "network",
};

const renderPage = () =>
  render(
    <MemoryRouter initialEntries={["/organizations/org-1/cascades"]}>
      <Routes>
        <Route path="/organizations/:id/cascades" element={<OrganizationCascades />} />
      </Routes>
    </MemoryRouter>
  );

describe("OrganizationCascades", () => {
  beforeEach(() => {
    listMock.mockReset();
    cancelMock.mockReset();
    eventsMock.mockReset();
    capabilitiesMock.mockReset();
    capabilitiesMock.mockResolvedValue({ operate: true, instanceAdmin: false });
  });

  it("opens on the cascades that need attention in this organization", async () => {
    listMock.mockResolvedValue([cascade]);
    renderPage();

    expect(await screen.findByText("network")).toBeInTheDocument();
    expect(listMock).toHaveBeenCalledWith("org-1", ["BLOCKED", "DEGRADED"]);
    expect(capabilitiesMock).toHaveBeenCalledWith("org-1");
    expect(screen.getByText("network").closest("a")).toHaveAttribute("href", "/organizations/org-1/workspaces/ws-1");
    expect(screen.getByRole("link", { name: "View" })).toHaveAttribute(
      "href",
      "/organizations/org-1/workspaces/ws-1/cascades/cascade-1"
    );
  });

  it("says nothing needs attention when the list is empty", async () => {
    listMock.mockResolvedValue([]);
    renderPage();

    expect(await screen.findByText("No cascades need attention.")).toBeInTheDocument();
  });

  it("lets someone with the manage job permission stop a cascade", async () => {
    listMock.mockResolvedValue([cascade]);
    cancelMock.mockResolvedValue(undefined);
    renderPage();

    await screen.findByText("network");
    await waitFor(() => expect(screen.getByRole("button", { name: "Stop future runs" })).toBeEnabled());
    await userEvent.click(screen.getByRole("button", { name: "Stop future runs" }));
    await userEvent.click(await screen.findByRole("button", { name: "Stop" }));

    await waitFor(() => expect(cancelMock).toHaveBeenCalledWith("org-1", "cascade-1"));
  });

  it("disables stopping for someone without the permission", async () => {
    capabilitiesMock.mockResolvedValue({ operate: false, instanceAdmin: false });
    listMock.mockResolvedValue([cascade]);
    renderPage();

    await screen.findByText("network");
    expect(screen.getByRole("button", { name: "Stop future runs" })).toBeDisabled();
  });

  it("shows an access-denied alert when the API refuses the list", async () => {
    listMock.mockRejectedValue({ response: { status: 403 } });
    renderPage();

    expect(await screen.findByText("Access Denied")).toBeInTheDocument();
  });

  it("reports a load failure in one toast and loads failed trigger events only when that tab is opened", async () => {
    listMock.mockRejectedValue(new Error("boom"));
    eventsMock.mockResolvedValue([]);
    renderPage();

    await waitFor(() => expect(listMock).toHaveBeenCalled());
    expect(eventsMock).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole("tab", { name: "Failed trigger events" }));

    await waitFor(() => expect(eventsMock).toHaveBeenCalledWith(undefined, "org-1"));
    expect(await screen.findByText("No failed run trigger events.")).toBeInTheDocument();
  });
});
