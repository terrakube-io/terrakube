import React from "react";
import { Card, Space, Tag, Typography, Button, theme } from "antd";
import {
  BellOutlined,
  BranchesOutlined,
  DeleteOutlined,
  EditOutlined,
  FolderOutlined,
  GlobalOutlined,
  SafetyCertificateOutlined,
  TeamOutlined,
} from "@ant-design/icons";
import { Link } from "react-router-dom";

const { Text, Paragraph } = Typography;

export const renderEnforcementTag = (level: string) => {
  switch (level?.toUpperCase()) {
    case "HARD_MANDATORY":
      return <Tag color="error">Hard Mandatory</Tag>;
    case "SOFT_MANDATORY":
      return <Tag color="warning">Soft Mandatory</Tag>;
    case "ADVISORY":
      return <Tag color="processing">Advisory</Tag>;
    default:
      return <Tag color="default">{level}</Tag>;
  }
};

type Props = {
  item: any;
  attachmentsCount: number;
  managePermission: boolean;
  onEdit: (id: string) => void;
  onDelete: (item: any) => void;
  orgid: string;
  notificationConfig?: { name: string; channelType?: string };
};

export const PolicySetCard: React.FC<Props> = ({
  item,
  attachmentsCount,
  managePermission,
  onEdit,
  onDelete,
  orgid,
  notificationConfig,
}) => {
  const { token } = theme.useToken();
  const attrs = item.attributes || {};

  return (
    <Card
      hoverable
      style={{
        width: "100%",
        marginBottom: 16,
        borderRadius: 8,
      }}
      styles={{ body: { padding: "20px 24px" } }}
      data-testid={`policy-set-card-${item.id}`}
    >
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "flex-start",
          gap: 16,
          flexWrap: "wrap",
        }}
      >
        <div style={{ flex: 1, minWidth: 260 }}>
          <Space wrap align="center" size={8}>
            <SafetyCertificateOutlined
              style={{ fontSize: 20, color: token.colorPrimary }}
            />
            <Link
              to={`/organizations/${orgid}/settings/policies/edit/${item.id}`}
              style={{
                fontSize: 16,
                fontWeight: 600,
                color: token.colorLink,
              }}
              data-testid={`policy-set-title-link-${item.id}`}
            >
              {attrs.name}
            </Link>
            {renderEnforcementTag(attrs.enforcementLevel)}
            {attrs.shadowEnforcementLevel && (
              <Tag color="default">Shadow: {attrs.shadowEnforcementLevel}</Tag>
            )}
            {attrs.global ? (
              <Tag color="gold" icon={<GlobalOutlined />}>
                Global
              </Tag>
            ) : (
              <Tag color="cyan">
                {attachmentsCount} {attachmentsCount === 1 ? "Attachment" : "Attachments"}
              </Tag>
            )}
            {attrs.overrideTeam && (
              <Tag color="geekblue" icon={<TeamOutlined />}>
                Override Team: {attrs.overrideTeam}
              </Tag>
            )}
            {notificationConfig && (
              <Tag color="purple" icon={<BellOutlined />} data-testid={`policy-set-notification-${item.id}`}>
                Notification: {notificationConfig.name}
              </Tag>
            )}
          </Space>

          {attrs.description && (
            <Paragraph
              type="secondary"
              style={{ marginTop: 8, marginBottom: 12, maxWidth: 800 }}
            >
              {attrs.description}
            </Paragraph>
          )}

          <Space
            size={16}
            wrap
            style={{
              fontSize: 12,
              color: token.colorTextSecondary,
              marginTop: attrs.description ? 0 : 8,
            }}
          >
            {attrs.repository && (
              <span>
                <GlobalOutlined style={{ marginRight: 4 }} />
                <b>Repo:</b> {attrs.repository}
              </span>
            )}
            {attrs.branch && (
              <span>
                <BranchesOutlined style={{ marginRight: 4 }} />
                {attrs.branch}
              </span>
            )}
            {attrs.folder && (
              <span>
                <FolderOutlined style={{ marginRight: 4 }} />
                {attrs.folder}
              </span>
            )}
          </Space>
        </div>

        <Space size={4}>
          <Button
            type="text"
            icon={<EditOutlined />}
            onClick={() => onEdit(item.id)}
            disabled={!managePermission}
            data-testid={`edit-policy-set-btn-${item.id}`}
          >
            Edit
          </Button>
          <Button
            type="text"
            danger
            icon={<DeleteOutlined />}
            onClick={() => onDelete(item)}
            disabled={!managePermission}
            data-testid={`delete-policy-set-btn-${item.id}`}
          >
            Delete
          </Button>
        </Space>
      </div>
    </Card>
  );
};
