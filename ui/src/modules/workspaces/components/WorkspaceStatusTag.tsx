import {
  CheckCircleOutlined,
  SyncOutlined,
  ExclamationCircleOutlined,
  InfoCircleOutlined,
  CloseCircleOutlined,
  StopOutlined,
  ClockCircleOutlined,
} from "@ant-design/icons";
import { JobStatus } from "../../../domain/types";
import { Tag } from "antd";
import { statusColors } from "../utils/workspaceStatusColors";

type Props = {
  status?: string;
};

export default function WorkspaceStatusTag({ status }: Props) {
  const getIcon = () => {
    switch (status) {
      case JobStatus.Completed:
      case JobStatus.NoChanges:
        return <CheckCircleOutlined />;
      case JobStatus.Running:
        return <SyncOutlined spin />;
      case JobStatus.WaitingApproval:
        return <ExclamationCircleOutlined />;
      case "NeverExecuted":
        return <InfoCircleOutlined />;
      case JobStatus.Rejected:
        return <CloseCircleOutlined />;
      case JobStatus.Cancelled:
      case JobStatus.Failed:
        return <StopOutlined />;
      default:
        <ClockCircleOutlined />;
    }
  };
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
    <Tag icon={getIcon()} color={status && statusColors[status]}>
      {getStatusText()}
    </Tag>
  );
}
