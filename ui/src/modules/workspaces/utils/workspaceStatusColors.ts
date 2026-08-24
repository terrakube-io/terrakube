import { JobStatus } from "../../../domain/types";

export const statusColors: Record<string, string> = {
  [JobStatus.Completed]: "#2eb039",
  [JobStatus.Running]: "#108ee9",
  [JobStatus.Queue]: "#8c8c8c",
  [JobStatus.Pending]: "#8c8c8c",
  [JobStatus.WaitingApproval]: "#fa8f37",
  [JobStatus.Rejected]: "#FB0136",
  [JobStatus.Failed]: "#FB0136",
  [JobStatus.NoChanges]: "#e037fa",
};
