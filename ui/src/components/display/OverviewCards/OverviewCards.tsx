import { Card, Typography } from "antd";
import { ReactNode } from "react";
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  MinusCircleOutlined,
  SyncOutlined,
} from "@ant-design/icons";
import { Link } from "react-router-dom";
import { RingSegment, StatusCounts, ringGradient, ringSegments, segmentColor } from "./overviewModel";
import "./OverviewCards.css";

export type Tone = "error" | "warning" | "accent" | "success";

/** The badge for the tone of a run status: red cross, amber clock, green check, spinner, or a dash. */
export function badgeForStatusTone(tone: "success" | "danger" | "info" | "warning" | "neutral"): {
  tone: Tone;
  icon: ReactNode;
} {
  switch (tone) {
    case "danger":
      return { tone: "error", icon: <CloseCircleOutlined /> };
    case "warning":
      return { tone: "warning", icon: <ClockCircleOutlined /> };
    case "success":
      return { tone: "success", icon: <CheckCircleOutlined /> };
    case "info":
      return { tone: "accent", icon: <SyncOutlined /> };
    default:
      return { tone: "accent", icon: <MinusCircleOutlined /> };
  }
}

/** A tinted square with an icon in it: the status of a row or a card at a glance. */
export function StatusBadge({ tone, children }: { tone: Tone; children: ReactNode }) {
  return (
    <span className={`overview-badge overview-badge-${tone}`} aria-hidden>
      {children}
    </span>
  );
}

/** A ring of the workspaces by the state of their last run, with the legend next to it. */
export function HealthRing({
  title,
  total,
  status,
  note,
  hrefFor,
}: {
  title: string;
  total: number;
  status: StatusCounts;
  note?: ReactNode;
  /** Where the workspaces in a state are listed, when they are. A state without a link is plain text. */
  hrefFor?: (key: RingSegment["key"]) => string | undefined;
}) {
  const segments = ringSegments(total, status).filter((segment) => segment.count > 0);
  return (
    <Card className="overview-card" aria-label={title}>
      <h3 className="overview-card-title">{title}</h3>
      <div className="overview-health">
        <div
          className="overview-ring"
          style={{ background: ringGradient(ringSegments(total, status)) }}
          role="img"
          aria-label={segments.map((segment) => `${segment.count} ${segment.label}`).join(", ") || "No workspaces"}
        >
          <div>
            <strong>{total}</strong>
            <span>workspaces</span>
          </div>
        </div>
        <ul className="overview-legend">
          {segments.map((segment) => (
            <li key={segment.key}>
              <span className="overview-swatch" style={{ background: segmentColor(segment.key) }} />
              {(() => {
                const text = `${segment.count} ${segment.label}`;
                const href = hrefFor?.(segment.key);
                return href ? <Link to={href}>{text}</Link> : text;
              })()}
            </li>
          ))}
        </ul>
      </div>
      {note && <Typography.Text type="secondary">{note}</Typography.Text>}
    </Card>
  );
}

export type Action = {
  key: string;
  icon: ReactNode;
  tone: Tone;
  count: number;
  label: string;
  cta: string;
  to: string;
};

/** A count that needs a person, and where to go for it. A count of zero is quiet and has no link. */
export function ActionCard({ action }: { action: Action }) {
  return (
    <Card className="overview-card overview-action">
      <StatusBadge tone={action.tone}>{action.icon}</StatusBadge>
      <span className={`overview-count${action.count ? ` overview-count-${action.tone}` : ""}`}>{action.count}</span>
      <span className="overview-action-label">{action.label}</span>
      {action.count > 0 ? <Link to={action.to}>{action.cta} &rarr;</Link> : <span />}
    </Card>
  );
}
