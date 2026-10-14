import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  MinusCircleOutlined,
  PauseCircleOutlined,
  StopOutlined,
  SyncOutlined,
} from "@ant-design/icons";
import { RunCascadeNodeStatus } from "@/domain/types";

export const cascadeNodeStatusColors: Record<string, string> = {
  [RunCascadeNodeStatus.Pending]: "#8c8c8c",
  [RunCascadeNodeStatus.Ready]: "#8c8c8c",
  [RunCascadeNodeStatus.Running]: "#108ee9",
  [RunCascadeNodeStatus.Succeeded]: "#2eb039",
  [RunCascadeNodeStatus.Failed]: "#FB0136",
  [RunCascadeNodeStatus.Skipped]: "#fa8f37",
  [RunCascadeNodeStatus.Blocked]: "#d4380d",
  [RunCascadeNodeStatus.Cancelled]: "#8c8c8c",
};

export function getCascadeNodeStatusIcon(status?: string) {
  switch (status) {
    case RunCascadeNodeStatus.Pending:
    case RunCascadeNodeStatus.Ready:
      return <ClockCircleOutlined />;
    case RunCascadeNodeStatus.Running:
      return <SyncOutlined spin />;
    case RunCascadeNodeStatus.Succeeded:
      return <CheckCircleOutlined />;
    case RunCascadeNodeStatus.Failed:
      return <CloseCircleOutlined />;
    case RunCascadeNodeStatus.Skipped:
      return <MinusCircleOutlined />;
    case RunCascadeNodeStatus.Blocked:
      return <PauseCircleOutlined />;
    case RunCascadeNodeStatus.Cancelled:
      return <StopOutlined />;
    default:
      return <ClockCircleOutlined />;
  }
}

export function getCascadeNodeStatusText(status?: string): string | undefined {
  switch (status) {
    case RunCascadeNodeStatus.Pending:
      return "Pending";
    case RunCascadeNodeStatus.Ready:
      return "Ready";
    case RunCascadeNodeStatus.Running:
      return "Running";
    case RunCascadeNodeStatus.Succeeded:
      return "Succeeded";
    case RunCascadeNodeStatus.Failed:
      return "Failed";
    case RunCascadeNodeStatus.Skipped:
      return "Skipped";
    case RunCascadeNodeStatus.Blocked:
      return "Blocked";
    case RunCascadeNodeStatus.Cancelled:
      return "Cancelled";
    default:
      return status;
  }
}
