import { CheckCircleFilled, ClockCircleFilled, CloseCircleFilled, LoadingOutlined, SyncOutlined } from "@ant-design/icons";
import { Button, List, Spin, Tag, Typography, message } from "antd";
import { useEffect, useState } from "react";
import axiosInstance, { getErrorMessage } from "@/config/axiosConfig";
import { NotificationChannelType } from "../types";
import { CHANNEL_META } from "./channelMeta";
import SettingsSection from "@/components/settings/SettingsSection/SettingsSection";

type DeliveryStatus = "PENDING" | "SENDING" | "SENT" | "FAILED";

type Delivery = {
  id: string;
  jobId: number;
  configurationName: string;
  channelType: NotificationChannelType;
  status: DeliveryStatus;
  attemptCount: number;
  lastAttemptAt: string | null;
  lastError: string | null;
  createdDate: string;
};

type Props = {
  workspaceId: string;
};

const STATUS_META: Record<
  DeliveryStatus,
  { tagColor: string; iconColor: string; icon: typeof CheckCircleFilled; label: string }
> = {
  SENT: { tagColor: "green", iconColor: "#389e0d", icon: CheckCircleFilled, label: "Sent" },
  PENDING: { tagColor: "orange", iconColor: "#d46b08", icon: ClockCircleFilled, label: "Pending" },
  SENDING: { tagColor: "blue", iconColor: "#1677ff", icon: LoadingOutlined, label: "Sending" },
  FAILED: { tagColor: "red", iconColor: "#cf1322", icon: CloseCircleFilled, label: "Failed" },
};

export const NotificationDeliveryHistory = ({ workspaceId }: Props) => {
  const [deliveries, setDeliveries] = useState<Delivery[]>([]);
  const [loading, setLoading] = useState(true);
  const [retryingId, setRetryingId] = useState<string | null>(null);
  const origin = new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin;

  const loadDeliveries = () => {
    setLoading(true);
    return axiosInstance
      .get(`${origin}/notification/v1/workspace/${workspaceId}/deliveries?limit=10`)
      .then((response) => setDeliveries(response.data))
      .catch((err) => message.error(getErrorMessage(err) || "Failed to load notification delivery history"))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadDeliveries();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspaceId]);

  const retryDelivery = (deliveryId: string) => {
    setRetryingId(deliveryId);
    axiosInstance
      .post(`${origin}/notification/v1/workspace/${workspaceId}/deliveries/${deliveryId}/retry`)
      .then(() => {
        message.success("Retry queued");
        return loadDeliveries();
      })
      .catch((err) => message.error(getErrorMessage(err) || "Failed to retry delivery"))
      .finally(() => setRetryingId(null));
  };

  if (!loading && deliveries.length === 0) {
    return null;
  }

  return (
    <SettingsSection maxWidth="100%">
      <Typography.Title level={4} style={{ marginTop: 0 }}>
        Recent Deliveries
      </Typography.Title>
      <Typography.Text type="secondary" style={{ display: "block", marginBottom: 12 }}>
        The last {deliveries.length} notification attempts for this workspace's runs.
      </Typography.Text>
      <Spin spinning={loading}>
        <List
          size="small"
          dataSource={deliveries}
          renderItem={(delivery) => {
            const statusMeta = STATUS_META[delivery.status];
            const StatusIcon = statusMeta.icon;
            const channelMeta = CHANNEL_META[delivery.channelType];
            const ChannelIcon = channelMeta.icon;
            return (
              <List.Item
                actions={
                  delivery.status === "FAILED"
                    ? [
                        <Button
                          key="retry"
                          size="small"
                          icon={<SyncOutlined />}
                          loading={retryingId === delivery.id}
                          onClick={() => retryDelivery(delivery.id)}
                        >
                          Retry
                        </Button>,
                      ]
                    : undefined
                }
              >
                <List.Item.Meta
                  avatar={<StatusIcon style={{ color: statusMeta.iconColor, fontSize: 20 }} />}
                  title={
                    <>
                      <Tag color={statusMeta.tagColor} icon={<StatusIcon />}>
                        {statusMeta.label}
                      </Tag>
                      <Tag color={channelMeta.color} icon={<ChannelIcon />}>
                        {channelMeta.label}
                      </Tag>
                      <Typography.Text>{delivery.configurationName}</Typography.Text>
                      <Typography.Text type="secondary" style={{ marginLeft: 8, fontSize: 12 }}>
                        Job #{delivery.jobId} · {new Date(delivery.createdDate).toLocaleString()}
                        {delivery.attemptCount > 1 && ` · ${delivery.attemptCount} attempts`}
                      </Typography.Text>
                    </>
                  }
                  description={
                    delivery.status === "FAILED" && delivery.lastError ? (
                      <Typography.Text type="danger" style={{ fontSize: 12 }}>
                        {delivery.lastError.length > 200 ? `${delivery.lastError.slice(0, 200)}...` : delivery.lastError}
                      </Typography.Text>
                    ) : undefined
                  }
                />
              </List.Item>
            );
          }}
        />
      </Spin>
    </SettingsSection>
  );
};
