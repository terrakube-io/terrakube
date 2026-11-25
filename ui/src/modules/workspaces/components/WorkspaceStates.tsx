import { Card, Typography } from "antd";
import { ClockCircleOutlined, SafetyOutlined } from "@ant-design/icons";
import { Link } from "react-router-dom";
import PolicyStatusTag from "@/components/display/PolicyStatusTag";
import { StatusBadge, Tone, badgeForStatusTone } from "@/components/display/OverviewCards/OverviewCards";
import { Workspace } from "@/domain/types";
import { shortAgo } from "@/modules/projects/projectHealth";
import { describeStatus } from "@/modules/projects/projectStatus";
import WorkspaceDrift from "./WorkspaceDrift";

type Props = {
  orgId: string;
  workspace: Workspace;
  /** The run the overview is about: the current one, or the latest. Absent for a workspace that never ran. */
  run?: { id: string; status: string; at?: string };
  runLink: (id: string) => string;
  onRunClick: (id: string) => void;
};

// Only a result is red or green; a workspace that was never checked is not drawn as either.
const policyTone = (status?: string): Tone =>
  status === "NON_COMPLIANT" ? "error" : status === "COMPLIANT" ? "success" : "accent";

/** Where the workspace stands: its last run, whether it drifted, and whether it complies. */
export default function WorkspaceStates({ orgId, workspace, run, runLink, onRunClick }: Props) {
  const status = run ? describeStatus(run.status) : null;
  const badge = status ? badgeForStatusTone(status.tone) : { tone: "accent" as const, icon: <ClockCircleOutlined /> };
  return (
    <div className="overview-states">
      <Card className="overview-card overview-state" role="region" aria-label="Last run">
        <StatusBadge tone={badge.tone}>{badge.icon}</StatusBadge>
        <span className="overview-state-text">
          <Typography.Text type="secondary">Last run</Typography.Text>
          <strong>{status ? status.label : "No runs yet"}</strong>
          {run && (
            <span>
              <Link to={runLink(run.id)} onClick={() => onRunClick(run.id)}>
                #{run.id}
              </Link>
              {run.at && <Typography.Text type="secondary"> · {shortAgo(run.at)}</Typography.Text>}
            </span>
          )}
        </span>
      </Card>
      <WorkspaceDrift orgId={orgId} workspace={workspace} />
      <Card className="overview-card overview-state" role="region" aria-label="Policy">
        <StatusBadge tone={policyTone(workspace.attributes?.policyComplianceStatus)}>
          <SafetyOutlined />
        </StatusBadge>
        <span className="overview-state-text">
          <Typography.Text type="secondary">Policy</Typography.Text>
          <PolicyStatusTag
            status={workspace.attributes?.policyComplianceStatus}
            organizationId={orgId}
            workspaceId={workspace.id}
            clickable
          />
        </span>
      </Card>
    </div>
  );
}
