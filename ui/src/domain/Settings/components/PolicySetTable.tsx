import React from "react";
import { Button, Flex, Table, Tag, Typography } from "antd";
import type { ColumnsType } from "antd/es/table";
import { DeleteOutlined, EditOutlined, GlobalOutlined } from "@ant-design/icons";
import { Link } from "react-router-dom";
import { attachmentsLabel, enforcementLabel, renderEnforcementTag } from "./policySetLabels";
import "../PolicySets.css";
import "./PolicyComponents.css";

const { Text } = Typography;

type Props = {
  policySets: any[];
  attachmentCounts: Record<string, number>;
  managePermission: boolean;
  onEdit: (id: string) => void;
  onDelete: (item: any) => void;
  orgid: string;
  currentPage: number;
  pageSize: number;
  onPageChange: (page: number, pageSize: number) => void;
  notificationConfigs?: Record<string, { id: string; name: string; channelType?: string }>;
};

export const PolicySetTable: React.FC<Props> = ({
  policySets,
  attachmentCounts,
  managePermission,
  onEdit,
  onDelete,
  orgid,
  currentPage,
  pageSize,
  onPageChange,
  notificationConfigs,
}) => {
  const columns: ColumnsType<any> = [
    {
      title: "Policy set",
      dataIndex: ["attributes", "name"],
      key: "name",
      sorter: (a, b) => (a.attributes?.name || "").localeCompare(b.attributes?.name || ""),
      render: (_: string, record: any) => {
        const attrs = record.attributes || {};
        return (
          <div className="policy-cell-stack">
            <Link
              to={`/organizations/${orgid}/settings/policies/edit/${record.id}`}
              className="policy-set-table-link"
              data-testid={`policy-set-table-link-${record.id}`}
            >
              {attrs.name}
            </Link>
            {attrs.description && (
              <Text type="secondary" className="policy-cell-secondary" ellipsis={{ tooltip: attrs.description }}>
                {attrs.description}
              </Text>
            )}
          </div>
        );
      },
    },
    {
      title: "Enforcement",
      dataIndex: ["attributes", "enforcementLevel"],
      key: "enforcementLevel",
      width: 170,
      render: (level: string, record: any) => {
        const attrs = record.attributes || {};
        return (
          <Flex vertical gap={4} align="flex-start">
            {renderEnforcementTag(level)}
            {attrs.shadowEnforcementLevel && <Tag>Shadow: {enforcementLabel(attrs.shadowEnforcementLevel)}</Tag>}
          </Flex>
        );
      },
    },
    {
      title: "Scope",
      key: "scope",
      width: 190,
      render: (_: any, record: any) => {
        const attrs = record.attributes || {};
        const notifId = record.relationships?.notificationConfiguration?.data?.id;
        const notif = notifId && notificationConfigs ? notificationConfigs[notifId] : null;
        return (
          <div className="policy-cell-stack">
            {attrs.global ? (
              <span>
                <GlobalOutlined /> Global
              </span>
            ) : (
              <span>{attachmentsLabel(attachmentCounts[record.id] ?? 0)}</span>
            )}
            {attrs.overrideTeam && (
              <Text type="secondary" className="policy-cell-secondary">
                Override: {attrs.overrideTeam}
              </Text>
            )}
            {notif && (
              <Text
                type="secondary"
                className="policy-cell-secondary"
                ellipsis={{ tooltip: notif.name }}
                data-testid={`policy-set-table-notif-${record.id}`}
              >
                {notif.name}
              </Text>
            )}
          </div>
        );
      },
    },
    {
      title: "Source",
      key: "repository",
      render: (_: any, record: any) => {
        const attrs = record.attributes || {};
        const ref = [attrs.branch, attrs.folder].filter(Boolean).join(" · ");
        return (
          <div className="policy-cell-stack">
            {attrs.repository && (
              <Text className="policy-mono" ellipsis={{ tooltip: attrs.repository }}>
                {attrs.repository}
              </Text>
            )}
            {ref && (
              <Text type="secondary" className="policy-mono" ellipsis={{ tooltip: ref }}>
                {ref}
              </Text>
            )}
          </div>
        );
      },
    },
    {
      title: "Actions",
      key: "actions",
      width: 96,
      align: "right",
      render: (_: any, record: any) => (
        <Flex gap={8} justify="flex-end">
          <Button
            icon={<EditOutlined />}
            onClick={() => onEdit(record.id)}
            disabled={!managePermission}
            aria-label={`Edit ${record.attributes?.name}`}
            data-testid={`table-edit-policy-set-btn-${record.id}`}
          />
          <Button
            icon={<DeleteOutlined />}
            onClick={() => onDelete(record)}
            disabled={!managePermission}
            aria-label={`Delete ${record.attributes?.name}`}
            data-testid={`table-delete-policy-set-btn-${record.id}`}
          />
        </Flex>
      ),
    },
  ];

  return (
    <Table
      rowKey="id"
      columns={columns}
      dataSource={policySets}
      tableLayout="fixed"
      scroll={{ x: 720 }}
      pagination={{
        current: currentPage,
        pageSize: pageSize,
        total: policySets.length,
        showSizeChanger: true,
        pageSizeOptions: ["10", "20", "50"],
        onChange: onPageChange,
        showTotal: (total, range) => `${range[0]}-${range[1]} of ${total} policy sets`,
      }}
      data-testid="policy-sets-compact-table"
    />
  );
};
