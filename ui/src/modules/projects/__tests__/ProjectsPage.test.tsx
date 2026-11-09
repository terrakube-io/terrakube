import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import projectService, { buildProjectSummaryFilter } from "../projectService";
import ProjectsPage from "../ProjectsPage";

jest.mock("../projectService", () => {
  const actual = jest.requireActual("../projectService");
  return {
    __esModule: true,
    buildProjectSummaryFilter: actual.buildProjectSummaryFilter,
    default: {
      listProjectSummaries: jest.fn(),
      countUnassignedWorkspaces: jest.fn(),
      createProject: jest.fn(),
    },
  };
});

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

const summary = (overrides: Record<string, unknown>) => ({
  id: "p",
  name: "Project",
  workspaceCount: 3,
  completedCount: 2,
  failedCount: 1,
  runningCount: 0,
  queuedCount: 0,
  waitingApprovalCount: 0,
  neverRunCount: 0,
  nonCompliantCount: 0,
  lockedCount: 0,
  attentionCount: 1,
  teamCount: 1,
  ...overrides,
});

const page = (items: unknown[], totalRecords = items.length) => ({
  isError: false,
  data: { items, totalRecords },
});

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

const lastQuery = () => {
  const calls = (projectService.listProjectSummaries as jest.Mock).mock.calls;
  return calls[calls.length - 1][1];
};

describe("buildProjectSummaryFilter", () => {
  it("is empty without a search or attention filter", () => {
    expect(buildProjectSummaryFilter({})).toBe("");
  });

  it("searches name and description and strips wildcard characters from the input", () => {
    expect(buildProjectSummaryFilter({ search: " bil*ling' " })).toBe(
      "(name=ini=*bil ling*,description=ini=*bil ling*)"
    );
  });

  it("combines the search with the attention filter", () => {
    expect(buildProjectSummaryFilter({ search: "billing", attentionOnly: true })).toBe(
      "(name=ini=*billing*,description=ini=*billing*);attentionCount=gt=0"
    );
  });
});

describe("ProjectsPage", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    sessionStorage.clear();
    localStorage.clear();
    (projectService.listProjectSummaries as jest.Mock).mockResolvedValue(
      page([summary({ id: "a", name: "Billing API" }), summary({ id: "b", name: "Platform", attentionCount: 0 })], 50)
    );
    (projectService.countUnassignedWorkspaces as jest.Mock).mockResolvedValue({ isError: false, data: 0 });
  });

  it("requests the first page sorted by attention and shows the server total", async () => {
    renderProjectsPage();

    expect(await screen.findByText("Billing API")).toBeInTheDocument();
    expect(lastQuery()).toMatchObject({ sort: "-attentionCount,+name", page: 1, pageSize: 24, attentionOnly: false });
    expect(screen.getByText("1 need attention")).toBeInTheDocument();
    expect(screen.getByText("Healthy")).toBeInTheDocument();
  });

  it("asks the server for the next page instead of paging locally", async () => {
    renderProjectsPage();
    await screen.findByText("Billing API");

    fireEvent.click(screen.getByRole("button", { name: "Next Page" }));

    await waitFor(() => expect(lastQuery()).toMatchObject({ page: 2 }));
  });

  it("debounces the search and resets to the first page", async () => {
    jest.useFakeTimers();
    try {
      renderProjectsPage();
      await act(async () => {
        await jest.advanceTimersByTimeAsync(400);
      });
      const calls = (projectService.listProjectSummaries as jest.Mock).mock.calls.length;

      fireEvent.change(screen.getByRole("textbox", { name: "Search projects by name" }), {
        target: { value: "bill" },
      });
      expect((projectService.listProjectSummaries as jest.Mock).mock.calls.length).toBe(calls);
      await act(async () => {
        await jest.advanceTimersByTimeAsync(400);
      });

      expect(lastQuery()).toMatchObject({ search: "bill", page: 1 });
    } finally {
      jest.useRealTimers();
    }
  });

  it("filters to projects that need attention", async () => {
    renderProjectsPage();
    await screen.findByText("Billing API");

    fireEvent.click(screen.getByRole("checkbox", { name: "Needs attention only" }));

    await waitFor(() => expect(lastQuery()).toMatchObject({ attentionOnly: true, page: 1 }));
  });

  it("remembers the cards view", async () => {
    renderProjectsPage();
    await screen.findByText("Billing API");

    fireEvent.click(screen.getByText("Cards"));

    expect(localStorage.getItem("terrakube-projects-view")).toBe("cards");
    expect(screen.getByText("Platform")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("mentions workspaces that are not in a project", async () => {
    (projectService.countUnassignedWorkspaces as jest.Mock).mockResolvedValue({ isError: false, data: 4 });

    renderProjectsPage();

    expect(await screen.findByText(/4 workspaces are not in a project/)).toBeInTheDocument();
  });

  it("shows a no-results state when the filters match nothing", async () => {
    (projectService.listProjectSummaries as jest.Mock).mockResolvedValue(page([]));
    renderProjectsPage();

    fireEvent.click(await screen.findByRole("checkbox", { name: "Needs attention only" }));

    await waitFor(() => expect(screen.getByText("No projects found")).toBeInTheDocument());
    expect(screen.queryByText(/You have not created any projects yet/)).not.toBeInTheDocument();
  });

  it("shows the empty state when the organization has no projects", async () => {
    (projectService.listProjectSummaries as jest.Mock).mockResolvedValue(page([]));

    renderProjectsPage();

    expect(await screen.findByText(/You have not created any projects yet/)).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "Search projects by name" })).not.toBeInTheDocument();
  });

  it("creates a project and reloads the list", async () => {
    renderProjectsPage();
    await screen.findByText("Billing API");
    (projectService.createProject as jest.Mock).mockResolvedValue(undefined);

    fireEvent.click(screen.getByRole("button", { name: /New project/ }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "Platform" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Create" }));

    await waitFor(() => expect(projectService.createProject).toHaveBeenCalledWith("org-1", { name: "Platform" }));
  });
});
