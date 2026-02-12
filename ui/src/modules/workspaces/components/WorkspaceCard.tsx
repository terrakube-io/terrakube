import { ClockCircleOutlined } from "@ant-design/icons";
import { Card, Space, Row, Col, Typography, Flex } from "antd";
import { DateTime } from "luxon";
import { IconContext } from "react-icons";
import { BiTerminal } from "react-icons/bi";
import { WorkspaceListItem } from "@/modules/workspaces/types";
import getVcsNameFromUrl from "@/modules/workspaces/utils/getVcsNameFromUrl";
import getVcsTypeFromUrl from "@/modules/workspaces/utils/getVcsTypeFromUrl";
import VcsLogo from "@/modules/workspaces/components/VcsLogo";
import WorkspaceStatusTag from "@/modules/workspaces/components/WorkspaceStatusTag";
import WorkspaceCardTags from "@/modules/workspaces/components/WorkspaceCardTags";
import { TagModel } from "@/modules/organizations/types";
import IacTypeLogo from "./IacTypeLogo";

type Props = {
  item: WorkspaceListItem;
  tags: TagModel[];
};
export default function WorkspaceCard({ item, tags }: Props) {
  return (
    <Card hoverable>
      <Space style={{ width: "100%" }} orientation="vertical">
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
                  <WorkspaceCardTags tags={tags} item={item} />
                </Flex>
              </Col>
            </Row>
          </Col>
        </Row>
        <Space size={40} style={{ marginTop: "25px" }}>
          <Space>
            <WorkspaceStatusTag status={item.lastStatus} /> <br />
          </Space>
          <Space>
            <ClockCircleOutlined />
            <Typography.Text>
              {item.lastRun ? DateTime.fromISO(item.lastRun).toRelative() : "Never Executed"}
            </Typography.Text>
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
              >
                {item.normalizedSource ? getVcsNameFromUrl(item.normalizedSource) : "Unknown"}
              </Typography.Link>
            </Space>
          ) : (
            <Typography.Text
              style={{
                verticalAlign: "middle",
                display: "inline-block",
              }}
            >
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
