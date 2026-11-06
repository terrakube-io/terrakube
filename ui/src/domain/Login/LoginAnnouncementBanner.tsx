import { useEffect, useState, useCallback } from "react";
import { PublicAnnouncement } from "@/modules/announcements/announcementTypes";
import { fetchPublicLoginAnnouncements } from "@/modules/announcements/announcementService";
import { AnnouncementTray } from "@/modules/announcements/AnnouncementTray";
import "./LoginAnnouncementBanner.css";

const POLL_INTERVAL_MS = 60_000;

export const LoginAnnouncementBanner = () => {
  const [announcements, setAnnouncements] = useState<PublicAnnouncement[]>([]);

  const loadAnnouncements = useCallback(async () => {
    try {
      const data = await fetchPublicLoginAnnouncements();
      setAnnouncements(data);
    } catch {
      // Silently ignore network failures on login screen
    }
  }, []);

  useEffect(() => {
    let active = true;
    fetchPublicLoginAnnouncements()
      .then((data) => {
        if (active) {
          setAnnouncements(data);
        }
      })
      .catch(() => {
        // Silently ignore network failures on login screen
      });

    const intervalId = setInterval(() => {
      loadAnnouncements();
    }, POLL_INTERVAL_MS);

    return () => {
      active = false;
      clearInterval(intervalId);
    };
  }, [loadAnnouncements]);

  if (announcements.length === 0) {
    return null;
  }

  return (
    <div className="login-announcements-wrapper" data-testid="login-announcements">
      <AnnouncementTray announcements={announcements} defaultExpanded={true} />
    </div>
  );
};

export default LoginAnnouncementBanner;
