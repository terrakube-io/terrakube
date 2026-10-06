import { Button, Card, Space } from "antd";
import { memo } from "react";
import { Link } from "react-router-dom";
import { Handle, NodeProps, Position } from "reactflow";
import CascadeNodeStatusTag from "@/components/display/CascadeNodeStatusTag/CascadeNodeStatusTag";
import { RunCascadeNodeStatus } from "@/domain/types";

export type CascadeNodeData = {
  organizationId: string;
  workspaceId: string;
  workspaceName: string;
  status: RunCascadeNodeStatus;
  actionLoading: boolean;
  onRetry: (nodeId: string) => void;
  onResume: (nodeId: string) => void;
};

/** Custom reactflow node for one workspace's position in a cascade - same shape as NodeResource. */
function CascadeNode({ id, data }: NodeProps<CascadeNodeData>) {
  const canRetry = data.status === RunCascadeNodeStatus.Failed || data.status === RunCascadeNodeStatus.Skipped;
  const canResume = data.status === RunCascadeNodeStatus.Blocked;

  return (
    <>
      <Handle type="target" position={Position.Top} isConnectable={false} />
      <Card style={{ width: 260 }} size="small">
        <Space direction="vertical" style={{ width: "100%" }}>
          <Link to={`/organizations/${data.organizationId}/workspaces/${data.workspaceId}`}>{data.workspaceName}</Link>
          <CascadeNodeStatusTag status={data.status} />
          {canRetry && (
            <Button size="small" loading={data.actionLoading} onClick={() => data.onRetry(id)}>
              Retry
            </Button>
          )}
          {canResume && (
            <Button size="small" loading={data.actionLoading} onClick={() => data.onResume(id)}>
              Resume
            </Button>
          )}
        </Space>
      </Card>
      <Handle type="source" position={Position.Bottom} isConnectable={false} />
    </>
  );
}

export default memo(CascadeNode);
