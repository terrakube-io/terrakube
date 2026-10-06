import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  PauseCircleOutlined,
  StopOutlined,
  SyncOutlined,
  WarningOutlined,
} from "@ant-design/icons";
import { RunCascadeStatus } from "@/domain/types";

// Same palette intent as workspaceStatusColors: running=blue, success=green,
// needs-attention=orange, terminal-unsuccessful=red/grey.
export const cascadeStatusColors: Record<string, string> = {
  [RunCascadeStatus.Running]: "#108ee9",
  [RunCascadeStatus.Completed]: "#2eb039",
  [RunCascadeStatus.Degraded]: "#fa8f37",
  [RunCascadeStatus.Blocked]: "#d4380d",
  [RunCascadeStatus.Cancelled]: "#8c8c8c",
  [RunCascadeStatus.Failed]: "#FB0136",
};

export function getCascadeStatusIcon(status?: string) {
  switch (status) {
    case RunCascadeStatus.Running:
      return <SyncOutlined spin />;
    case RunCascadeStatus.Completed:
      return <CheckCircleOutlined />;
    case RunCascadeStatus.Degraded:
      return <WarningOutlined />;
    case RunCascadeStatus.Blocked:
      return <PauseCircleOutlined />;
    case RunCascadeStatus.Cancelled:
      return <StopOutlined />;
    case RunCascadeStatus.Failed:
      return <CloseCircleOutlined />;
    default:
      return <SyncOutlined spin />;
  }
}

export function getCascadeStatusText(status?: string): string | undefined {
  switch (status) {
    case RunCascadeStatus.Running:
      return "Running";
    case RunCascadeStatus.Completed:
      return "Completed";
    case RunCascadeStatus.Degraded:
      return "Degraded";
    case RunCascadeStatus.Blocked:
      return "Blocked";
    case RunCascadeStatus.Cancelled:
      return "Cancelled";
    case RunCascadeStatus.Failed:
      return "Failed";
    default:
      return status;
  }
}
