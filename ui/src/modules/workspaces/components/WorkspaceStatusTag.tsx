import { Tag } from "antd";
import { JobStatus } from "../../../domain/types";
import { statusColors } from "../utils/workspaceStatusColors";
import { getWorkspaceStatusIcon } from "../utils/workspaceStatusIcon";

type Props = {
  status?: string;
};

export default function WorkspaceStatusTag({ status }: Props) {
  const getStatusText = () => {
    switch (status) {
      case JobStatus.Completed:
        return JobStatus.Completed;
      case JobStatus.NoChanges:
        return "No Changes";
      case JobStatus.Running:
        return JobStatus.Running;
      case JobStatus.WaitingApproval:
        return "Waiting Approval";
      case "NeverExecuted":
        return "Never Executed";
      case JobStatus.Rejected:
        return JobStatus.Rejected;
      case JobStatus.Cancelled:
        return JobStatus.Cancelled;
      case JobStatus.Failed:
        return JobStatus.Failed;
      default:
        return status;
    }
  };

  return (
    <Tag icon={getWorkspaceStatusIcon(status)} color={status && statusColors[status]}>
      {getStatusText()}
    </Tag>
  );
}
