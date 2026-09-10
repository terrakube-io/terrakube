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

export default function PolicyStatusTag({
  status,
  organizationId,
  workspaceId,
  clickable = false,
  className,
  style,
}: PolicyStatusTagProps) {
  const normalizedStatus = status ? status.toUpperCase() : "UNKNOWN";

  let color = "default";
  let icon = <QuestionCircleOutlined />;
  let label = "UNKNOWN";

  switch (normalizedStatus) {
    case "COMPLIANT":
      color = "success";
      icon = <CheckCircleOutlined />;
      label = "COMPLIANT";
      break;
    case "NON_COMPLIANT":
      color = "error";
      icon = <CloseCircleOutlined />;
      label = "NON-COMPLIANT";
      break;
    case "EXEMPTED":
      color = "processing";
      icon = <ExclamationCircleOutlined />;
      label = "EXEMPTED";
      break;
    case "UNKNOWN":
    default:
      color = "default";
      icon = <QuestionCircleOutlined />;
      label = "UNKNOWN";
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
      data-testid={`policy-status-tag-${label.toLowerCase()}`}
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
