import React from "react";
import { Link } from "react-router-dom";
import { Tag, Tooltip } from "antd";
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  ExclamationCircleOutlined,
  QuestionCircleOutlined,
} from "@ant-design/icons";

export type PolicyComplianceStatus = "COMPLIANT" | "NON_COMPLIANT" | "EXEMPTED" | "UNKNOWN" | string;

export type PolicyStatusTagProps = {
  status?: PolicyComplianceStatus | null;
  organizationId?: string;
  workspaceId?: string;
  clickable?: boolean;
  className?: string;
  style?: React.CSSProperties;
};

export const policyStatusColors: Record<string, string> = {
  COMPLIANT: "#2eb039",
  NON_COMPLIANT: "#FB0136",
  EXEMPTED: "#108ee9",
  UNKNOWN: "#8c8c8c",
};

export default function PolicyStatusTag({
  status,
  organizationId,
  workspaceId,
  clickable = false,
  className,
  style,
}: PolicyStatusTagProps) {
  const normalizedStatus = status ? status.toUpperCase().replace("-", "_") : "UNKNOWN";

  let color = "#8c8c8c";
  let icon = <QuestionCircleOutlined />;
  let label = "Unknown";
  let testIdKey = "unknown";

  switch (normalizedStatus) {
    case "COMPLIANT":
      color = "#2eb039";
      icon = <CheckCircleOutlined />;
      label = "Compliant";
      testIdKey = "compliant";
      break;
    case "NON_COMPLIANT":
      color = "#FB0136";
      icon = <CloseCircleOutlined />;
      label = "Non-Compliant";
      testIdKey = "non-compliant";
      break;
    case "EXEMPTED":
      color = "#108ee9";
      icon = <ExclamationCircleOutlined />;
      label = "Exempted";
      testIdKey = "exempted";
      break;
    case "UNKNOWN":
    default:
      color = "#8c8c8c";
      icon = <QuestionCircleOutlined />;
      label = "Unknown";
      testIdKey = "unknown";
      break;
  }

  const isInteractive = clickable && Boolean(organizationId && workspaceId);

  const tag = (
    <Tag
      color={color}
      icon={icon}
      className={className}
      style={{
        cursor: isInteractive ? "pointer" : "default",
        marginInlineEnd: 0,
        ...style,
      }}
      data-testid={`policy-status-tag-${testIdKey}`}
    >
      {label}
    </Tag>
  );

  if (isInteractive) {
    return (
      <Tooltip title={`Policy compliance: ${label}. Click to view policies.`}>
        <Link
          to={`/organizations/${organizationId}/workspaces/${workspaceId}/settings/policies`}
          onClick={(e) => e.stopPropagation()}
          style={{ display: "inline-flex", textDecoration: "none", verticalAlign: "middle", position: "relative", zIndex: 2 }}
          aria-label={`Policy compliance: ${label}`}
        >
          {tag}
        </Link>
      </Tooltip>
    );
  }

  return (
    <Tooltip title={`Policy compliance: ${label}`}>
      <span style={{ display: "inline-flex", verticalAlign: "middle" }}>{tag}</span>
    </Tooltip>
  );
}
