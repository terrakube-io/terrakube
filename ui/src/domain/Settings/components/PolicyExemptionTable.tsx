import React, { useState } from "react";
import { Button, Empty, Flex, Grid, Table, Tag, Tooltip, Typography, message } from "antd";
import type { ColumnsType } from "antd/es/table";
import {
  AppstoreOutlined,
  CopyOutlined,
  DeleteOutlined,
  EditOutlined,
  FolderOutlined,
  GlobalOutlined,
} from "@ant-design/icons";
import { DateTime } from "luxon";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";
import { formatOrdinalDate } from "@/modules/utils/dates";
import "../PolicySets.css";
import "./PolicyComponents.css";

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

const renderExpiration = (expiresAt: string | number | null) => {
  if (!expiresAt) {
    return <Tag>Permanent</Tag>;
  }
  const expDate = DateTime.fromJSDate(new Date(expiresAt));
  if (!expDate.isValid) {
    return <Text>{String(expiresAt)}</Text>;
  }
  const date = formatOrdinalDate(expDate);
  const diffDays = Math.ceil(expDate.diffNow("days").days);

  if (diffDays < 0) {
    return (
      <div className="policy-cell-stack">
        <Tag color="error">Expired</Tag>
        <Text type="secondary" className="policy-cell-secondary">
          {date}
        </Text>
      </div>
    );
  }
  if (diffDays <= 7) {
    return (
      <div className="policy-cell-stack">
        <Tag color="warning">
          {diffDays === 0 ? "Expires today" : `Expires in ${diffDays} day${diffDays > 1 ? "s" : ""}`}
        </Tag>
        <Text type="secondary" className="policy-cell-secondary">
          {date}
        </Text>
      </div>
    );
  }
  return <Text>{date}</Text>;
};

const scopeLabel = (record: ExemptionRecord) => {
  if (record.scopeType === "WORKSPACE") {
    return { icon: <AppstoreOutlined />, name: record.workspaceName || record.workspaceId, type: "Workspace" };
  }
  if (record.scopeType === "PROJECT") {
    return { icon: <FolderOutlined />, name: record.projectName || record.projectId, type: "Project" };
  }
  return { icon: <GlobalOutlined />, name: "Organization-wide", type: undefined };
};

// Rule IDs are snake_case: let them wrap after underscores instead of truncating the part that tells them apart.
const breakAtUnderscores = (ruleId: string) =>
  ruleId.split("_").flatMap((part, i, parts) => (i < parts.length - 1 ? [part + "_", <wbr key={i} />] : [part]));

export const PolicyExemptionTable: React.FC<PolicyExemptionTableProps> = ({
  items,
  loading = false,
  managePermission = true,
  currentWorkspaceId,
  onEdit,
  onDelete,
  pageSize = 10,
}) => {
  const screens = Grid.useBreakpoint();
  const [pendingRevoke, setPendingRevoke] = useState<ExemptionRecord | null>(null);

  const isInherited = (record: ExemptionRecord) =>
    Boolean(
      record.isInherited ||
      (currentWorkspaceId &&
        (record.scopeType === "ORGANIZATION" ||
          (record.scopeType === "PROJECT" && record.workspaceId !== currentWorkspaceId)))
    );

  const columns: ColumnsType<ExemptionRecord> = [
    {
      title: "Rule",
      dataIndex: "ruleId",
      key: "ruleId",
      render: (ruleId: string, record) => (
        <div className="policy-cell-stack">
          <Flex align="flex-start" gap={4}>
            <Text strong className="policy-mono policy-rule-id">
              {breakAtUnderscores(ruleId)}
            </Text>
            <Tooltip title="Copy rule ID">
              <Button
                type="text"
                size="small"
                icon={<CopyOutlined />}
                aria-label={`Copy rule ID ${ruleId}`}
                onClick={() =>
                  // navigator.clipboard is missing on plain HTTP; that ends up in the error branch too.
                  Promise.resolve()
                    .then(() => navigator.clipboard.writeText(ruleId))
                    .then(
                      () => message.success("Rule ID copied"),
                      () => message.error("Could not copy the rule ID")
                    )
                }
              />
            </Tooltip>
          </Flex>
          <Text type="secondary" className="policy-cell-secondary" ellipsis={{ tooltip: record.policySetName }}>
            {record.policySetName || "—"}
          </Text>
        </div>
      ),
    },
    {
      title: "Scope",
      key: "scope",
      width: 150,
      render: (_, record) => {
        const { icon, name, type } = scopeLabel(record);
        return (
          <div className="policy-cell-stack">
            <Text ellipsis={{ tooltip: name }}>
              {icon} {name}
            </Text>
            {type && (
              <Text type="secondary" className="policy-cell-secondary">
                {type}
              </Text>
            )}
            {isInherited(record) && (
              <Tooltip title="Set for the organization or a project. Change it in the organization's policy settings.">
                <Tag className="policy-inherited-tag">Inherited</Tag>
              </Tooltip>
            )}
          </div>
        );
      },
    },
    {
      title: "Reason",
      key: "reason",
      width: 170,
      render: (_, record) =>
        record.ticketReference || record.justification ? (
          <div className="policy-cell-stack">
            {record.ticketReference && (
              <Text ellipsis={{ tooltip: record.ticketReference }}>{record.ticketReference}</Text>
            )}
            {record.justification && (
              <Text type="secondary" className="policy-cell-secondary" ellipsis={{ tooltip: record.justification }}>
                {record.justification}
              </Text>
            )}
          </div>
        ) : (
          <Text type="secondary">—</Text>
        ),
    },
    {
      title: "Expires",
      dataIndex: "expiresAt",
      key: "expiresAt",
      width: 160,
      render: (expiresAt: string | number | null) => renderExpiration(expiresAt),
    },
    {
      title: "Actions",
      key: "actions",
      width: 112,
      align: "right",
      render: (_, record) => {
        if (!managePermission) {
          return (
            <Tooltip title="Requires permission to manage policies">
              <Text type="secondary">Read-only</Text>
            </Tooltip>
          );
        }
        if (isInherited(record)) {
          return null;
        }
        return (
          <Flex gap={8} justify="flex-end">
            <Button
              icon={<EditOutlined />}
              onClick={() => onEdit(record)}
              aria-label={`Edit exemption ${record.ruleId}`}
              data-testid={`edit-exemption-${record.id}`}
            />
            <Button
              icon={<DeleteOutlined />}
              onClick={() => setPendingRevoke(record)}
              aria-label={`Revoke exemption ${record.ruleId}`}
              data-testid={`delete-exemption-${record.id}`}
            />
          </Flex>
        );
      },
    },
  ];

  return (
    <>
      <Table<ExemptionRecord>
        dataSource={items}
        columns={columns}
        rowKey="id"
        loading={loading}
        tableLayout="fixed"
        pagination={{ pageSize, showSizeChanger: true }}
        // Fits the content column at 1024px and up; narrower screens scroll the table, not the page.
        scroll={screens.lg ? undefined : { x: "max-content" }}
        locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="No exemptions" /> }}
      />
      <DeleteConfirmationModal
        open={pendingRevoke !== null}
        title="Revoke exemption"
        message={`Runs evaluated from now on are checked against ${pendingRevoke?.ruleId} again, unless another exemption covers them.`}
        okText="Revoke exemption"
        onConfirm={() => {
          if (pendingRevoke) onDelete(pendingRevoke);
          setPendingRevoke(null);
        }}
        onCancel={() => setPendingRevoke(null)}
      />
    </>
  );
};
