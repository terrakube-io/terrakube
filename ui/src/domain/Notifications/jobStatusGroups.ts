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
  statuses: { value: JobStatus; label: string }[];
};

export const JOB_STATUS_GROUPS: JobStatusGroup[] = [
  {
    key: "needs-attention",
    label: "Needs Attention",
    color: "orange",
    icon: ClockCircleOutlined,
    statuses: [{ value: JobStatus.WaitingApproval, label: "Waiting for Approval" }],
  },
  {
    key: "completed",
    label: "Completed",
    color: "green",
    icon: CheckCircleOutlined,
    statuses: [
      { value: JobStatus.Completed, label: "Completed" },
      { value: JobStatus.NoChanges, label: "Completed (No Changes)" },
    ],
  },
  {
    key: "errored",
    label: "Errored",
    color: "red",
    icon: ExclamationCircleOutlined,
    statuses: [
      { value: JobStatus.Failed, label: "Failed" },
      { value: JobStatus.Rejected, label: "Rejected" },
      { value: JobStatus.Cancelled, label: "Cancelled" },
    ],
  },
  {
    key: "in-progress",
    label: "In Progress",
    color: "blue",
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
      { value: JobStatus.NotExecuted, label: "Not Executed" },
      { value: JobStatus.Unknown, label: "Unknown" },
      { value: JobStatus.NeverExecuted, label: "Never Executed" },
    ],
  },
];
