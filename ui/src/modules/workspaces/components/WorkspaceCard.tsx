import { ClockCircleOutlined, FolderOutlined } from "@ant-design/icons";
import { Card, Space, Row, Col, Tag, Typography, Flex } from "antd";
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
      <Space className="workspace-card-body" orientation="vertical">
        <Row>
          <Col span={12}>
            <Typography.Title level={3}>{item.name}</Typography.Title>
            <Typography.Text type="secondary">
              {item.description || "No description provided for this workspace"}
            </Typography.Text>
          </Col>
          <Col span={12}>
            <Row justify="start">
              <Col span={24}>
                <Flex justify="end" wrap gap="small">
                  {item.projectName && (
                    <Tag icon={<FolderOutlined />} color="blue">
                      {item.projectName}
                    </Tag>
                  )}
                  <WorkspaceTagChips bindings={item.tags} tags={tags} />
                </Flex>
              </Col>
            </Row>
          </Col>
        </Row>
        <Space size={40} className="workspace-card-meta" wrap>
          <Space>
            <WorkspaceStatusTag status={item.lastStatus} /> <br />
          </Space>
          <Space>
            <PolicyStatusTag
              status={item.policyComplianceStatus}
              organizationId={orgId}
              workspaceId={item.id}
              clickable
            />
          </Space>
          <Space>
            <ClockCircleOutlined />
            <Typography.Text>{relativeTime(item.lastRun) ?? "Never executed"}</Typography.Text>
          </Space>
          <Space>
            <IacTypeLogo type={item.iacType} />
            <Typography.Text>{item.terraformVersion}</Typography.Text>
          </Space>
          {item.branch !== "remote-content" && item.normalizedSource ? (
            <Space>
              <VcsLogo type={getVcsTypeFromUrl(item.normalizedSource)} />
              <Typography.Link
                href={item.normalizedSource}
                target="_blank"
                rel="noreferrer"
                onClick={(e) => e.stopPropagation()}
                className="workspace-card-vcs-link"
              >
                {item.normalizedSource ? getVcsNameFromUrl(item.normalizedSource) : "Unknown"}
              </Typography.Link>
            </Space>
          ) : (
            <Typography.Text className="workspace-card-cli">
              <IconContext.Provider value={{ size: "1.4em" }}>
                <BiTerminal />
              </IconContext.Provider>
              &nbsp;&nbsp;cli/api driven workflow
            </Typography.Text>
          )}
        </Space>
      </Space>
    </Card>
  );
}
