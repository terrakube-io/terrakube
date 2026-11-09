import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { Modal } from "antd";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router-dom";
import { listOrganizationNames } from "@/modules/navigation/navigationService";
import { approveRuns, findWaitingRuns } from "@/modules/projects/bulkWorkspaceActions";
import InboxPage from "../InboxPage";
import { loadInbox } from "../inboxService";
import { InboxData } from "../inboxModel";

jest.mock("@/hooks", () => ({ usePolling: jest.fn() }));
jest.mock("@/components/layout/PageWrapper/PageWrapper", () => ({
  __esModule: true,
  default: ({ children }: { children: ReactNode }) => <>{children}</>,
}));
jest.mock("@/modules/navigation/navigationService");
jest.mock("@/modules/projects/bulkWorkspaceActions");
jest.mock("../inboxService");

const names = listOrganizationNames as jest.Mock;
const load = loadInbox as jest.Mock;
const waiting = findWaitingRuns as jest.Mock;
const approve = approveRuns as jest.Mock;

const item = (kind: "waiting" | "failed" | "policy", name: string, extra = {}) => ({
  key: `${kind}:${name}`,
  kind,
  orgId: "org-1",
  orgName: "JXP",
  workspaceId: `${name}-id`,
  name,
  at: new Date(Date.now() - 3 * 60 * 60 * 1000).toISOString(),
  ...extra,
});

const data = (overrides: Partial<InboxData> = {}): InboxData => ({
  items: [item("waiting", "ledger-db", { projectName: "payments" }), item("failed", "gateway"), item("policy", "vpc")],
  counts: { waiting: 1, failed: 1, policy: 1 },
  failedOrganizations: [],
  ...overrides,
});

const renderPage = () =>
  render(
    <MemoryRouter>
      <InboxPage />
    </MemoryRouter>
  );

describe("InboxPage", () => {
  // Confirmation dialogs are rendered outside the tree, so one test must not leave its dialog for the next.
  afterEach(() => Modal.destroyAll());

  beforeEach(() => {
    jest.resetAllMocks();
    names.mockResolvedValue([{ id: "org-1", name: "JXP" }]);
    load.mockResolvedValue(data());
  });

  it("lists approvals first, then failures, then policy, with where each one is", async () => {
    renderPage();

    const headings = await screen.findAllByText(/\(1\)$/);
    expect(headings.map((heading) => heading.textContent)).toEqual([
      "Waiting for approval (1)",
      "Failed runs (1)",
      "Policy violations (1)",
    ]);
    expect(screen.getByText("JXP / payments")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "ledger-db" })).toHaveAttribute(
      "href",
      "/organizations/org-1/workspaces/ledger-db-id"
    );
  });

  it("links a failure to its runs", async () => {
    renderPage();

    const row = (await screen.findByRole("link", { name: "gateway" })).closest("li")!;

    expect(within(row).getByRole("link", { name: "Open run" })).toHaveAttribute(
      "href",
      "/organizations/org-1/workspaces/gateway-id/runs"
    );
  });

  it("says how many more there are than it lists", async () => {
    load.mockResolvedValue(data({ counts: { waiting: 1, failed: 25, policy: 1 } }));

    renderPage();

    expect(await screen.findByText(/Showing the 1 most recent of 25/)).toBeInTheDocument();
  });

  it("offers Approve only for runs that are waiting", async () => {
    renderPage();
    await screen.findByRole("link", { name: "ledger-db" });

    expect(screen.getAllByRole("button", { name: "Approve" })).toHaveLength(1);
  });

  it("approves after confirming, and refreshes", async () => {
    waiting.mockResolvedValue([{ jobId: "7", workspaceId: "ledger-db-id" }]);
    approve.mockResolvedValue({ succeeded: ["7"], failed: [] });
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "Approve" }));

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getAllByText("Approve the run waiting on ledger-db?").length).toBeGreaterThan(0);
    fireEvent.click(within(dialog).getByRole("button", { name: "Approve" }));

    await waitFor(() => expect(approve).toHaveBeenCalledWith("org-1", ["7"]));
    expect(waiting).toHaveBeenCalledWith("org-1", ["ledger-db-id"]);
    await waitFor(() => expect(load.mock.calls.length).toBeGreaterThan(1));
  });

  it("says so when the run was already handled, and approves nothing", async () => {
    waiting.mockResolvedValue([]);
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "Approve" }));

    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Approve" }));

    expect(await screen.findByText("That run is no longer waiting for approval.")).toBeInTheDocument();
    expect(approve).not.toHaveBeenCalled();
  });

  it("reports when nothing needs anyone", async () => {
    load.mockResolvedValue(data({ items: [], counts: { waiting: 0, failed: 0, policy: 0 } }));
    names.mockResolvedValue([
      { id: "a", name: "JXP" },
      { id: "b", name: "Admin" },
    ]);

    renderPage();

    expect(await screen.findByText("Nothing needs you across 2 organizations.")).toBeInTheDocument();
  });

  it("never calls it quiet when an organization could not be read", async () => {
    load.mockResolvedValue(
      data({ items: [], counts: { waiting: 0, failed: 0, policy: 0 }, failedOrganizations: ["Cybersecurity"] })
    );

    renderPage();

    expect(await screen.findByText(/Could not read Cybersecurity/)).toBeInTheDocument();
    expect(screen.queryByText(/Nothing needs you/)).not.toBeInTheDocument();
  });

  it("offers a retry when the whole load fails", async () => {
    names.mockRejectedValue(new Error("down"));

    renderPage();

    expect(await screen.findByText("Could not load what needs attention.")).toBeInTheDocument();
    names.mockResolvedValue([{ id: "org-1", name: "JXP" }]);
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByRole("link", { name: "gateway" })).toBeInTheDocument();
  });
});
