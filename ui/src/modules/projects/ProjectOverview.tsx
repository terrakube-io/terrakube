import { Alert, Button, Card, Select, Skeleton, Tooltip, Typography } from "antd";
import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { ProjectSummaryModel } from "@/domain/types";
import projectService, { ProjectWorkspaceBrief } from "./projectService";
import { ProjectHealthBar } from "./ProjectHealthBar";
import { PROJECT_TAB_LABELS, ProjectTab, getStoredLandingTab, setLandingTab } from "./projectLandingTab";
import { shortAgo, shortDuration } from "./projectHealth";
import { describeWorkspace } from "./projectStatus";
import { attentionFilter, projectWorkspacesLink, statusForHealth } from "./projectLinks";

type Props = {
  orgid: string;
  summary: ProjectSummaryModel | null;
  loading: boolean;
  /** Set when the counts could not be loaded, so they are never drawn as 0. */
  failed?: boolean;
  onRetry?: () => void;
  onOpenTab: (tab: ProjectTab) => void;
};

function Tile({ value, label, tone, to }: { value: number | string; label: string; tone?: "danger"; to?: string }) {
  const card = (
    <Card size="small" hoverable={Boolean(to)}>
      <div className={`project-tile-number${tone ? ` project-tile-number-${tone}` : ""}`}>{value}</div>
      <Typography.Text type="secondary">{label}</Typography.Text>
    </Card>
  );
  // A tile with something behind it links to it; an empty one stays plain.
  return to ? (
    <Link to={to} className="project-tile-link" aria-label={`${value} ${label}, show them`}>
      {card}
    </Link>
  ) : (
    card
  );
}

function StatusLine({ orgid, workspace }: { orgid: string; workspace: ProjectWorkspaceBrief }) {
  const { label, tone } = describeWorkspace(workspace.lastJobStatus, workspace.policyComplianceStatus);
  return (
    <li>
      <span>
        <Link to={`/organizations/${orgid}/workspaces/${workspace.id}`}>{workspace.name}</Link>{" "}
        {/* The status leads to the runs, which is where a failure or a pending approval is read. */}
        <Link
          to={`/organizations/${orgid}/workspaces/${workspace.id}/runs`}
          className={`project-status project-status-${tone}`}
          aria-label={`${label}, open the runs of ${workspace.name}`}
        >
          {label}
        </Link>
      </span>
      <Tooltip title={workspace.lastJobDate ? new Date(workspace.lastJobDate).toLocaleString() : "No runs yet"}>
        <Typography.Text type="secondary">{shortAgo(workspace.lastJobDate)}</Typography.Text>
      </Tooltip>
    </li>
  );
}

export default function ProjectOverview({ orgid, summary, loading, failed = false, onRetry, onOpenTab }: Props) {
  const [landing, setLanding] = useState<ProjectTab | "default">(() => getStoredLandingTab() ?? "default");
  const [attention, setAttention] = useState<ProjectWorkspaceBrief[]>([]);
  const [recent, setRecent] = useState<ProjectWorkspaceBrief[]>([]);
  // A list that is still loading, or could not be loaded, is never drawn as an empty one.
  const [attentionState, setAttentionState] = useState<"loading" | "ready" | "failed">("ready");
  const [recentState, setRecentState] = useState<"loading" | "ready" | "failed">("loading");
  const projectId = summary?.id;
  const attentionCount = summary?.attentionCount ?? 0;
  const workspaceCount = summary?.workspaceCount ?? 0;

  useEffect(() => {
    if (!projectId) return;
    let current = true;
    setRecentState("loading");
    projectService.listRecentWorkspaces(orgid, projectId).then((response) => {
      if (!current) return;
      setRecent(response.isError ? [] : (response.data ?? []));
      setRecentState(response.isError ? "failed" : "ready");
    });
    return () => {
      current = false;
    };
  }, [orgid, projectId, workspaceCount]);

  useEffect(() => {
    if (!projectId || attentionCount === 0) {
      setAttention([]);
      setAttentionState("ready");
      return;
    }
    let current = true;
    setAttentionState("loading");
    projectService.listAttentionWorkspaces(orgid, projectId).then((response) => {
      if (!current) return;
      setAttention(response.isError ? [] : (response.data ?? []));
      setAttentionState(response.isError ? "failed" : "ready");
    });
    return () => {
      current = false;
    };
  }, [orgid, projectId, attentionCount]);

  if (failed) {
    return (
      <Alert
        type="warning"
        showIcon
        message="Counts unavailable."
        action={
          onRetry && (
            <Button size="small" onClick={onRetry}>
              Retry
            </Button>
          )
        }
      />
    );
  }
  if (loading && !summary) return <Skeleton active paragraph={{ rows: 6 }} />;
  if (!summary) return <Alert type="warning" showIcon message="Project summary is not available" />;

  const wait = shortDuration(summary.queuedSince);
  const queuedLabel =
    summary.queuedJobCount > 0 && wait && wait !== "now" ? `queued now (longest wait ${wait})` : "queued now";

  return (
    <div className="project-overview">
      <div className="project-tiles">
        <Tile value={summary.workspaceCount} label="workspaces" to={projectWorkspacesLink(orgid, summary.id)} />
        <Tile
          value={summary.attentionCount}
          label="need attention"
          tone={summary.attentionCount > 0 ? "danger" : undefined}
          to={
            summary.attentionCount > 0 ? projectWorkspacesLink(orgid, summary.id, attentionFilter(summary)) : undefined
          }
        />
        <Tile
          value={summary.queuedJobCount}
          label={queuedLabel}
          to={summary.queuedJobCount > 0 ? projectWorkspacesLink(orgid, summary.id, { status: "pending" }) : undefined}
        />
        <Tile
          value={summary.teamCount}
          label="teams with access"
          to={`/organizations/${orgid}/projects/${summary.id}/teams`}
        />
      </div>

      <Card size="small" title="Health">
        <ProjectHealthBar
          summary={summary}
          legend="counts"
          size="large"
          hrefFor={(key) => projectWorkspacesLink(orgid, summary.id, { status: statusForHealth(key) })}
        />
        <Typography.Paragraph type="secondary" className="project-overview-meta">
          Last run: {summary.lastRunAt ? new Date(summary.lastRunAt).toLocaleString() : "Never"}
          {summary.lockedCount > 0 && ` · ${summary.lockedCount} locked`}
        </Typography.Paragraph>
      </Card>

      <div className="project-overview-columns">
        <Card size="small" title="Needs attention">
          {attentionState === "loading" ? (
            <Skeleton active paragraph={{ rows: 2 }} title={false} />
          ) : attentionState === "failed" ? (
            <Typography.Text type="secondary">
              {attentionCount} need attention, but the list could not be loaded.
            </Typography.Text>
          ) : attention.length === 0 ? (
            <Typography.Text type="secondary">Nothing needs attention.</Typography.Text>
          ) : (
            <>
              <ul className="project-activity-list">
                {attention.map((workspace) => (
                  <StatusLine key={workspace.id} orgid={orgid} workspace={workspace} />
                ))}
              </ul>
              <Button
                type="link"
                size="small"
                className="project-overview-link"
                onClick={() => onOpenTab("workspaces")}
              >
                View all {summary.attentionCount} in Workspaces →
              </Button>
            </>
          )}
        </Card>
        <Card size="small" title="Recent activity">
          {recentState === "loading" ? (
            <Skeleton active paragraph={{ rows: 2 }} title={false} />
          ) : recentState === "failed" ? (
            <Typography.Text type="secondary">Recent activity could not be loaded.</Typography.Text>
          ) : recent.length === 0 ? (
            <Typography.Text type="secondary">No runs yet.</Typography.Text>
          ) : (
            <ul className="project-activity-list">
              {recent.map((workspace) => (
                <StatusLine key={workspace.id} orgid={orgid} workspace={workspace} />
              ))}
            </ul>
          )}
        </Card>
      </div>

      <div className="project-overview-landing">
        <Typography.Text type="secondary">Open projects on: </Typography.Text>
        <Select
          size="small"
          aria-label="Default project tab"
          value={landing}
          options={[
            { value: "default", label: "Each project's setting" },
            ...Object.entries(PROJECT_TAB_LABELS).map(([value, label]) => ({ value, label })),
          ]}
          onChange={(value: ProjectTab | "default") => {
            setLanding(value);
            setLandingTab(value === "default" ? null : value);
          }}
        />
      </div>
    </div>
  );
}
