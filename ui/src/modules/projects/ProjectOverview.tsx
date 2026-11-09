import { Alert, Button, Card, Select, Skeleton, Typography } from "antd";
import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { ProjectSummaryModel } from "@/domain/types";
import projectService, { ProjectWorkspaceBrief } from "./projectService";
import { ProjectHealthBar } from "./ProjectHealthBar";
import { PROJECT_TAB_LABELS, ProjectTab, getStoredLandingTab, setLandingTab } from "./projectLandingTab";
import { shortAgo, shortDuration } from "./projectHealth";
import { describeWorkspace } from "./projectStatus";

type Props = {
  orgid: string;
  summary: ProjectSummaryModel | null;
  loading: boolean;
  /** Set when the counts could not be loaded, so they are never drawn as 0. */
  failed?: boolean;
  onRetry?: () => void;
  onOpenTab: (tab: ProjectTab) => void;
};

function Tile({ value, label, tone }: { value: number | string; label: string; tone?: "danger" }) {
  return (
    <Card size="small">
      <div className={`project-tile-number${tone ? ` project-tile-number-${tone}` : ""}`}>{value}</div>
      <Typography.Text type="secondary">{label}</Typography.Text>
    </Card>
  );
}

function StatusLine({ orgid, workspace }: { orgid: string; workspace: ProjectWorkspaceBrief }) {
  const { label, tone } = describeWorkspace(workspace.lastJobStatus, workspace.policyComplianceStatus);
  return (
    <li>
      <span>
        <Link to={`/organizations/${orgid}/workspaces/${workspace.id}`}>{workspace.name}</Link>{" "}
        <span className={`project-status project-status-${tone}`}>{label}</span>
      </span>
      <Typography.Text type="secondary">{shortAgo(workspace.lastJobDate)}</Typography.Text>
    </li>
  );
}

export default function ProjectOverview({ orgid, summary, loading, failed = false, onRetry, onOpenTab }: Props) {
  const [landing, setLanding] = useState<ProjectTab | "default">(() => getStoredLandingTab() ?? "default");
  const [attention, setAttention] = useState<ProjectWorkspaceBrief[]>([]);
  const [recent, setRecent] = useState<ProjectWorkspaceBrief[]>([]);
  const projectId = summary?.id;
  const attentionCount = summary?.attentionCount ?? 0;
  const workspaceCount = summary?.workspaceCount ?? 0;

  useEffect(() => {
    if (!projectId) return;
    projectService.listRecentWorkspaces(orgid, projectId).then((response) => {
      setRecent(response.isError ? [] : (response.data ?? []));
    });
  }, [orgid, projectId, workspaceCount]);

  useEffect(() => {
    if (!projectId || attentionCount === 0) {
      setAttention([]);
      return;
    }
    projectService.listAttentionWorkspaces(orgid, projectId).then((response) => {
      setAttention(response.isError ? [] : (response.data ?? []));
    });
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
        <Tile value={summary.workspaceCount} label="workspaces" />
        <Tile
          value={summary.attentionCount}
          label="need attention"
          tone={summary.attentionCount > 0 ? "danger" : undefined}
        />
        <Tile value={summary.queuedJobCount} label={queuedLabel} />
        <Tile value={summary.teamCount} label="teams with access" />
      </div>

      <Card size="small" title="Health">
        <ProjectHealthBar summary={summary} legend="counts" size="large" />
        <Typography.Paragraph type="secondary" className="project-overview-meta">
          Last run: {summary.lastRunAt ? new Date(summary.lastRunAt).toLocaleString() : "Never"}
          {summary.lockedCount > 0 && ` · ${summary.lockedCount} locked`}
        </Typography.Paragraph>
      </Card>

      <div className="project-overview-columns">
        <Card size="small" title="Needs attention">
          {attention.length === 0 ? (
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
          {recent.length === 0 ? (
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
