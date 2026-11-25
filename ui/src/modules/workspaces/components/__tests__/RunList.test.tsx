import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { axe, toHaveNoViolations } from "jest-axe";
import { MemoryRouter } from "react-router-dom";
import RunList from "../RunList";
import { FlatJob, JobStatus } from "@/domain/types";
import { ORGANIZATION_ARCHIVE } from "@/config/actionTypes";

expect.extend(toHaveNoViolations);

const mockApproveRuns = jest.fn();
jest.mock("@/modules/projects/bulkWorkspaceActions", () => ({
  approveRuns: (...args: unknown[]) => mockApproveRuns(...args),
}));

jest.mock("@/config/axiosConfig", () => ({
  __esModule: true,
  default: {
    get: jest.fn().mockResolvedValue({ data: { data: [{ id: "tpl-1", attributes: { name: "Plan and Apply" } }] } }),
  },
}));

const job = (id: string, status: JobStatus, extra: Partial<FlatJob> = {}): FlatJob => ({
  id,
  title: `Run ${id} title`,
  status,
  latestChange: "2 hours ago",
  createdBy: "admin@example.com",
  commitId: "937b249da41c50a9e944f7205e95f694842c5394",
  createdDate: "2026-10-04T10:00:00Z",
  updatedDate: "2026-10-04T10:01:05Z",
  templateReference: "tpl-1",
  ...extra,
});

const renderList = (jobs: FlatJob[]) =>
  render(
    <MemoryRouter>
      <RunList jobs={jobs} onRunClick={jest.fn()} runLink={(id) => `/runs/${id}`} />
    </MemoryRouter>
  );

describe("RunList", () => {
  beforeEach(() => sessionStorage.clear());

  it("shows the count, only statuses that have runs, and a byline with commit and duration", async () => {
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, "org-1");
    renderList([job("1", JobStatus.Completed), job("2", JobStatus.Failed), job("3", JobStatus.Completed)]);

    expect(screen.getByRole("heading", { name: "Runs (3)" })).toBeInTheDocument();
    const chips = within(screen.getByRole("group", { name: "Filter by status" }));
    expect(chips.getByRole("button", { name: "All 3" })).toBeInTheDocument();
    expect(chips.getByRole("button", { name: "Failed 1" })).toBeInTheDocument();
    expect(chips.getByRole("button", { name: "Completed 2" })).toBeInTheDocument();
    expect(chips.queryByRole("button", { name: /running/i })).not.toBeInTheDocument();

    const rows = screen.getAllByRole("listitem");
    expect(within(rows[0]).getByRole("link", { name: "Run 3 title" })).toHaveAttribute("href", "/runs/3");
    expect(within(rows[0]).getByText("Current")).toBeInTheDocument();
    expect(within(rows[0]).getByText("937b249")).toBeInTheDocument();
    expect(within(rows[0]).getByText("1m 05s")).toBeInTheDocument();
    await waitFor(() => expect(within(rows[0]).getByText("Plan and Apply")).toBeInTheDocument());
  });

  it("shows what the plan changes, and nothing for a run without a summary", () => {
    renderList([
      job("1", JobStatus.Completed),
      job("2", JobStatus.WaitingApproval, { planAdd: 3, planChange: 1, planDestroy: 0 }),
      job("3", JobStatus.Completed, { planAdd: 0, planChange: 0, planDestroy: 0 }),
    ]);

    const rows = screen.getAllByRole("listitem");
    // Newest first: run 3, run 2, run 1.
    expect(within(rows[0]).getByText("No changes")).toBeInTheDocument();
    expect(within(rows[1]).getByText("3 to add, 1 to change, 0 to destroy")).toBeInTheDocument();
    expect(within(rows[1]).getByText("+3").parentElement).toHaveTextContent("+3 ~1 -0");
    expect(within(rows[2]).queryByText(/to add|No changes/)).not.toBeInTheDocument();
  });

  it("offers Approve only on a run that waits, and only to someone who may approve", async () => {
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, "org-1");
    mockApproveRuns.mockResolvedValue({ succeeded: ["2"], failed: [] });
    const onApproved = jest.fn();
    const jobs = [job("1", JobStatus.Completed), job("2", JobStatus.WaitingApproval)];
    const { unmount } = render(
      <MemoryRouter>
        <RunList
          jobs={jobs}
          onRunClick={jest.fn()}
          runLink={(id) => `/runs/${id}`}
          canApprove
          onApproved={onApproved}
        />
      </MemoryRouter>
    );

    expect(screen.getAllByRole("button", { name: "Approve" })).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "Approve" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Approve" }));

    await waitFor(() => expect(mockApproveRuns).toHaveBeenCalledWith("org-1", ["2"]));
    await waitFor(() => expect(onApproved).toHaveBeenCalled());
    unmount();

    renderList(jobs);
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
  });

  it("has no accessibility violations with plan counts and an Approve button", async () => {
    const { container } = render(
      <MemoryRouter>
        <RunList
          jobs={[
            job("1", JobStatus.Completed),
            job("2", JobStatus.WaitingApproval, { planAdd: 3, planChange: 1, planDestroy: 0 }),
          ]}
          onRunClick={jest.fn()}
          runLink={(id) => `/runs/${id}`}
          canApprove
        />
      </MemoryRouter>
    );

    expect(await axe(container)).toHaveNoViolations();
  });

  it("marks a run that was a drift check with what it found", () => {
    renderList([job("1", JobStatus.Completed, { driftResult: "drift", planAdd: 0, planChange: 2, planDestroy: 0 })]);

    expect(screen.getByText("Drift check: drift")).toBeInTheDocument();
  });

  it("names the pull request that started a run", () => {
    renderList([job("1", JobStatus.Completed, { via: "Github" as never, prNumber: 1873 })]);

    expect(screen.getByText(/PR #1873/)).toBeInTheDocument();
  });

  it("filters by status and search, and clears back to every run", () => {
    renderList([job("1", JobStatus.Completed), job("2", JobStatus.Failed)]);

    fireEvent.click(screen.getByRole("button", { name: "Failed 1" }));
    expect(screen.getAllByRole("listitem")).toHaveLength(1);
    expect(screen.getByRole("link", { name: "Run 2 title" })).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Search runs"), { target: { value: "nothing like this" } });
    expect(screen.getByText("No runs match these filters.")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));
    expect(screen.getAllByRole("listitem")).toHaveLength(2);
  });

  it("shows an empty state when the workspace has no runs", () => {
    renderList([]);
    expect(screen.getByRole("heading", { name: "Runs (0)" })).toBeInTheDocument();
    expect(screen.getByText(/No runs yet/)).toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "Filter by status" })).not.toBeInTheDocument();
  });

  describe("policy result", () => {
    const renderWithPolicy = (policyByRun: Record<string, "FAILED" | "PASSED" | "WARNING">) =>
      render(
        <MemoryRouter>
          <RunList
            jobs={[job("1", JobStatus.Completed), job("2", JobStatus.Completed), job("3", JobStatus.Completed)]}
            onRunClick={jest.fn()}
            runLink={(id) => `/runs/${id}`}
            policyByRun={policyByRun}
          />
        </MemoryRouter>
      );

    it("shows what the policy checks said next to the status, and nothing for a run that was not evaluated", async () => {
      renderWithPolicy({ "1": "FAILED", "2": "PASSED" });

      expect(await screen.findByText("Policy failed")).toBeInTheDocument();
      expect(screen.getByText("Policy passed")).toBeInTheDocument();
      expect(screen.queryAllByText(/^Policy /)).toHaveLength(2);
    });

    it("has no policy column at all when no result was read", async () => {
      renderList([job("1", JobStatus.Completed)]);

      await screen.findByText("Run 1 title");
      expect(screen.queryByText(/^Policy /)).not.toBeInTheDocument();
    });
  });
});
