import { useEffect, useState } from "react";
import { PublicAnnouncement } from "@/modules/announcements/announcementTypes";
import { fetchPublicLoginAnnouncements } from "@/modules/announcements/announcementService";
import { AnnouncementTray } from "@/modules/announcements/AnnouncementTray";
import "./LoginAnnouncementBanner.css";

export const LoginAnnouncementBanner = () => {
  const [announcements, setAnnouncements] = useState<PublicAnnouncement[]>([]);

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
    return () => {
      active = false;
    };
  }, []);

  if (announcements.length === 0) {
    return null;
  }

  return (
    <div className="login-announcements-wrapper" data-testid="login-announcements">
      <AnnouncementTray announcements={announcements} />
    </div>
  );
};

export default LoginAnnouncementBanner;

