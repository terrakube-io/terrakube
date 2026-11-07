import { useState } from "react";
import { Alert, Button, Typography, Tag, Tooltip } from "antd";
import ReactMarkdown from "react-markdown";
import rehypeSanitize from "rehype-sanitize";
import {
  InfoCircleOutlined,
  WarningOutlined,
  AlertOutlined,
  CloseOutlined,
  ClockCircleOutlined,
  DownOutlined,
  UpOutlined,
} from "@ant-design/icons";
import { Announcement, AnnouncementSeverity, PublicAnnouncement } from "./announcementTypes";
import { formatSchedule, formatFullSchedule } from "./announcementStorage";
import "./AnnouncementBanner.css";

const { Text } = Typography;

const SEVERITY_CONFIG: Record<AnnouncementSeverity, { icon: React.ReactNode; type: "info" | "warning" | "error" }> = {
  info: { icon: <InfoCircleOutlined />, type: "info" },
  warning: { icon: <WarningOutlined />, type: "warning" },
  critical: { icon: <AlertOutlined />, type: "error" },
};

export interface AnnouncementBannerProps {
  announcement: Announcement | PublicAnnouncement;
  onDismiss?: (announcement: Announcement | PublicAnnouncement) => void;
  className?: string;
  defaultExpanded?: boolean;
}

export const AnnouncementBanner = ({
  announcement,
  onDismiss,
  className,
  defaultExpanded = false,
}: AnnouncementBannerProps) => {
  const [isExpanded, setIsExpanded] = useState(defaultExpanded);
  const config = SEVERITY_CONFIG[announcement.severity] || SEVERITY_CONFIG.info;
  const scheduleText = formatSchedule(announcement.startsAt, announcement.endsAt);
  const fullScheduleText = formatFullSchedule(announcement.startsAt, announcement.endsAt);
  const hasScope = "scope" in announcement && Boolean(announcement.scope);
  const isGlobal = hasScope && (announcement as Announcement).scope === "GLOBAL";

  return (
    <Alert
      className={`announcement-banner announcement-banner-${announcement.severity} ${className ?? ""}`}
      type={config.type}
      icon={config.icon}
      showIcon
      title={
        <div className="announcement-banner-header">
          <div className="announcement-title-group">
            {hasScope && (
              <Tag color={isGlobal ? "blue" : "purple"} className="announcement-scope-tag">
                {isGlobal ? "Platform" : "Organization"}
              </Tag>
            )}
            <Text strong className="announcement-title">
              {announcement.title}
            </Text>
            {scheduleText && (
              <Tooltip title={fullScheduleText ?? scheduleText}>
                <Tag icon={<ClockCircleOutlined />} className="announcement-schedule-tag">
                  {scheduleText}
                </Tag>
              </Tooltip>
            )}
          </div>
          <div className="announcement-actions-group">
            <Button
              type="link"
              size="small"
              className="announcement-details-btn"
              onClick={() => setIsExpanded(!isExpanded)}
              icon={isExpanded ? <UpOutlined /> : <DownOutlined />}
            >
              {isExpanded ? "Hide details" : "Details"}
            </Button>
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
        </div>
      }
      description={
        isExpanded ? (
          <div className="announcement-message" data-testid="announcement-description">
            <ReactMarkdown rehypePlugins={[rehypeSanitize]}>{announcement.message}</ReactMarkdown>
          </div>
        ) : null
      }
    />
  );
};

export default AnnouncementBanner;
