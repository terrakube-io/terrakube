import {
  DeleteOutlined,
  ClockCircleOutlined,
  UserOutlined,
  SafetyCertificateOutlined,
  SyncOutlined,
  HourglassOutlined,
} from "@ant-design/icons";
import { Button, Flex, Typography, Tag, theme } from "antd";
import { DateTime } from "luxon";
import { UserToken } from "@/modules/user/types";
import { relativeTime } from "@/modules/utils/dates";

type Props = {
  token: UserToken;
  loading: boolean;
  onDelete: (id: string) => void;
};

export default function TokenGridItem({ token, onDelete, loading }: Props) {
  const { token: themeToken } = theme.useToken();

  const expiryDate =
    token.createdDate && (token.days > 0 || token.hours > 0 || token.minutes > 0)
      ? DateTime.fromISO(token.createdDate).plus({ days: token.days, hours: token.hours, minutes: token.minutes })
      : null;

  return (
    <div
      className="token-item"
      style={{
        border: `1px solid ${themeToken.colorBorderSecondary}`,
        borderRadius: themeToken.borderRadiusLG,
        padding: "16px",
        backgroundColor: themeToken.colorBgContainer,
      }}
    >
      <Flex justify="space-between" align="start">
        <Flex gap="middle" align="center" style={{ width: "100%" }}>
          <div
            style={{
              backgroundColor: themeToken.colorFillTertiary,
              padding: "8px",
              borderRadius: "50%",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
            }}
          >
            <SafetyCertificateOutlined style={{ fontSize: "20px", color: themeToken.colorTextSecondary }} />
          </div>

          <Flex vertical gap={4} style={{ flex: 1 }}>
            <Flex gap="small" align="center">
              <Typography.Text strong style={{ fontSize: "16px" }}>
                {token.description}
              </Typography.Text>

              {expiryDate ? (
                <Tag
                  icon={<HourglassOutlined />}
                  style={{
                    backgroundColor: "#fff7e6",
                    borderColor: "#ffd591",
                    color: "#d46b08",
                    margin: 0,
                  }}
                >
                  Expires {expiryDate.toFormat("MMMM d, yyyy")}
                </Tag>
              ) : (
                <Tag color="blue">Never expires</Tag>
              )}

              {token.source === "CLI_LOGIN" && <Tag color="geekblue">CLI login</Tag>}
            </Flex>
          </Flex>

          <Button type="text" icon={<DeleteOutlined />} danger loading={loading} onClick={() => onDelete(token.id)} />
        </Flex>
      </Flex>

      <div
        style={{
          marginTop: "16px",
          paddingTop: "16px",
          borderTop: `1px solid ${themeToken.colorBorderSecondary}`,
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          color: themeToken.colorTextSecondary,
          fontSize: "13px",
        }}
      >
        <Flex gap="middle" align="center">
          <Flex gap="small" align="center">
            <ClockCircleOutlined />
            <span>Created {relativeTime(token.createdDate) ?? "Unknown"} by user</span>
          </Flex>
          <Flex gap="small" align="center">
            <UserOutlined />
            <Typography.Text type="secondary">{token.createdBy}</Typography.Text>
          </Flex>
        </Flex>

        <Flex gap="small" align="center">
          <SyncOutlined />
          <span>{token.lastUsedAt ? `Last used ${relativeTime(token.lastUsedAt) ?? "recently"}` : "Never used"}</span>
        </Flex>
      </div>
    </div>
  );
}
