import { Alert, Button, Card, Col, Row, Select, Skeleton, Statistic, Typography } from "antd";
import { ProjectSummaryModel } from "@/domain/types";
import { ProjectHealthBar } from "./ProjectHealthBar";
import { PROJECT_TAB_LABELS, ProjectTab, getStoredLandingTab, setLandingTab } from "./projectLandingTab";
import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import projectService, { AttentionWorkspace } from "./projectService";

type Props = {
  orgid: string;
  summary: ProjectSummaryModel | null;
  loading: boolean;
  onOpenTab: (tab: ProjectTab) => void;
};

export default function ProjectOverview({ orgid, summary, loading, onOpenTab }: Props) {
  const [landing, setLanding] = useState<ProjectTab | "default">(() => getStoredLandingTab() ?? "default");
  const [attention, setAttention] = useState<AttentionWorkspace[]>([]);
  const projectId = summary?.id;
  const attentionCount = summary?.attentionCount ?? 0;

  useEffect(() => {
    if (!projectId || attentionCount === 0) {
      setAttention([]);
      return;
    }
    projectService.listAttentionWorkspaces(orgid, projectId).then((response) => {
      if (!response.isError) setAttention(response.data ?? []);
    });
  }, [orgid, projectId, attentionCount]);

  if (loading && !summary) return <Skeleton active />;
  if (!summary) return <Alert type="warning" showIcon message="Project summary is not available" />;

  const running = summary.runningCount + summary.queuedCount;

  return (
    <div className="project-settings-panel">
      {summary.attentionCount > 0 && (
        <Alert
          type="error"
          showIcon
          className="project-overview-alert"
          message={`${summary.attentionCount} workspace${summary.attentionCount === 1 ? " needs" : "s need"} attention`}
          description={
            <>
              Failed, waiting for approval or non-compliant.{" "}
              <Button
                type="link"
                size="small"
                className="project-overview-link"
                onClick={() => onOpenTab("workspaces")}
              >
                View workspaces
              </Button>
            </>
          }
        />
      )}
      {attention.length > 0 && (
        <Card size="small" title="Workspaces that need attention" className="project-overview-health">
          <ul className="project-attention-list">
            {attention.map((workspace) => (
              <li key={workspace.id}>
                <Link to={`/organizations/${orgid}/workspaces/${workspace.id}`}>{workspace.name}</Link>{" "}
                <Typography.Text type="secondary">
                  {workspace.policyComplianceStatus === "NON_COMPLIANT" ? "Non-compliant" : workspace.lastJobStatus}
                </Typography.Text>
              </li>
            ))}
          </ul>
        </Card>
      )}
      <Row gutter={[16, 16]}>
        <Col span={8}>
          <Card size="small">
            <Statistic title="Workspaces" value={summary.workspaceCount} />
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small">
            <Statistic title="Running or queued" value={running} />
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small">
            <Statistic title="Locked" value={summary.lockedCount} />
          </Card>
        </Col>
      </Row>
      <Card size="small" title="Workspace health" className="project-overview-health">
        <ProjectHealthBar summary={summary} />
        <Typography.Paragraph type="secondary" className="project-overview-meta">
          Last run: {summary.lastRunAt ? new Date(summary.lastRunAt).toLocaleString() : "Never"}.{" "}
          <Button type="link" size="small" className="project-overview-link" onClick={() => onOpenTab("teams")}>
            {summary.teamCount} team{summary.teamCount === 1 ? "" : "s"} with project access
          </Button>
        </Typography.Paragraph>
      </Card>
      <div className="project-overview-landing">
        <Typography.Text type="secondary">Open this project on: </Typography.Text>
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
      </div>
    </div>
  );
}
