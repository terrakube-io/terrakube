import { JobStatus } from "../../../domain/types";

export const statusColors: Record<string, string> = {
  [JobStatus.Completed]: "var(--tk-status-success)",
  [JobStatus.Running]: "var(--tk-status-info)",
  [JobStatus.Queue]: "#6e6e6e",
  [JobStatus.Pending]: "#6e6e6e",
  [JobStatus.WaitingApproval]: "var(--tk-status-warning)",
  [JobStatus.NotExecuted]: "var(--tk-status-warning)",
  [JobStatus.Rejected]: "var(--tk-status-error)",
  [JobStatus.Failed]: "var(--tk-status-error)",
  [JobStatus.Cancelled]: "var(--tk-status-error)",
  [JobStatus.NoChanges]: "#c026d3",
  [JobStatus.Approved]: "var(--tk-status-success)",
  [JobStatus.Unknown]: "#6e6e6e",
};
