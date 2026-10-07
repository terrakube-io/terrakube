import { axiosAdmin } from "@/config/axiosConfig";

export enum RunTriggerEventStatus {
  Pending = "PENDING",
  Processing = "PROCESSING",
  Processed = "PROCESSED",
  Failed = "FAILED",
}

export type RunTriggerEventSummary = {
  id: string;
  jobId: number;
  workspaceName: string;
  status: RunTriggerEventStatus;
  attemptCount: number;
  lastError?: string;
  nextAttemptAt?: string;
  createdDate?: string;
};

export type ReplayResult = {
  eventId: string;
  rearmed: boolean;
};

/** Defaults to FAILED server-side - the exception queue an operator actually needs to see. */
export async function listRunTriggerEvents(
  status: RunTriggerEventStatus = RunTriggerEventStatus.Failed
): Promise<RunTriggerEventSummary[]> {
  const response = await axiosAdmin.get<RunTriggerEventSummary[]>("/admin/v1/run-trigger-events", {
    params: { status },
  });
  return response.data;
}

export async function replayRunTriggerEvent(eventId: string): Promise<ReplayResult> {
  const response = await axiosAdmin.post<ReplayResult>(`/admin/v1/run-trigger-events/${eventId}/replay`, {});
  return response.data;
}

export { getAdminErrorMessage } from "@/modules/cascades/cascadeService";
