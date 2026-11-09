import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import projectService, { buildProjectSummaryFilter } from "../projectService";
import ProjectsPage from "../ProjectsPage";

jest.mock("@/modules/api/apiWrapper", () => ({ apiPost: jest.fn() }));

jest.mock("../projectService", () => {
  const actual = jest.requireActual("../projectService");
  return {
    __esModule: true,
    buildProjectSummaryFilter: actual.buildProjectSummaryFilter,
    default: {
      listProjectSummaries: jest.fn(),
      getProjectIndexCounts: jest.fn(),
      getUnassignedSummary: jest.fn(),
      getCurrentGroups: jest.fn(),
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
  compliantCount: 3,
  exemptedCount: 0,
  queuedJobCount: 0,
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

describe("listProjectSummaries request", () => {
  it("sends first and after as strings, because Elide declares them as StringOrInt, which only accepts string values", async () => {
    const { apiPost } = jest.requireMock("@/modules/api/apiWrapper");
    apiPost.mockResolvedValue({ isError: false, data: { organization: { edges: [] } } });
    const actual = jest.requireActual("../projectService").default;

    await actual.listProjectSummaries("org-1", { sort: "+name", page: 3, pageSize: 24 });

    const body = apiPost.mock.calls[0][1];
    expect(body.variables.first).toBe("24");
    expect(body.variables.after).toBe("48");
    expect(body.query).toContain("$first: StringOrInt");
  });

  it("leaves the filter out entirely when there is none, because Elide rejects a null filter", async () => {
    const { apiPost } = jest.requireMock("@/modules/api/apiWrapper");
    apiPost.mockResolvedValue({ isError: false, data: { organization: { edges: [] } } });
    const actual = jest.requireActual("../projectService").default;

    await actual.listProjectSummaries("org-1", { sort: "+name", page: 1, pageSize: 24 });

    const body = apiPost.mock.calls[apiPost.mock.calls.length - 1][1];
    expect(body.variables).not.toHaveProperty("filter");
    expect(body.query).not.toContain("$filter");
  });
});

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

const counts = { all: 50, attention: 3, mine: 2, empty: 1, stale: 4 };

describe("ProjectsPage", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    sessionStorage.clear();
    localStorage.clear();
    (projectService.listProjectSummaries as jest.Mock).mockResolvedValue(
      page(
        [
          summary({ id: "a", name: "Billing API" }),
          summary({ id: "b", name: "Platform", attentionCount: 0, failedCount: 0 }),
        ],
        50
      )
    );
    (projectService.getProjectIndexCounts as jest.Mock).mockResolvedValue({ isError: false, data: counts });
    (projectService.getUnassignedSummary as jest.Mock).mockResolvedValue({
      isError: false,
      data: summary({ id: "unassigned", name: "Unassigned", workspaceCount: 0 }),
    });
    (projectService.getCurrentGroups as jest.Mock).mockResolvedValue(["devs"]);
  });

  it("requests the first page sorted by attention and shows the total in the title", async () => {
    renderProjectsPage();

    expect(await screen.findByText("Billing API")).toBeInTheDocument();
    expect(lastQuery()).toMatchObject({ sort: "-attentionCount,+name", page: 1, chip: "all", groups: ["devs"] });
    await waitFor(() => expect(screen.getByRole("group", { name: "Filter projects" })).toHaveTextContent("All 50"));
  });

  it("shows the attention badge on a card", async () => {
    localStorage.setItem("terrakube-projects-view", "cards");
    renderProjectsPage();

    expect(await screen.findByText("1 need attention")).toBeInTheDocument();
  });

  it("shows cards for a small catalogue and a table for a large one, and a saved choice wins", async () => {
    (projectService.getProjectIndexCounts as jest.Mock).mockResolvedValue({
      isError: false,
      data: { ...counts, all: 6 },
    });
    const { unmount } = renderProjectsPage();
    await screen.findByText("Billing API");
    await waitFor(() => expect(screen.queryByRole("table")).not.toBeInTheDocument());
    unmount();

    localStorage.setItem("terrakube-projects-view", "table");
    renderProjectsPage();
    expect(await screen.findByRole("table")).toBeInTheDocument();
  });

  it("uses a table above 24 projects", async () => {
    renderProjectsPage();

    expect(await screen.findByRole("table")).toBeInTheDocument();
    for (const column of ["Project", "Health", "Workspaces", "Attention", "Policy", "Teams", "Last run"]) {
      expect(screen.getByRole("columnheader", { name: new RegExp(column) })).toBeInTheDocument();
    }
  });

  it("filters by a chip and goes back to the first page", async () => {
    renderProjectsPage();
    await screen.findByText("Billing API");

    fireEvent.click(screen.getByText(/Needs attention 3/));

    await waitFor(() => expect(lastQuery()).toMatchObject({ chip: "attention", page: 1 }));
  });

  it("asks the server for the next page instead of paging locally", async () => {
    localStorage.setItem("terrakube-projects-view", "table");
    (projectService.listProjectSummaries as jest.Mock).mockResolvedValue(
      page([summary({ id: "a", name: "Billing API" })], 120)
    );
    renderProjectsPage();
    await screen.findByText("Billing API");

    fireEvent.click(screen.getByRole("button", { name: "Next Page" }));

    await waitFor(() => expect(lastQuery()).toMatchObject({ page: 2 }));
  });

  it("sorts on the server when a column header is clicked", async () => {
    renderProjectsPage();
    await screen.findByRole("table");

    fireEvent.click(screen.getByRole("columnheader", { name: /Workspaces/ }));

    await waitFor(() => expect(lastQuery().sort).toBe("+workspaceCount,+name"));
  });

  it("debounces the search and resets to the first page", async () => {
    jest.useFakeTimers();
    try {
      renderProjectsPage();
      await act(async () => {
        await jest.advanceTimersByTimeAsync(400);
      });
      const calls = (projectService.listProjectSummaries as jest.Mock).mock.calls.length;

      fireEvent.change(screen.getByRole("textbox", { name: "Search projects by name" }), { target: { value: "bill" } });
      expect((projectService.listProjectSummaries as jest.Mock).mock.calls.length).toBe(calls);
      await act(async () => {
        await jest.advanceTimersByTimeAsync(400);
      });

      expect(lastQuery()).toMatchObject({ search: "bill", page: 1 });
    } finally {
      jest.useRealTimers();
    }
  });

  it("shows workspaces that are in no project as an Unassigned entry", async () => {
    (projectService.getUnassignedSummary as jest.Mock).mockResolvedValue({
      isError: false,
      data: summary({ id: "unassigned", name: "Unassigned", workspaceCount: 41, attentionCount: 3 }),
    });
    localStorage.setItem("terrakube-projects-view", "cards");

    renderProjectsPage();

    expect(await screen.findByText("Unassigned")).toBeInTheDocument();
    expect(screen.getByText("41 workspaces")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Move them to a project" })).toBeInTheDocument();
  });

  it("does not show the Unassigned entry while filtering", async () => {
    (projectService.getUnassignedSummary as jest.Mock).mockResolvedValue({
      isError: false,
      data: summary({ id: "unassigned", name: "Unassigned", workspaceCount: 41 }),
    });
    localStorage.setItem("terrakube-projects-view", "cards");
    renderProjectsPage();
    await screen.findByText("Unassigned");

    fireEvent.click(screen.getByText(/Empty 1/));

    await waitFor(() => expect(screen.queryByText("Unassigned")).not.toBeInTheDocument());
  });

  it("shows a no-match state with a way to clear the search", async () => {
    renderProjectsPage();
    await screen.findByText("Billing API");
    (projectService.listProjectSummaries as jest.Mock).mockResolvedValue(page([]));

    fireEvent.change(screen.getByRole("textbox", { name: "Search projects by name" }), { target: { value: "pay" } });

    expect(await screen.findByText('No projects match "pay".')).toBeInTheDocument();
    expect(screen.queryByText(/groups workspaces so a team/)).not.toBeInTheDocument();
    (projectService.listProjectSummaries as jest.Mock).mockResolvedValue(
      page([summary({ id: "a", name: "Billing API" })])
    );
    fireEvent.click(screen.getByRole("button", { name: "Clear search" }));
    await waitFor(() => expect(lastQuery()).toMatchObject({ search: "" }));
  });

  it("shows the empty state when the organization has no projects", async () => {
    (projectService.listProjectSummaries as jest.Mock).mockResolvedValue(page([]));
    (projectService.getProjectIndexCounts as jest.Mock).mockResolvedValue({
      isError: false,
      data: { all: 0, attention: 0, mine: 0, empty: 0, stale: 0 },
    });

    renderProjectsPage();

    expect(await screen.findByText(/A project groups workspaces so a team shares access/)).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "Search projects by name" })).not.toBeInTheDocument();
  });

  it("never shows a count it could not load as 0, and offers a retry", async () => {
    (projectService.getProjectIndexCounts as jest.Mock).mockResolvedValue({ isError: true, data: undefined });
    renderProjectsPage();

    expect(await screen.findByText("Counts unavailable.")).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "Filter projects" })).toHaveTextContent("All -");
    (projectService.getProjectIndexCounts as jest.Mock).mockResolvedValue({ isError: false, data: counts });
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    await waitFor(() => expect(screen.queryByText("Counts unavailable.")).not.toBeInTheDocument());
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
