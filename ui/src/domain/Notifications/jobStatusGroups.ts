import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  ExclamationCircleOutlined,
  QuestionCircleOutlined,
  SyncOutlined,
} from "@ant-design/icons";
import { JobStatus } from "../types";

type JobStatusGroup = {
  key: string;
  label: string;
  color: string;
  icon: typeof CheckCircleOutlined;
  statuses: { value: JobStatus; label: string; hint?: string }[];
};

export const JOB_STATUS_GROUPS: JobStatusGroup[] = [
  {
    key: "needs-attention",
    label: "Needs attention",
    color: "warning",
    icon: ClockCircleOutlined,
    statuses: [
      {
        value: JobStatus.WaitingApproval,
        label: "Waiting for approval",
        hint: "Includes manual run approvals and soft-mandatory policy reviews",
      },
    ],
  },
  {
    key: "completed",
    label: "Completed",
    color: "success",
    icon: CheckCircleOutlined,
    statuses: [
      { value: JobStatus.Completed, label: "Completed" },
      { value: JobStatus.NoChanges, label: "Completed (no changes)" },
    ],
  },
  {
    key: "errored",
    label: "Errored",
    color: "error",
    icon: ExclamationCircleOutlined,
    statuses: [
      {
        value: JobStatus.Failed,
        label: "Failed",
        hint: "Includes run errors and hard-mandatory policy violations",
      },
      { value: JobStatus.Rejected, label: "Rejected" },
      { value: JobStatus.Cancelled, label: "Cancelled" },
    ],
  },
  {
    key: "in-progress",
    label: "In progress",
    color: "processing",
    icon: SyncOutlined,
    statuses: [
      { value: JobStatus.Pending, label: "Pending" },
      { value: JobStatus.Approved, label: "Approved" },
      { value: JobStatus.Queue, label: "Queued" },
      { value: JobStatus.Running, label: "Running" },
    ],
  },
  {
    key: "other",
    label: "Other",
    color: "default",
    icon: QuestionCircleOutlined,
    statuses: [
      { value: JobStatus.NotExecuted, label: "Not executed" },
      { value: JobStatus.Unknown, label: "Unknown" },
      { value: JobStatus.NeverExecuted, label: "Never executed" },
    ],
  },
];
