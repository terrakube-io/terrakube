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
  organizationId?: string;
  organizationName?: string;
};

export type ReplayResult = {
  eventId: string;
  rearmed: boolean;
};

/**
 * Defaults to FAILED - the exception queue an operator actually needs to see. With an organization
 * only its events are returned (instance admin or manage job); without one, every organization's
 * (instance admin only).
 */
export async function listRunTriggerEvents(
  status: RunTriggerEventStatus = RunTriggerEventStatus.Failed,
  organizationId?: string
): Promise<RunTriggerEventSummary[]> {
  const path = organizationId
    ? `/cascade/v1/organization/${organizationId}/trigger-event`
    : "/cascade/v1/trigger-event";
  const response = await axiosAdmin.get<RunTriggerEventSummary[]>(path, { params: { status } });
  return response.data;
}

export async function replayRunTriggerEvent(organizationId: string, eventId: string): Promise<ReplayResult> {
  const response = await axiosAdmin.post<ReplayResult>(
    `/cascade/v1/organization/${organizationId}/trigger-event/${eventId}/replay`,
    {}
  );
  return response.data;
}

export { getAdminErrorMessage } from "@/modules/cascades/cascadeService";
