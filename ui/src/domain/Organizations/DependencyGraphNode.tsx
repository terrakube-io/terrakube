import { ApartmentOutlined, ArrowLeftOutlined, ArrowRightOutlined } from "@ant-design/icons";
import { Button, Tooltip } from "antd";
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
  hiddenConnections?: number;
  upstreamCount?: number;
  downstreamCount?: number;
  onInspect?: (id: string) => void;
};

/** One workspace in the organization's static dependency topology - read-only, no node actions. */
function DependencyGraphNode({ id, data }: NodeProps<DependencyGraphNodeData>) {
  return (
    <div
      className={`workspace-graph__node workspace-graph__topology-node${data.isFocus ? " workspace-graph__node--selected" : ""}`}
      style={{ opacity: data.dimmed ? 0.35 : 1 }}
    >
      <Handle type="target" position={Position.Left} isConnectable={false} />
      <div className="workspace-graph__node-kind">
        <ApartmentOutlined aria-hidden /> Workspace
        {data.isFocus && <span className="workspace-graph__focus-label">Focused</span>}
      </div>
      <Tooltip title={data.workspaceName}>
        <Link
          className="workspace-graph__node-name nodrag"
          to={`/organizations/${data.organizationId}/workspaces/${data.workspaceId}/run-triggers`}
        >
          {data.workspaceName}
        </Link>
      </Tooltip>
      <div className="workspace-graph__connections">
        <span>
          <ArrowLeftOutlined aria-hidden /> {data.upstreamCount ?? 0} upstream
        </span>
        <span>
          <ArrowRightOutlined aria-hidden /> {data.downstreamCount ?? 0} downstream
        </span>
      </div>
      <div className="workspace-graph__topology-footer nodrag">
        <span>
          {!!data.hiddenConnections && <>{data.hiddenConnections} outside view</>}
          {!data.upstreamCount && !data.downstreamCount && "No dependencies"}
        </span>
        <Button
          type="link"
          size="small"
          aria-label={`Inspect ${data.workspaceName}`}
          onClick={() => data.onInspect?.(id)}
        >
          Details
        </Button>
      </div>
      <Handle type="source" position={Position.Right} isConnectable={false} />
    </div>
  );
}

export default memo(DependencyGraphNode);
