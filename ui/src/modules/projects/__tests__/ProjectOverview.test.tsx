import { fireEvent, render, screen } from "@testing-library/react";
import ProjectOverview from "../ProjectOverview";
import { getLandingTab, setLandingTab } from "../projectLandingTab";

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

describe("ProjectOverview", () => {
  beforeEach(() => localStorage.clear());

  it("shows the counts, the attention alert and a link to the workspaces", () => {
    const onOpenTab = jest.fn();
    render(<ProjectOverview summary={summary} loading={false} onOpenTab={onOpenTab} />);

    expect(screen.getByText("1 workspace needs attention")).toBeInTheDocument();
    expect(screen.getByText("Running or queued").closest(".ant-statistic")).toHaveTextContent("2");
    expect(screen.getByText("Locked").closest(".ant-statistic")).toHaveTextContent("2");
    fireEvent.click(screen.getByText("View workspaces"));
    expect(onOpenTab).toHaveBeenCalledWith("workspaces");
  });

  it("does not raise an alert for a healthy project", () => {
    render(<ProjectOverview summary={{ ...summary, attentionCount: 0 }} loading={false} onOpenTab={jest.fn()} />);

    expect(screen.queryByText(/need.* attention/)).not.toBeInTheDocument();
  });

  it("explains when the summary could not be loaded", () => {
    render(<ProjectOverview summary={null} loading={false} onOpenTab={jest.fn()} />);

    expect(screen.getByText("Project summary is not available")).toBeInTheDocument();
  });
});
