import { ExclamationCircleOutlined, InfoCircleOutlined, WarningOutlined } from "@ant-design/icons";
import { useEffect, useState } from "react";
import { fetchPublicLoginAnnouncements } from "../announcementService";
import { formatAnnouncementTiming, sortAnnouncements } from "../announcementUtils";
import type { AnnouncementSeverity, PublicAnnouncementItem } from "../types";
import { AnnouncementMarkdown } from "./AnnouncementMarkdown";
import "./LoginAnnouncements.css";

function LoginSeverityIcon({ severity }: { severity: AnnouncementSeverity }) {
  switch (severity) {
    case "CRITICAL":
      return <ExclamationCircleOutlined className="login-announcement-icon" aria-hidden="true" />;
    case "WARNING":
      return <WarningOutlined className="login-announcement-icon" aria-hidden="true" />;
    case "INFO":
    default:
      return <InfoCircleOutlined className="login-announcement-icon" aria-hidden="true" />;
  }
}

export function LoginAnnouncements() {
  const [announcements, setAnnouncements] = useState<PublicAnnouncementItem[]>([]);
  const [serverNowMs, setServerNowMs] = useState<number>(() => Date.now());

  useEffect(() => {
    let cancelled = false;
    fetchPublicLoginAnnouncements()
      .then(({ announcements: items, serverTimeMs }) => {
        if (cancelled) return;
        setAnnouncements(sortAnnouncements(items));
        setServerNowMs(serverTimeMs);
      })
      .catch(() => {
        // Login screen must remain fully usable if public announcements fail to load
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (announcements.length === 0) {
    return null;
  }

  return (
    <div className="login-announcements" role="region" aria-label="Platform announcements">
      {announcements.map((item) => {
        const timingLabel = formatAnnouncementTiming(item, serverNowMs);
        return (
          <div
            key={item.id}
            className={`login-announcement-card login-announcement-card--${item.severity.toLowerCase()}`}
          >
            <LoginSeverityIcon severity={item.severity} />
            <div className="login-announcement-body">
              <div className="login-announcement-header">
                <strong className="login-announcement-title">{item.title}</strong>
                {timingLabel && <span className="login-announcement-timing">{timingLabel}</span>}
              </div>
              <AnnouncementMarkdown content={item.message} className="login-announcement-message" />
            </div>
          </div>
        );
      })}
    </div>
  );
}
