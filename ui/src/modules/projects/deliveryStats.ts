export type DeliveryJob = {
  status: string;
  createdDate?: string | null;
  updatedDate?: string | null;
  approvedAt?: string | null;
};

export type Delivery = {
  /** Runs started in the window, all of them, even when only some were read. */
  runs: number;
  /** Percentage of finished runs that did not fail, or null when none has finished. */
  succeededPercent: number | null;
  /** From a run starting to someone approving it, so it includes the plan. Null when none was approved. */
  medianApproveMs: number | null;
  /** From a run starting to it finishing, so it includes any wait for approval. Null when none finished. */
  medianRunMs: number | null;
  /** True when the window held more runs than were read, so the medians describe the latest ones. */
  partial: boolean;
};

// Rejected and cancelled runs were stopped by a person, so they say nothing about whether delivery works.
const SUCCEEDED = new Set(["completed", "noChanges"]);
const FINISHED = new Set(["completed", "noChanges", "failed"]);

const time = (iso?: string | null) => (iso ? new Date(iso).getTime() : NaN);

export function median(values: number[]): number | null {
  const sorted = values.filter((value) => Number.isFinite(value) && value >= 0).sort((a, b) => a - b);
  if (sorted.length === 0) return null;
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
}

export function summarizeDelivery(jobs: DeliveryJob[], total: number): Delivery {
  const finished = jobs.filter((job) => FINISHED.has(job.status));
  const succeeded = finished.filter((job) => SUCCEEDED.has(job.status)).length;
  return {
    runs: total,
    succeededPercent: finished.length === 0 ? null : Math.round((succeeded / finished.length) * 100),
    medianApproveMs: median(jobs.map((job) => time(job.approvedAt) - time(job.createdDate))),
    medianRunMs: median(finished.map((job) => time(job.updatedDate) - time(job.createdDate))),
    partial: total > jobs.length,
  };
}

/** "45 s", "22 min", "3 h", "2 d": a span in the unit a person would say it in. */
export function formatSpan(ms: number | null): string {
  if (ms === null) return "-";
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${seconds} s`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.round(minutes / 60);
  if (hours < 48) return `${hours} h`;
  return `${Math.round(hours / 24)} d`;
}
