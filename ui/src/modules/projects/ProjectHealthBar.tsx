import { Tooltip } from "antd";
import { Link } from "react-router-dom";
import { ProjectSummaryModel } from "@/domain/types";
import { HealthKey, HealthSegment, healthSegments } from "./projectHealth";

type Props = {
  summary: ProjectSummaryModel;
  /** "counts" lists every status with its number under the bar, as on the project overview. */
  legend?: "none" | "labels" | "counts";
  size?: "small" | "large";
  /** When given, each status in the legend links to the workspaces with that status. */
  hrefFor?: (key: HealthKey) => string;
};

function describe(segments: HealthSegment[]): string {
  return segments
    .filter((segment) => segment.count > 0)
    .map((segment) => `${segment.count} ${segment.label.toLowerCase()}`)
    .join(", ");
}

/** The key to the colours, shown once under a list of bars. */
export function HealthLegend() {
  const labels: { key: string; label: string }[] = [
    { key: "completed", label: "Completed" },
    { key: "failed", label: "Failed" },
    { key: "running", label: "Running" },
    { key: "queued", label: "Queued" },
    { key: "waiting", label: "Waiting approval" },
    { key: "never", label: "Never executed" },
  ];
  return (
    <ul className="project-health-legend" aria-label="Health colours">
      {labels.map((item) => (
        <li key={item.key}>
          <span className={`project-health-swatch health-${item.key}`} aria-hidden />
          {item.label}
        </li>
      ))}
    </ul>
  );
}

/** Status roll-up of a project's workspaces. Colour is always paired with a label, and queued is hatched. */
export function ProjectHealthBar({ summary, legend = "none", size = "small", hrefFor }: Props) {
  const segments = healthSegments(summary);
  if (summary.workspaceCount === 0) {
    return <span className="project-health-empty">No workspaces</span>;
  }
  const present = segments.filter((segment) => segment.count > 0);
  const description = describe(segments);

  return (
    <div className={`project-health project-health-${size}`}>
      <Tooltip title={description}>
        <div className="project-health-bar" role="img" aria-label={description}>
          {present.map((segment) => (
            <span
              key={segment.key}
              className={`project-health-segment health-${segment.key}`}
              style={{ flexGrow: segment.count }}
            />
          ))}
        </div>
      </Tooltip>
      {legend !== "none" && (
        <ul className="project-health-legend">
          {(legend === "counts" ? present : segments).map((segment) => (
            <li key={segment.key}>
              <span className={`project-health-swatch health-${segment.key}`} aria-hidden />
              {(() => {
                const text = legend === "counts" ? `${segment.count} ${segment.label.toLowerCase()}` : segment.label;
                return hrefFor && segment.count > 0 ? <Link to={hrefFor(segment.key)}>{text}</Link> : text;
              })()}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
