import { Tooltip } from "antd";
import { Link } from "react-router-dom";
import { ProjectSummaryModel } from "@/domain/types";
import StatusPill from "./StatusPill";
import { ProjectHealthBar } from "./ProjectHealthBar";
import { attentionBadge, shortAgo } from "./projectHealth";
import { attentionFilter, projectWorkspacesLink } from "./projectLinks";

type Props = {
  /** False when the caller cannot see which teams have access, so the slot shows a dash and the total on hover. */
  canSeeTeams?: boolean;
  orgid: string;
  project: ProjectSummaryModel;
  /** The workspaces that are in no project. Drawn dashed, with a way to move them into one. */
  unassigned?: boolean;
};

function plural(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? "" : "s"}`;
}

export default function ProjectCard({ orgid, project, unassigned = false, canSeeTeams = true }: Props) {
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
        {badge &&
          (unassigned ? (
            <StatusPill tone={badge.tone}>{badge.text}</StatusPill>
          ) : (
            <Link
              to={projectWorkspacesLink(orgid, project.id, attentionFilter(project))}
              className="project-card-badge-link"
              aria-label={`${badge.text} in ${project.name}, show them`}
            >
              <StatusPill tone={badge.tone}>{badge.text}</StatusPill>
            </Link>
          ))}
      </header>
      <p className="project-card-description">{project.description || "No description"}</p>
      <ProjectHealthBar summary={project} />
      <footer className="project-card-footer">
        <span>{neverRan ? "0 runs yet" : plural(project.workspaceCount, "workspace")}</span>
        {unassigned ? (
          <Link to={`/organizations/${orgid}/workspaces`}>Move them to a project</Link>
        ) : (
          <>
            {canSeeTeams ? (
              <span>{plural(project.teamCount, "team")}</span>
            ) : (
              <Tooltip
                title={`Only users who manage project access can see team names. Total: ${plural(project.teamCount, "team")}.`}
              >
                <span aria-label={`${plural(project.teamCount, "team")}, names hidden`}>-</span>
              </Tooltip>
            )}
            <Tooltip title={project.lastRunAt ? new Date(project.lastRunAt).toLocaleString() : "No runs yet"}>
              <span>{project.lastRunAt ? `last run ${shortAgo(project.lastRunAt)}` : "-"}</span>
            </Tooltip>
          </>
        )}
      </footer>
    </article>
  );
}
