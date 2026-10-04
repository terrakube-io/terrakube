import React from "react";
import { Button, Flex, Tag, Typography } from "antd";
import {
  BellOutlined,
  BranchesOutlined,
  DeleteOutlined,
  EditOutlined,
  FolderOutlined,
  GlobalOutlined,
  TeamOutlined,
} from "@ant-design/icons";
import { Link } from "react-router-dom";
import { attachmentsLabel, enforcementLabel, renderEnforcementTag } from "./policySetLabels";
import "../PolicySets.css";

type Props = {
  item: any;
  attachmentsCount: number;
  managePermission: boolean;
  onEdit: (id: string) => void;
  onDelete: (item: any) => void;
  orgid: string;
  notificationConfig?: { name: string; channelType?: string };
};

// A flat resource card: 1px border, no shadow, outlined icon actions named after the policy set.
export const PolicySetCard: React.FC<Props> = ({
  item,
  attachmentsCount,
  managePermission,
  onEdit,
  onDelete,
  orgid,
  notificationConfig,
}) => {
  const attrs = item.attributes || {};

  return (
    <article className="policy-set-card" data-testid={`policy-set-card-${item.id}`}>
      <div className="policy-set-card-body">
        <div className="policy-set-card-title">
          <Link
            to={`/organizations/${orgid}/settings/policies/edit/${item.id}`}
            data-testid={`policy-set-title-link-${item.id}`}
          >
            {attrs.name}
          </Link>
          {renderEnforcementTag(attrs.enforcementLevel)}
          {attrs.shadowEnforcementLevel && <Tag>Shadow: {enforcementLabel(attrs.shadowEnforcementLevel)}</Tag>}
          {attrs.global ? <Tag icon={<GlobalOutlined />}>Global</Tag> : <Tag>{attachmentsLabel(attachmentsCount)}</Tag>}
        </div>

        {attrs.description && (
          <Typography.Paragraph type="secondary" className="policy-set-card-description">
            {attrs.description}
          </Typography.Paragraph>
        )}

        <div className="policy-set-card-meta">
          {attrs.repository && (
            <span className="policy-mono">
              <GlobalOutlined />
              {attrs.repository}
            </span>
          )}
          {attrs.branch && (
            <span className="policy-mono">
              <BranchesOutlined />
              {attrs.branch}
            </span>
          )}
          {attrs.folder && (
            <span className="policy-mono">
              <FolderOutlined />
              {attrs.folder}
            </span>
          )}
          {attrs.overrideTeam && (
            <span>
              <TeamOutlined />
              Override team: {attrs.overrideTeam}
            </span>
          )}
          {notificationConfig && (
            <span data-testid={`policy-set-notification-${item.id}`}>
              <BellOutlined />
              Notification: {notificationConfig.name}
            </span>
          )}
        </div>
      </div>

      <Flex gap={8}>
        <Button
          icon={<EditOutlined />}
          onClick={() => onEdit(item.id)}
          disabled={!managePermission}
          aria-label={`Edit ${attrs.name}`}
          data-testid={`edit-policy-set-btn-${item.id}`}
        />
        <Button
          icon={<DeleteOutlined />}
          onClick={() => onDelete(item)}
          disabled={!managePermission}
          aria-label={`Delete ${attrs.name}`}
          data-testid={`delete-policy-set-btn-${item.id}`}
        />
      </Flex>
    </article>
  );
};
