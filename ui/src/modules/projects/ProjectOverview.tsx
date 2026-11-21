import { Alert, Button, Card, Select, Skeleton, Tooltip, Typography } from "antd";
import { projectOrigin, rememberWorkspaceOrigin } from "@/modules/workspaces/workspaceOrigin";
import { useEffect, useState } from "react";
import { ClockCircleOutlined, CloseCircleOutlined, SafetyOutlined } from "@ant-design/icons";
import {
  Action,
  ActionCard,
  HealthRing,
  StatusBadge,
  Tone,
  badgeForStatusTone,
} from "@/components/display/OverviewCards/OverviewCards";
import { RingSegment } from "@/components/display/OverviewCards/overviewModel";
import { Link, useNavigate } from "react-router-dom";
import { EmptyState } from "@/components/feedback/EmptyState";
import { ProjectSummaryModel } from "@/domain/types";
import projectService, { ProjectWorkspaceBrief } from "./projectService";
import { PROJECT_TAB_LABELS, ProjectTab, getStoredLandingTab, setLandingTab } from "./projectLandingTab";
import { HealthKey, shortAgo, shortDuration } from "./projectHealth";
import { describeWorkspace } from "./projectStatus";
import { SourceMix } from "./SourceMix";
import { projectWorkspacesLink, statusForHealth } from "./projectLinks";
import ProjectDelivery from "./ProjectDelivery";

type Props = {
  orgid: string;
  summary: ProjectSummaryModel | null;
  loading: boolean;
  /** Set when the counts could not be loaded, so they are never drawn as 0. */
  failed?: boolean;
  onRetry?: () => void;
  onOpenTab: (tab: ProjectTab) => void;
};

// Which part of the health bar a state of the ring is. Running and queued workspaces are one wedge and have no list.
const RING_TO_HEALTH: Partial<Record<RingSegment["key"], HealthKey>> = {
  completed: "completed",
  failed: "failed",
  waiting: "waiting",
  never: "never",
};

function StatusLine({
  orgid,
  projectId,
  workspace,
}: {
  orgid: string;
  projectId: string;
  workspace: ProjectWorkspaceBrief;
}) {
  const { label, tone } = describeWorkspace(workspace.lastJobStatus, workspace.policyComplianceStatus);
  // A policy violation is the one amber state that is not a clock: nothing is waiting, a rule is broken.
  const badge =
    label === "Non-compliant" ? { tone: "warning" as Tone, icon: <SafetyOutlined /> } : badgeForStatusTone(tone);
  const remember = () => rememberWorkspaceOrigin(workspace.id, projectOrigin(orgid, projectId));
  return (
    <li className="overview-row">
      <StatusBadge tone={badge.tone}>{badge.icon}</StatusBadge>
      <span className="overview-row-main">
        <Link to={`/organizations/${orgid}/workspaces/${workspace.id}`} onClick={remember}>
          <strong>{workspace.name}</strong>
        </Link>
        <Tooltip title={workspace.lastJobDate ? new Date(workspace.lastJobDate).toLocaleString() : "No runs yet"}>
          <Typography.Text type="secondary" className="overview-row-sub">
            {label}
            {workspace.lastJobDate ? ` · ${shortAgo(workspace.lastJobDate)}` : ""}
          </Typography.Text>
        </Tooltip>
      </span>
      {/* The status leads to the runs, which is where a failure or a pending approval is read. */}
      <Link
        to={`/organizations/${orgid}/workspaces/${workspace.id}/runs`}
        onClick={remember}
        aria-label={`${label}, open the runs of ${workspace.name}`}
      >
        Open
      </Link>
    </li>
  );
}

export default function ProjectOverview({ orgid, summary, loading, failed = false, onRetry, onOpenTab }: Props) {
  const [landing, setLanding] = useState<ProjectTab | "default">(() => getStoredLandingTab() ?? "default");
  const [attention, setAttention] = useState<ProjectWorkspaceBrief[]>([]);
  const [recent, setRecent] = useState<ProjectWorkspaceBrief[]>([]);
  const navigate = useNavigate();
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

  const actions: Action[] = [
    {
      key: "failed",
      icon: <CloseCircleOutlined />,
      tone: "error",
      count: summary.failedCount,
      label: "Failed runs",
      cta: "Review",
      to: projectWorkspacesLink(orgid, summary.id, { status: "failed" }),
    },
    {
      key: "waiting",
      icon: <ClockCircleOutlined />,
      tone: "warning",
      count: summary.waitingApprovalCount,
      label: "Waiting approval",
      cta: "Approve",
      to: projectWorkspacesLink(orgid, summary.id, { status: "waitingApproval" }),
    },
    {
      key: "policy",
      icon: <SafetyOutlined />,
      tone: "accent",
      count: summary.nonCompliantCount,
      label: "Policy violations",
      cta: "View",
      to: projectWorkspacesLink(orgid, summary.id, { policy: "NON_COMPLIANT" }),
    },
  ];

  return (
    <div className="project-overview">
      {summary.workspaceCount === 0 && (
        <Card size="small">
          <EmptyState description="No workspaces in this project yet.">
            <Button
              type="primary"
              onClick={() => navigate(`/organizations/${orgid}/workspaces/create?projectId=${summary.id}`)}
            >
              New workspace
            </Button>
            <Button onClick={() => onOpenTab("workspaces")}>Move existing</Button>
          </EmptyState>
        </Card>
      )}
      <div className="overview-top">
        <HealthRing
          title="Project health"
          total={summary.workspaceCount}
          status={{
            completed: summary.completedCount,
            failed: summary.failedCount,
            waiting: summary.waitingApprovalCount,
            never: Math.max(0, summary.neverRunCount - summary.noRunStatusCount),
            noStatus: summary.noRunStatusCount,
          }}
          hrefFor={(key) => {
            const health = RING_TO_HEALTH[key];
            return health ? projectWorkspacesLink(orgid, summary.id, { status: statusForHealth(health) }) : undefined;
          }}
          footer={
            summary.workspaceCount > 0 ? (
              <SourceMix vcs={summary.vcsDrivenCount} cli={summary.cliDrivenCount} locked={summary.lockedCount} />
            ) : undefined
          }
          note={
            <>
              <Link to={projectWorkspacesLink(orgid, summary.id)}>
                {summary.workspaceCount} workspace{summary.workspaceCount === 1 ? "" : "s"}
              </Link>
              {summary.queuedJobCount > 0 && (
                <>
                  {" · "}
                  <Link to={projectWorkspacesLink(orgid, summary.id, { status: "pending" })}>{queuedLabel}</Link>
                </>
              )}
              {" · Last run: "}
              {summary.lastRunAt ? (
                <span title={new Date(summary.lastRunAt).toLocaleString()}>{shortAgo(summary.lastRunAt)}</span>
              ) : (
                "Never"
              )}
            </>
          }
        />
        <div className="overview-actions">
          {actions.map((action) => (
            <ActionCard key={action.key} action={action} />
          ))}
        </div>
      </div>

      <div className="overview-columns">
        <Card className="overview-card" aria-label="Needs attention">
          <div className="overview-card-head">
            <h3 className="overview-card-title">Needs attention</h3>
            {attention.length > 0 && (
              <Button
                type="link"
                size="small"
                className="project-overview-link"
                onClick={() => onOpenTab("workspaces")}
              >
                View all {summary.attentionCount} in Workspaces →
              </Button>
            )}
          </div>
          {attention.length === 0 ? (
            <Typography.Text type="secondary">Nothing needs attention.</Typography.Text>
          ) : (
            <ul className="overview-list">
              {attention.map((workspace) => (
                <StatusLine key={workspace.id} orgid={orgid} projectId={summary.id} workspace={workspace} />
              ))}
            </ul>
          )}
        </Card>
        <Card className="overview-card" aria-label="Recent activity">
          <div className="overview-card-head">
            <h3 className="overview-card-title">Recent activity</h3>
          </div>
          {recent.length === 0 ? (
            <Typography.Text type="secondary">No runs yet.</Typography.Text>
          ) : (
            <ul className="overview-list">
              {recent.map((workspace) => (
                <StatusLine key={workspace.id} orgid={orgid} projectId={summary.id} workspace={workspace} />
              ))}
            </ul>
          )}
        </Card>
      </div>

      <div className="overview-columns">
        <ProjectDelivery orgid={orgid} projectId={summary.id} />
        <Card className="overview-card" aria-label="Details">
          <h3 className="overview-card-title">Details</h3>
          <dl className="project-overview-details">
            <div>
              <dt>Teams</dt>
              <dd>
                <Link to={`/organizations/${orgid}/projects/${summary.id}/teams`}>{summary.teamCount} with access</Link>
              </dd>
            </div>
            <div>
              <dt>Opens on</dt>
              <dd>
                <Select
                  size="small"
                  aria-label="Default project tab"
                  value={landing}
                  options={[
                    { value: "default", label: "Project default" },
                    ...Object.entries(PROJECT_TAB_LABELS).map(([value, label]) => ({ value, label })),
                  ]}
                  onChange={(value: ProjectTab | "default") => {
                    setLanding(value);
                    setLandingTab(value === "default" ? null : value);
                  }}
                />
              </dd>
            </div>
          </dl>
        </Card>
      </div>
    </div>
  );
}
