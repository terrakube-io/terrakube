import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import GoRedirect from "../GoRedirect";
import * as service from "../navigationService";

jest.mock("../navigationService");

const mocked = service as jest.Mocked<typeof service>;

function Where() {
  return <div data-testid="where">{useLocation().pathname}</div>;
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/go/:org/*" element={<GoRedirect />} />
        <Route path="*" element={<Where />} />
      </Routes>
    </MemoryRouter>
  );
}

describe("GoRedirect", () => {
  beforeEach(() => {
    jest.resetAllMocks();
    mocked.resolveOrganization.mockResolvedValue({ id: "org-id", name: "JXP" });
  });

  it("sends a workspace name to its page", async () => {
    mocked.findWorkspaceByName.mockResolvedValue({ id: "ws-id", name: "ledger-db" });

    renderAt("/go/JXP/workspaces/ledger-db");

    expect(await screen.findByTestId("where")).toHaveTextContent("/organizations/org-id/workspaces/ws-id");
    expect(mocked.findWorkspaceByName).toHaveBeenCalledWith("org-id", "ledger-db");
  });

  it("sends a run link to the run", async () => {
    mocked.findWorkspaceByName.mockResolvedValue({ id: "ws-id", name: "ledger-db" });

    renderAt("/go/JXP/workspaces/ledger-db/runs/42");

    expect(await screen.findByTestId("where")).toHaveTextContent("/organizations/org-id/workspaces/ws-id/runs/42");
  });

  it("sends a project name and a bare organization to their pages", async () => {
    mocked.findProjectByName.mockResolvedValue({ id: "proj-id", name: "payments" });

    const { unmount } = renderAt("/go/JXP/projects/payments");
    expect(await screen.findByTestId("where")).toHaveTextContent("/organizations/org-id/projects/proj-id");
    unmount();

    renderAt("/go/JXP");
    expect(await screen.findByTestId("where")).toHaveTextContent("/organizations/org-id/projects");
  });

  it("says what could not be found, and offers a way on", async () => {
    mocked.findWorkspaceByName.mockResolvedValue(null);

    renderAt("/go/JXP/workspaces/gone");

    expect(await screen.findByText('Could not find the workspace "gone" in JXP')).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Search this organization's workspaces/ })).toHaveAttribute(
      "href",
      "/organizations/org-id/workspaces"
    );
  });

  it("says when the organization is unknown", async () => {
    mocked.resolveOrganization.mockResolvedValue(null);

    renderAt("/go/nowhere/workspaces/x");

    expect(await screen.findByText('Could not find the organization "nowhere"')).toBeInTheDocument();
    expect(mocked.findWorkspaceByName).not.toHaveBeenCalled();
  });

  it("refuses a link shape it does not know without asking the server", async () => {
    renderAt("/go/JXP/nonsense");

    expect(await screen.findByText("Could not find that link")).toBeInTheDocument();
    expect(mocked.resolveOrganization).not.toHaveBeenCalled();
  });

  it("reports a failed request instead of a missing page", async () => {
    mocked.resolveOrganization.mockRejectedValue(new Error("down"));

    renderAt("/go/JXP/workspaces/x");

    expect(await screen.findByText("That link could not be followed")).toBeInTheDocument();
  });
});
