import { useState, useMemo } from "react";
import { Button } from "antd";
import { DownOutlined, UpOutlined } from "@ant-design/icons";
import { Announcement, PublicAnnouncement } from "./announcementTypes";
import { AnnouncementBanner } from "./AnnouncementBanner";
import { isAnnouncementDismissed, dismissAnnouncement } from "./announcementStorage";
import "./AnnouncementTray.css";

export interface AnnouncementTrayProps {
  announcements: (Announcement | PublicAnnouncement)[];
  onDismissOverride?: (announcement: Announcement | PublicAnnouncement) => void;
}

export const AnnouncementTray = ({ announcements, onDismissOverride }: AnnouncementTrayProps) => {
  const [dismissedMap, setDismissedMap] = useState<Record<string, boolean>>({});
  const [isExpanded, setIsExpanded] = useState(false);

  const visibleAnnouncements = useMemo(() => {
    return announcements.filter((a) => !dismissedMap[a.id] && !isAnnouncementDismissed(a));
  }, [announcements, dismissedMap]);

  const handleDismiss = (a: Announcement | PublicAnnouncement) => {
    dismissAnnouncement(a);
    setDismissedMap((prev) => ({ ...prev, [a.id]: true }));
    if (onDismissOverride) {
      onDismissOverride(a);
    }
  };

  if (visibleAnnouncements.length === 0) {
    return null;
  }

  const topBanner = visibleAnnouncements[0];
  const remainingBanners = visibleAnnouncements.slice(1);

  return (
    <div className="announcement-tray" data-testid="announcement-tray">
      <AnnouncementBanner announcement={topBanner} onDismiss={handleDismiss} />
      {remainingBanners.length > 0 && (
        <>
          <div className="announcement-tray-collapsible">
            <Button
              type="link"
              size="small"
              className="announcement-tray-toggle-btn"
              onClick={() => setIsExpanded(!isExpanded)}
              icon={isExpanded ? <UpOutlined /> : <DownOutlined />}
            >
              {isExpanded
                ? "Hide additional announcements"
                : `View ${remainingBanners.length} more active announcement${remainingBanners.length > 1 ? "s" : ""}`}
            </Button>
          </div>
          {isExpanded && (
            <div className="announcement-tray-stack">
              {remainingBanners.map((a) => (
                <AnnouncementBanner key={a.id} announcement={a} onDismiss={handleDismiss} />
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
};

export default AnnouncementTray;
