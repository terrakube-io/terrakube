import {
  DeleteOutlined,
  ClockCircleOutlined,
  ExclamationCircleOutlined,
  SafetyCertificateOutlined,
  UserOutlined,
} from "@ant-design/icons";
import { Button, Flex, Typography, Tag, theme } from "antd";
import { DateTime } from "luxon";
import { UserToken } from "@/modules/user/types";
import { formatOrdinalDate, relativeTime } from "@/modules/utils/dates";

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
  const expired = expiryDate !== null && expiryDate < DateTime.now();

  // Drives the icon tile and status line colors in TokenList.css.
  const status = expired ? "expired" : expiryDate ? "expiring" : "never";

  return (
    <div
      className="token-item"
      data-status={status}
      style={{
        border: `1px solid ${themeToken.colorBorder}`,
        borderRadius: themeToken.borderRadiusLG,
        backgroundColor: themeToken.colorBgContainer,
      }}
    >
      <span className="token-item-icon" aria-hidden="true">
        {expired ? <ExclamationCircleOutlined /> : <SafetyCertificateOutlined />}
      </span>

      <div className="token-item-body">
        <Flex gap="small" align="center" wrap>
          <Typography.Text className="token-item-name">{token.description}</Typography.Text>
          {token.source === "CLI_LOGIN" && <Tag color="geekblue">CLI login</Tag>}
        </Flex>

        <span className="token-item-status">
          {expired
            ? `This token expired ${formatOrdinalDate(expiryDate)}`
            : expiryDate
              ? `Expires ${formatOrdinalDate(expiryDate)}`
              : "Never expires"}
        </span>

        <Flex className="token-item-meta" justify="space-between" align="center" gap="middle" wrap>
          <Flex gap="small" align="center" wrap>
            <ClockCircleOutlined />
            <span>Created {relativeTime(token.createdDate) ?? "Unknown"} by user</span>
            <UserOutlined />
            <span>{token.createdBy}</span>
          </Flex>
          <span>{token.lastUsedAt ? `Last used ${relativeTime(token.lastUsedAt) ?? "recently"}` : "Never used"}</span>
        </Flex>
      </div>

      <Button
        icon={<DeleteOutlined />}
        loading={loading}
        aria-label={`Delete token ${token.description}`}
        onClick={() => onDelete(token.id)}
      />
    </div>
  );
}
