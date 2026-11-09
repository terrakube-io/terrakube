import { DateTime } from "luxon";
import { ProjectSummaryModel } from "@/domain/types";

export type HealthKey = "completed" | "failed" | "running" | "queued" | "waiting" | "never";

export type HealthSegment = { key: HealthKey; label: string; count: number };

// Same order as the legend in the design: the colours are always paired with a label, and queued is hatched.
export function healthSegments(summary: ProjectSummaryModel): HealthSegment[] {
  return [
    { key: "completed", label: "Completed", count: summary.completedCount },
    { key: "failed", label: "Failed", count: summary.failedCount },
    { key: "running", label: "Running", count: summary.runningCount },
    { key: "queued", label: "Queued", count: summary.queuedCount },
    { key: "waiting", label: "Waiting approval", count: summary.waitingApprovalCount },
    { key: "never", label: "Never run", count: summary.neverRunCount },
  ];
}

export type AttentionBadge = { tone: "danger" | "warning"; text: string } | null;

// Red when runs failed or wait for a person, amber when the only issue is policy, nothing when all is well.
export function attentionBadge(summary: ProjectSummaryModel): AttentionBadge {
  const runIssues = summary.failedCount + summary.waitingApprovalCount;
  if (runIssues > 0) return { tone: "danger", text: `${summary.attentionCount} need attention` };
  if (summary.nonCompliantCount > 0) return { tone: "warning", text: `${summary.nonCompliantCount} non-compliant` };
  return null;
}

// The count in the table's Attention column: 2 or more is red, one is amber, none is grey.
export function attentionTone(count: number): "danger" | "warning" | "neutral" {
  if (count >= 2) return "danger";
  if (count === 1) return "warning";
  return "neutral";
}

export type PolicyRollup = { tone: "danger" | "success" | "neutral"; text: string };

export function policyRollup(summary: ProjectSummaryModel): PolicyRollup {
  if (summary.nonCompliantCount > 0) return { tone: "danger", text: `${summary.nonCompliantCount} non-compliant` };
  if (summary.compliantCount + summary.exemptedCount > 0) return { tone: "success", text: "Compliant" };
  return { tone: "neutral", text: "Not evaluated" };
}

// "just now", "4m ago", "3h ago", "2d ago": compact enough for a card footer or a table cell.
export function shortAgo(iso?: string | null, now: DateTime = DateTime.now()): string {
  const duration = shortDuration(iso, now);
  if (duration === null) return "-";
  return duration === "now" ? "just now" : `${duration} ago`;
}

// "6m", "3h", "2d": how long since a moment, or null without one.
export function shortDuration(iso?: string | null, now: DateTime = DateTime.now()): string | null {
  if (!iso) return null;
  const then = DateTime.fromISO(iso);
  if (!then.isValid) return null;
  const seconds = Math.max(0, Math.round(now.diff(then, "seconds").seconds));
  if (seconds < 60) return "now";
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h`;
  return `${Math.floor(hours / 24)}d`;
}

export type ProjectChip = "all" | "attention" | "mine" | "empty" | "stale";

// Cards for a small catalogue and a table for a large one, as in the design. A saved choice always wins.
export const CARDS_UNTIL = 24;

export function defaultView(total: number): "cards" | "table" {
  return total <= CARDS_UNTIL ? "cards" : "table";
}
