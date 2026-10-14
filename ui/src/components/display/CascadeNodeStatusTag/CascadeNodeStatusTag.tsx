import type { CSSProperties } from "react";
import { Tag } from "antd";
import {
  cascadeNodeStatusColors,
  getCascadeNodeStatusIcon,
  getCascadeNodeStatusText,
} from "@/modules/cascades/utils/cascadeNodeStatus";

type Props = {
  status?: string;
};

export default function CascadeNodeStatusTag({ status }: Props) {
  return (
    <Tag
      icon={getCascadeNodeStatusIcon(status)}
      className="tk-status-tag"
      style={{ "--status-color": status && cascadeNodeStatusColors[status] } as CSSProperties}
    >
      {getCascadeNodeStatusText(status)}
    </Tag>
  );
}
