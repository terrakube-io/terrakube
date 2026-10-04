import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import RunList from "../RunList";
import { FlatJob, JobStatus } from "@/domain/types";

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
});
