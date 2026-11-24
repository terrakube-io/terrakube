/**
 * How many workspaces last ended in each state. Completed includes runs that found no changes and never includes a
 * workspace with no status, as everywhere else. Running is running, pending, approved and queued.
 */
export type StatusCounts = { completed: number; failed: number; waiting: number; running: number; never: number };

export type RingSegment = {
  key: "completed" | "failed" | "waiting" | "running" | "other" | "never";
  label: string;
  count: number;
};

/**
 * The workspaces by state, in the order of the legend. Whatever the five counts do not name (rejected, cancelled and
 * the like) is "other", so nothing finished is ever drawn as running.
 */
export function ringSegments(total: number, status: StatusCounts): RingSegment[] {
  const other = Math.max(0, total - status.completed - status.failed - status.waiting - status.running - status.never);
  return [
    { key: "completed", label: "completed", count: status.completed },
    { key: "failed", label: "failed", count: status.failed },
    { key: "waiting", label: "waiting approval", count: status.waiting },
    { key: "running", label: "running or queued", count: status.running },
    { key: "other", label: "stopped or other", count: other },
    { key: "never", label: "never executed", count: status.never },
  ];
}

const COLORS: Record<RingSegment["key"], string> = {
  completed: "var(--ant-color-success, #52c41a)",
  failed: "var(--ant-color-error, #ff4d4f)",
  waiting: "var(--ant-color-warning, #faad14)",
  running: "var(--ant-color-info, #1677ff)",
  other: "var(--ant-color-text-tertiary, #8c8c8c)",
  never: "var(--tk-text-quaternary, #bfbfbf)",
};

export const segmentColor = (key: RingSegment["key"]) => COLORS[key];

/** A conic-gradient for the ring. An empty organization is one grey ring. */
export function ringGradient(segments: RingSegment[]): string {
  const total = segments.reduce((sum, segment) => sum + segment.count, 0);
  if (total === 0) return `conic-gradient(${COLORS.never} 0 100%)`;
  let from = 0;
  const stops = segments
    .filter((segment) => segment.count > 0)
    .map((segment) => {
      const to = from + (segment.count / total) * 100;
      const stop = `${COLORS[segment.key]} ${from.toFixed(2)}% ${to.toFixed(2)}%`;
      from = to;
      return stop;
    });
  return `conic-gradient(${stops.join(", ")})`;
}
