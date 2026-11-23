import {
  CloseOutlined,
  DownOutlined,
  ExclamationCircleOutlined,
  InfoCircleOutlined,
  UpOutlined,
  WarningOutlined,
} from "@ant-design/icons";
import { useState } from "react";
import { formatAnnouncementTiming } from "../announcementUtils";
import type { AnnouncementItem, AnnouncementSeverity } from "../types";
import { useAnnouncements } from "../useAnnouncements";
import { AnnouncementMarkdown } from "./AnnouncementMarkdown";
import "./AnnouncementBanner.css";

function SeverityIcon({ severity }: { severity: AnnouncementSeverity }) {
  switch (severity) {
    case "CRITICAL":
      return <ExclamationCircleOutlined className="announcement-banner-icon" aria-hidden="true" />;
    case "WARNING":
      return <WarningOutlined className="announcement-banner-icon" aria-hidden="true" />;
    case "INFO":
    default:
      return <InfoCircleOutlined className="announcement-banner-icon" aria-hidden="true" />;
  }
}

function AnnouncementRow({
  item,
  serverNowMs,
  onDismiss,
  extraControls,
}: {
  item: AnnouncementItem;
  serverNowMs: number;
  onDismiss: (item: AnnouncementItem) => void;
  extraControls?: React.ReactNode;
}) {
  const timingLabel = formatAnnouncementTiming(item, serverNowMs);
  const severityClass = `announcement-banner-row--${item.severity.toLowerCase()}`;

  return (
    <div
      className={`announcement-banner-row ${severityClass}`}
      data-testid={`announcement-banner-${item.id}`}
      data-severity={item.severity}
    >
      <div className="announcement-banner-main">
        <SeverityIcon severity={item.severity} />
        <div className="announcement-banner-text">
          <div className="announcement-banner-heading">
            <span className="announcement-banner-scope-pill">
              {item.scope === "GLOBAL" ? "Platform" : item.organizationName || "Organization"}
            </span>
            <strong className="announcement-banner-title">{item.title}</strong>
            {timingLabel && <span className="announcement-banner-timing">{timingLabel}</span>}
          </div>
          <AnnouncementMarkdown content={item.message} className="announcement-banner-message" />
        </div>
      </div>
      <div className="announcement-banner-actions">
        {extraControls}
        {item.dismissible && (
          <button
            type="button"
            className="announcement-banner-dismiss"
            aria-label={`Dismiss announcement: ${item.title}`}
            onClick={() => onDismiss(item)}
          >
            <CloseOutlined aria-hidden="true" />
          </button>
        )}
      </div>
    </div>
  );
}

export function AnnouncementBanner() {
  const { visibleAnnouncements, serverNowMs, dismiss } = useAnnouncements();
  const [expanded, setExpanded] = useState(false);

  if (visibleAnnouncements.length === 0) {
    return null;
  }

  const [primary, ...rest] = visibleAnnouncements;
  const remainingCount = rest.length;

  return (
    <div className="announcement-banner-tray" role="region" aria-label="Active announcements">
      <AnnouncementRow
        item={primary}
        serverNowMs={serverNowMs}
        onDismiss={dismiss}
        extraControls={
          remainingCount > 0 ? (
            <button
              type="button"
              className="announcement-banner-toggle"
              aria-expanded={expanded}
              onClick={() => setExpanded((prev) => !prev)}
            >
              <span>
                View {remainingCount} more active announcement{remainingCount === 1 ? "" : "s"}
              </span>
              {expanded ? <UpOutlined aria-hidden="true" /> : <DownOutlined aria-hidden="true" />}
            </button>
          ) : undefined
        }
      />
      {expanded && remainingCount > 0 && (
        <div className="announcement-banner-stack">
          {rest.map((item) => (
            <AnnouncementRow key={item.id} item={item} serverNowMs={serverNowMs} onDismiss={dismiss} />
          ))}
        </div>
      )}
    </div>
  );
}
