import { Drawer, Tabs, Button, Tag, Empty, Typography, Tooltip } from "antd";
import { UndoOutlined, CloseOutlined } from "@ant-design/icons";
import ReactMarkdown from "react-markdown";
import rehypeSanitize from "rehype-sanitize";
import { Announcement } from "./announcementTypes";
import { formatSchedule, formatFullSchedule } from "./announcementStorage";
import "./AnnouncementDrawer.css";

const { Text } = Typography;

export interface AnnouncementDrawerProps {
  open: boolean;
  onClose: () => void;
  activeAnnouncements: Announcement[];
  dismissedAnnouncements: Announcement[];
  onRestore: (id: string) => void;
  onDismiss: (announcement: Announcement) => void;
}

export const AnnouncementDrawer = ({
  open,
  onClose,
  activeAnnouncements,
  dismissedAnnouncements,
  onRestore,
  onDismiss,
}: AnnouncementDrawerProps) => {
  const items = [
    {
      key: "active",
      label: `Active (${activeAnnouncements.length})`,
      children: (
        <div className="announcement-drawer-list">
          {activeAnnouncements.length === 0 ? (
            <Empty description="No active notices" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          ) : (
            activeAnnouncements.map((a) => {
              const schedule = formatSchedule(a.startsAt, a.endsAt);
              const fullSchedule = formatFullSchedule(a.startsAt, a.endsAt);
              const isGlobal = a.scope === "GLOBAL";
              const isPinnedToBanner = a.severity === "critical" || a.severity === "warning";
              return (
                <div
                  key={a.id}
                  className={`announcement-drawer-card announcement-card-${a.severity}`}
                  data-testid={`drawer-active-${a.id}`}
                >
                  <div className="announcement-drawer-card-header">
                    <div className="announcement-drawer-badges">
                      <Tag color={isGlobal ? "blue" : "purple"}>{isGlobal ? "Platform" : "Organization"}</Tag>
                      <Tag
                        color={a.severity === "critical" ? "error" : a.severity === "warning" ? "warning" : "default"}
                      >
                        {a.severity.toUpperCase()}
                      </Tag>
                      {isPinnedToBanner && (
                        <Tag color="orange" className="announcement-pinned-tag">
                          Pinned to Banner
                        </Tag>
                      )}
                    </div>
                    {a.dismissible && (
                      <Button
                        type="text"
                        size="small"
                        icon={<CloseOutlined />}
                        onClick={() => onDismiss(a)}
                        aria-label={`Dismiss ${a.title}`}
                      />
                    )}
                  </div>
                  <Text strong className="announcement-drawer-title">
                    {a.title}
                  </Text>
                  {schedule && (
                    <div className="announcement-drawer-schedule">
                      <Tooltip title={fullSchedule ?? schedule}>
                        <Text type="secondary">{schedule}</Text>
                      </Tooltip>
                    </div>
                  )}
                  <div className="announcement-drawer-message">
                    <ReactMarkdown rehypePlugins={[rehypeSanitize]}>{a.message}</ReactMarkdown>
                  </div>
                </div>
              );
            })
          )}
        </div>
      ),
    },
    {
      key: "dismissed",
      label: `Dismissed (${dismissedAnnouncements.length})`,
      children: (
        <div className="announcement-drawer-list">
          {dismissedAnnouncements.length === 0 ? (
            <Empty description="No dismissed notices" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          ) : (
            dismissedAnnouncements.map((a) => {
              const isGlobal = a.scope === "GLOBAL";
              return (
                <div
                  key={a.id}
                  className="announcement-drawer-card announcement-card-dismissed"
                  data-testid={`drawer-dismissed-${a.id}`}
                >
                  <div className="announcement-drawer-card-header">
                    <div className="announcement-drawer-badges">
                      <Tag color={isGlobal ? "blue" : "purple"}>{isGlobal ? "Platform" : "Organization"}</Tag>
                      <Tag>{a.severity.toUpperCase()}</Tag>
                      <Tag>Dismissed</Tag>
                    </div>
                    <Button
                      type="link"
                      size="small"
                      icon={<UndoOutlined />}
                      onClick={() => onRestore(a.id)}
                      aria-label={`Restore ${a.title}`}
                    >
                      Restore to Banner
                    </Button>
                  </div>
                  <Text strong className="announcement-drawer-title">
                    {a.title}
                  </Text>
                  <div className="announcement-drawer-message">
                    <ReactMarkdown rehypePlugins={[rehypeSanitize]}>{a.message}</ReactMarkdown>
                  </div>
                </div>
              );
            })
          )}
        </div>
      ),
    },
  ];

  return (
    <Drawer
      title="Announcements & Notices"
      placement="right"
      size="default"
      open={open}
      onClose={onClose}
      className="announcement-drawer"
    >
      <Tabs defaultActiveKey="active" items={items} />
    </Drawer>
  );
};

export default AnnouncementDrawer;
