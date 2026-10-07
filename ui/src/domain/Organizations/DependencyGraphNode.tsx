import { Card } from "antd";
import { memo } from "react";
import { Link } from "react-router-dom";
import { Handle, NodeProps, Position } from "reactflow";

export type DependencyGraphNodeData = {
  organizationId: string;
  workspaceId: string;
  workspaceName: string;
  /** This node is the workspace the view is scoped to, or the one the search box matched. */
  isFocus?: boolean;
  /** Not the focus node and not one of its direct neighbors - rendered faded, not hidden. */
  dimmed?: boolean;
};

/** One workspace in the organization's static dependency topology - read-only, no node actions. */
function DependencyGraphNode({ data }: NodeProps<DependencyGraphNodeData>) {
  return (
    <>
      <Handle type="target" position={Position.Left} isConnectable={false} />
      <Card
        size="small"
        style={{
          width: 200,
          opacity: data.dimmed ? 0.35 : 1,
          borderColor: data.isFocus ? "var(--tk-accent)" : undefined,
          borderWidth: data.isFocus ? 2 : undefined,
          transition: "opacity 0.15s ease",
        }}
      >
        <Link to={`/organizations/${data.organizationId}/workspaces/${data.workspaceId}/run-triggers`}>
          {data.workspaceName}
        </Link>
      </Card>
      <Handle type="source" position={Position.Right} isConnectable={false} />
    </>
  );
}

export default memo(DependencyGraphNode);
