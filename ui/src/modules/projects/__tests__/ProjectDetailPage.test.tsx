import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import projectService from "../projectService";
import ProjectDetailPage from "../ProjectDetailPage";

jest.mock("../projectService", () => ({
  __esModule: true,
  default: { getProject: jest.fn(), getProjectSummary: jest.fn(), updateProject: jest.fn() },
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
  });

  it("opens the overview with the header actions and says how the caller has access", async () => {
    renderAt("/organizations/org/projects/p1");

    expect(await screen.findByText("overview content")).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "payments-prod" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "New workspace" })).toBeEnabled();
    expect(screen.getByText("Write")).toBeInTheDocument();
    expect(screen.getByText("payments-devs")).toBeInTheDocument();
    expect(screen.getByText(/You can manage access and delete the project/)).toBeInTheDocument();
  });

  it("says what the caller cannot do when they cannot manage the project", async () => {
    mockOrgPermissions = { manageWorkspace: false, manageCollection: false };
    mockProjectPermissions = { ...mockProjectPermissions, manageProject: false, manageWorkspace: false };

    renderAt("/organizations/org/projects/p1");

    expect(await screen.findByText(/You cannot manage access or delete the project/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "New workspace" })).toBeDisabled();
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
