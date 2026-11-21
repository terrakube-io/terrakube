import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import projectService from "../projectService";
import { useManagedInCode } from "@/modules/organizations/managedInCode";
import ProjectDetailPage from "../ProjectDetailPage";

jest.mock("@/modules/organizations/managedInCode", () => ({ useManagedInCode: jest.fn(() => null) }));
jest.mock("../projectService", () => ({
  __esModule: true,
  default: {
    getProject: jest.fn(),
    getProjectSummary: jest.fn(),
    updateProject: jest.fn(),
    listOrganizationTemplates: jest.fn(),
  },
}));
let mockOrgPermissions: any;
jest.mock("@/modules/permissions/useOrgPermissions", () => ({
  useOrgPermissions: () => ({ permissions: mockOrgPermissions }),
}));
let mockProjectPermissions: any;
jest.mock("@/modules/permissions/useProjectPermissions", () => ({
  useProjectPermissions: () => ({ permissions: mockProjectPermissions, loading: false }),
}));
jest.mock("@/components/layout/PageWrapper/PageWrapper", () => ({
  __esModule: true,
  default: ({ children, actions, title }: { children: ReactNode; actions: ReactNode; title: string }) => (
    <>
      <h1>{title}</h1>
      {actions}
      {children}
    </>
  ),
}));
jest.mock("../ProjectWorkspaces", () => ({ __esModule: true, default: () => <div>workspaces tab</div> }));
jest.mock("../ProjectPoliciesTab", () => ({ __esModule: true, default: () => <div>policies tab</div> }));
jest.mock("../ProjectAccessTab", () => ({ __esModule: true, default: () => <div>team access tab</div> }));
jest.mock("../ProjectVariablesTab", () => ({ __esModule: true, default: () => <div>variables section</div> }));
jest.mock("../ProjectEffectiveVariables", () => ({ __esModule: true, default: () => <div>effective variables</div> }));
jest.mock("../ProjectDefaultTags", () => ({ __esModule: true, default: () => <div>default tags</div> }));
jest.mock("../ProjectDeleteDialog", () => ({
  __esModule: true,
  default: ({ open }: { open: boolean }) => (open ? <div>delete dialog</div> : null),
}));
jest.mock("../ProjectOverview", () => ({ __esModule: true, default: () => <div>overview content</div> }));

const service = projectService as jest.Mocked<typeof projectService>;

function Where() {
  return <div data-testid="where">{useLocation().pathname}</div>;
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route
          path="/organizations/:orgid/projects/:id/:section?"
          element={
            <>
              <ProjectDetailPage organizationName="acme" setOrganizationName={jest.fn()} />
              <Where />
            </>
          }
        />
      </Routes>
    </MemoryRouter>
  );
}

const project = (overrides = {}) => ({
  isError: false,
  data: { id: "p1", name: "payments-prod", description: "Card processing", ...overrides },
});

describe("ProjectDetailPage", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    localStorage.clear();
    mockOrgPermissions = { manageWorkspace: true, manageCollection: true };
    mockProjectPermissions = {
      manageProject: true,
      manageWorkspace: true,
      manageState: false,
      manageJob: false,
      planJob: false,
      approveJob: false,
      managePolicies: false,
      grantedThrough: ["payments-devs"],
      accessLevel: "write",
    };
    service.getProject.mockResolvedValue(project() as never);
    service.getProjectSummary.mockResolvedValue({ isError: false, data: { id: "p1", workspaceCount: 3 } } as never);
    service.updateProject.mockResolvedValue(undefined);
    service.listOrganizationTemplates.mockResolvedValue([
      { id: "t-plan", name: "Plan", planOnly: true },
      { id: "t-apply", name: "Plan and apply", planOnly: false },
    ]);
  });

  it("opens the overview with the header actions and says how the caller has access", async () => {
    renderAt("/organizations/org/projects/p1");

    expect(await screen.findByText("overview content")).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "payments-prod" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "New workspace" })).toBeEnabled();
    // The role is a chip in the header. A banner is only for someone who cannot change the project.
    expect(screen.getByRole("button", { name: "Your access in this project" })).toHaveTextContent("Write");
    expect(screen.getByRole("button", { name: "Your access in this project" })).toHaveTextContent("via payments-devs");
    expect(screen.queryByText(/Editing the project, managing its access and deleting it need/)).not.toBeInTheDocument();
  });

  it("lists what the role allows when the chip is opened", async () => {
    renderAt("/organizations/org/projects/p1");

    fireEvent.click(await screen.findByRole("button", { name: "Your access in this project" }));

    expect(await screen.findByLabelText("Can: Create and manage workspaces")).toBeInTheDocument();
    expect(screen.getByLabelText("Cannot: Approve runs")).toBeInTheDocument();
    expect(screen.getByLabelText("Cannot: Manage state")).toBeInTheDocument();
  });

  it("says what the caller cannot do when they cannot manage the project", async () => {
    mockOrgPermissions = { manageWorkspace: false, manageCollection: false };
    mockProjectPermissions = { ...mockProjectPermissions, manageProject: false, manageWorkspace: false };

    renderAt("/organizations/org/projects/p1");

    expect(
      await screen.findByText(/Editing the project, managing its access and deleting it need the Admin role/)
    ).toBeInTheDocument();
    expect(screen.queryByText(/You can see this project but not change it/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "New workspace" })).toBeDisabled();
    expect(screen.getByLabelText(/Editing a project needs the Admin role/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Creating a workspace here needs write access/)).toBeInTheDocument();
  });

  it.each([
    ["workspaces", "workspaces tab"],
    ["policies", "policies tab"],
    ["teams", "team access tab"],
  ])("shows the %s section for its address", async (section, content) => {
    renderAt(`/organizations/org/projects/p1/${section}`);

    expect(await screen.findByText(content)).toBeInTheDocument();
  });

  it("sends a bare address to the saved landing tab once, and leaves explicit addresses alone", async () => {
    service.getProject.mockResolvedValue(project({ landingTab: "workspaces" }) as never);

    renderAt("/organizations/org/projects/p1");

    await waitFor(() =>
      expect(screen.getByTestId("where")).toHaveTextContent("/organizations/org/projects/p1/workspaces")
    );
    expect(await screen.findByText("workspaces tab")).toBeInTheDocument();
  });

  it("does not redirect when the address already names a section", async () => {
    service.getProject.mockResolvedValue(project({ landingTab: "workspaces" }) as never);

    renderAt("/organizations/org/projects/p1/policies");

    await screen.findByText("policies tab");
    expect(screen.getByTestId("where")).toHaveTextContent("/organizations/org/projects/p1/policies");
  });

  it("uses the person's own choice over the project's", async () => {
    localStorage.setItem("terrakube-project-landing-tab", "teams");
    service.getProject.mockResolvedValue(project({ landingTab: "workspaces" }) as never);

    renderAt("/organizations/org/projects/p1");

    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent("/organizations/org/projects/p1/teams"));
  });

  describe("settings", () => {
    it("saves the defaults for new workspaces and clears them when set back to the organization default", async () => {
      service.getProject.mockResolvedValue(
        project({
          defaultTerraformVersion: "1.9.8",
          defaultExecutionMode: "local",
          organizationExecutionMode: "remote",
        }) as never
      );
      renderAt("/organizations/org/projects/p1/settings");

      const version = await screen.findByLabelText("Terraform version");
      await waitFor(() => expect(version).toHaveValue("1.9.8"));
      expect(screen.getAllByText("Defaulted from project").length).toBeGreaterThan(0);
      fireEvent.change(version, { target: { value: "" } });
      fireEvent.click(screen.getByRole("button", { name: "Save settings" }));

      await waitFor(() => expect(service.updateProject).toHaveBeenCalled());
      expect(service.updateProject).toHaveBeenCalledWith(
        "org",
        "p1",
        expect.objectContaining({ defaultTerraformVersion: null, defaultExecutionMode: "local" })
      );
    });

    it("saves the default template, and clears it when set back to no default", async () => {
      service.getProject.mockResolvedValue(project({ defaultTemplate: "t-apply" }) as never);
      renderAt("/organizations/org/projects/p1/settings");

      await screen.findByLabelText("Terraform version");
      await waitFor(() => expect(screen.getByText("Plan and apply")).toBeInTheDocument());
      expect(screen.getAllByText("Defaulted from project").length).toBeGreaterThan(0);
      fireEvent.click(screen.getByRole("button", { name: "Save settings" }));

      await waitFor(() => expect(service.updateProject).toHaveBeenCalled());
      expect(service.updateProject).toHaveBeenCalledWith(
        "org",
        "p1",
        expect.objectContaining({ defaultTemplate: "t-apply" })
      );
    });

    it("is read-only, with a notice, when the organization is kept in code and says so", async () => {
      (useManagedInCode as jest.Mock).mockReturnValue({ repository: "https://github.com/example/x", readOnly: true });
      renderAt("/organizations/org/projects/p1/settings");

      expect(await screen.findByText(/These settings are read-only here/)).toBeInTheDocument();
      expect(await screen.findByLabelText("Terraform version")).toBeDisabled();
      expect(screen.getByLabelText("Owner")).toBeDisabled();
      (useManagedInCode as jest.Mock).mockReturnValue(null);
    });

    it("only warns when the organization is kept in code without locking the screens", async () => {
      (useManagedInCode as jest.Mock).mockReturnValue({ repository: "https://github.com/example/x", readOnly: false });
      renderAt("/organizations/org/projects/p1/settings");

      expect(await screen.findByText(/Edits here are reverted on the next apply/)).toBeInTheDocument();
      expect(await screen.findByLabelText("Terraform version")).toBeEnabled();
      (useManagedInCode as jest.Mock).mockReturnValue(null);
    });

    it("saves the owner team trimmed, and clears it when emptied", async () => {
      service.getProject.mockResolvedValue(project({ ownerTeam: "payments-devs" }) as never);
      renderAt("/organizations/org/projects/p1/settings");

      const owner = await screen.findByLabelText("Owner");
      await waitFor(() => expect(owner).toHaveValue("payments-devs"));
      fireEvent.change(owner, { target: { value: "  web-platform " } });
      fireEvent.click(screen.getByRole("button", { name: "Save settings" }));
      await waitFor(() => expect(service.updateProject).toHaveBeenCalled());
      expect(service.updateProject).toHaveBeenLastCalledWith(
        "org",
        "p1",
        expect.objectContaining({ ownerTeam: "web-platform" })
      );

      fireEvent.change(owner, { target: { value: "" } });
      fireEvent.click(screen.getByRole("button", { name: "Save settings" }));
      await waitFor(() => expect(service.updateProject).toHaveBeenCalledTimes(2));
      expect(service.updateProject).toHaveBeenLastCalledWith("org", "p1", expect.objectContaining({ ownerTeam: null }));
    });

    it("sends no template when none is set", async () => {
      renderAt("/organizations/org/projects/p1/settings");

      await screen.findByLabelText("Terraform version");
      fireEvent.click(await screen.findByRole("button", { name: "Save settings" }));

      await waitFor(() => expect(service.updateProject).toHaveBeenCalled());
      expect(service.updateProject).toHaveBeenCalledWith(
        "org",
        "p1",
        expect.objectContaining({ defaultTemplate: null })
      );
    });

    it("says where the execution mode comes from when the project sets none", async () => {
      service.getProject.mockResolvedValue(project({ organizationExecutionMode: "remote" }) as never);

      renderAt("/organizations/org/projects/p1/settings");

      expect(await screen.findByText("Defaulted from organisation (remote)")).toBeInTheDocument();
      expect(screen.getByText("Not set: the create form suggests the latest")).toBeInTheDocument();
    });

    it("opens the delete dialog instead of deleting straight away", async () => {
      renderAt("/organizations/org/projects/p1/settings");

      fireEvent.click(await screen.findByRole("button", { name: /Delete Project/ }));

      expect(screen.getByText("delete dialog")).toBeInTheDocument();
    });
  });
});
