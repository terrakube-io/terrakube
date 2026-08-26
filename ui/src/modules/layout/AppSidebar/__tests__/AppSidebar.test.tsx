import { render, screen, fireEvent } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import AppSidebar from "../AppSidebar";
import * as sidebarPreference from "../sidebarPreference";
import { FlatOrganization } from "@/domain/types";

jest.mock("@/modules/organizations/organizationService", () => ({
  __esModule: true,
  default: {
    listOrganizationsGraphQL: jest.fn().mockResolvedValue([]),
    getOrganizationNameGraphQL: jest.fn().mockResolvedValue(null),
  },
}));

jest.mock("@/components/HelpMenu", () => ({
  HelpMenu: () => <div data-testid="help-menu" />,
}));

jest.mock("@/components/UserMenu", () => ({
  UserMenu: () => <div data-testid="user-menu" />,
}));

const mockNavigate = jest.fn();
jest.mock("react-router-dom", () => ({
  ...jest.requireActual("react-router-dom"),
  useNavigate: () => mockNavigate,
}));

const organizations: FlatOrganization[] = [{ id: "org-1", name: "Acme Corp" }];

function renderSidebar(path: string, overrides: Partial<Parameters<typeof AppSidebar>[0]> = {}) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppSidebar
        organizationName="Acme Corp"
        setOrganizationName={jest.fn()}
        organizations={organizations}
        onOrgChange={jest.fn()}
        workspaceManageState={true}
        {...overrides}
      />
    </MemoryRouter>
  );
}

describe("AppSidebar", () => {
  beforeEach(() => {
    mockNavigate.mockClear();
    sessionStorage.clear();
  });

  it("shows a single Organizations item when there is no organization in the URL", async () => {
    renderSidebar("/organizations");

    expect(await screen.findByText("Organizations")).toBeInTheDocument();
    expect(screen.queryByText("Workspaces")).not.toBeInTheDocument();
  });

  it("shows Projects/Workspaces/Registry/Settings inside an organization", async () => {
    renderSidebar("/organizations/org-1/workspaces");

    expect(await screen.findByText("Projects")).toBeInTheDocument();
    expect(screen.getByText("Workspaces")).toBeInTheDocument();
    expect(screen.getByText("Registry")).toBeInTheDocument();
    expect(screen.getByText("Settings")).toBeInTheDocument();
  });

  it("navigates to the organizations picker when Organizations is clicked", async () => {
    renderSidebar("/organizations");

    const item = await screen.findByText("Organizations");
    fireEvent.click(item);

    expect(mockNavigate).toHaveBeenCalledWith("/organizations");
  });

  it("does not treat the /organizations/create route as an organization id", async () => {
    renderSidebar("/organizations/create");

    expect(await screen.findByText("Organizations")).toBeInTheDocument();
    expect(screen.queryByText("Workspaces")).not.toBeInTheDocument();
    expect(screen.queryByText("Projects")).not.toBeInTheDocument();
    expect(screen.queryByText("Settings")).not.toBeInTheDocument();
  });
});

describe("sidebar chrome", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("navigates home when the logo/header is clicked", async () => {
    renderSidebar("/organizations/org-1/workspaces");
    await screen.findByText("Workspaces");

    fireEvent.click(screen.getByAltText("Terrakube"));

    expect(mockNavigate).toHaveBeenCalledWith("/");
  });

  it("shows the organization switcher in the footer when expanded", async () => {
    renderSidebar("/organizations/org-1/workspaces");
    await screen.findByText("Workspaces");

    expect(screen.getByText("Acme Corp")).toBeInTheDocument();
  });

  it("has an accessible label on the collapse trigger", async () => {
    renderSidebar("/organizations/org-1/workspaces");
    await screen.findByText("Workspaces");

    expect(screen.getByLabelText("Collapse sidebar")).toBeInTheDocument();
  });

  it("collapses, hides the organization switcher, and persists the preference when the trigger is clicked", async () => {
    const setSpy = jest.spyOn(sidebarPreference, "setStoredSidebarCollapsed");
    renderSidebar("/organizations/org-1/workspaces");
    await screen.findByText("Workspaces");

    fireEvent.click(screen.getByLabelText("Collapse sidebar"));

    expect(setSpy).toHaveBeenCalledWith(true);
    expect(screen.queryByText("Acme Corp")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Expand sidebar")).toBeInTheDocument();
  });
});

describe("settings context", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("shows the settings sub-nav grouped items instead of the top-level nav", async () => {
    renderSidebar("/organizations/org-1/settings/general");

    expect(await screen.findByText("General")).toBeInTheDocument();
    expect(screen.getByText("Teams")).toBeInTheDocument();
    expect(screen.getByText("Tags")).toBeInTheDocument();
    expect(screen.getByText("Global Variables")).toBeInTheDocument();
    expect(screen.getByText("Variable Collections")).toBeInTheDocument();
    expect(screen.getByText("VCS Providers")).toBeInTheDocument();
    expect(screen.getByText("SSH Keys")).toBeInTheDocument();
    expect(screen.getByText("Agents")).toBeInTheDocument();
    expect(screen.getByText("Federated Credentials")).toBeInTheDocument();
    expect(screen.getByText("Templates")).toBeInTheDocument();
    expect(screen.getByText("Actions")).toBeInTheDocument();
    expect(screen.queryByText("Projects")).not.toBeInTheDocument();
    expect(screen.queryByText("Registry")).not.toBeInTheDocument();
  });

  it("navigates to the settings sub-path when a settings item is clicked", async () => {
    renderSidebar("/organizations/org-1/settings/general");

    fireEvent.click(await screen.findByText("Teams"));

    expect(mockNavigate).toHaveBeenCalledWith("/organizations/org-1/settings/teams");
  });

  it("navigates back to workspaces when the back link is clicked", async () => {
    renderSidebar("/organizations/org-1/settings/general");

    fireEvent.click(await screen.findByText("Workspaces"));

    expect(mockNavigate).toHaveBeenCalledWith("/organizations/org-1/workspaces");
  });

  it("does not show a collapse trigger (force-expanded)", async () => {
    renderSidebar("/organizations/org-1/settings/general");
    await screen.findByText("General");

    expect(screen.queryByLabelText("Collapse sidebar")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Expand sidebar")).not.toBeInTheDocument();
  });
});

describe("workspace-detail context", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("shows the 6 workspace sections plus the back link instead of the top-level nav", async () => {
    renderSidebar("/organizations/org-1/workspaces/ws-1");

    expect(await screen.findByText("Overview")).toBeInTheDocument();
    expect(screen.getByText("Runs")).toBeInTheDocument();
    expect(screen.getByText("States")).toBeInTheDocument();
    expect(screen.getByText("Variables")).toBeInTheDocument();
    expect(screen.getByText("Schedules")).toBeInTheDocument();
    expect(screen.getByText("Settings")).toBeInTheDocument();
    expect(screen.getByText("Workspaces")).toBeInTheDocument();
    expect(screen.queryByText("Projects")).not.toBeInTheDocument();
    expect(screen.queryByText("Registry")).not.toBeInTheDocument();
  });

  it("navigates to the bare workspace URL when Overview is clicked", async () => {
    renderSidebar("/organizations/org-1/workspaces/ws-1/runs");

    fireEvent.click(await screen.findByText("Overview"));

    expect(mockNavigate).toHaveBeenCalledWith("/organizations/org-1/workspaces/ws-1");
  });

  it("navigates to the section sub-path when a section is clicked", async () => {
    renderSidebar("/organizations/org-1/workspaces/ws-1");

    fireEvent.click(await screen.findByText("Runs"));

    expect(mockNavigate).toHaveBeenCalledWith("/organizations/org-1/workspaces/ws-1/runs");
  });

  it("navigates back to the workspaces list when the back link is clicked", async () => {
    renderSidebar("/organizations/org-1/workspaces/ws-1");

    fireEvent.click(await screen.findByText("Workspaces"));

    expect(mockNavigate).toHaveBeenCalledWith("/organizations/org-1/workspaces");
  });

  it("disables the States item when workspaceManageState is false", async () => {
    renderSidebar("/organizations/org-1/workspaces/ws-1", { workspaceManageState: false });
    await screen.findByText("Overview");

    fireEvent.click(screen.getByText("States"));

    expect(mockNavigate).not.toHaveBeenCalledWith("/organizations/org-1/workspaces/ws-1/states");
  });

  it("allows navigating to States when workspaceManageState is true", async () => {
    renderSidebar("/organizations/org-1/workspaces/ws-1", { workspaceManageState: true });
    await screen.findByText("Overview");

    fireEvent.click(screen.getByText("States"));

    expect(mockNavigate).toHaveBeenCalledWith("/organizations/org-1/workspaces/ws-1/states");
  });

  it("does not treat the plain workspaces list route as a workspace-detail context", async () => {
    renderSidebar("/organizations/org-1/workspaces");

    expect(await screen.findByText("Workspaces")).toBeInTheDocument();
    expect(screen.getByText("Projects")).toBeInTheDocument();
    expect(screen.queryByText("Overview")).not.toBeInTheDocument();
  });

  it("can still be collapsed (with icons) while viewing a workspace's own sections", async () => {
    renderSidebar("/organizations/org-1/workspaces/ws-1");
    await screen.findByText("Overview");

    expect(screen.getByLabelText("Collapse sidebar")).toBeInTheDocument();
  });
});

describe("workspace-settings context", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("shows the workspace settings sub-nav instead of the org-settings nav or the workspace-detail nav", async () => {
    renderSidebar("/organizations/org-1/workspaces/ws-1/settings", { workspaceManageState: true });

    expect(await screen.findByText("General")).toBeInTheDocument();
    expect(screen.getByText("Locking")).toBeInTheDocument();
    expect(screen.getByText("SSH Key")).toBeInTheDocument();
    expect(screen.getByText("Webhook")).toBeInTheDocument();
    expect(screen.getByText("State Shared")).toBeInTheDocument();
    expect(screen.getByText("Team Access")).toBeInTheDocument();
    expect(screen.getByText("Destruction and Deletion")).toBeInTheDocument();
    expect(screen.getByText("Back to Workspace")).toBeInTheDocument();
    expect(screen.queryByText("Global Variables")).not.toBeInTheDocument();
    expect(screen.queryByText("Teams")).not.toBeInTheDocument();
    expect(screen.queryByText("Overview")).not.toBeInTheDocument();
  });

  it("navigates to the workspace settings sub-path when an item is clicked", async () => {
    renderSidebar("/organizations/org-1/workspaces/ws-1/settings", { workspaceManageState: true });

    fireEvent.click(await screen.findByText("Locking"));

    expect(mockNavigate).toHaveBeenCalledWith("/organizations/org-1/workspaces/ws-1/settings/locking");
  });

  it("navigates back to the workspace overview when the back link is clicked", async () => {
    renderSidebar("/organizations/org-1/workspaces/ws-1/settings", { workspaceManageState: true });

    fireEvent.click(await screen.findByText("Back to Workspace"));

    expect(mockNavigate).toHaveBeenCalledWith("/organizations/org-1/workspaces/ws-1");
  });

  it("does not show a collapse trigger (force-expanded)", async () => {
    renderSidebar("/organizations/org-1/workspaces/ws-1/settings", { workspaceManageState: true });
    await screen.findByText("General");

    expect(screen.queryByLabelText("Collapse sidebar")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Expand sidebar")).not.toBeInTheDocument();
  });
});

describe("user-settings context", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("shows the account-settings items instead of the top-level nav", async () => {
    renderSidebar("/settings/tokens");

    expect(await screen.findByText("Tokens")).toBeInTheDocument();
    expect(screen.getByText("Theme")).toBeInTheDocument();
    expect(screen.getByText("Home")).toBeInTheDocument();
    expect(screen.queryByText("Organizations")).not.toBeInTheDocument();
  });

  it("navigates to the theme settings path when Theme is clicked", async () => {
    renderSidebar("/settings/tokens");

    fireEvent.click(await screen.findByText("Theme"));

    expect(mockNavigate).toHaveBeenCalledWith("/settings/theme");
  });

  it("navigates home when the back link is clicked", async () => {
    renderSidebar("/settings/tokens");

    fireEvent.click(await screen.findByText("Home"));

    expect(mockNavigate).toHaveBeenCalledWith("/");
  });

  it("does not show a collapse trigger (force-expanded)", async () => {
    renderSidebar("/settings/tokens");
    await screen.findByText("Tokens");

    expect(screen.queryByLabelText("Collapse sidebar")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Expand sidebar")).not.toBeInTheDocument();
  });
});
