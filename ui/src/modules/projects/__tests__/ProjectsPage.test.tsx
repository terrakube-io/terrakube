import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import projectService from "../projectService";
import ProjectsPage from "../ProjectsPage";

jest.mock("../projectService", () => ({
  __esModule: true,
  default: {
    listProjects: jest.fn(),
    createProject: jest.fn(),
  },
}));

jest.mock("@/modules/permissions/useOrgPermissions", () => ({
  useOrgPermissions: () => ({ permissions: { manageWorkspace: true } }),
}));

jest.mock("@/components/layout/PageWrapper/PageWrapper", () => ({
  __esModule: true,
  default: ({ children, actions }: { children: ReactNode; actions: ReactNode }) => (
    <>
      {actions}
      {children}
    </>
  ),
}));

const projects = [
  ...Array.from({ length: 10 }, (_, index) => ({
    id: `project-${index + 1}`,
    name: `Project ${index + 1}`,
    workspaceCount: index === 0 ? 12 : 0,
    teamCount: index === 0 ? 4 : 0,
  })),
  { id: "billing-api", name: "Billing API", workspaceCount: 8, teamCount: 2 },
];

function renderProjectsPage() {
  return render(
    <MemoryRouter initialEntries={["/organizations/org-1/projects"]}>
      <Routes>
        <Route
          path="/organizations/:id/projects"
          element={<ProjectsPage organizationName="org-1" setOrganizationName={jest.fn()} />}
        />
      </Routes>
    </MemoryRouter>
  );
}

describe("ProjectsPage search", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    sessionStorage.clear();
    (projectService.listProjects as jest.Mock).mockResolvedValue({ isError: false, data: projects });
  });

  it("shows workspace and team counts, including zero, in each project row", async () => {
    renderProjectsPage();

    const project = (await screen.findByText("Project 1")).closest("tr")!;
    expect(screen.getByRole("columnheader", { name: "Workspaces" })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Teams" })).toBeInTheDocument();
    expect(within(project).getByText("12")).toBeInTheDocument();
    expect(within(project).getByText("4")).toBeInTheDocument();
    expect(within(screen.getByText("Project 2").closest("tr")!).getAllByText("0")).toHaveLength(2);
  });

  it("links both counts, including zero, to the matching project's tabs", async () => {
    renderProjectsPage();
    await screen.findByText("Project 1");

    for (const [name, count, tab] of [
      ["12 workspaces in Project 1", "12", "workspaces"],
      ["4 teams with permissions in Project 1", "4", "teams"],
      ["0 workspaces in Project 2", "0", "workspaces"],
      ["0 teams with permissions in Project 2", "0", "teams"],
    ]) {
      const projectId = name.endsWith("1") ? "project-1" : "project-2";
      const link = screen.getByRole("link", { name });
      expect(link).toHaveTextContent(count);
      expect(link).toHaveAttribute("href", `/organizations/org-1/projects/${projectId}?tab=${tab}`);
    }
  });

  it("reloads changed counts when the Projects list is reopened", async () => {
    const { unmount } = renderProjectsPage();
    await screen.findByText("Project 1");
    unmount();
    (projectService.listProjects as jest.Mock).mockResolvedValue({
      isError: false,
      data: [{ ...projects[0], workspaceCount: 13, teamCount: 5 }],
    });

    renderProjectsPage();

    const project = (await screen.findByText("Project 1")).closest("tr")!;
    expect(within(project).getByText("13")).toBeInTheDocument();
    expect(within(project).getByText("5")).toBeInTheDocument();
  });

  it("searches all projects case-insensitively and restores the full list when cleared", async () => {
    renderProjectsPage();

    fireEvent.click(await screen.findByTitle("Next Page"));
    expect(await screen.findByText("Billing API")).toBeInTheDocument();

    const search = await screen.findByRole("textbox", { name: "Search projects by name" });
    fireEvent.change(search, { target: { value: "BILLING" } });

    expect(screen.getByText("Billing API")).toBeInTheDocument();
    expect(screen.queryByText("Project 1")).not.toBeInTheDocument();

    fireEvent.change(search, { target: { value: "" } });

    expect(screen.getByText("Project 1")).toBeInTheDocument();
    expect(screen.getByTitle("Next Page")).toBeInTheDocument();
  });

  it("shows a no-results state when the search has no matches", async () => {
    renderProjectsPage();

    fireEvent.change(await screen.findByRole("textbox", { name: "Search projects by name" }), {
      target: { value: "missing project" },
    });

    await waitFor(() => expect(screen.getByText("No projects found")).toBeInTheDocument());
    expect(screen.queryByText("Project 1")).not.toBeInTheDocument();
  });

  it("ignores leading and trailing whitespace in the query", async () => {
    renderProjectsPage();
    await screen.findByText("Project 1");

    fireEvent.change(screen.getByRole("textbox", { name: "Search projects by name" }), {
      target: { value: " billing " },
    });

    expect(screen.getByText("Billing API")).toBeInTheDocument();
    expect(screen.queryByText("Project 1")).not.toBeInTheDocument();
  });

  it("paginates the filtered results rather than the complete list", async () => {
    (projectService.listProjects as jest.Mock).mockResolvedValue({
      isError: false,
      data: [
        ...Array.from({ length: 12 }, (_, index) => ({
          id: `billing-${index}`,
          name: `Billing ${index + 1}`,
          workspaceCount: 0,
          teamCount: 0,
        })),
        ...projects,
      ],
    });
    renderProjectsPage();
    await screen.findByText("Billing 1");

    fireEvent.change(screen.getByRole("textbox", { name: "Search projects by name" }), {
      target: { value: "billing" },
    });
    fireEvent.click(screen.getByTitle("Next Page"));

    expect(screen.getByText("Billing 11")).toBeInTheDocument();
    expect(screen.getByText("Billing 12")).toBeInTheDocument();
    expect(screen.getByText("Billing API")).toBeInTheDocument();
    expect(within(screen.getByRole("table")).getAllByRole("row")).toHaveLength(4);
    expect(screen.getByTitle("Next Page")).toHaveAttribute("aria-disabled", "true");
  });

  it("preserves the selected page size after a search with no matches", async () => {
    renderProjectsPage();
    await screen.findByText("Project 1");
    fireEvent.mouseDown(screen.getByRole("combobox"));
    fireEvent.click(await screen.findByTitle("20 / page"));
    expect(screen.getByText("Billing API")).toBeInTheDocument();

    const search = screen.getByRole("textbox", { name: "Search projects by name" });
    fireEvent.change(search, { target: { value: "missing project" } });
    expect(screen.getByText("No projects found")).toBeInTheDocument();
    fireEvent.change(search, { target: { value: "" } });

    expect(screen.getByText("Project 1")).toBeInTheDocument();
    expect(screen.getByText("Billing API")).toBeInTheDocument();
  });

  it("clears the active search after a successful project creation", async () => {
    renderProjectsPage();
    await screen.findByText("Project 1");
    fireEvent.change(screen.getByRole("textbox", { name: "Search projects by name" }), {
      target: { value: "billing" },
    });
    fireEvent.click(screen.getByRole("button", { name: /New project/ }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "Platform" } });
    (projectService.createProject as jest.Mock).mockResolvedValue(undefined);
    (projectService.listProjects as jest.Mock).mockResolvedValue({
      isError: false,
      data: [{ id: "platform", name: "Platform", workspaceCount: 0, teamCount: 0 }, ...projects],
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Create" }));

    expect(await screen.findByText("Platform")).toBeInTheDocument();
    expect(projectService.createProject).toHaveBeenCalledWith("org-1", { name: "Platform" });
    expect(screen.getByRole("textbox", { name: "Search projects by name" })).toHaveValue("");
  });

  it("keeps the existing empty state when the organization has no projects", async () => {
    (projectService.listProjects as jest.Mock).mockResolvedValue({ isError: false, data: [] });

    renderProjectsPage();

    expect(await screen.findByText(/You have not created any projects yet/)).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "Search projects by name" })).not.toBeInTheDocument();
  });
});
