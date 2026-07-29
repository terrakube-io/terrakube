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

export function getWorkspaceStatusIcon(status?: string) {
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
      return <ClockCircleOutlined />;
  }
}
