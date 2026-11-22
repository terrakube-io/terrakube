import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { Workspace } from "@/domain/types";
import { listDriftChecks } from "../../driftService";
import WorkspaceStates from "../WorkspaceStates";

jest.mock("../../driftService");
(listDriftChecks as jest.Mock).mockResolvedValue({ isError: false, data: [] });

const workspace = (attributes: Partial<Workspace["attributes"]> = {}): Workspace =>
  ({ id: "ws-1", attributes: { name: "ledger-db", ...attributes }, relationships: {} }) as unknown as Workspace;

const renderStates = (props: Partial<React.ComponentProps<typeof WorkspaceStates>> = {}) =>
  render(
    <MemoryRouter>
      <WorkspaceStates
        orgId="org-1"
        workspace={workspace()}
        runLink={(id) => `/organizations/org-1/workspaces/ws-1/runs/${id}`}
        onRunClick={jest.fn()}
        {...props}
      />
    </MemoryRouter>
  );

describe("WorkspaceStates", () => {
  it("says what the last run did, which run it was and how long ago", () => {
    renderStates({ run: { id: "7", status: "failed", at: new Date().toISOString() } });

    const card = screen.getByLabelText("Last run");
    expect(card).toHaveTextContent("Failed");
    expect(screen.getByRole("link", { name: "#7" })).toHaveAttribute(
      "href",
      "/organizations/org-1/workspaces/ws-1/runs/7"
    );
  });

  it("says so when the workspace never ran", () => {
    renderStates();

    expect(screen.getByLabelText("Last run")).toHaveTextContent("No runs yet");
    expect(screen.queryByRole("link", { name: /^#/ })).not.toBeInTheDocument();
  });

  it("shows the policy result next to the run and the drift", () => {
    renderStates({ workspace: workspace({ policyComplianceStatus: "NON_COMPLIANT", driftStatus: "drifted" }) });

    expect(screen.getByLabelText("Policy")).toHaveTextContent(/non-compliant/i);
    expect(screen.getByLabelText("Drift")).toBeInTheDocument();
  });

  it("has no drift card for a workspace that was never checked", () => {
    renderStates();

    expect(screen.queryByLabelText("Drift")).not.toBeInTheDocument();
  });

  it("does not draw a workspace that was never checked as compliant", () => {
    const { container } = renderStates({ workspace: workspace({ policyComplianceStatus: "UNKNOWN" }) });

    expect(container.querySelector(".overview-badge-success")).toBeNull();
    expect(container.querySelector(".overview-badge-error")).toBeNull();
  });
});
