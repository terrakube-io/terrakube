import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { message } from "antd";
import ProjectWorkspaces from "../ProjectWorkspaces";
import workspaceService from "@/modules/workspaces/workspaceService";
import { usePolling } from "@/hooks";
import { JobStatus } from "@/domain/types";
import { WorkspaceListItem } from "@/modules/workspaces/types";
import { formatDateTimeWithZone } from "@/modules/utils/dates";

jest.mock("@/hooks", () => ({ usePolling: jest.fn() }));
jest.mock("@/modules/workspaces/workspaceService", () => ({ listWorkspacePage: jest.fn(), listWorkspaces: jest.fn() }));
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

function renderTab(projectId = "proj-1") {
  return render(
    <MemoryRouter>
      <ProjectWorkspaces orgid="org-1" projectId={projectId} />
    </MemoryRouter>
  );
}

const lastRequest = () => listWorkspacePage.mock.calls.at(-1)![0];
const poll = () => act(async () => (usePolling as jest.Mock).mock.calls.at(-1)![0]());

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
      { organizationId: "org-1", first: 10, after: 0, projectId: "proj-1", sort: "name_asc" },
      false
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
      "Name",
      "Status",
      "Last run",
      "Repository",
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

    expect(await screen.findByText("Never executed")).toBeInTheDocument();
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
    await userEvent.click(screen.getByTitle("2"));
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

    await userEvent.click(screen.getByText("Name"));
    await waitFor(() => expect(lastRequest().sort).toBe("name_desc"));
    await userEvent.click(screen.getByText("Name"));
    await waitFor(() => expect(lastRequest().sort).toBe("name_asc"));
  });

  it("pages on the server and sizes the page from the selector", async () => {
    listWorkspacePage.mockResolvedValue(page([workspace()], 45));
    renderTab();
    await screen.findByText("network-production");

    await userEvent.click(screen.getByTitle("3"));
    await waitFor(() => expect(lastRequest()).toEqual(expect.objectContaining({ first: 10, after: 20 })));
  });

  it("goes back to the last page when the page it is on no longer exists", async () => {
    listWorkspacePage.mockResolvedValueOnce(page([workspace()], 25));
    renderTab();
    await screen.findByText("network-production");

    listWorkspacePage.mockResolvedValueOnce(page([], 10));
    await userEvent.click(screen.getByTitle("3"));
    await waitFor(() => expect(lastRequest()).toEqual(expect.objectContaining({ after: 0 })));
  });

  it("says so when the project has no workspaces", async () => {
    listWorkspacePage.mockResolvedValue(page([]));
    renderTab();

    expect(await screen.findByText("No workspaces assigned to this project yet.")).toBeInTheDocument();
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
    await userEvent.click(screen.getByTitle("2"));

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
});
