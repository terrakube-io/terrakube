import { Tooltip } from "antd";
import { Link } from "react-router-dom";
import { ProjectSummaryModel } from "@/domain/types";
import { ProjectHealthBar } from "./ProjectHealthBar";
import { attentionBadge, shortAgo } from "./projectHealth";

type Props = {
  orgid: string;
  project: ProjectSummaryModel;
  /** The workspaces that are in no project. Drawn dashed, with a way to move them into one. */
  unassigned?: boolean;
};

function plural(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? "" : "s"}`;
}

export default function ProjectCard({ orgid, project, unassigned = false }: Props) {
  const badge = attentionBadge(project);
  const neverRan = !unassigned && project.workspaceCount > 0 && !project.lastRunAt;

  return (
    <article className={`project-card${unassigned ? " project-card-unassigned" : ""}`}>
      <header className="project-card-header">
        {unassigned ? (
          <span className="project-card-name">{project.name}</span>
        ) : (
          <Link className="project-card-name" to={`/organizations/${orgid}/projects/${project.id}`}>
            {project.name}
          </Link>
        )}
        {badge && <span className={`project-badge project-badge-${badge.tone}`}>{badge.text}</span>}
      </header>
      <p className="project-card-description">{project.description || "No description"}</p>
      <ProjectHealthBar summary={project} />
      <footer className="project-card-footer">
        <span>{neverRan ? "0 runs yet" : plural(project.workspaceCount, "workspace")}</span>
        {unassigned ? (
          <Link to={`/organizations/${orgid}/workspaces`}>Move them to a project</Link>
        ) : (
          <>
            <span>{plural(project.teamCount, "team")}</span>
            <Tooltip title={project.lastRunAt ? new Date(project.lastRunAt).toLocaleString() : "No runs yet"}>
              <span>{project.lastRunAt ? `last run ${shortAgo(project.lastRunAt)}` : "-"}</span>
            </Tooltip>
          </>
        )}
      </footer>
    </article>
  );
}
