import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { Cascades } from "../Cascades";

const listMock = jest.fn();
const cancelMock = jest.fn();

jest.mock("@/modules/cascades/cascadeService", () => ({
  listCascadesForWorkspace: (...args: unknown[]) => listMock(...args),
  cancelCascade: (...args: unknown[]) => cancelMock(...args),
  getAdminErrorMessage: () => "error",
}));

const renderPage = () =>
  render(
    <MemoryRouter>
      <Cascades organizationId="org-1" workspaceId="ws-1" workspaceName="platform" />
    </MemoryRouter>
  );

describe("Cascades", () => {
  beforeEach(() => {
    listMock.mockReset();
    cancelMock.mockReset();
  });

  it("asks the service for this workspace's cascades", async () => {
    listMock.mockResolvedValue([]);
    renderPage();

    await waitFor(() => expect(listMock).toHaveBeenCalledWith("ws-1"));
  });

  it("renders each cascade's status and origin job", async () => {
    listMock.mockResolvedValue([
      { id: "cascade-1", status: "DEGRADED", originJobId: "900", createdDate: "2026-01-02T00:00:00Z" },
    ]);
    renderPage();

    expect(await screen.findByText("Degraded")).toBeInTheDocument();
    expect(screen.getByText("#900")).toBeInTheDocument();
  });

  it("says so when the workspace has no cascades yet", async () => {
    listMock.mockResolvedValue([]);
    renderPage();

    expect(await screen.findByText("No cascade has started from this workspace yet.")).toBeInTheDocument();
  });

  it("offers Cancel only for a cascade that can still dispatch", async () => {
    listMock.mockResolvedValue([
      { id: "running", status: "RUNNING", originJobId: "1" },
      { id: "completed", status: "COMPLETED", originJobId: "2" },
    ]);
    renderPage();

    await screen.findByText("Running");
    const cancelButtons = screen.getAllByRole("button", { name: /cancel/i });
    expect(cancelButtons).toHaveLength(1);
  });

  it("cancels a cascade on confirm and reloads the list", async () => {
    listMock.mockResolvedValueOnce([{ id: "cascade-1", status: "RUNNING", originJobId: "900" }]);
    cancelMock.mockResolvedValue(undefined);
    listMock.mockResolvedValueOnce([{ id: "cascade-1", status: "CANCELLED", originJobId: "900" }]);
    renderPage();

    await screen.findByText("Running");
    await userEvent.click(screen.getByRole("button", { name: /cancel/i }));
    await userEvent.click(screen.getByRole("button", { name: /yes/i }));

    await waitFor(() => expect(cancelMock).toHaveBeenCalledWith("cascade-1"));
    expect(await screen.findByText("Cancelled")).toBeInTheDocument();
  });

  it("links View to the cascade's own page, not a drawer", async () => {
    listMock.mockResolvedValue([{ id: "cascade-1", status: "RUNNING", originJobId: "900" }]);
    renderPage();

    const viewLink = (await screen.findByRole("button", { name: /view/i })).closest("a");
    expect(viewLink).toHaveAttribute("href", "/organizations/org-1/workspaces/ws-1/cascades/cascade-1");
  });
});
