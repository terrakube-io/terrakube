import { Tooltip } from "antd";
import { ProjectSummaryModel } from "@/domain/types";

type Segment = { key: string; label: string; count: number; className: string };

function healthSegments(summary: ProjectSummaryModel): Segment[] {
  return [
    { key: "failed", label: "Failed", count: summary.failedCount, className: "health-failed" },
    { key: "waiting", label: "Waiting approval", count: summary.waitingApprovalCount, className: "health-waiting" },
    {
      key: "running",
      label: "Running",
      count: summary.runningCount + summary.queuedCount,
      className: "health-running",
    },
    { key: "completed", label: "Applied", count: summary.completedCount, className: "health-completed" },
    { key: "never", label: "Never run", count: summary.neverRunCount, className: "health-never" },
  ].filter((segment) => segment.count > 0);
}

export function ProjectHealthBar({ summary }: { summary: ProjectSummaryModel }) {
  if (summary.workspaceCount === 0) {
    return <span className="project-health-empty">No workspaces</span>;
  }
  const description = healthSegments(summary)
    .map((segment) => `${segment.count} ${segment.label.toLowerCase()}`)
    .join(", ");
  return (
    <Tooltip title={description}>
      <div className="project-health-bar" role="img" aria-label={description}>
        {healthSegments(summary).map((segment) => (
          <span
            key={segment.key}
            className={`project-health-segment ${segment.className}`}
            style={{ flexGrow: segment.count }}
          />
        ))}
      </div>
    </Tooltip>
  );
}
