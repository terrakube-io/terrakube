import type { CSSProperties, ReactNode } from "react";
import { Tag } from "antd";
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  ExclamationCircleOutlined,
  QuestionCircleOutlined,
} from "@ant-design/icons";

export type PillTone = "danger" | "warning" | "success" | "neutral";

const TONES: Record<PillTone, { color?: string; icon: ReactNode }> = {
  danger: { color: "var(--tk-status-error)", icon: <CloseCircleOutlined /> },
  warning: { color: "var(--tk-status-warning)", icon: <ExclamationCircleOutlined /> },
  success: { color: "var(--tk-status-success)", icon: <CheckCircleOutlined /> },
  neutral: { icon: <QuestionCircleOutlined /> },
};

/** A status in the tag style of the workspace list: tinted and bordered, with the icon of its tone. */
export default function StatusPill({
  tone,
  children,
  icon = true,
}: {
  tone: PillTone;
  children: ReactNode;
  /** A bare number reads better without the icon. */
  icon?: boolean;
}) {
  const { color, icon: toneIcon } = TONES[tone];
  return (
    <Tag
      icon={icon ? toneIcon : undefined}
      className="tk-status-tag"
      style={{ "--status-color": color, margin: 0 } as CSSProperties}
    >
      {children}
    </Tag>
  );
}
