export type StatusTone = "success" | "danger" | "info" | "warning" | "neutral";

const LABELS: Record<string, { label: string; tone: StatusTone }> = {
  completed: { label: "Applied", tone: "success" },
  noChanges: { label: "No changes", tone: "success" },
  failed: { label: "Failed", tone: "danger" },
  running: { label: "Running", tone: "info" },
  waitingApproval: { label: "Waiting approval", tone: "warning" },
  pending: { label: "Queued", tone: "neutral" },
  approved: { label: "Queued", tone: "neutral" },
  queue: { label: "Queued", tone: "neutral" },
  NeverExecuted: { label: "Never run", tone: "neutral" },
};

// The wording and colour of a workspace's last run status, as the design shows it.
export function describeStatus(status?: string): { label: string; tone: StatusTone } {
  return (status && LABELS[status]) || { label: status ?? "Unknown", tone: "neutral" };
}

// A run that failed or waits for a person is shown as such; otherwise a policy violation is the thing to flag.
export function describeWorkspace(
  lastJobStatus?: string,
  policyComplianceStatus?: string
): { label: string; tone: StatusTone } {
  if (policyComplianceStatus === "NON_COMPLIANT" && lastJobStatus !== "failed" && lastJobStatus !== "waitingApproval") {
    return { label: "Non-compliant", tone: "warning" };
  }
  return describeStatus(lastJobStatus);
}
