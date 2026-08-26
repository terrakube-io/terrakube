import { Tag } from "antd";
import { statusColors } from "../utils/workspaceStatusColors";
import { getWorkspaceStatusIcon } from "../utils/workspaceStatusIcon";
import { getWorkspaceStatusText } from "../utils/workspaceStatusText";

type Props = {
  status?: string;
};

export default function WorkspaceStatusTag({ status }: Props) {
  return (
    <Tag icon={getWorkspaceStatusIcon(status)} color={status && statusColors[status]}>
      {getWorkspaceStatusText(status)}
    </Tag>
  );
}
