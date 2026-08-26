import { JobStatus } from "../../../domain/types";

export function getWorkspaceStatusText(status?: string): string | undefined {
  switch (status) {
    case JobStatus.Completed:
      return "Completed";
    case JobStatus.NoChanges:
      return "No Changes";
    case JobStatus.Running:
      return "Running";
    case JobStatus.Queue:
      return "Queued";
    case JobStatus.Pending:
      return "Pending";
    case JobStatus.WaitingApproval:
      return "Waiting Approval";
    case JobStatus.NotExecuted:
      return "Not Executed";
    case "NeverExecuted":
      return "Never Executed";
    case JobStatus.Rejected:
      return "Rejected";
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
