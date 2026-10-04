import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  LoadingOutlined,
  SyncOutlined,
} from "@ant-design/icons";
import { Button, List, Spin, Tag, Typography, message } from "antd";
import { useEffect, useState } from "react";
import axiosInstance, { getErrorMessage } from "@/config/axiosConfig";
import { NotificationChannelType } from "../types";
import { CHANNEL_META } from "./channelMeta";
import SettingsSection from "@/components/settings/SettingsSection/SettingsSection";
import "./Notifications.css";

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

// Status is semantic color plus a text label (DESIGN.md, The Status Is Semantic Rule).
const STATUS_META: Record<DeliveryStatus, { color: string; icon: typeof CheckCircleOutlined; label: string }> = {
  SENT: { color: "success", icon: CheckCircleOutlined, label: "Sent" },
  PENDING: { color: "warning", icon: ClockCircleOutlined, label: "Pending" },
  SENDING: { color: "processing", icon: LoadingOutlined, label: "Sending" },
  FAILED: { color: "error", icon: CloseCircleOutlined, label: "Failed" },
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
    <SettingsSection
      maxWidth="100%"
      title="Recent deliveries"
      description={`The last ${deliveries.length} notification attempts for this workspace's runs.`}
    >
      <Spin spinning={loading}>
        <List
          size="small"
          dataSource={deliveries}
          renderItem={(delivery) => {
            const statusMeta = STATUS_META[delivery.status];
            const StatusIcon = statusMeta.icon;
            const channelMeta = CHANNEL_META[delivery.channelType];
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
                          aria-label={`Retry delivery to ${delivery.configurationName}`}
                          onClick={() => retryDelivery(delivery.id)}
                        >
                          Retry
                        </Button>,
                      ]
                    : undefined
                }
              >
                <List.Item.Meta
                  title={
                    <>
                      <Tag color={statusMeta.color} icon={<StatusIcon />}>
                        {statusMeta.label}
                      </Tag>
                      <Tag>{channelMeta.label}</Tag>
                      <Typography.Text>{delivery.configurationName}</Typography.Text>
                      <Typography.Text type="secondary" className="notification-meta-text notification-delivery-meta">
                        Job #{delivery.jobId} · {new Date(delivery.createdDate).toLocaleString()}
                        {delivery.attemptCount > 1 && ` · ${delivery.attemptCount} attempts`}
                      </Typography.Text>
                    </>
                  }
                  description={
                    delivery.status === "FAILED" && delivery.lastError ? (
                      <Typography.Text type="danger" className="notification-meta-text">
                        {delivery.lastError.length > 200
                          ? `${delivery.lastError.slice(0, 200)}...`
                          : delivery.lastError}
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
