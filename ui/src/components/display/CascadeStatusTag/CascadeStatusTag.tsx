import type { CSSProperties } from "react";
import { Tag } from "antd";
import {
  cascadeStatusColors,
  getCascadeStatusIcon,
  getCascadeStatusText,
} from "@/modules/cascades/utils/cascadeStatus";

type Props = {
  status?: string;
};

export default function CascadeStatusTag({ status }: Props) {
  return (
    <Tag
      icon={getCascadeStatusIcon(status)}
      className="tk-status-tag"
      style={{ "--status-color": status && cascadeStatusColors[status] } as CSSProperties}
    >
      {getCascadeStatusText(status)}
    </Tag>
  );
}
