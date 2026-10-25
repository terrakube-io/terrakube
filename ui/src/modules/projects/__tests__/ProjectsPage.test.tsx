import { fireEvent, render, screen, waitFor } from "@testing-library/react";
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
  useOrgPermissions: () => ({ permissions: { manageWorkspace: false } }),
}));

jest.mock("@/components/layout/PageWrapper/PageWrapper", () => ({
  __esModule: true,
  default: ({ children }: { children: ReactNode }) => <>{children}</>,
}));

const projects = [
  ...Array.from({ length: 10 }, (_, index) => ({
    id: `project-${index + 1}`,
    name: `Project ${index + 1}`,
  })),
  { id: "billing-api", name: "Billing API" },
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

  it("keeps the existing empty state when the organization has no projects", async () => {
    (projectService.listProjects as jest.Mock).mockResolvedValue({ isError: false, data: [] });

    renderProjectsPage();

    expect(await screen.findByText(/You have not created any projects yet/)).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "Search projects by name" })).not.toBeInTheDocument();
  });
});
