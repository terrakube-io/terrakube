// Job cannot be paged through Elide ("Cannot paginate job"), so what a read of a workspace's jobs brings back is limited
// by a window on the creation date in the filter instead.
export const RUN_POLICY_WINDOW_DAYS = 90;
export const DRIFT_WINDOW_DAYS = 120;

/** The RSQL term that keeps only the jobs created in the last {@code days} days. */
export function createdWithin(days: number, now: Date = new Date()): string {
  return `createdDate=ge=${new Date(now.getTime() - days * 24 * 60 * 60 * 1000).toISOString()}`;
}
