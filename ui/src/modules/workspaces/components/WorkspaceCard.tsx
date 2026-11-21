import { ClockCircleOutlined, DollarOutlined, FolderOutlined } from "@ant-design/icons";
import { Card, Space, Tag, Tooltip, Typography, Flex } from "antd";
import { COST_ESTIMATION_DISCLAIMER, formatMonthlyCost } from "@/modules/workspaces/utils/costFormat";
import { IconContext } from "react-icons";
import { BiTerminal } from "react-icons/bi";
import { WorkspaceListItem } from "@/modules/workspaces/types";
import getVcsNameFromUrl from "@/modules/workspaces/utils/getVcsNameFromUrl";
import getVcsTypeFromUrl from "@/modules/workspaces/utils/getVcsTypeFromUrl";
import VcsLogo from "@/components/display/VcsLogo";
import WorkspaceStatusTag from "@/components/display/WorkspaceStatusTag";
import PolicyStatusTag from "@/components/display/PolicyStatusTag";
import WorkspaceTagChips from "@/modules/workspaces/components/WorkspaceTagChips";
import { TagModel } from "@/modules/organizations/types";
import { ORGANIZATION_ARCHIVE } from "@/config/actionTypes";
import IacTypeLogo from "./IacTypeLogo";
import { relativeTime } from "@/modules/utils/dates";
import "./WorkspaceCard.css";

type Props = {
  item: WorkspaceListItem;
  tags: TagModel[];
  organizationId?: string;
};
export default function WorkspaceCard({ item, tags, organizationId }: Props) {
  const orgId = organizationId || sessionStorage.getItem(ORGANIZATION_ARCHIVE) || undefined;
  return (
    <Card hoverable className="workspace-card">
      <div className="workspace-card-head">
        <Typography.Title level={3} className="workspace-card-title" title={item.name}>
          {item.name}
        </Typography.Title>
        <Flex justify="end" wrap gap="small" className="workspace-card-tags">
          {item.projectName && (
            <Tag icon={<FolderOutlined />} color="blue">
              {item.projectName}
            </Tag>
          )}
          <WorkspaceTagChips bindings={item.tags} tags={tags} />
        </Flex>
      </div>
      <Typography.Paragraph type="secondary" className="workspace-card-description" ellipsis={{ rows: 2 }}>
        {item.description || "No description provided for this workspace"}
      </Typography.Paragraph>
      <div className="workspace-card-statuses">
        <WorkspaceStatusTag status={item.lastStatus} />
        <PolicyStatusTag status={item.policyComplianceStatus} organizationId={orgId} workspaceId={item.id} clickable />
      </div>
      <div className="workspace-card-meta">
        <Space>
          <ClockCircleOutlined />
          <Typography.Text>{relativeTime(item.lastRun) ?? "Never executed"}</Typography.Text>
        </Space>
        <Space>
          <IacTypeLogo type={item.iacType} />
          <Typography.Text>{item.terraformVersion}</Typography.Text>
        </Space>
        {item.currentMonthlyCost != null && (
          <Tooltip title={COST_ESTIMATION_DISCLAIMER}>
            <Space>
              <DollarOutlined />
              <Typography.Text>{formatMonthlyCost(item.currentMonthlyCost, item.costCurrency)}</Typography.Text>
            </Space>
          </Tooltip>
        )}
      </div>
      <div className="workspace-card-source">
        {item.branch !== "remote-content" && item.normalizedSource ? (
          <>
            <VcsLogo type={getVcsTypeFromUrl(item.normalizedSource)} />
            <Typography.Link
              href={item.normalizedSource}
              target="_blank"
              rel="noreferrer"
              onClick={(e) => e.stopPropagation()}
              className="workspace-card-vcs-link"
              title={item.normalizedSource}
            >
              {item.normalizedSource ? getVcsNameFromUrl(item.normalizedSource) : "Unknown"}
            </Typography.Link>
          </>
        ) : (
          <Typography.Text className="workspace-card-cli">
            <IconContext.Provider value={{ size: "1.4em" }}>
              <BiTerminal />
            </IconContext.Provider>
            &nbsp;&nbsp;cli/api driven workflow
          </Typography.Text>
        )}
      </div>
    </Card>
  );
}
