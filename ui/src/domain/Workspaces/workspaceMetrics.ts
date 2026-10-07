import { DateTime } from "luxon";
import { formatDuration } from "@/modules/utils/dates";
import { FlatJob, JobStatus } from "../types";

// Statuses after which a run's updatedDate marks its end.
export const FINISHED_JOB_STATUSES: string[] = [
  JobStatus.Completed,
  JobStatus.NoChanges,
  JobStatus.NotExecuted,
  JobStatus.Rejected,
  JobStatus.Cancelled,
  JobStatus.Failed,
];

const SUCCESSFUL_JOB_STATUSES: string[] = [JobStatus.Completed, JobStatus.NoChanges];
// Rejected, cancelled and not-executed runs include time spent waiting for approval, not running.
const TIMED_JOB_STATUSES: string[] = [...SUCCESSFUL_JOB_STATUSES, JobStatus.Failed];

export type WorkspaceMetrics = {
  runCount: number;
  averageDuration: string | null;
  failedRuns: number;
  lastSuccessfulRun: string | null;
};

// Metrics over the runs already loaded on the workspace page; null when none of them has finished.
export function computeWorkspaceMetrics(jobs: FlatJob[]): WorkspaceMetrics | null {
  const finished = jobs.filter((job) => FINISHED_JOB_STATUSES.includes(job.status));
  if (finished.length === 0) return null;

  const durations = finished
    .filter((job) => TIMED_JOB_STATUSES.includes(job.status))
    .map((job) => {
      const start = DateTime.fromISO(job.createdDate ?? "");
      const end = DateTime.fromISO(job.updatedDate ?? "");
      return start.isValid && end.isValid && end >= start ? end.toMillis() - start.toMillis() : null;
    })
    .filter((ms): ms is number => ms !== null);
  const averageMs = durations.length ? durations.reduce((sum, ms) => sum + ms, 0) / durations.length : null;

  const lastSuccessfulRun =
    finished
      .filter((job) => SUCCESSFUL_JOB_STATUSES.includes(job.status) && job.updatedDate)
      .map((job) => job.updatedDate!)
      .sort((a, b) => DateTime.fromISO(b).toMillis() - DateTime.fromISO(a).toMillis())[0] ?? null;

  return {
    runCount: jobs.length,
    averageDuration:
      averageMs === null ? null : formatDuration(new Date(0).toISOString(), new Date(averageMs).toISOString()),
    failedRuns: finished.filter((job) => job.status === JobStatus.Failed).length,
    lastSuccessfulRun,
  };
}
