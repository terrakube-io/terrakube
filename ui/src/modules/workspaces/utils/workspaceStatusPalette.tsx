import {
  BarsOutlined,
  ExclamationCircleOutlined,
  StopOutlined,
  SyncOutlined,
  CheckCircleOutlined,
  InfoCircleOutlined,
  ClockCircleOutlined,
} from "@ant-design/icons";
import { JobStatus } from "@/domain/types";
import { WorkspaceStatusFilter } from "./workspaceFilter";

export type WorkspaceStatusPaletteEntry = {
  value: string;
  label: string;
  icon: JSX.Element;
  color?: string;
};

/** Shared icon+color mapping for job statuses, used by the workspace list's status
 * pills and by any other UI that needs to summarize workspace status counts. */
export const WORKSPACE_STATUS_PALETTE: WorkspaceStatusPaletteEntry[] = [
  { value: WorkspaceStatusFilter.All, label: "All", icon: <BarsOutlined /> },
  {
    value: JobStatus.WaitingApproval,
    label: "Waiting approval",
    icon: <ExclamationCircleOutlined />,
    color: "var(--tk-status-warning)",
  },
  { value: JobStatus.Failed, label: "Failed", icon: <StopOutlined />, color: "var(--tk-status-error)" },
  { value: JobStatus.Pending, label: "Pending", icon: <ClockCircleOutlined />, color: "#8c8c8c" },
  { value: JobStatus.Queue, label: "Queued", icon: <ClockCircleOutlined />, color: "#8c8c8c" },
  { value: JobStatus.Running, label: "Running", icon: <SyncOutlined />, color: "var(--tk-status-info)" },
  { value: JobStatus.Completed, label: "Completed", icon: <CheckCircleOutlined />, color: "var(--tk-status-success)" },
  { value: WorkspaceStatusFilter.NeverExecuted, label: "Never executed", icon: <InfoCircleOutlined /> },
];
