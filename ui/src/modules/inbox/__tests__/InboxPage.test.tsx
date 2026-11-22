import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { Modal } from "antd";
import { axe, toHaveNoViolations } from "jest-axe";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router-dom";
import { ORGANIZATION_ARCHIVE } from "@/config/actionTypes";
import projectService from "@/modules/projects/projectService";
import { usePolling } from "@/hooks";
import { listOrganizationNames } from "@/modules/navigation/navigationService";
import { approveRuns, findWaitingRuns } from "@/modules/projects/bulkWorkspaceActions";
import InboxPage from "../InboxPage";
import { loadInboxCounts, loadInboxPage, loadOrganizationCounts } from "../inboxService";
import { InboxCounts, InboxItem, InboxKind } from "../inboxModel";

expect.extend(toHaveNoViolations);

jest.mock("@/hooks", () => ({ usePolling: jest.fn() }));
jest.mock("@/components/layout/PageWrapper/PageWrapper", () => ({
  __esModule: true,
  default: ({ children }: { children: ReactNode }) => <>{children}</>,
}));
jest.mock("@/modules/navigation/navigationService");
jest.mock("@/modules/projects/bulkWorkspaceActions");
jest.mock("../inboxService");
jest.mock("@/modules/projects/projectService", () => ({
  __esModule: true,
  default: { getCurrentGroups: jest.fn() },
}));

const names = listOrganizationNames as jest.Mock;
const counts = loadInboxCounts as jest.Mock;
const pages = loadInboxPage as jest.Mock;
const summaries = loadOrganizationCounts as jest.Mock;
const waiting = findWaitingRuns as jest.Mock;
const approve = approveRuns as jest.Mock;
const groups = projectService.getCurrentGroups as jest.Mock;

const ORG = { id: "org-1", name: "JXP" };

const item = (kind: InboxKind, name: string, extra: Partial<InboxItem> = {}): InboxItem => ({
  key: `${kind}:${name}`,
  kind,
  orgId: "org-1",
  orgName: "JXP",
  workspaceId: `${name}-id`,
  name,
  at: new Date(Date.now() - 3 * 60 * 60 * 1000).toISOString(),
  ...extra,
});

const some = (overrides: Partial<InboxCounts> = {}): InboxCounts => ({
  waiting: 1,
  failed: 1,
  drifted: 0,
  policy: 1,
  ...overrides,
});

const rowsOf: Record<InboxKind, InboxItem[]> = {
  waiting: [item("waiting", "ledger-db", { projectName: "payments", ownerTeam: "payments-devs" })],
  failed: [item("failed", "gateway")],
  drifted: [],
  policy: [item("policy", "vpc")],
};

const renderPage = (path = "/inbox") =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <InboxPage />
    </MemoryRouter>
  );

describe("InboxPage", () => {
  // Confirmation dialogs are rendered outside the tree, so one test must not leave its dialog for the next.
  afterEach(() => Modal.destroyAll());

  beforeEach(() => {
    jest.resetAllMocks();
    sessionStorage.clear();
    names.mockResolvedValue([ORG]);
    counts.mockResolvedValue(some());
    pages.mockImplementation(async (_org, query) => ({
      items: rowsOf[query.kind as InboxKind],
      total: rowsOf[query.kind as InboxKind].length,
    }));
  });

  describe("one organization", () => {
    it("opens the first kind that has something, and puts the number of each kind on the rail", async () => {
      counts.mockResolvedValue(some({ waiting: 2, failed: 10, drifted: 1, policy: 0 }));
      renderPage();

      await screen.findByRole("link", { name: "ledger-db" });
      const rail = screen.getByRole("navigation", { name: "What needs attention" });
      expect(within(rail).getByRole("button", { name: "Waiting for approval 2" })).toHaveAttribute(
        "aria-current",
        "page"
      );
      expect(within(rail).getByRole("button", { name: "Failed runs 10" })).toBeInTheDocument();
      expect(within(rail).getByRole("button", { name: "Policy violations 0" })).toBeInTheDocument();
      expect(pages).toHaveBeenCalledWith(ORG, expect.objectContaining({ kind: "waiting", page: 1, pageSize: 25 }));
      expect(counts).toHaveBeenCalledWith(ORG, null, "");
    });

    it("opens on the failures when nothing waits for approval", async () => {
      counts.mockResolvedValue(some({ waiting: 0, failed: 3, policy: 0 }));
      renderPage();

      await screen.findByRole("link", { name: "gateway" });

      expect(pages).toHaveBeenCalledWith(ORG, expect.objectContaining({ kind: "failed" }));
      expect(pages).not.toHaveBeenCalledWith(ORG, expect.objectContaining({ kind: "waiting" }));
    });

    it("opens the organization the person was last in when they have several", async () => {
      names.mockResolvedValue([ORG, { id: "org-2", name: "Acme" }]);
      sessionStorage.setItem(ORGANIZATION_ARCHIVE, "org-2");
      renderPage();

      await screen.findByRole("link", { name: "ledger-db" });

      expect(counts).toHaveBeenCalledWith({ id: "org-2", name: "Acme" }, null, "");
    });

    it("says what happened to each one and for how long, and where it is", async () => {
      renderPage();

      expect(await screen.findByText(/^Waiting 3h$/)).toBeInTheDocument();
      expect(screen.getByText("payments · payments-devs")).toBeInTheDocument();

      fireEvent.click(screen.getByRole("button", { name: /Failed runs/ }));
      expect(await screen.findByText("Failed 3h ago")).toBeInTheDocument();

      fireEvent.click(screen.getByRole("button", { name: /Policy violations/ }));
      expect(await screen.findByText("Non-compliant")).toBeInTheDocument();
    });

    it("reads another kind from the server when it is picked on the rail, from the first page", async () => {
      counts.mockResolvedValue(some({ failed: 60 }));
      renderPage("/inbox?org=org-1&kind=waiting&page=2");
      await screen.findByRole("link", { name: "ledger-db" });

      fireEvent.click(screen.getByRole("button", { name: /Failed runs/ }));

      await waitFor(() =>
        expect(pages).toHaveBeenCalledWith(ORG, expect.objectContaining({ kind: "failed", page: 1, pageSize: 25 }))
      );
    });

    it("reads a page of a kind from the server, and the next page when asked", async () => {
      counts.mockResolvedValue(some({ failed: 60 }));
      pages.mockResolvedValue({ items: [item("failed", "gateway")], total: 60 });
      renderPage("/inbox?org=org-1&kind=failed&page=2");

      await screen.findByRole("link", { name: "gateway" });
      expect(pages).toHaveBeenCalledWith(ORG, expect.objectContaining({ kind: "failed", page: 2, pageSize: 25 }));
      expect(screen.getByText("26-50 of 60")).toBeInTheDocument();

      fireEvent.click(screen.getByRole("button", { name: "Next Page" }));

      await waitFor(() =>
        expect(pages).toHaveBeenCalledWith(ORG, expect.objectContaining({ kind: "failed", page: 3 }))
      );
    });

    it("searches by name on the server, after a moment, and starts again from the first page", async () => {
      renderPage("/inbox?org=org-1&kind=failed&page=2");
      await screen.findByRole("textbox", { name: "Search workspaces by name" });

      fireEvent.change(screen.getByRole("textbox", { name: "Search workspaces by name" }), {
        target: { value: "gate" },
      });

      await waitFor(() => expect(counts).toHaveBeenCalledWith(ORG, null, "gate"), { timeout: 2000 });
      expect(pages).toHaveBeenCalledWith(ORG, expect.objectContaining({ kind: "failed", page: 1, search: "gate" }));
    });

    it("says what a search found nothing for, and not that nothing needs anyone", async () => {
      counts.mockResolvedValue(some({ waiting: 0, failed: 0, policy: 0 }));
      renderPage("/inbox?org=org-1&q=zzz");

      expect(await screen.findByText('Nothing matches "zzz" in JXP.')).toBeInTheDocument();
    });

    it("asks for everything by default, and for the person's own teams when they choose My teams", async () => {
      groups.mockResolvedValue(["payments-devs"]);
      renderPage();
      await screen.findByRole("link", { name: "ledger-db" });

      fireEvent.click(screen.getByText("My teams"));

      await waitFor(() => expect(counts).toHaveBeenCalledWith(ORG, { ownerTeams: ["payments-devs"] }, ""));
    });

    it("opens on My teams from the address, and says what an empty one means", async () => {
      groups.mockResolvedValue(["payments-devs"]);
      counts.mockResolvedValue(some({ waiting: 0, failed: 0, policy: 0 }));
      renderPage("/inbox?scope=mine");

      expect(await screen.findByText(/Nothing needs your teams/)).toBeInTheDocument();
    });

    it("reports when nothing needs anyone", async () => {
      counts.mockResolvedValue(some({ waiting: 0, failed: 0, policy: 0 }));
      renderPage();

      expect(await screen.findByText("Nothing needs you in JXP.")).toBeInTheDocument();
    });

    it("links a failure to its runs, drift to its plan and a policy violation to the workspace", async () => {
      counts.mockResolvedValue(some({ waiting: 0, failed: 1, drifted: 1, policy: 1 }));
      pages.mockImplementation(async (_org, query) => ({
        items:
          query.kind === "drifted" ? [item("drifted", "network", { runId: "40" })] : rowsOf[query.kind as InboxKind],
        total: 1,
      }));
      renderPage();
      await screen.findByRole("link", { name: "gateway" });
      expect(screen.getByRole("link", { name: "Open run" })).toHaveAttribute(
        "href",
        "/organizations/org-1/workspaces/gateway-id/runs"
      );

      fireEvent.click(screen.getByRole("button", { name: /Drifted/ }));
      expect(await screen.findByRole("link", { name: "View plan" })).toHaveAttribute(
        "href",
        "/organizations/org-1/workspaces/network-id/runs/40"
      );
    });

    it("offers Approve only for runs that are waiting", async () => {
      renderPage();
      await screen.findByRole("link", { name: "ledger-db" });
      expect(screen.getAllByRole("button", { name: "Approve" })).toHaveLength(1);

      fireEvent.click(screen.getByRole("button", { name: /Failed runs/ }));
      await screen.findByRole("link", { name: "gateway" });

      expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
    });

    it("keeps the filters in the address, so going back from a workspace lands on the same list", async () => {
      renderPage("/inbox?org=org-1&kind=failed&q=gate");
      await screen.findByRole("link", { name: "gateway" });

      fireEvent.click(screen.getByRole("link", { name: "gateway" }));

      expect(JSON.stringify(sessionStorage)).toContain("/inbox?org=org-1&kind=failed&q=gate");
    });

    it("offers a retry when the load fails", async () => {
      counts.mockRejectedValueOnce(new Error("down"));
      renderPage();

      fireEvent.click(await screen.findByRole("button", { name: "Retry" }));

      expect(await screen.findByRole("link", { name: "ledger-db" })).toBeInTheDocument();
    });

    it("has no accessibility violations", async () => {
      const { container } = renderPage();
      await screen.findByRole("link", { name: "ledger-db" });

      expect(await axe(container)).toHaveNoViolations();
    });
  });

  describe("approving", () => {
    it("approves one run after confirming, and refreshes", async () => {
      waiting.mockResolvedValue([{ jobId: "7", workspaceId: "ledger-db-id" }]);
      approve.mockResolvedValue({ succeeded: ["7"], failed: [] });
      renderPage();
      fireEvent.click(await screen.findByRole("button", { name: "Approve" }));

      const dialog = await screen.findByRole("dialog");
      expect(within(dialog).getAllByText("Approve the run waiting on ledger-db?").length).toBeGreaterThan(0);
      fireEvent.click(within(dialog).getByRole("button", { name: "Approve" }));

      await waitFor(() => expect(approve).toHaveBeenCalledWith("org-1", ["7"]));
      expect(waiting).toHaveBeenCalledWith("org-1", ["ledger-db-id"]);
      await waitFor(() => expect(counts.mock.calls.length).toBeGreaterThan(1));
    });

    it("says so when the run was already handled, and approves nothing", async () => {
      waiting.mockResolvedValue([]);
      renderPage();
      fireEvent.click(await screen.findByRole("button", { name: "Approve" }));

      fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Approve" }));

      expect(await screen.findByText("That run is no longer waiting for approval.")).toBeInTheDocument();
      expect(approve).not.toHaveBeenCalled();
    });

    describe("several at once", () => {
      beforeEach(() => {
        counts.mockResolvedValue(some({ waiting: 3, failed: 0, policy: 0 }));
        pages.mockResolvedValue({
          items: [item("waiting", "a-db"), item("waiting", "b-db"), item("waiting", "c-db")],
          total: 3,
        });
      });

      it("approves the ones picked, in one confirmation", async () => {
        waiting.mockResolvedValue([
          { jobId: "1", workspaceId: "a-db-id" },
          { jobId: "2", workspaceId: "c-db-id" },
        ]);
        approve.mockResolvedValue({ succeeded: ["1", "2"], failed: [] });
        renderPage("/inbox?org=org-1&kind=waiting");
        fireEvent.click(await screen.findByRole("checkbox", { name: "Select a-db" }));
        fireEvent.click(screen.getByRole("checkbox", { name: "Select c-db" }));

        expect(screen.getByText("2 selected")).toBeInTheDocument();
        fireEvent.click(screen.getByRole("button", { name: "Approve 2" }));
        fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Approve 2" }));

        await waitFor(() => expect(approve).toHaveBeenCalledWith("org-1", ["1", "2"]));
        expect(waiting).toHaveBeenCalledWith("org-1", ["a-db-id", "c-db-id"]);
        expect(await screen.findByText("Approved 2")).toBeInTheDocument();
      });

      it("picks everything on the page in one go, and clears the choice", async () => {
        renderPage("/inbox?org=org-1&kind=waiting");
        fireEvent.click(await screen.findByRole("checkbox", { name: "Select all on this page" }));

        expect(screen.getByText("3 selected")).toBeInTheDocument();
        fireEvent.click(screen.getByRole("button", { name: "Clear" }));
        expect(screen.queryByText("3 selected")).not.toBeInTheDocument();
      });

      it("counts the runs that were no longer waiting instead of calling them failures", async () => {
        waiting.mockResolvedValue([{ jobId: "1", workspaceId: "a-db-id" }]);
        approve.mockResolvedValue({ succeeded: ["1"], failed: [] });
        renderPage("/inbox?org=org-1&kind=waiting");
        fireEvent.click(await screen.findByRole("checkbox", { name: "Select a-db" }));
        fireEvent.click(screen.getByRole("checkbox", { name: "Select b-db" }));
        fireEvent.click(screen.getByRole("button", { name: "Approve 2" }));

        fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Approve 2" }));

        expect(await screen.findByText("Approved 1, 1 no longer waiting")).toBeInTheDocument();
      });
    });
  });

  describe("many organizations", () => {
    const two = [ORG, { id: "org-2", name: "Acme" }];
    const row = (org: { id: string; name: string }, over: Partial<InboxCounts>) => ({
      org,
      counts: some({ waiting: 0, failed: 0, drifted: 0, policy: 0, ...over }),
    });

    beforeEach(() => {
      names.mockResolvedValue(two);
      summaries.mockResolvedValue({
        rows: [row(ORG, { failed: 2 }), row(two[1], { waiting: 3, failed: 7 })],
        failedOrganizations: [],
      });
    });

    it("summarises one line per organization, the busiest first, and not their rows", async () => {
      renderPage();

      const buttons = await screen.findAllByRole("button", { name: /^Open / });
      expect(buttons.map((button) => button.getAttribute("aria-label"))).toEqual(["Open Acme", "Open JXP"]);
      expect(screen.getByText("10")).toBeInTheDocument();
      expect(pages).not.toHaveBeenCalled();
    });

    it("refreshes the summary on every poll while the list of organizations is short", async () => {
      renderPage();
      await screen.findAllByRole("button", { name: /^Open / });
      summaries.mockClear();

      const poll = (usePolling as jest.Mock).mock.calls.at(-1)[0] as () => Promise<void>;
      await act(async () => poll());

      expect(summaries).toHaveBeenCalledTimes(1);
    });

    it("does not sweep more than 20 organizations on every poll", async () => {
      const many = Array.from({ length: 21 }, (_, index) => ({ id: `org-${index}`, name: `Org ${index}` }));
      names.mockResolvedValue(many);
      summaries.mockResolvedValue({ rows: many.map((org) => row(org, { failed: 1 })), failedOrganizations: [] });
      renderPage();
      await screen.findAllByRole("button", { name: /^Open / });
      summaries.mockClear();

      const poll = (usePolling as jest.Mock).mock.calls.at(-1)[0] as () => Promise<void>;
      await act(async () => poll());

      expect(summaries).not.toHaveBeenCalled();
    });

    it("opens one kind of an organization from its number", async () => {
      renderPage();
      fireEvent.click(await screen.findByRole("button", { name: "7 failed in Acme" }));

      await waitFor(() =>
        expect(pages).toHaveBeenCalledWith({ id: "org-2", name: "Acme" }, expect.objectContaining({ kind: "failed" }))
      );
    });

    it("says how many organizations have nothing", async () => {
      summaries.mockResolvedValue({ rows: [row(ORG, {}), row(two[1], { failed: 1 })], failedOrganizations: [] });
      renderPage();

      expect(await screen.findByText("1 other organization has nothing that needs you.")).toBeInTheDocument();
    });

    it("opens the rows of an organization from its line", async () => {
      renderPage();
      fireEvent.click(await screen.findByRole("button", { name: "Open Acme" }));

      await waitFor(() => expect(counts).toHaveBeenCalledWith({ id: "org-2", name: "Acme" }, null, ""));
    });

    it("never calls it quiet when an organization could not be read", async () => {
      summaries.mockResolvedValue({ rows: [row(ORG, {})], failedOrganizations: ["Acme"] });
      renderPage();

      expect(await screen.findByText(/Could not read Acme/)).toBeInTheDocument();
      expect(screen.queryByText(/Nothing needs you across/)).not.toBeInTheDocument();
    });

    it("says nothing needs anyone across all of them", async () => {
      summaries.mockResolvedValue({ rows: [row(ORG, {}), row(two[1], {})], failedOrganizations: [] });
      renderPage();

      expect(await screen.findByText("Nothing needs you across 2 organizations.")).toBeInTheDocument();
    });
  });
});
