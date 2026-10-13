import { AimOutlined, ExportOutlined } from "@ant-design/icons";
import { Alert, Button, List, Space, Tag, Typography } from "antd";
import { Link } from "react-router-dom";
import CascadeNodeStatusTag from "@/components/display/CascadeNodeStatusTag/CascadeNodeStatusTag";
import { LinkButton } from "@/components/navigation/LinkButton";
import { RunCascadeNodeStatus } from "@/domain/types";
import { CascadeNodeData } from "./CascadeNode";

/** One dependency edge of a cascade, with both ends resolved to the nodes the graph draws. */
export type CascadeDependency = {
  id: string;
  sourceNodeId: string;
  targetNodeId: string;
  sourceName: string;
  targetName: string;
  mode?: string;
  onDestroy?: string;
  status?: string;
};

type Guidance = {
  type: "warning" | "info" | "error" | "success";
  title: string;
  text?: string;
  /** Whether rechecking can change anything: false when the pause is a deliberate destroy policy. */
  canRecheck: boolean;
  canRetry: boolean;
};

const CONDITION_LABELS: Record<string, string> = { EACH: "Each change", ANY: "First change", ALL: "All upstreams" };
const DEPENDENCY_STATUS_LABELS: Record<string, string> = {
  SATISFIED: "Satisfied",
  BLOCKED: "Paused",
  SKIPPED: "No eligible change",
  PENDING: "Waiting",
};

const nameList = (names: string[]) =>
  names.length <= 3 ? names.join(", ") : `${names.slice(0, 3).join(", ")} and ${names.length - 3} more`;

/** What state this workspace is in, why, and what (if anything) an operator can do about it. */
export function describeNode(
  status: RunCascadeNodeStatus,
  upstream: CascadeDependency[],
  reason?: string
): Guidance | undefined {
  switch (status) {
    case RunCascadeNodeStatus.Blocked: {
      const paused = upstream.filter((dep) => dep.status === "BLOCKED");
      const byPolicy = paused.filter((dep) => dep.onDestroy === "BLOCK");
      const byFailure = paused.filter((dep) => dep.onDestroy !== "BLOCK");
      if (byPolicy.length > 0 && byFailure.length === 0) {
        return {
          type: "warning",
          title: "Paused by a destroy policy",
          text: `${nameList(byPolicy.map((dep) => dep.sourceName))} was destroyed, and this dependency is set to pause the dependent run. Rechecking cannot override a deliberate pause: start a new run on this workspace once you are ready.`,
          canRecheck: false,
          canRetry: false,
        };
      }
      if (byFailure.length > 0) {
        return {
          type: "warning",
          title: "Waiting for an upstream workspace to recover",
          text: `${nameList(byFailure.map((dep) => dep.sourceName))} did not succeed. Retry it, then recheck this workspace.`,
          canRecheck: true,
          canRetry: false,
        };
      }
      return { type: "warning", title: "Paused", text: reason, canRecheck: true, canRetry: false };
    }
    case RunCascadeNodeStatus.Failed:
      return {
        type: "error",
        title: "This workspace's run failed",
        text: "Open the latest run to see what failed, then retry it.",
        canRecheck: false,
        canRetry: true,
      };
    case RunCascadeNodeStatus.Skipped:
      return {
        type: "info",
        title: "Not run",
        text: reason ?? "No eligible change reached this workspace.",
        canRecheck: false,
        canRetry: false,
      };
    case RunCascadeNodeStatus.Cancelled:
      return {
        type: "info",
        title: "Stopped by an operator",
        text: reason,
        canRecheck: false,
        canRetry: false,
      };
    case RunCascadeNodeStatus.Running:
      return { type: "info", title: "Run in progress", canRecheck: false, canRetry: false };
    case RunCascadeNodeStatus.Pending:
    case RunCascadeNodeStatus.Ready:
      return { type: "info", title: "Waiting to run", text: reason, canRecheck: false, canRetry: false };
    default:
      return undefined;
  }
}

type Props = {
  nodeId: string;
  data: Omit<CascadeNodeData, "onRetry" | "onResume" | "onInspect" | "canOperate" | "actionLoading">;
  upstream: CascadeDependency[];
  downstream: CascadeDependency[];
  organizationId: string;
  canOperate: boolean;
  busy: boolean;
  onRetry: (nodeId: string) => void;
  onResume: (nodeId: string) => void;
  onSelect: (nodeId: string) => void;
  onFocus: (nodeId: string) => void;
};

const DependencyList = ({
  dependencies,
  side,
  empty,
  onSelect,
}: {
  dependencies: CascadeDependency[];
  side: "source" | "target";
  empty: string;
  onSelect: (nodeId: string) => void;
}) => (
  <List
    size="small"
    dataSource={dependencies}
    pagination={dependencies.length > 8 ? { pageSize: 8 } : false}
    locale={{ emptyText: empty }}
    renderItem={(dep) => (
      <List.Item>
        <Button type="link" onClick={() => onSelect(side === "source" ? dep.sourceNodeId : dep.targetNodeId)}>
          {side === "source" ? dep.sourceName : dep.targetName}
        </Button>
        <Space size={4} wrap>
          {dep.mode && <Tag>{CONDITION_LABELS[dep.mode] ?? dep.mode}</Tag>}
          <Tag color={dep.status === "BLOCKED" ? "red" : undefined}>
            {DEPENDENCY_STATUS_LABELS[dep.status ?? "PENDING"] ?? dep.status}
          </Tag>
        </Space>
      </List.Item>
    )}
  />
);

/** The side panel for one workspace of a cascade: what happened, what to do, and where it sits in the graph. */
export const CascadeNodeInspector = ({
  nodeId,
  data,
  upstream,
  downstream,
  organizationId,
  canOperate,
  busy,
  onRetry,
  onResume,
  onSelect,
  onFocus,
}: Props) => {
  const guidance = describeNode(data.status, upstream, data.reason);
  const showRetry = canOperate && guidance?.canRetry;
  const showRecheck = canOperate && guidance?.canRecheck;
  const attempts = data.attempts ?? [];

  return (
    <Space orientation="vertical" size="middle" style={{ width: "100%" }}>
      <CascadeNodeStatusTag status={data.status} />
      {guidance && <Alert showIcon type={guidance.type} title={guidance.title} description={guidance.text} />}

      <Space wrap>
        {showRetry && (
          <Button type="primary" loading={busy} onClick={() => onRetry(nodeId)}>
            Retry failed run
          </Button>
        )}
        {showRecheck && (
          <Button type="primary" loading={busy} onClick={() => onResume(nodeId)}>
            Recheck dependencies
          </Button>
        )}
        <LinkButton to={`/organizations/${organizationId}/workspaces/${data.workspaceId}`} icon={<ExportOutlined />}>
          Open workspace
        </LinkButton>
        <Button icon={<AimOutlined />} onClick={() => onFocus(nodeId)}>
          Focus on this workspace
        </Button>
      </Space>

      <section>
        <Typography.Title level={5}>Depends on</Typography.Title>
        <DependencyList
          dependencies={upstream}
          side="source"
          empty="Nothing: this is where the change started"
          onSelect={onSelect}
        />
      </section>
      <section>
        <Typography.Title level={5}>Starts</Typography.Title>
        <DependencyList
          dependencies={downstream}
          side="target"
          empty="Nothing: no workspace depends on this one"
          onSelect={onSelect}
        />
      </section>
      <section>
        <Typography.Title level={5}>Run attempts</Typography.Title>
        <List
          size="small"
          dataSource={attempts}
          pagination={attempts.length > 5 ? { pageSize: 5 } : false}
          locale={{ emptyText: "No run has been started for this workspace" }}
          renderItem={(attempt) => (
            <List.Item>
              <Space>
                Attempt {attempt.number}
                <CascadeNodeStatusTag status={attempt.outcome} />
                {attempt.jobId && (
                  <Link to={`/organizations/${organizationId}/workspaces/${data.workspaceId}/runs/${attempt.jobId}`}>
                    Run #{attempt.jobId}
                  </Link>
                )}
              </Space>
            </List.Item>
          )}
        />
      </section>
    </Space>
  );
};
