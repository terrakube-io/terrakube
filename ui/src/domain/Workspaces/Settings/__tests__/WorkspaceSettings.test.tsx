import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { useManagedInCode } from "@/modules/organizations/managedInCode";
import { WorkspaceSettings } from "../WorkspaceSettings";

// Each section is replaced by a marker that says whether it was allowed to change the workspace, because what is
// under test is what WorkspaceSettings passes down, not the sections themselves.
function Marker({ name, manageWorkspace }: { name: string; manageWorkspace?: boolean }) {
  return <div data-testid={name} data-manage={String(manageWorkspace)} />;
}
jest.mock("../General", () => ({
  WorkspaceGeneral: (props: any) => <Marker name="general" manageWorkspace={props.manageWorkspace} />,
}));
jest.mock("../Policies", () => ({
  WorkspacePolicies: (props: any) => <Marker name="policies" manageWorkspace={props.manageWorkspace} />,
}));
jest.mock("../Locking", () => ({
  WorkspaceLocking: (props: any) => <Marker name="locking" manageWorkspace={props.manageWorkspace} />,
}));
jest.mock("../SSHKey", () => ({
  WorkspaceSSHKey: (props: any) => <Marker name="sshkey" manageWorkspace={props.manageWorkspace} />,
}));
jest.mock("../Webhook", () => ({
  WorkspaceWebhook: (props: any) => <Marker name="webhook" manageWorkspace={props.manageWorkspace} />,
}));
jest.mock("../Notifications", () => ({
  WorkspaceNotifications: (props: any) => <Marker name="notifications" manageWorkspace={props.manageWorkspace} />,
}));
jest.mock("../Advanced", () => ({
  WorkspaceAdvanced: (props: any) => <Marker name="advanced" manageWorkspace={props.manageWorkspace} />,
}));
jest.mock("../StateShared", () => ({
  WorkspaceStateShared: (props: any) => <Marker name="state" manageWorkspace={props.manageWorkspace} />,
}));
jest.mock("../TeamAccess", () => ({
  WorkspaceTeamAccess: (props: any) => <Marker name="team-access" manageWorkspace={props.manageWorkspace} />,
}));
jest.mock("@/modules/organizations/managedInCode");
jest.mock("@/components/settings/ManagedInCodeBanner", () => ({
  ManagedInCodeBanner: ({ readOnlyHere }: { readOnlyHere?: boolean }) => (
    <div data-testid="banner" data-read-only-here={String(Boolean(readOnlyHere))} />
  ),
}));

const managed = useManagedInCode as jest.Mock;

const renderSection = (activeSection: string) =>
  render(
    <MemoryRouter initialEntries={["/organizations/org-1/workspaces/ws-1"]}>
      <Routes>
        <Route
          path="/organizations/:orgid/workspaces/:id"
          element={
            <WorkspaceSettings
              workspace={{ id: "ws-1", attributes: { name: "ws" } } as never}
              orgTemplates={[]}
              manageWorkspace
              activeSection={activeSection}
            />
          }
        />
      </Routes>
    </MemoryRouter>
  );

describe("WorkspaceSettings when the organization is managed in code", () => {
  it("disables General and says so when the organization is read-only", () => {
    managed.mockReturnValue({ readOnly: true });
    renderSection("general");

    expect(screen.getByTestId("general")).toHaveAttribute("data-manage", "false");
    expect(screen.getByTestId("banner")).toHaveAttribute("data-read-only-here", "true");
  });

  it("leaves the other sections editable, and does not call them read-only", () => {
    managed.mockReturnValue({ readOnly: true });
    renderSection("locking");

    expect(screen.getByTestId("locking")).toHaveAttribute("data-manage", "true");
    expect(screen.getByTestId("banner")).toHaveAttribute("data-read-only-here", "false");
  });

  it("leaves General editable when the organization only warns", () => {
    managed.mockReturnValue({ readOnly: false });
    renderSection("general");

    expect(screen.getByTestId("general")).toHaveAttribute("data-manage", "true");
  });
});
