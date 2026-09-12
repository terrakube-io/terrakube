import React from "react";
import {
  Button,
  Empty,
  Popconfirm,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import {
  AppstoreOutlined,
  CopyOutlined,
  DeleteOutlined,
  EditOutlined,
  FolderOutlined,
  GlobalOutlined,
  LinkOutlined,
  SafetyCertificateOutlined,
} from "@ant-design/icons";

const { Text } = Typography;

export type ExemptionRecord = {
  id: string;
  ruleId: string;
  policySetId: string;
  policySetName: string;
  ticketReference: string;
  justification: string;
  expiresAt: string | number | null;
  scopeType: "ORGANIZATION" | "PROJECT" | "WORKSPACE";
  workspaceId?: string;
  workspaceName?: string;
  projectId?: string;
  projectName?: string;
  createdDate?: string;
  createdBy?: string;
  isInherited?: boolean;
};

export type PolicyExemptionTableProps = {
  items: ExemptionRecord[];
  loading?: boolean;
  managePermission?: boolean;
  currentWorkspaceId?: string;
  onEdit: (item: ExemptionRecord) => void;
  onDelete: (item: ExemptionRecord) => void;
  pageSize?: number;
};

export const PolicyExemptionTable: React.FC<PolicyExemptionTableProps> = ({
  items,
  loading = false,
  managePermission = true,
  currentWorkspaceId,
  onEdit,
  onDelete,
  pageSize = 10,
}) => {
  const renderExpirationBadge = (expiresAt: string | number | null) => {
    if (!expiresAt) {
      return <Tag color="default">Permanent</Tag>;
    }
    try {
      const expDate = new Date(expiresAt);
      const now = new Date();
      const diffMs = expDate.getTime() - now.getTime();
      const diffDays = Math.ceil(diffMs / (1000 * 60 * 60 * 24));
      const formattedDate = !isNaN(expDate.getTime())
        ? expDate.toISOString().slice(0, 10)
        : String(expiresAt).slice(0, 10);

      if (diffDays < 0) {
        return <Tag color="error">Expired ({formattedDate})</Tag>;
      }
      if (diffDays === 0) {
        return <Tag color="volcano">Expires today</Tag>;
      }
      if (diffDays <= 7) {
        return (
          <Tag color="volcano">
            Expiring in {diffDays} day{diffDays > 1 ? "s" : ""}
          </Tag>
        );
      }
      return (
        <Tag color="purple">
          {diffDays} days remaining ({formattedDate})
        </Tag>
      );
    } catch {
      const fallbackStr = String(expiresAt || "").slice(0, 10);
      return <Tag color="purple">Expires: {fallbackStr}</Tag>;
    }
  };

  const renderScopeTag = (record: ExemptionRecord) => {
    let scopeBadge;
    if (record.scopeType === "WORKSPACE") {
      const label = `Workspace: ${record.workspaceName || record.workspaceId}`;
      scopeBadge = (
        <Tooltip title={label}>
          <Tag
            color="geekblue"
            icon={<AppstoreOutlined />}
            style={{
              maxWidth: 175,
              overflow: "hidden",
              textOverflow: "ellipsis",
              verticalAlign: "bottom",
            }}
          >
            {label}
          </Tag>
        </Tooltip>
      );
    } else if (record.scopeType === "PROJECT") {
      const label = `Project: ${record.projectName || record.projectId}`;
      scopeBadge = (
        <Tooltip title={label}>
          <Tag
            color="cyan"
            icon={<FolderOutlined />}
            style={{
              maxWidth: 175,
              overflow: "hidden",
              textOverflow: "ellipsis",
              verticalAlign: "bottom",
            }}
          >
            {label}
          </Tag>
        </Tooltip>
      );
    } else {
      scopeBadge = (
        <Tag color="blue" icon={<GlobalOutlined />}>
          Organization-Wide
        </Tag>
      );
    }

    const isInherited =
      record.isInherited ||
      (currentWorkspaceId &&
        (record.scopeType === "ORGANIZATION" ||
          (record.scopeType === "PROJECT" && record.workspaceId !== currentWorkspaceId)));

    return (
      <Space wrap size={4}>
        {scopeBadge}
        {isInherited && <Tag color="default">Inherited</Tag>}
      </Space>
    );
  };

  const columns: ColumnsType<ExemptionRecord> = [
    {
      title: "Rule ID",
      dataIndex: "ruleId",
      key: "ruleId",
      width: 250,
      render: (ruleId: string) => (
        <Space size={4} wrap={false}>
          <Text
            code
            strong
            style={{
              whiteSpace: "nowrap",
              wordBreak: "keep-all",
            }}
          >
            {ruleId}
          </Text>
          <Tooltip title="Copy Rule ID">
            <Button
              type="text"
              size="small"
              icon={<CopyOutlined />}
              onClick={() => navigator.clipboard?.writeText(ruleId)}
            />
          </Tooltip>
        </Space>
      ),
    },
    {
      title: "Policy Set",
      dataIndex: "policySetName",
      key: "policySetName",
      width: 170,
      render: (name: string) => (
        <Tooltip title={name || "Policy Set"}>
          <Tag
            color="blue"
            icon={<SafetyCertificateOutlined />}
            style={{
              maxWidth: 155,
              overflow: "hidden",
              textOverflow: "ellipsis",
              verticalAlign: "bottom",
            }}
          >
            {name || "Policy Set"}
          </Tag>
        </Tooltip>
      ),
    },
    {
      title: "Scope",
      key: "scope",
      width: 190,
      render: (_, record) => renderScopeTag(record),
    },
    {
      title: "Ticket",
      dataIndex: "ticketReference",
      key: "ticketReference",
      width: 120,
      render: (ticket: string) =>
        ticket ? (
          <Tag color="cyan" icon={<LinkOutlined />}>
            {ticket}
          </Tag>
        ) : (
          <Text type="secondary">—</Text>
        ),
    },
    {
      title: "Justification",
      dataIndex: "justification",
      key: "justification",
      width: 190,
      ellipsis: true,
      render: (justification: string) =>
        justification ? (
          <Tooltip title={justification} placement="topLeft">
            <span style={{ fontStyle: "italic" }}>"{justification}"</span>
          </Tooltip>
        ) : (
          <Text type="secondary">—</Text>
        ),
    },
    {
      title: "Expiration",
      dataIndex: "expiresAt",
      key: "expiresAt",
      width: 200,
      render: (expiresAt: string | null) => renderExpirationBadge(expiresAt),
    },
    {
      title: "Actions",
      key: "actions",
      width: 100,
      align: "center",
      render: (_, record) => {
        const isInherited =
          record.isInherited ||
          (currentWorkspaceId &&
            (record.scopeType === "ORGANIZATION" ||
              (record.scopeType === "PROJECT" && record.workspaceId !== currentWorkspaceId)));

        if (!managePermission) {
          return (
            <Tooltip title="Requires Policy Management permission">
              <span style={{ color: "#999" }}>Read-only</span>
            </Tooltip>
          );
        }

        if (isInherited) {
          return (
            <Tooltip title="This exemption is defined at Organization/Project level and cannot be modified from workspace settings.">
              <span style={{ color: "#999", fontSize: 12 }}>Inherited</span>
            </Tooltip>
          );
        }

        return (
          <Space size={8}>
            <Tooltip title="Edit Exemption">
              <Button
                type="text"
                size="small"
                icon={<EditOutlined />}
                onClick={() => onEdit(record)}
                data-testid={`edit-exemption-${record.id}`}
              />
            </Tooltip>
            <Tooltip title="Revoke Exemption">
              <Popconfirm
                title="Revoke Policy Exemption"
                description="Are you sure you want to revoke this policy waiver? Evaluated runs may immediately fail policy checks."
                onConfirm={() => onDelete(record)}
                okText="Revoke"
                cancelText="Cancel"
                okButtonProps={{ danger: true }}
              >
                <Button
                  type="text"
                  danger
                  size="small"
                  icon={<DeleteOutlined />}
                  data-testid={`delete-exemption-${record.id}`}
                />
              </Popconfirm>
            </Tooltip>
          </Space>
        );
      },
    },
  ];

  return (
    <Table<ExemptionRecord>
      dataSource={items}
      columns={columns}
      rowKey="id"
      loading={loading}
      pagination={{ pageSize, showSizeChanger: true }}
      scroll={{ x: 1050 }}
      locale={{
        emptyText: (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="No policy exemptions configured"
          />
        ),
      }}
    />
  );
};
