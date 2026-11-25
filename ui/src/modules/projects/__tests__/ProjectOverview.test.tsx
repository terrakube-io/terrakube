import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import projectService from "../projectService";
import ProjectOverview from "../ProjectOverview";

jest.mock("../projectService", () => ({
  __esModule: true,
  default: { listAttentionWorkspaces: jest.fn(), listRecentWorkspaces: jest.fn(), getDelivery: jest.fn() },
}));
const recentList = projectService.listRecentWorkspaces as jest.Mock;
const deliveryOf = projectService.getDelivery as jest.Mock;
const attentionList = projectService.listAttentionWorkspaces as jest.Mock;

const renderOverview = (props: any) =>
  render(
    <MemoryRouter>
      <ProjectOverview orgid="org" {...props} />
    </MemoryRouter>
  );
import { getLandingTab, resolveLandingTab, setLandingTab } from "../projectLandingTab";
import { sectionForTab } from "../projectNav";

const summary = {
  id: "p1",
  name: "Payments",
  workspaceCount: 5,
  completedCount: 3,
  failedCount: 1,
  runningCount: 1,
  queuedCount: 1,
  waitingApprovalCount: 0,
  neverRunCount: 0,
  nonCompliantCount: 0,
  hardViolationCount: 0,
  softViolationCount: 0,
  compliantCount: 0,
  exemptedCount: 0,
  queuedJobCount: 0,
  lockedCount: 2,
  attentionCount: 1,
  teamCount: 2,
};

describe("project landing tab", () => {
  beforeEach(() => localStorage.clear());

  it("defaults to the overview", () => {
    expect(getLandingTab()).toBe("overview");
  });

  it("remembers a valid choice and ignores an unknown stored value", () => {
    setLandingTab("workspaces");
    expect(getLandingTab()).toBe("workspaces");
    localStorage.setItem("terrakube-project-landing-tab", "bogus");
    expect(getLandingTab()).toBe("overview");
  });
});

describe("resolveLandingTab", () => {
  beforeEach(() => localStorage.clear());

  it("falls back to the overview when nothing is set", () => {
    expect(resolveLandingTab(undefined, undefined)).toBe("overview");
  });

  it("prefers the project over the organization", () => {
    expect(resolveLandingTab("teams", "workspaces")).toBe("teams");
    expect(resolveLandingTab(undefined, "workspaces")).toBe("workspaces");
  });

  it("lets the person's own choice win, until they forget it", () => {
    setLandingTab("general");
    expect(resolveLandingTab("teams", "workspaces")).toBe("general");
    setLandingTab(null);
    expect(resolveLandingTab("teams", "workspaces")).toBe("teams");
  });

  it("opens a project on its Variables page when that is the landing tab, not on Settings", () => {
    expect(resolveLandingTab("variables", null)).toBe("variables");
    expect(sectionForTab("variables")).toBe("variables");
    setLandingTab("variables");
    expect(getLandingTab()).toBe("variables");
    expect(sectionForTab("general")).toBe("settings");
  });

  it("ignores values it does not know", () => {
    expect(resolveLandingTab("nonsense", "also-nonsense")).toBe("overview");
  });
});

describe("ProjectOverview", () => {
  beforeEach(() => {
    localStorage.clear();
    attentionList.mockReset();
    recentList.mockReset();
    attentionList.mockResolvedValue({ isError: false, data: [] });
    recentList.mockResolvedValue({ isError: false, data: [] });
    deliveryOf.mockReset();
    deliveryOf.mockResolvedValue({ isError: true });
  });

  it("shows the delivery numbers of the project", async () => {
    deliveryOf.mockResolvedValue({
      isError: false,
      data: { runs: 12, succeededPercent: 90, medianApproveMs: 600_000, medianRunMs: 1_800_000, partial: false },
    });
    renderOverview({ summary, onOpenTab: jest.fn() });

    expect(await screen.findByText("90%")).toBeInTheDocument();
    expect(deliveryOf).toHaveBeenCalledWith("org", "p1", 30);
  });

  it("says the workspace count, the queue and the last run in one line under the health bar", () => {
    renderOverview({ summary: { ...summary, lockedCount: 2 }, loading: false, onOpenTab: jest.fn() });

    expect(screen.getByRole("link", { name: "5 workspaces" })).toBeInTheDocument();
    expect(screen.getByText(/2 locked/)).toBeInTheDocument();
    expect(screen.queryByText(/queued now/)).not.toBeInTheDocument();
  });

  it("adds the longest wait to the queue, which links to the pending workspaces", () => {
    const queuedSince = new Date(Date.now() - 6 * 60 * 1000).toISOString();

    renderOverview({
      summary: { ...summary, queuedJobCount: 2, queuedSince },
      loading: false,
      onOpenTab: jest.fn(),
    });

    expect(screen.getByRole("link", { name: "queued now (longest wait 6m)" })).toHaveAttribute(
      "href",
      "/organizations/org/projects/p1/workspaces?status=pending"
    );
  });

  it("leads with the health of the project, then what needs attention", () => {
    renderOverview({ summary, loading: false, onOpenTab: jest.fn() });

    const health = screen.getByLabelText("Project health");
    const attention = screen.getByLabelText("Needs attention");
    expect(health.compareDocumentPosition(attention) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("offers the failed, waiting and policy counts as cards that lead to the workspaces behind them", () => {
    renderOverview({
      summary: { ...summary, failedCount: 2, waitingApprovalCount: 1, nonCompliantCount: 3 },
      loading: false,
      onOpenTab: jest.fn(),
    });

    expect(screen.getByText("Failed runs").previousSibling).toHaveTextContent("2");
    expect(screen.getByRole("link", { name: /Review/ })).toHaveAttribute(
      "href",
      "/organizations/org/projects/p1/workspaces?status=failed"
    );
    expect(screen.getByRole("link", { name: /Approve/ })).toHaveAttribute(
      "href",
      "/organizations/org/projects/p1/workspaces?status=waitingApproval"
    );
    expect(screen.getByRole("link", { name: /^View/ })).toHaveAttribute(
      "href",
      "/organizations/org/projects/p1/workspaces?policy=NON_COMPLIANT"
    );
  });

  it("links the teams with access from the details", () => {
    renderOverview({ summary: { ...summary, teamCount: 3 }, loading: false, onOpenTab: jest.fn() });

    expect(screen.getByRole("link", { name: "3 with access" })).toHaveAttribute(
      "href",
      "/organizations/org/projects/p1/teams"
    );
  });

  it("lists the health counts under the bar", () => {
    renderOverview({ summary, loading: false, onOpenTab: jest.fn() });

    expect(screen.getByText("3 completed")).toBeInTheDocument();
    expect(screen.getByText("1 failed")).toBeInTheDocument();
  });

  it("says the attention list could not be loaded instead of saying nothing needs attention", async () => {
    attentionList.mockResolvedValue({ isError: true, data: [] });
    renderOverview({ summary: { ...summary, attentionCount: 2 }, loading: false, onOpenTab: jest.fn() });

    expect(await screen.findByText("2 need attention, but the list could not be loaded.")).toBeInTheDocument();
    expect(screen.queryByText("Nothing needs attention.")).not.toBeInTheDocument();
  });

  it("does not say nothing needs attention while the list is still loading", () => {
    attentionList.mockReturnValue(new Promise(() => undefined));
    renderOverview({ summary: { ...summary, attentionCount: 2 }, loading: false, onOpenTab: jest.fn() });

    expect(screen.queryByText("Nothing needs attention.")).not.toBeInTheDocument();
  });

  it("lists the workspaces that need attention and links to the full list", async () => {
    const onOpenTab = jest.fn();
    attentionList.mockResolvedValue({
      isError: false,
      data: [
        { id: "w1", name: "network", lastJobStatus: "failed", lastJobDate: new Date().toISOString() },
        { id: "w2", name: "database", lastJobStatus: "completed", policyComplianceStatus: "NON_COMPLIANT" },
      ],
    });

    renderOverview({ summary: { ...summary, attentionCount: 2 }, loading: false, onOpenTab });

    expect(await screen.findByRole("link", { name: "network" })).toHaveAttribute(
      "href",
      "/organizations/org/workspaces/w1"
    );
    expect(screen.getByText(/Non-compliant/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /View all 2 in Workspaces/ }));
    expect(onOpenTab).toHaveBeenCalledWith("workspaces");
  });

  it("says nothing needs attention, and does not ask for the list", () => {
    renderOverview({ summary: { ...summary, attentionCount: 0 }, loading: false, onOpenTab: jest.fn() });

    expect(screen.getByText("Nothing needs attention.")).toBeInTheDocument();
    expect(attentionList).not.toHaveBeenCalled();
  });

  it("shows recent activity with friendly status names", async () => {
    recentList.mockResolvedValue({
      isError: false,
      data: [
        { id: "w3", name: "gateway-us", lastJobStatus: "completed", lastJobDate: new Date().toISOString() },
        { id: "w4", name: "fraud-rules", lastJobStatus: "waitingApproval" },
      ],
    });

    renderOverview({ summary, loading: false, onOpenTab: jest.fn() });

    expect(await screen.findByRole("link", { name: "gateway-us" })).toBeInTheDocument();
    const recent = within(screen.getByLabelText("Recent activity"));
    expect(recent.getByText(/Applied/)).toBeInTheDocument();
    expect(recent.getByText(/Waiting approval/)).toBeInTheDocument();
  });

  it("never draws counts that could not be loaded, and offers a retry", () => {
    const onRetry = jest.fn();
    renderOverview({ summary: null, loading: false, failed: true, onRetry, onOpenTab: jest.fn() });

    expect(screen.getByText("Counts unavailable.")).toBeInTheDocument();
    expect(screen.queryByText("workspaces")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(onRetry).toHaveBeenCalled();
  });

  it("explains when there is no summary", () => {
    renderOverview({ summary: null, loading: false, onOpenTab: jest.fn() });

    expect(screen.getByText("Project summary is not available")).toBeInTheDocument();
  });

  describe("click-through", () => {
    it("links each health status to the workspaces with that status", () => {
      renderOverview({ summary, loading: false, onOpenTab: jest.fn() });

      // Completed is completed plus noChanges, which the list cannot filter on together, so it is not a link.
      expect(screen.getByText("3 completed")).toBeInTheDocument();
      expect(screen.queryByRole("link", { name: "3 completed" })).not.toBeInTheDocument();
      expect(screen.getByRole("link", { name: "1 failed" })).toHaveAttribute(
        "href",
        "/organizations/org/projects/p1/workspaces?status=failed"
      );
    });

    it("sends a status chip to the runs of that workspace", async () => {
      attentionList.mockResolvedValue({
        isError: false,
        data: [{ id: "w1", name: "network", lastJobStatus: "failed" }],
      });

      renderOverview({ summary, loading: false, onOpenTab: jest.fn() });

      expect(await screen.findByRole("link", { name: /Failed, open the runs of network/ })).toHaveAttribute(
        "href",
        "/organizations/org/workspaces/w1/runs"
      );
    });
  });
});

describe("an empty project", () => {
  it("offers to create or move in a workspace", () => {
    recentList.mockResolvedValue({ isError: false, data: [] });
    const onOpenTab = jest.fn();

    renderOverview({
      summary: { ...summary, workspaceCount: 0, attentionCount: 0, completedCount: 0 },
      onOpenTab,
      canCreateWorkspace: true,
      canMoveWorkspaces: true,
    });

    expect(screen.getByText("No workspaces in this project yet.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "New workspace" })).toBeEnabled();
    fireEvent.click(screen.getByRole("button", { name: "Move existing" }));
    expect(onOpenTab).toHaveBeenCalledWith("workspaces");
  });

  it("disables both actions, with the reason, for someone who may not use them", () => {
    recentList.mockResolvedValue({ isError: false, data: [] });
    const onOpenTab = jest.fn();

    renderOverview({
      summary: { ...summary, workspaceCount: 0, attentionCount: 0, completedCount: 0 },
      onOpenTab,
    });

    expect(screen.getByRole("button", { name: "New workspace" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Move existing" })).toBeDisabled();
    expect(
      screen.getByRole("group", { name: /Creating a workspace needs the Manage Workspaces permission/ })
    ).toBeInTheDocument();
    expect(onOpenTab).not.toHaveBeenCalled();
  });

  it("shows no call to action when the project has workspaces", () => {
    recentList.mockResolvedValue({ isError: false, data: [] });
    attentionList.mockResolvedValue({ isError: false, data: [] });

    renderOverview({ summary, onOpenTab: jest.fn() });

    expect(screen.queryByText("No workspaces in this project yet.")).not.toBeInTheDocument();
  });
});
