import { useState } from "react";
import { Badge, Tooltip } from "antd";
import { BellOutlined } from "@ant-design/icons";
import { useAnnouncements } from "./useAnnouncements";
import AnnouncementDrawer from "./AnnouncementDrawer";
import "./AnnouncementBell.css";

export interface AnnouncementBellProps {
  organizationId?: string | null;
}

export const AnnouncementBell = ({ organizationId }: AnnouncementBellProps) => {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const { announcements, dismissedAnnouncements, restoreAnnouncement, dismissAnnouncement } =
    useAnnouncements(organizationId);

  const activeCount = announcements.length;

  return (
    <>
      <Tooltip title="Announcements & Notices" placement="bottom">
        <button
          type="button"
          className="announcement-bell-button"
          aria-label="Announcements & Notices"
          onClick={() => setDrawerOpen(true)}
          data-testid="announcement-bell-button"
        >
          <Badge count={activeCount} size="small" offset={[2, -2]}>
            <BellOutlined className="announcement-bell-icon" />
          </Badge>
        </button>
      </Tooltip>
      <AnnouncementDrawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        activeAnnouncements={announcements}
        dismissedAnnouncements={dismissedAnnouncements}
        onRestore={restoreAnnouncement}
        onDismiss={dismissAnnouncement}
      />
    </>
  );
};

export default AnnouncementBell;
