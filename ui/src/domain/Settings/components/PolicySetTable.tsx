import React from "react";
import { Table, Tag, Space, Typography, Button } from "antd";
import type { ColumnsType } from "antd/es/table";
import {
  BranchesOutlined,
  DeleteOutlined,
  EditOutlined,
  FolderOutlined,
  GlobalOutlined,
  TeamOutlined,
} from "@ant-design/icons";
import { Link } from "react-router-dom";
import { renderEnforcementTag } from "./PolicySetCard";

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
}) => {
  const columns: ColumnsType<any> = [
    {
      title: "Policy Set",
      dataIndex: ["attributes", "name"],
      key: "name",
      sorter: (a, b) =>
        (a.attributes?.name || "").localeCompare(b.attributes?.name || ""),
      render: (_: string, record: any) => {
        const attrs = record.attributes || {};
        return (
          <Space direction="vertical" size={2} style={{ maxWidth: 360 }}>
            <Link
              to={`/organizations/${orgid}/settings/policies/edit/${record.id}`}
              style={{ fontWeight: 600, fontSize: 14 }}
              data-testid={`policy-set-table-link-${record.id}`}
            >
              {attrs.name}
            </Link>
            {attrs.description && (
              <Text
                type="secondary"
                ellipsis={{ tooltip: attrs.description }}
                style={{ fontSize: 12, maxWidth: 340 }}
              >
                {attrs.description}
              </Text>
            )}
          </Space>
        );
      },
    },
    {
      title: "Enforcement Level",
      dataIndex: ["attributes", "enforcementLevel"],
      key: "enforcementLevel",
      width: 180,
      render: (level: string, record: any) => {
        const attrs = record.attributes || {};
        return (
          <Space direction="vertical" size={4}>
            {renderEnforcementTag(level)}
            {attrs.shadowEnforcementLevel && (
              <Tag color="default">Shadow: {attrs.shadowEnforcementLevel}</Tag>
            )}
          </Space>
        );
      },
    },
    {
      title: "Scope / Attachments",
      key: "scope",
      width: 180,
      render: (_: any, record: any) => {
        const attrs = record.attributes || {};
        const count = attachmentCounts[record.id] ?? 0;
        return (
          <Space direction="vertical" size={4}>
            {attrs.global ? (
              <Tag color="gold" icon={<GlobalOutlined />}>
                Global
              </Tag>
            ) : (
              <Tag color="cyan">
                {count} {count === 1 ? "Attachment" : "Attachments"}
              </Tag>
            )}
            {attrs.overrideTeam && (
              <Tag color="geekblue" icon={<TeamOutlined />}>
                Override: {attrs.overrideTeam}
              </Tag>
            )}
          </Space>
        );
      },
    },
    {
      title: "Repository / Source",
      key: "repository",
      render: (_: any, record: any) => {
        const attrs = record.attributes || {};
        return (
          <Space direction="vertical" size={2} style={{ fontSize: 12 }}>
            {attrs.repository && (
              <Text ellipsis={{ tooltip: attrs.repository }} style={{ maxWidth: 220 }}>
                {attrs.repository}
              </Text>
            )}
            <Space size={8} wrap>
              {attrs.branch && (
                <span>
                  <BranchesOutlined /> {attrs.branch}
                </span>
              )}
              {attrs.folder && (
                <span>
                  <FolderOutlined /> {attrs.folder}
                </span>
              )}
            </Space>
          </Space>
        );
      },
    },
    {
      title: "Actions",
      key: "actions",
      width: 150,
      align: "right",
      render: (_: any, record: any) => (
        <Space size={4}>
          <Button
            type="text"
            icon={<EditOutlined />}
            onClick={() => onEdit(record.id)}
            disabled={!managePermission}
            data-testid={`table-edit-policy-set-btn-${record.id}`}
          >
            Edit
          </Button>
          <Button
            type="text"
            danger
            icon={<DeleteOutlined />}
            onClick={() => onDelete(record)}
            disabled={!managePermission}
            data-testid={`table-delete-policy-set-btn-${record.id}`}
          >
            Delete
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <Table
      rowKey="id"
      columns={columns}
      dataSource={policySets}
      pagination={{
        current: currentPage,
        pageSize: pageSize,
        total: policySets.length,
        showSizeChanger: true,
        pageSizeOptions: ["10", "20", "50"],
        onChange: onPageChange,
        showTotal: (total, range) =>
          `${range[0]}-${range[1]} of ${total} policy sets`,
      }}
      data-testid="policy-sets-compact-table"
    />
  );
};
