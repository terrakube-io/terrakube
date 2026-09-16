import type { CSSProperties } from "react";
import { Tag } from "antd";
import { statusColors } from "@/modules/workspaces/utils/workspaceStatusColors";
import { getWorkspaceStatusIcon } from "@/modules/workspaces/utils/workspaceStatusIcon";
import { getWorkspaceStatusText } from "@/modules/workspaces/utils/workspaceStatusText";

type Props = {
  status?: string;
};

export default function WorkspaceStatusTag({ status }: Props) {
  return (
    <Tag
      icon={getWorkspaceStatusIcon(status)}
      className="tk-status-tag"
      style={{ "--status-color": status && statusColors[status] } as CSSProperties}
    >
      {getWorkspaceStatusText(status)}
    </Tag>
  );
}
