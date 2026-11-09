import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, useLocation } from "react-router-dom";
import { message } from "antd";
import ProjectWorkspaces from "../ProjectWorkspaces";
import workspaceService from "@/modules/workspaces/workspaceService";
import { usePolling } from "@/hooks";
import { JobStatus } from "@/domain/types";
import { WorkspaceListItem } from "@/modules/workspaces/types";
import { formatDateTimeWithZone } from "@/modules/utils/dates";

jest.mock("@/hooks", () => ({ usePolling: jest.fn() }));
jest.mock("@/modules/workspaces/workspaceService", () => ({ listWorkspacePage: jest.fn(), listWorkspaces: jest.fn() }));
let mockFilterProps: any;
jest.mock("@/modules/workspaces/components/WorkspaceFilter", () => ({
  __esModule: true,
  default: (props: unknown) => {
    mockFilterProps = props;
    return null;
  },
}));
jest.mock("../projectService", () => ({
  __esModule: true,
  default: { listProjectDefaultTags: jest.fn().mockResolvedValue([{ id: "d1", tagId: "t-env", value: "prod" }]) },
}));
jest.mock("antd", () => ({ ...jest.requireActual("antd"), message: { error: jest.fn() } }));

const twoHoursAgo = new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString();

const workspace = (overrides: Partial<WorkspaceListItem> = {}): WorkspaceListItem => ({
  id: "ws-1",
  name: "network-production",
  iacType: "terraform",
  source: "https://github.com/acme/network",
  normalizedSource: "https://github.com/acme/network",
  lastStatus: JobStatus.Completed,
  lastRun: twoHoursAgo,
  projectId: "proj-1",
  ...overrides,
});

const page = (workspaces: WorkspaceListItem[], totalRecords = workspaces.length) => ({
  isError: false,
  responseCode: 200,
  data: {
    organizationName: "acme",
    workspaces,
    pageInfo: { hasNextPage: false, totalRecords },
    statusCounts: {},
    policyCounts: {},
  },
});

const failure = (status = "Failed to load workspaces", errorMessage = "The API is not reachable") => ({
  isError: true,
  responseCode: 500,
  error: { status, message: errorMessage },
  data: undefined,
});

const listWorkspacePage = workspaceService.listWorkspacePage as jest.Mock;

function Where() {
  const location = useLocation();
  return <div data-testid="where">{location.pathname + location.search}</div>;
}

function renderTab(projectId = "proj-1", canManage = false, path = "/projects/proj-1/workspaces") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ProjectWorkspaces orgid="org-1" projectId={projectId} canManage={canManage} />
      <Where />
    </MemoryRouter>
  );
}

const lastRequest = () => listWorkspacePage.mock.calls[listWorkspacePage.mock.calls.length - 1]![0];
const poll = () =>
  act(async () => (usePolling as jest.Mock).mock.calls[(usePolling as jest.Mock).mock.calls.length - 1]![0]());

describe("ProjectWorkspaces", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    listWorkspacePage.mockResolvedValue(page([workspace()]));
  });

  it("asks the server for one page of this project and never loads the whole organization", async () => {
    renderTab();

    expect(await screen.findByText("network-production")).toBeInTheDocument();
    expect(listWorkspacePage).toHaveBeenCalledTimes(1);
    expect(listWorkspacePage).toHaveBeenCalledWith(
      {
        organizationId: "org-1",
        first: 10,
        after: 0,
        projectId: "proj-1",
        sort: "name_asc",
        search: undefined,
        status: "All",
        policyStatus: "All",
        tagFilters: [],
      },
      true
    );
    expect(workspaceService.listWorkspaces).not.toHaveBeenCalled();
  });

  it("shows the name link, status, last run and repository", async () => {
    renderTab();

    const link = await screen.findByRole("link", { name: "network-production" });
    expect(link).toHaveAttribute("href", "/organizations/org-1/workspaces/ws-1");
    const row = link.closest("tr")!;
    expect(within(row).getByText("2 hours ago")).toBeInTheDocument();
    const repository = within(row).getByRole("link", { name: "acme/network" });
    expect(repository).toHaveAttribute("href", "https://github.com/acme/network");
    expect(repository).toHaveAttribute("target", "_blank");
    expect(repository).toHaveAttribute("title", "https://github.com/acme/network");
    expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
      "Workspace",
      "Status",
      "Policy",
      "Tags",
      "Last run",
    ]);
  });

  it("shows the workflow instead of a repository for CLI and API driven workspaces", async () => {
    listWorkspacePage.mockResolvedValue(
      page([
        workspace({ id: "ws-cli", name: "cli-driven", branch: "remote-content" }),
        workspace({ id: "ws-empty", name: "no-source", source: "", normalizedSource: undefined }),
      ])
    );
    renderTab();

    await screen.findByText("cli-driven");
    expect(screen.getAllByText("cli/api driven workflow")).toHaveLength(2);
    expect(screen.queryByRole("link", { name: "acme/network" })).not.toBeInTheDocument();
  });

  it("shows Never executed for a workspace without a run", async () => {
    listWorkspacePage.mockResolvedValue(page([workspace({ lastRun: undefined, lastStatus: undefined })]));
    renderTab();

    expect((await screen.findAllByText("Never executed")).length).toBeGreaterThan(0);
  });

  it("shows the exact time with the timezone when the last run is focused", async () => {
    renderTab();

    const time = await screen.findByText("2 hours ago");
    act(() => time.focus());
    expect(await screen.findByRole("tooltip")).toHaveTextContent(formatDateTimeWithZone(twoHoursAgo));
  });

  it("sorts by last run on the server, newest first, and restarts at the first page", async () => {
    listWorkspacePage.mockResolvedValue(page([workspace()], 25));
    renderTab();
    await screen.findByText("network-production");
    await userEvent.click(screen.getByRole("button", { name: "Page 2" }));
    await waitFor(() => expect(lastRequest()).toEqual(expect.objectContaining({ after: 10, sort: "name_asc" })));

    await userEvent.click(screen.getByText("Last run"));
    await waitFor(() => expect(lastRequest()).toEqual(expect.objectContaining({ after: 0, sort: "lastRun_desc" })));

    await userEvent.click(screen.getByText("Last run"));
    await waitFor(() => expect(lastRequest()).toEqual(expect.objectContaining({ sort: "lastRun_asc" })));

    await userEvent.click(screen.getByText("Last run"));
    await waitFor(() => expect(lastRequest()).toEqual(expect.objectContaining({ sort: "name_asc" })));
  });

  it("toggles the name order without a way to clear it", async () => {
    renderTab();
    await screen.findByText("network-production");

    await userEvent.click(screen.getByText("Workspace"));
    await waitFor(() => expect(lastRequest().sort).toBe("name_desc"));
    await userEvent.click(screen.getByText("Workspace"));
    await waitFor(() => expect(lastRequest().sort).toBe("name_asc"));
  });

  it("pages on the server and sizes the page from the selector", async () => {
    listWorkspacePage.mockResolvedValue(page([workspace()], 45));
    renderTab();
    await screen.findByText("network-production");

    await userEvent.click(screen.getByRole("button", { name: "Page 3" }));
    await waitFor(() => expect(lastRequest()).toEqual(expect.objectContaining({ first: 10, after: 20 })));
  });

  it("goes back to the last page when the page it is on no longer exists", async () => {
    listWorkspacePage.mockResolvedValueOnce(page([workspace()], 25));
    renderTab();
    await screen.findByText("network-production");

    listWorkspacePage.mockResolvedValueOnce(page([], 10));
    await userEvent.click(screen.getByRole("button", { name: "Page 3" }));
    await waitFor(() => expect(lastRequest()).toEqual(expect.objectContaining({ after: 0 })));
  });

  it("says so when the project has no workspaces", async () => {
    listWorkspacePage.mockResolvedValue(page([]));
    renderTab();

    expect(await screen.findByText("No workspaces in this project.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "New workspace" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Move existing" })).toBeInTheDocument();
  });

  it("shows a failed first load as an error with a retry, not as an empty project", async () => {
    listWorkspacePage.mockResolvedValueOnce(failure("Failed to load workspaces", "The API is not reachable"));
    renderTab();

    expect(await screen.findByText("The API is not reachable")).toBeInTheDocument();
    expect(screen.queryByText("No workspaces assigned to this project yet.")).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("network-production")).toBeInTheDocument();
    expect(screen.queryByText("The API is not reachable")).not.toBeInTheDocument();
  });

  it("keeps the rows and says so when a later page or sort fails", async () => {
    listWorkspacePage.mockResolvedValue(page([workspace()], 25));
    renderTab();
    await screen.findByText("network-production");

    listWorkspacePage.mockResolvedValueOnce(failure("Failed", "Timed out"));
    await userEvent.click(screen.getByRole("button", { name: "Page 2" }));

    await waitFor(() => expect(message.error).toHaveBeenCalledWith("Timed out"));
    expect(screen.getByText("network-production")).toBeInTheDocument();
  });

  it("refreshes quietly: new data replaces the rows and a failure changes nothing", async () => {
    renderTab();
    await screen.findByText("network-production");
    expect(usePolling).toHaveBeenCalledWith(expect.any(Function), expect.objectContaining({ interval: 10000 }));

    listWorkspacePage.mockResolvedValueOnce(page([workspace({ id: "ws-2", name: "network-staging" })]));
    await poll();
    expect(await screen.findByText("network-staging")).toBeInTheDocument();

    listWorkspacePage.mockResolvedValueOnce(failure());
    await poll();
    expect(message.error).not.toHaveBeenCalled();
    expect(screen.getByText("network-staging")).toBeInTheDocument();
  });

  it("ignores a slow response that was overtaken by a newer request", async () => {
    let resolveFirst: (value: unknown) => void = () => {};
    listWorkspacePage.mockReturnValueOnce(new Promise((resolve) => (resolveFirst = resolve)));
    renderTab();

    listWorkspacePage.mockResolvedValueOnce(page([workspace({ id: "ws-2", name: "newer-result" })]));
    await poll();
    expect(await screen.findByText("newer-result")).toBeInTheDocument();

    await act(async () => resolveFirst(page([workspace({ name: "stale-result" })])));
    expect(screen.queryByText("stale-result")).not.toBeInTheDocument();
    expect(screen.getByText("newer-result")).toBeInTheDocument();
  });

  describe("filters", () => {
    it("sends the status, policy, search and tag filters to the server and starts again at the first page", async () => {
      listWorkspacePage.mockResolvedValue(page([workspace()], 45));
      renderTab();
      await screen.findByText("network-production");

      act(() => mockFilterProps.onStatusChange("failed"));
      await waitFor(() => expect(lastRequest()).toEqual(expect.objectContaining({ status: "failed", after: 0 })));
      act(() => mockFilterProps.onPolicyStatusChange("NON_COMPLIANT"));
      await waitFor(() => expect(lastRequest()).toEqual(expect.objectContaining({ policyStatus: "NON_COMPLIANT" })));
      act(() => mockFilterProps.onTagFiltersChange([{ tagId: "t1", value: "prod" }]));
      await waitFor(() =>
        expect(lastRequest()).toEqual(expect.objectContaining({ tagFilters: [{ tagId: "t1", value: "prod" }] }))
      );
    });

    it("says no workspaces match when filters hide every row, not that the project is empty", async () => {
      renderTab();
      await screen.findByText("network-production");
      listWorkspacePage.mockResolvedValue(page([]));

      act(() => mockFilterProps.onStatusChange("failed"));

      expect(await screen.findByText("No workspaces match these filters.")).toBeInTheDocument();
      expect(screen.queryByText("No workspaces in this project.")).not.toBeInTheDocument();
    });
  });

  it("marks tags that came from the project", async () => {
    listWorkspacePage.mockResolvedValue(
      page([workspace({ tags: [{ tagId: "t-env", value: "prod" }, { tagId: "t-own" }] })])
    );
    renderTab();

    await screen.findByText("network-production");
    expect(await screen.findAllByText("from project")).toHaveLength(1);
  });

  describe("bulk selection", () => {
    it("is not offered without permission", async () => {
      renderTab("proj-1", false);
      await screen.findByText("network-production");

      expect(screen.queryAllByRole("checkbox")).toHaveLength(0);
    });

    it("shows the bulk bar for ticked rows and covers this page only", async () => {
      listWorkspacePage.mockResolvedValue(
        page([workspace({ id: "a", name: "alpha" }), workspace({ id: "b", name: "beta" })], 18)
      );
      renderTab("proj-1", true);
      await screen.findByText("alpha");

      const boxes = screen.getAllByRole("checkbox");
      await userEvent.click(boxes[1]);
      await userEvent.click(boxes[2]);

      expect(screen.getByText("2 of 18 selected")).toBeInTheDocument();
      expect(screen.getByText("(this page only)")).toBeInTheDocument();
    });
  });

  it("switches to cards", async () => {
    renderTab();
    await screen.findByText("network-production");

    await userEvent.click(screen.getByText("Cards"));

    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.getByText("network-production")).toBeInTheDocument();
  });

  it("pages the cards on the server too, so a project with many workspaces is not cut at ten", async () => {
    listWorkspacePage.mockResolvedValue(page([workspace()], 45));
    renderTab();
    await screen.findByText("network-production");
    await userEvent.click(screen.getByText("Cards"));

    await userEvent.click(screen.getByRole("button", { name: "Page 3" }));

    await waitFor(() => expect(lastRequest()).toEqual(expect.objectContaining({ first: 10, after: 20 })));
  });

  describe("the filters live in the address", () => {
    it("opens already filtered when linked to from a number", async () => {
      renderTab(
        "proj-1",
        false,
        "/projects/proj-1/workspaces?status=failed&policy=NON_COMPLIANT&q=net&tag=t1~prod&tag=t2&sort=lastRun_desc&page=2&size=20"
      );

      await screen.findByText("network-production");
      expect(lastRequest()).toEqual(
        expect.objectContaining({
          status: "failed",
          policyStatus: "NON_COMPLIANT",
          search: "net",
          sort: "lastRun_desc",
          after: 20,
          first: 20,
          tagFilters: [
            { tagId: "t1", value: "prod" },
            { tagId: "t2", value: undefined },
          ],
        })
      );
      expect(mockFilterProps.status).toBe("failed");
      expect(mockFilterProps.search).toBe("net");
    });

    it("ignores values it does not know", async () => {
      renderTab("proj-1", false, "/projects/proj-1/workspaces?status=bogus&policy=nope&sort=x&page=zero&size=3");

      await screen.findByText("network-production");
      expect(lastRequest()).toEqual(
        expect.objectContaining({ status: "All", policyStatus: "All", sort: "name_asc", first: 10, after: 0 })
      );
    });

    it("writes a changed filter into the address and keeps a plain address plain", async () => {
      renderTab();
      await screen.findByText("network-production");
      expect(screen.getByTestId("where")).toHaveTextContent(/^\/projects\/proj-1\/workspaces$/);

      act(() => mockFilterProps.onStatusChange("waitingApproval"));

      await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent("status=waitingApproval"));
      act(() => mockFilterProps.onTagFiltersChange([{ tagId: "t1", value: "prod" }]));
      await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent("tag=t1%7Eprod"));
      act(() => mockFilterProps.onStatusChange("All"));
      await waitFor(() => expect(screen.getByTestId("where")).not.toHaveTextContent("status="));
    });
  });
});
