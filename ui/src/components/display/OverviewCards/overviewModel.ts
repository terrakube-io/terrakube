/** How many workspaces last ended in each state. The rest (running, queued, pending) is what is left over. */
export type StatusCounts = { completed: number; failed: number; waiting: number; never: number };

export type RingSegment = { key: "completed" | "failed" | "waiting" | "other" | "never"; label: string; count: number };

/** The workspaces by state, in the order of the legend. Running and queued ones are what the four counts leave over. */
export function ringSegments(total: number, status: StatusCounts): RingSegment[] {
  const other = Math.max(0, total - status.completed - status.failed - status.waiting - status.never);
  return [
    { key: "completed", label: "completed", count: status.completed },
    { key: "failed", label: "failed", count: status.failed },
    { key: "waiting", label: "waiting approval", count: status.waiting },
    { key: "other", label: "running or queued", count: other },
    { key: "never", label: "never executed", count: status.never },
  ];
}

const COLORS: Record<RingSegment["key"], string> = {
  completed: "var(--ant-color-success, #52c41a)",
  failed: "var(--ant-color-error, #ff4d4f)",
  waiting: "var(--ant-color-warning, #faad14)",
  other: "var(--ant-color-info, #1677ff)",
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
