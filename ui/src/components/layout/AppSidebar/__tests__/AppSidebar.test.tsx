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

jest.mock("@/components/layout/HelpMenu", () => ({
  HelpMenu: () => <div data-testid="help-menu" />,
}));

jest.mock("@/components/layout/UserMenu", () => ({
  UserMenu: () => <div data-testid="user-menu" />,
}));

const orgId = "3fa85f64-5717-4562-b3fc-2c963f66afa6";
const workspaceId = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
const orgPath = `/organizations/${orgId}`;
const workspacePath = `${orgPath}/workspaces/${workspaceId}`;

const organizations: FlatOrganization[] = [{ id: orgId, name: "Acme Corp" }];

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
    sessionStorage.clear();
  });

  it("shows a single Organizations item when there is no organization in the URL", async () => {
    renderSidebar("/organizations");

    expect(await screen.findByText("Organizations")).toBeInTheDocument();
    expect(screen.queryByText("Workspaces")).not.toBeInTheDocument();
  });

  it("shows Projects/Workspaces/Registry/Settings inside an organization", async () => {
    renderSidebar(`${orgPath}/workspaces`);

    expect(await screen.findByText("Projects")).toBeInTheDocument();
    expect(screen.getByText("Workspaces")).toBeInTheDocument();
    expect(screen.getByText("Registry")).toBeInTheDocument();
    expect(screen.getByText("Settings")).toBeInTheDocument();
  });

  it("links Organizations to the organizations picker", async () => {
    renderSidebar("/organizations");

    const item = await screen.findByText("Organizations");

    expect(item.closest("a")).toHaveAttribute("href", "/organizations");
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

  it("links the logo/header home", async () => {
    renderSidebar(`${orgPath}/workspaces`);
    await screen.findByText("Workspaces");

    expect(screen.getByAltText("Terrakube").closest("a")).toHaveAttribute("href", "/");
  });

  it("shows the organization switcher in the footer when expanded", async () => {
    renderSidebar(`${orgPath}/workspaces`);
    await screen.findByText("Workspaces");

    expect(screen.getByText("Acme Corp")).toBeInTheDocument();
  });

  it("has an accessible label on the collapse trigger", async () => {
    renderSidebar(`${orgPath}/workspaces`);
    await screen.findByText("Workspaces");

    expect(screen.getByLabelText("Collapse sidebar")).toBeInTheDocument();
  });

  it("collapses, hides the organization switcher, and persists the preference when the trigger is clicked", async () => {
    const setSpy = jest.spyOn(sidebarPreference, "setStoredSidebarCollapsed");
    renderSidebar(`${orgPath}/workspaces`);
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
    renderSidebar(`${orgPath}/settings/general`);

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

  it("links settings items to their settings sub-path", async () => {
    renderSidebar(`${orgPath}/settings/general`);

    const item = await screen.findByText("Teams");

    expect(item.closest("a")).toHaveAttribute("href", `${orgPath}/settings/teams`);
  });

  it("links back to workspaces from the back link", async () => {
    renderSidebar(`${orgPath}/settings/general`);

    const backLink = await screen.findByText("Workspaces");

    expect(backLink.closest("a")).toHaveAttribute("href", `${orgPath}/workspaces`);
  });

  it("does not show a collapse trigger (force-expanded)", async () => {
    renderSidebar(`${orgPath}/settings/general`);
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
    renderSidebar(workspacePath);

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

  it("links Overview to the bare workspace URL", async () => {
    renderSidebar(`${workspacePath}/runs`);

    const overview = await screen.findByText("Overview");

    expect(overview.closest("a")).toHaveAttribute("href", workspacePath);
  });

  it("links sections to their section sub-path", async () => {
    renderSidebar(workspacePath);

    const runs = await screen.findByText("Runs");

    expect(runs.closest("a")).toHaveAttribute("href", `${workspacePath}/runs`);
  });

  it("links back to the workspaces list from the back link", async () => {
    renderSidebar(workspacePath);

    const backLink = await screen.findByText("Workspaces");

    expect(backLink.closest("a")).toHaveAttribute("href", `${orgPath}/workspaces`);
  });

  it("disables the States item when workspaceManageState is false", async () => {
    renderSidebar(workspacePath, { workspaceManageState: false });
    await screen.findByText("Overview");

    expect(screen.getByText("States").closest("a")).toBeNull();
  });

  it("links to States when workspaceManageState is true", async () => {
    renderSidebar(workspacePath, { workspaceManageState: true });
    await screen.findByText("Overview");

    expect(screen.getByText("States").closest("a")).toHaveAttribute("href", `${workspacePath}/states`);
  });

  it("does not treat the plain workspaces list route as a workspace-detail context", async () => {
    renderSidebar(`${orgPath}/workspaces`);

    expect(await screen.findByText("Workspaces")).toBeInTheDocument();
    expect(screen.getByText("Projects")).toBeInTheDocument();
    expect(screen.queryByText("Overview")).not.toBeInTheDocument();
  });

  it("can still be collapsed (with icons) while viewing a workspace's own sections", async () => {
    renderSidebar(workspacePath);
    await screen.findByText("Overview");

    expect(screen.getByLabelText("Collapse sidebar")).toBeInTheDocument();
  });
});

describe("workspace-settings context", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("shows the workspace settings sub-nav instead of the org-settings nav or the workspace-detail nav", async () => {
    renderSidebar(`${workspacePath}/settings`, { workspaceManageState: true });

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

  it("links workspace settings items to their sub-path", async () => {
    renderSidebar(`${workspacePath}/settings`, { workspaceManageState: true });

    const locking = await screen.findByText("Locking");

    expect(locking.closest("a")).toHaveAttribute("href", `${workspacePath}/settings/locking`);
  });

  it("links back to the workspace overview from the back link", async () => {
    renderSidebar(`${workspacePath}/settings`, { workspaceManageState: true });

    const backLink = await screen.findByText("Back to Workspace");

    expect(backLink.closest("a")).toHaveAttribute("href", workspacePath);
  });

  it("does not show a collapse trigger (force-expanded)", async () => {
    renderSidebar(`${workspacePath}/settings`, { workspaceManageState: true });
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

  it("links Theme to the theme settings path", async () => {
    renderSidebar("/settings/tokens");

    const themeItem = await screen.findByText("Theme");

    expect(themeItem.closest("a")).toHaveAttribute("href", "/settings/theme");
  });

  it("links home from the back link", async () => {
    renderSidebar("/settings/tokens");

    const backLink = await screen.findByText("Home");

    expect(backLink.closest("a")).toHaveAttribute("href", "/");
  });

  it("does not show a collapse trigger (force-expanded)", async () => {
    renderSidebar("/settings/tokens");
    await screen.findByText("Tokens");

    expect(screen.queryByLabelText("Collapse sidebar")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Expand sidebar")).not.toBeInTheDocument();
  });
});
