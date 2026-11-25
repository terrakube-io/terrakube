import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import projectService, { buildProjectSummaryFilter, withNeverRunLast } from "../projectService";
import ProjectsPage from "../ProjectsPage";

jest.mock("@/modules/api/apiWrapper", () => ({ apiPost: jest.fn() }));

jest.mock("../projectService", () => {
  const actual = jest.requireActual("../projectService");
  return {
    __esModule: true,
    buildProjectSummaryFilter: actual.buildProjectSummaryFilter,
    withNeverRunLast: actual.withNeverRunLast,
    default: {
      listProjectSummaries: jest.fn(),
      getProjectIndexCounts: jest.fn(),
      getUnassignedSummary: jest.fn(),
      getCurrentGroups: jest.fn(),
      createProject: jest.fn(),
    },
  };
});

let mockManageWorkspace = true;
jest.mock("@/modules/permissions/useOrgPermissions", () => ({
  useOrgPermissions: () => ({ permissions: { manageWorkspace: mockManageWorkspace } }),
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
  hardViolationCount: 0,
  softViolationCount: 0,
  lockedCount: 0,
  attentionCount: 1,
  teamCount: 1,
  ...overrides,
});

const page = (items: unknown[], totalRecords = items.length) => ({
  isError: false,
  data: { items, totalRecords },
});

function Where() {
  const location = useLocation();
  return <div data-testid="where">{location.pathname + location.search}</div>;
}

function renderProjectsPage(path = "/organizations/org-1/projects") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route
          path="/organizations/:id/projects"
          element={
            <>
              <ProjectsPage organizationName="org-1" setOrganizationName={jest.fn()} />
              <Where />
            </>
          }
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

describe("withNeverRunLast", () => {
  it("puts hasRun first in front of the last run, whichever way it is ordered", () => {
    expect(withNeverRunLast("-lastRunAt,+name")).toBe("-hasRun,-lastRunAt,+name");
    expect(withNeverRunLast("+lastRunAt,+name")).toBe("-hasRun,+lastRunAt,+name");
  });

  it("leaves a sort that does not use the last run as it is", () => {
    expect(withNeverRunLast("-attentionCount,+name")).toBe("-attentionCount,+name");
  });
});

describe("buildProjectSummaryFilter", () => {
  it("is empty without a search or attention filter", () => {
    expect(buildProjectSummaryFilter({})).toBe("");
  });

  it("searches name and description for the whole text, spaces and punctuation included", () => {
    expect(buildProjectSummaryFilter({ search: " prod network (eu) " })).toBe(
      '(name=ini="*prod network (eu)*",description=ini="*prod network (eu)*")'
    );
  });

  it("does not let the person add their own wildcards", () => {
    expect(buildProjectSummaryFilter({ search: "bil*ling" })).toBe(
      '(name=ini="*bil ling*",description=ini="*bil ling*")'
    );
  });

  it("combines the search with the attention filter", () => {
    expect(buildProjectSummaryFilter({ search: "billing", attentionOnly: true })).toBe(
      '(name=ini="*billing*",description=ini="*billing*");attentionCount=gt=0'
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

  it("keeps the page from the address after the search delay, because the search did not change", async () => {
    jest.useFakeTimers();
    try {
      renderProjectsPage("/organizations/org-1/projects?page=3&size=24");
      await act(async () => {
        jest.advanceTimersByTime(1000);
      });

      expect(lastQuery()).toMatchObject({ page: 3 });
    } finally {
      jest.useRealTimers();
    }
  });

  it("loads the list once on first load, after the groups are known", async () => {
    renderProjectsPage();
    await screen.findByText("Billing API");

    expect((projectService.listProjectSummaries as jest.Mock).mock.calls).toHaveLength(1);
    expect(lastQuery()).toMatchObject({ groups: ["devs"] });
  });

  it("shows the newest answer when an older, slower one arrives last", async () => {
    let releaseFirst: (value: unknown) => void = () => undefined;
    (projectService.listProjectSummaries as jest.Mock)
      .mockReturnValueOnce(new Promise((resolve) => (releaseFirst = resolve)))
      .mockResolvedValue(page([summary({ id: "new", name: "Newer answer" })], 1));
    renderProjectsPage();
    await waitFor(() => expect(projectService.listProjectSummaries).toHaveBeenCalledTimes(1));

    fireEvent.click(await screen.findByRole("button", { name: /Needs attention\s*3/ }));
    await screen.findByText("Newer answer");

    await act(async () => {
      releaseFirst(page([summary({ id: "old", name: "Older answer" })], 1));
    });

    expect(screen.getByText("Newer answer")).toBeInTheDocument();
    expect(screen.queryByText("Older answer")).not.toBeInTheDocument();
  });

  it("marks the active chip as pressed, like the workspace list", async () => {
    renderProjectsPage();

    await screen.findByText("Billing API");
    expect(screen.getByRole("button", { name: /All\s*50/ })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: /Empty\s*1/ })).toHaveAttribute("aria-pressed", "false");
  });

  it("shows a dash with the total on hover instead of the team count when the caller cannot see who has access", async () => {
    mockManageWorkspace = false;
    try {
      localStorage.setItem("terrakube-projects-view", "cards");
      renderProjectsPage();

      const dashes = await screen.findAllByLabelText("1 team, names hidden");
      expect(dashes.length).toBeGreaterThan(0);
      expect(screen.queryByText("1 team")).not.toBeInTheDocument();
    } finally {
      mockManageWorkspace = true;
    }
  });

  it("shows the team count to callers who manage workspaces", async () => {
    localStorage.setItem("terrakube-projects-view", "cards");
    renderProjectsPage();

    expect((await screen.findAllByText("1 team")).length).toBeGreaterThan(0);
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

    fireEvent.click(screen.getByRole("button", { name: /Needs attention\s*3/ }));

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

  it("applies a saved view: its search, chip and sort, back on page 1, with the address following", async () => {
    localStorage.setItem(
      "terrakube-saved-views:projects:org-1",
      JSON.stringify([{ name: "Ledger problems", query: "q=ledger&chip=attention&sort=-workspaceCount%2C%2Bname" }])
    );
    renderProjectsPage("/organizations/org-1/projects?page=3&size=24");
    await screen.findByText("Billing API");

    fireEvent.click(screen.getByRole("button", { name: /Saved views/ }));
    fireEvent.click(await screen.findByText("Ledger problems"));

    await waitFor(() =>
      expect(lastQuery()).toMatchObject({ search: "ledger", chip: "attention", sort: "-workspaceCount,+name", page: 1 })
    );
    await waitFor(() => expect(screen.getByTestId("where").textContent).toContain("chip=attention"));
    expect(screen.getByTestId("where").textContent).toContain("q=ledger");
    expect(screen.getByTestId("where").textContent).not.toContain("page=3");
  });

  it("falls back to the defaults for what a saved view does not say", async () => {
    localStorage.setItem(
      "terrakube-saved-views:projects:org-1",
      JSON.stringify([{ name: "Only a search", query: "q=ledger" }])
    );
    renderProjectsPage("/organizations/org-1/projects?chip=attention&sort=%2Bname");
    await screen.findByText("Billing API");

    fireEvent.click(screen.getByRole("button", { name: /Saved views/ }));
    fireEvent.click(await screen.findByText("Only a search"));

    await waitFor(() =>
      expect(lastQuery()).toMatchObject({ search: "ledger", chip: "all", sort: "-attentionCount,+name", page: 1 })
    );
  });

  it("keeps the Unassigned entry in table view when the first page is full", async () => {
    const full = Array.from({ length: 50 }, (_, index) => summary({ id: `p${index}`, name: `Project ${index}` }));
    (projectService.listProjectSummaries as jest.Mock).mockResolvedValue(page(full, 80));
    (projectService.getUnassignedSummary as jest.Mock).mockResolvedValue({
      isError: false,
      data: summary({ id: "unassigned", name: "Unassigned", workspaceCount: 41 }),
    });
    renderProjectsPage();

    expect(await screen.findByText("Project 49")).toBeInTheDocument();
    expect(await screen.findByText("Unassigned")).toBeInTheDocument();
  });

  it("does not show the Unassigned entry while filtering", async () => {
    (projectService.getUnassignedSummary as jest.Mock).mockResolvedValue({
      isError: false,
      data: summary({ id: "unassigned", name: "Unassigned", workspaceCount: 41 }),
    });
    localStorage.setItem("terrakube-projects-view", "cards");
    renderProjectsPage();
    await screen.findByText("Unassigned");

    fireEvent.click(screen.getByRole("button", { name: /Empty\s*1/ }));

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

  describe("the view lives in the address", () => {
    it("opens with the filter, search, sort and page from the link", async () => {
      renderProjectsPage(
        "/organizations/org-1/projects?chip=empty&q=pay&sort=-workspaceCount,%2Bname&page=2&size=24&view=table"
      );

      await screen.findByRole("table");
      expect(lastQuery()).toMatchObject({
        chip: "empty",
        search: "pay",
        sort: "-workspaceCount,+name",
        page: 2,
        pageSize: 24,
      });
      expect(screen.getByRole("textbox", { name: "Search projects by name" })).toHaveValue("pay");
    });

    it("ignores values it does not know instead of breaking", async () => {
      renderProjectsPage("/organizations/org-1/projects?chip=bogus&sort=drop-table&page=-4&size=7&view=sideways");

      await screen.findByText("Billing API");
      expect(lastQuery()).toMatchObject({ chip: "all", sort: "-attentionCount,+name", page: 1 });
    });

    it("writes the filter into the address, and keeps a plain address plain", async () => {
      renderProjectsPage();
      await screen.findByText("Billing API");
      expect(screen.getByTestId("where")).toHaveTextContent(/^\/organizations\/org-1\/projects$/);

      fireEvent.click(screen.getByRole("button", { name: /Empty\s*1/ }));

      await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent("chip=empty"));
    });

    it("summarises an active filter and clears it", async () => {
      renderProjectsPage("/organizations/org-1/projects?chip=attention");

      expect(await screen.findByText(/Showing 50 of 50 projects/)).toBeInTheDocument();
      fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));

      await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent(/projects$/));
      expect(screen.queryByText(/Showing/)).not.toBeInTheDocument();
    });
  });

  describe("click-through", () => {
    it("links the attention badge of a card to the workspaces behind it", async () => {
      localStorage.setItem("terrakube-projects-view", "cards");
      renderProjectsPage();

      const link = await screen.findByRole("link", { name: /1 need attention in Billing API/ });
      expect(link).toHaveAttribute("href", "/organizations/org-1/projects/a/workspaces?status=failed");
    });

    it("links the attention count and the policy of a table row, and shows the description", async () => {
      (projectService.listProjectSummaries as jest.Mock).mockResolvedValue(
        page(
          [
            summary({
              id: "a",
              name: "Billing API",
              description: "Card processing",
              nonCompliantCount: 2,
              hardViolationCount: 2,
            }),
          ],
          50
        )
      );
      renderProjectsPage("/organizations/org-1/projects?view=table");

      expect(await screen.findByText("Card processing")).toBeInTheDocument();
      expect(screen.getByRole("link", { name: /1 need attention in Billing API/ })).toHaveAttribute(
        "href",
        "/organizations/org-1/projects/a/workspaces?status=failed"
      );
      expect(screen.getByRole("link", { name: /2 hard in Billing API/ })).toHaveAttribute(
        "href",
        "/organizations/org-1/projects/a/workspaces?policy=NON_COMPLIANT"
      );
    });
  });
});
