import { Tag } from "antd";

const ENFORCEMENT: Record<string, { label: string; color: string }> = {
  HARD_MANDATORY: { label: "Hard mandatory", color: "error" },
  SOFT_MANDATORY: { label: "Soft mandatory", color: "warning" },
  ADVISORY: { label: "Advisory", color: "processing" },
};

export const enforcementLabel = (level?: string) => ENFORCEMENT[level?.toUpperCase() ?? ""]?.label ?? level;

export const renderEnforcementTag = (level: string) => (
  <Tag color={ENFORCEMENT[level?.toUpperCase()]?.color ?? "default"}>{enforcementLabel(level)}</Tag>
);

export const attachmentsLabel = (count: number) => `${count} ${count === 1 ? "attachment" : "attachments"}`;
