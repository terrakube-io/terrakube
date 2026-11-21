import { Alert, Button, Card, Skeleton, Space, Tag, Typography } from "antd";
import { ClockCircleOutlined, CloseCircleOutlined, SafetyOutlined, SwapOutlined } from "@ant-design/icons";
import { ReactNode, useCallback, useEffect, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { EmptyState } from "@/components/feedback/EmptyState";
import AccessChip from "@/components/display/AccessChip/AccessChip";
import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";
import { organizationCapabilities } from "@/modules/permissions/accessModel";
import { useOrgPermissions } from "@/modules/permissions/useOrgPermissions";
import WorkspaceStatusTag from "@/components/display/WorkspaceStatusTag/WorkspaceStatusTag";
import { usePolling } from "@/hooks";
import { useOrganizationName } from "@/hooks/useOrganizationName";
import { confirmApprove } from "@/modules/inbox/confirmApprove";
import { InboxData, InboxItem } from "@/modules/inbox/inboxModel";
import { loadInbox } from "@/modules/inbox/inboxService";
import { ProjectHealthBar } from "@/modules/projects/ProjectHealthBar";
import { attentionBadge, shortAgo } from "@/modules/projects/projectHealth";
import projectService from "@/modules/projects/projectService";
import { ProjectSummaryModel } from "@/domain/types";
import { inboxOrigin, rememberWorkspaceOrigin } from "@/modules/workspaces/workspaceOrigin";
import { Action, ActionCard, HealthRing, StatusBadge, Tone } from "@/components/display/OverviewCards/OverviewCards";
import { OrgOverview, complianceRate, loadOrgOverview } from "./orgOverviewService";
import "@/modules/projects/Projects.css";

const POLL_MS = 30000;
const NEEDS_YOU_ROWS = 5;
const PROJECT_ROWS = 5;
const WORST_FIRST = "-attentionCount,+name";

/** How the organization is doing, and what needs a person, before the lists of workspaces and projects. */
export default function OrgOverviewPage() {
  const { id: orgId = "" } = useParams();
  const navigate = useNavigate();
  const { permissions } = useOrgPermissions(orgId);
  const name = useOrganizationName(orgId) ?? "";
  const [overview, setOverview] = useState<OrgOverview | null>(null);
  const [inbox, setInbox] = useState<InboxData | null>(null);
  const [projects, setProjects] = useState<ProjectSummaryModel[] | null>(null);
  const [error, setError] = useState(false);
  const alive = useRef(true);

  const load = useCallback(async () => {
    try {
      const [nextOverview, nextInbox, nextProjects] = await Promise.all([
        loadOrgOverview(orgId),
        loadInbox([{ id: orgId, name }]),
        projectService.listProjectSummaries(orgId, { sort: WORST_FIRST, page: 1, pageSize: PROJECT_ROWS, chip: "all" }),
      ]);
      if (!alive.current) return;
      setOverview(nextOverview);
      setInbox(nextInbox);
      setProjects(nextProjects.isError ? [] : (nextProjects.data?.items ?? []));
      setError(false);
    } catch {
      if (alive.current) setError(true);
    }
  }, [orgId, name]);

  useEffect(() => {
    alive.current = true;
    load();
    return () => {
      alive.current = false;
    };
  }, [load]);

  usePolling(load, { interval: POLL_MS, immediate: false });

  const base = `/organizations/${orgId}`;
  const counts = inbox?.counts;
  const rate = overview ? complianceRate(overview.counts) : null;
  const needsYou: InboxItem[] = inbox ? inbox.items.slice(0, NEEDS_YOU_ROWS) : [];
  const loading = !error && (!overview || !inbox || !projects);
  const hiddenProjects = overview && projects ? Math.max(0, overview.counts.projects - projects.length) : 0;

  const KIND_BADGE: Record<InboxItem["kind"], { icon: ReactNode; tone: Tone; text: string }> = {
    waiting: { icon: <ClockCircleOutlined />, tone: "warning", text: "Waiting for approval" },
    failed: { icon: <CloseCircleOutlined />, tone: "error", text: "Failed" },
    drifted: { icon: <SwapOutlined />, tone: "accent", text: "Drifted" },
    policy: { icon: <SafetyOutlined />, tone: "error", text: "Policy violation" },
  };

  const actionCards: Action[] = counts
    ? [
        {
          key: "failed",
          icon: <CloseCircleOutlined />,
          tone: "error",
          count: counts.failed,
          label: "Failed runs",
          cta: "Review",
          to: "/inbox",
        },
        {
          key: "waiting",
          icon: <ClockCircleOutlined />,
          tone: "warning",
          count: counts.waiting,
          label: "Waiting approval",
          cta: "Approve",
          to: "/inbox",
        },
        {
          key: "drifted",
          icon: <SwapOutlined />,
          tone: "accent",
          count: counts.drifted,
          label: "Drifted",
          cta: "View plans",
          to: "/inbox",
        },
      ]
    : [];

  return (
    <PageWrapper
      width="full"
      title={name || "Overview"}
      subTitle={
        overview ? `${overview.counts.workspaces} workspaces in ${overview.counts.projects} projects` : undefined
      }
      loading={false}
      loadingText="Loading..."
      breadcrumbs={[{ label: "Overview", path: `${base}/overview` }]}
      actions={
        <Space>
          <AccessChip
            scope="organization"
            level={permissions.accessLevel}
            teams={permissions.grantedThrough ?? []}
            items={organizationCapabilities(permissions)}
          />
          <Button onClick={() => navigate(`${base}/workspaces/import`)}>Import</Button>
          <Button type="primary" onClick={() => navigate(`${base}/workspaces/create`)}>
            New workspace
          </Button>
        </Space>
      }
    >
      {error && !overview && (
        <Alert
          type="error"
          showIcon
          message="Could not load the overview."
          action={
            <Button size="small" onClick={load}>
              Retry
            </Button>
          }
        />
      )}
      {loading && <Skeleton active paragraph={{ rows: 8 }} />}
      {overview && inbox && counts && projects && (
        <>
          <div className="overview-top">
            <HealthRing
              title="Workspace health"
              total={overview.counts.workspaces}
              status={overview.counts.status}
              note={rate !== null ? `${rate}% compliant` : undefined}
            />
            <div className="overview-actions">
              {actionCards.map((action) => (
                <ActionCard key={action.key} action={action} />
              ))}
            </div>
          </div>

          <div className="overview-columns">
            <Card className="overview-card" aria-label="Needs you">
              <div className="overview-card-head">
                <h3 className="overview-card-title">Needs you</h3>
                <Link to="/inbox">Open inbox</Link>
              </div>
              {needsYou.length === 0 ? (
                <EmptyState description="Nothing needs you." />
              ) : (
                <ul className="overview-list">
                  {needsYou.map((item) => {
                    const badge = KIND_BADGE[item.kind];
                    return (
                      <li key={item.key} className="overview-row">
                        <StatusBadge tone={badge.tone}>{badge.icon}</StatusBadge>
                        <span className="overview-row-main">
                          <Link
                            to={`${base}/workspaces/${item.workspaceId}`}
                            onClick={() => rememberWorkspaceOrigin(item.workspaceId, inboxOrigin())}
                          >
                            <strong>{item.name}</strong>
                          </Link>
                          <Typography.Text type="secondary" className="overview-row-sub">
                            {item.projectName ? `${item.projectName} · ` : ""}
                            {badge.text} · {shortAgo(item.at)}
                          </Typography.Text>
                        </span>
                        {item.kind === "waiting" ? (
                          <Button size="small" type="primary" onClick={() => confirmApprove(item, load)}>
                            Approve
                          </Button>
                        ) : (
                          <Link
                            to={`${base}/workspaces/${item.workspaceId}/runs${item.runId ? `/${item.runId}` : ""}`}
                            onClick={() => rememberWorkspaceOrigin(item.workspaceId, inboxOrigin())}
                          >
                            {item.kind === "drifted" ? "View plan" : "Open"}
                          </Link>
                        )}
                      </li>
                    );
                  })}
                </ul>
              )}
            </Card>

            <Card className="overview-card" aria-label="Recent runs">
              <div className="overview-card-head">
                <h3 className="overview-card-title">Recent runs</h3>
              </div>
              {overview.recent.length === 0 ? (
                <EmptyState description="No runs yet." />
              ) : (
                <ul className="overview-list">
                  {overview.recent.map((run) => (
                    <li key={run.workspaceId} className="overview-row">
                      <span className="overview-row-main">
                        <Link to={`${base}/workspaces/${run.workspaceId}/runs`}>
                          <strong>{run.name}</strong>
                        </Link>
                        <Typography.Text type="secondary" className="overview-row-sub">
                          {run.projectName ? `${run.projectName} · ` : ""}
                          {shortAgo(run.at)}
                        </Typography.Text>
                      </span>
                      <WorkspaceStatusTag status={run.status} />
                    </li>
                  ))}
                </ul>
              )}
            </Card>
          </div>

          <Card className="overview-card" aria-label="Projects">
            <div className="overview-card-head">
              <h3 className="overview-card-title">
                Projects
                {hiddenProjects > 0 && (
                  <Typography.Text type="secondary" className="overview-card-subtitle">
                    {" "}
                    {projects.length} of {overview.counts.projects}, the ones that need attention most
                  </Typography.Text>
                )}
              </h3>
              <Link to={`${base}/projects`}>
                {overview.counts.projects > projects.length
                  ? `All ${overview.counts.projects} projects`
                  : "All projects"}
              </Link>
            </div>
            {projects.length === 0 ? (
              <EmptyState description="No projects yet." />
            ) : (
              <div className="overview-projects">
                {projects.map((project) => {
                  const badge = attentionBadge(project);
                  return (
                    <div key={project.id} className="overview-project">
                      <div className="overview-project-head">
                        <Link to={`${base}/projects/${project.id}`}>
                          <strong>{project.name}</strong>
                        </Link>
                        {badge ? (
                          <Tag color={badge.tone === "danger" ? "error" : "warning"}>{badge.text}</Tag>
                        ) : project.workspaceCount > 0 ? (
                          <Tag color="success">Healthy</Tag>
                        ) : null}
                      </div>
                      <ProjectHealthBar summary={project} />
                      {(project.workspaceCount > 0 || project.ownerTeam) && (
                        <Typography.Text type="secondary" className="overview-row-sub">
                          {[
                            project.workspaceCount > 0
                              ? `${project.workspaceCount} workspace${project.workspaceCount === 1 ? "" : "s"}`
                              : null,
                            project.ownerTeam ? `Owner ${project.ownerTeam}` : null,
                          ]
                            .filter(Boolean)
                            .join(" · ")}
                        </Typography.Text>
                      )}
                    </div>
                  );
                })}
                {hiddenProjects > 0 && (
                  <Link to={`${base}/projects`} className="overview-project overview-project-more">
                    <strong>+{hiddenProjects} more</strong>
                    <span>project{hiddenProjects === 1 ? "" : "s"}, listed by what needs attention &rarr;</span>
                  </Link>
                )}
              </div>
            )}
          </Card>
        </>
      )}
    </PageWrapper>
  );
}
