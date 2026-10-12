import { Button, Tooltip } from "antd";
import { CSSProperties, memo } from "react";
import { Link } from "react-router-dom";
import { Handle, NodeProps, Position } from "reactflow";
import CascadeNodeStatusTag from "@/components/display/CascadeNodeStatusTag/CascadeNodeStatusTag";
import { RunCascadeNodeStatus } from "@/domain/types";
import { cascadeNodeStatusColors } from "@/modules/cascades/utils/cascadeNodeStatus";

export type CascadeNodeData = {
  organizationId: string;
  workspaceId: string;
  workspaceName: string;
  status: RunCascadeNodeStatus;
  actionLoading: boolean;
  canOperate: boolean;
  reason?: string;
  attempts?: { jobId?: string; number?: number; outcome?: string }[];
  hiddenConnections?: number;
  onInspect?: (nodeId: string) => void;
  onRetry: (nodeId: string) => void;
  onResume: (nodeId: string) => void;
};

/** Fixed-size cards keep large graphs legible; the inspector holds unbounded attempt history. */
function CascadeNode({ id, data, selected }: NodeProps<CascadeNodeData>) {
  const latest = data.attempts?.[data.attempts.length - 1];
  return (
    <div
      className={`workspace-graph__node${selected ? " workspace-graph__node--selected" : ""}`}
      style={{ "--graph-status-color": cascadeNodeStatusColors[data.status] } as CSSProperties}
    >
      <Handle type="target" position={Position.Left} isConnectable={false} />
      <Tooltip title={data.workspaceName}>
        <Link
          className="workspace-graph__node-name nodrag"
          to={`/organizations/${data.organizationId}/workspaces/${data.workspaceId}`}
        >
          {data.workspaceName}
        </Link>
      </Tooltip>
      <div className="workspace-graph__node-meta">
        <CascadeNodeStatusTag status={data.status} />
      </div>
      <div className="workspace-graph__node-meta">
        {latest?.jobId ? (
          <Link
            className="nodrag"
            to={`/organizations/${data.organizationId}/workspaces/${data.workspaceId}/runs/${latest.jobId}`}
          >
            Latest run #{latest.jobId}
          </Link>
        ) : (
          "No run dispatched yet"
        )}
        {!!data.hiddenConnections && <> · {data.hiddenConnections} connections outside view</>}
      </div>
      <div className="workspace-graph__node-actions nodrag">
        <Button
          type="link"
          size="small"
          aria-label={`Inspect ${data.workspaceName}`}
          onClick={() => data.onInspect?.(id)}
        >
          Details
        </Button>
        {data.canOperate && data.status === RunCascadeNodeStatus.Failed && (
          <Button size="small" loading={data.actionLoading} onClick={() => data.onRetry(id)}>
            Retry
          </Button>
        )}
        {data.canOperate && data.status === RunCascadeNodeStatus.Blocked && (
          <Button size="small" loading={data.actionLoading} onClick={() => data.onResume(id)}>
            Recheck dependencies
          </Button>
        )}
      </div>
      <Handle type="source" position={Position.Right} isConnectable={false} />
    </div>
  );
}
export default memo(CascadeNode);
