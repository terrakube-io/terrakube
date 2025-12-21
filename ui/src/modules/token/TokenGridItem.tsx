import {
  DeleteOutlined,
  ExclamationCircleOutlined,
  QuestionCircleOutlined,
  CalendarOutlined,
  UserOutlined,
} from "@ant-design/icons";
import { Card, Popconfirm, Button, Flex, Typography } from "antd";
import { DateTime } from "luxon";
import { UserToken } from "@/modules/user/types";

type Props = {
  token: UserToken;
  loading: boolean;
  onDelete: (id: string) => void;
};

export default function TokenGridItem({ token, onDelete, loading }: Props) {
  return (
    <Card
      type="inner"
      className="card"
      title={
        <Flex justify="space-between" align="center" gap="middle">
          <Typography.Text className="title" ellipsis>
            {token.description}
          </Typography.Text>
          <Popconfirm
            onConfirm={() => {
              onDelete(token.id);
            }}
            style={{ width: "20px" }}
            styles={{
              body: {
                width: "250px",
              },
            }}
            okButtonProps={{ color: "red", variant: "solid" }}
            okText="Yes"
            cancelText="No"
            title="Delete token?"
            description="This operation is irreversible. Are you sure you want to proceed?"
            icon={<QuestionCircleOutlined style={{ color: "red" }} />}
          >
            <Button variant="outlined" color="red" icon={<DeleteOutlined />} loading={loading} />
          </Popconfirm>
        </Flex>
      }
      variant="borderless"
    >
      <Flex vertical gap="small">
        <Flex gap="small">
          <UserOutlined />
          <Typography.Text strong type="secondary">
            Created By:
          </Typography.Text>
          {token.createdBy}
        </Flex>

        <Flex gap="small">
          <CalendarOutlined />
          <Typography.Text strong type="secondary">
            Created:
          </Typography.Text>
          {token.createdDate ? (
            <Typography.Text>
              {DateTime.fromISO(token.createdDate).toRelative()} (
              {DateTime.fromISO(token.createdDate).toFormat("yyyy-MM-dd")})
            </Typography.Text>
          ) : (
            <Typography.Text type="danger">Unknown</Typography.Text>
          )}
        </Flex>
        <Flex gap="small">
          <ExclamationCircleOutlined />
          <Typography.Text strong type="secondary">
            Expires:
          </Typography.Text>
          {token.createdDate ? (
            <Typography.Text type="warning">
              {(token.days > 0 || token.hours > 0 || token.minutes > 0) && token.createdDate
                ? DateTime.fromISO(token.createdDate)
                    .plus({ days: token.days, hours: token.hours, minutes: token.minutes })
                    .toLocaleString(DateTime.DATETIME_MED)
                : "Token without expiration date"}
            </Typography.Text>
          ) : (
            <Typography.Text type="danger">Unknown</Typography.Text>
          )}
        </Flex>
      </Flex>
    </Card>
  );
}
