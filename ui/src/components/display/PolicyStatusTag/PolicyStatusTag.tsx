import React from "react";
import { Link } from "react-router-dom";
import { Tag, Tooltip } from "antd";
import clsx from "classnames";
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  ExclamationCircleOutlined,
  QuestionCircleOutlined,
} from "@ant-design/icons";
import "./PolicyStatusTag.css";

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
  const normalizedStatus = status ? status.toUpperCase().replace("-", "_") : "UNKNOWN";

  // Unknown leaves --status-color unset: the tk-status-tag default in global.css is the neutral grey.
  let color: string | undefined;
  let icon = <QuestionCircleOutlined />;
  let label = "Unknown";
  let testIdKey = "unknown";

  switch (normalizedStatus) {
    case "COMPLIANT":
      color = "var(--tk-status-success)";
      icon = <CheckCircleOutlined />;
      label = "Compliant";
      testIdKey = "compliant";
      break;
    case "NON_COMPLIANT":
      color = "var(--tk-status-error)";
      icon = <CloseCircleOutlined />;
      label = "Non-Compliant";
      testIdKey = "non-compliant";
      break;
    case "EXEMPTED":
      color = "var(--tk-status-info)";
      icon = <ExclamationCircleOutlined />;
      label = "Exempted";
      testIdKey = "exempted";
      break;
    case "UNKNOWN":
    default:
      icon = <QuestionCircleOutlined />;
      label = "Unknown";
      testIdKey = "unknown";
      break;
  }

  const isInteractive = clickable && Boolean(organizationId && workspaceId);

  const tag = (
    <Tag
      icon={icon}
      className={clsx(
        "tk-status-tag",
        "policy-status-tag",
        isInteractive && "policy-status-tag-interactive",
        className
      )}
      style={{ "--status-color": color, ...style } as React.CSSProperties}
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
          className="policy-status-tag-link"
          aria-label={`Policy compliance: ${label}`}
        >
          {tag}
        </Link>
      </Tooltip>
    );
  }

  return (
    <Tooltip title={`Policy compliance: ${label}`}>
      <span className="policy-status-tag-anchor">{tag}</span>
    </Tooltip>
  );
}
