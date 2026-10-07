import { JobStatus } from "../../../domain/types";

export function getWorkspaceStatusText(status?: string): string | undefined {
  switch (status) {
    case JobStatus.Completed:
      return "Completed";
    case JobStatus.NoChanges:
      return "No changes";
    case JobStatus.Running:
      return "Running";
    case JobStatus.Queue:
      return "Queued";
    case JobStatus.Pending:
      return "Pending";
    case JobStatus.WaitingApproval:
      return "Waiting approval";
    case JobStatus.NotExecuted:
      return "Not executed";
    case "NeverExecuted":
      return "Never executed";
    case JobStatus.Rejected:
      return "Discarded";
    case JobStatus.Cancelled:
      return "Cancelled";
    case JobStatus.Failed:
      return "Failed";
    case JobStatus.Approved:
      return "Approved";
    case JobStatus.Unknown:
      return "Unknown";
    default:
      return status;
  }
}
