import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import projectService from "../projectService";
import ProjectOverview from "../ProjectOverview";

jest.mock("../projectService", () => ({ __esModule: true, default: { listAttentionWorkspaces: jest.fn() } }));
const attentionList = projectService.listAttentionWorkspaces as jest.Mock;

const renderOverview = (props: any) =>
  render(
    <MemoryRouter>
      <ProjectOverview orgid="org" {...props} />
    </MemoryRouter>
  );
import { getLandingTab, resolveLandingTab, setLandingTab } from "../projectLandingTab";

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

  it("ignores values it does not know", () => {
    expect(resolveLandingTab("nonsense", "also-nonsense")).toBe("overview");
  });
});

describe("ProjectOverview", () => {
  beforeEach(() => {
    localStorage.clear();
    attentionList.mockResolvedValue({ isError: false, data: [] });
  });

  it("shows the counts, the attention alert and a link to the workspaces", () => {
    const onOpenTab = jest.fn();
    renderOverview({ summary, loading: false, onOpenTab });

    expect(screen.getByText("1 workspace needs attention")).toBeInTheDocument();
    expect(screen.getByText("Running or queued").closest(".ant-statistic")).toHaveTextContent("2");
    expect(screen.getByText("Locked").closest(".ant-statistic")).toHaveTextContent("2");
    fireEvent.click(screen.getByText("View workspaces"));
    expect(onOpenTab).toHaveBeenCalledWith("workspaces");
  });

  it("does not raise an alert for a healthy project", () => {
    renderOverview({ summary: { ...summary, attentionCount: 0 }, loading: false, onOpenTab: jest.fn() });

    expect(screen.queryByText(/need.* attention/)).not.toBeInTheDocument();
  });

  it("explains when the summary could not be loaded", () => {
    renderOverview({ summary: null, loading: false, onOpenTab: jest.fn() });

    expect(screen.getByText("Project summary is not available")).toBeInTheDocument();
  });
});

describe("ProjectOverview attention list", () => {
  beforeEach(() => {
    localStorage.clear();
    attentionList.mockReset();
  });

  it("lists the workspaces that need attention", async () => {
    attentionList.mockResolvedValue({
      isError: false,
      data: [
        { id: "w1", name: "network", lastJobStatus: "failed" },
        { id: "w2", name: "database", lastJobStatus: "completed", policyComplianceStatus: "NON_COMPLIANT" },
      ],
    });

    renderOverview({ summary, loading: false, onOpenTab: jest.fn() });

    expect(await screen.findByText("network")).toHaveAttribute("href", "/organizations/org/workspaces/w1");
    expect(screen.getByText("Non-compliant")).toBeInTheDocument();
  });

  it("does not ask for the list when nothing needs attention", () => {
    renderOverview({ summary: { ...summary, attentionCount: 0 }, loading: false, onOpenTab: jest.fn() });

    expect(attentionList).not.toHaveBeenCalled();
  });
});
