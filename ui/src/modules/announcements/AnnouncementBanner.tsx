import { Alert, Button, Typography, Tag } from "antd";
import ReactMarkdown from "react-markdown";
import rehypeSanitize from "rehype-sanitize";
import {
  InfoCircleOutlined,
  WarningOutlined,
  AlertOutlined,
  CloseOutlined,
  ClockCircleOutlined,
} from "@ant-design/icons";
import { Announcement, AnnouncementSeverity, PublicAnnouncement } from "./announcementTypes";
import "./AnnouncementBanner.css";

const { Text } = Typography;

const SEVERITY_CONFIG: Record<AnnouncementSeverity, { icon: React.ReactNode; type: "info" | "warning" | "error" }> = {
  info: { icon: <InfoCircleOutlined />, type: "info" },
  warning: { icon: <WarningOutlined />, type: "warning" },
  critical: { icon: <AlertOutlined />, type: "error" },
};

function formatSchedule(startsAt?: string | null, endsAt?: string | null): string | null {
  if (!startsAt && !endsAt) return null;
  const formatDate = (iso: string) => {
    try {
      const d = new Date(iso);
      return d.toUTCString().replace("GMT", "UTC");
    } catch {
      return iso;
    }
  };

  if (startsAt && endsAt) {
    return `${formatDate(startsAt)} – ${formatDate(endsAt)}`;
  }
  if (startsAt) {
    return `From ${formatDate(startsAt)}`;
  }
  return `Until ${formatDate(endsAt!)}`;
}

export interface AnnouncementBannerProps {
  announcement: Announcement | PublicAnnouncement;
  onDismiss?: (announcement: Announcement | PublicAnnouncement) => void;
  className?: string;
}

export const AnnouncementBanner = ({ announcement, onDismiss, className }: AnnouncementBannerProps) => {
  const config = SEVERITY_CONFIG[announcement.severity] || SEVERITY_CONFIG.info;
  const scheduleText = formatSchedule(announcement.startsAt, announcement.endsAt);

  return (
    <Alert
      className={`announcement-banner announcement-banner-${announcement.severity} ${className ?? ""}`}
      type={config.type}
      icon={config.icon}
      showIcon
      title={
        <div className="announcement-banner-header">
          <div className="announcement-title-group">
            <Text strong className="announcement-title">
              {announcement.title}
            </Text>
            {scheduleText && (
              <Tag icon={<ClockCircleOutlined />} className="announcement-schedule-tag">
                {scheduleText}
              </Tag>
            )}
          </div>
          {announcement.dismissible && onDismiss && (
            <Button
              type="text"
              size="small"
              className="announcement-dismiss-btn"
              icon={<CloseOutlined />}
              onClick={() => onDismiss(announcement)}
              aria-label="Dismiss banner"
            />
          )}
        </div>
      }
      description={
        <div className="announcement-message">
          <ReactMarkdown rehypePlugins={[rehypeSanitize]}>{announcement.message}</ReactMarkdown>
        </div>
      }
    />
  );
};

export default AnnouncementBanner;
