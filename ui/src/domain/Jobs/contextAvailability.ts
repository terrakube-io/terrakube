/**
 * How the API's `/context/v1/{jobId}` response should be interpreted by the Job Details page.
 *
 * - `persisted`   — structured context is stored; an empty change set here genuinely means "No changes".
 * - `pending`     — no context object yet (persistence still catching up); NOT an empty plan.
 * - `unavailable` — the store returned a controlled `503`/timeout; retry, never infer "No changes".
 */
export type ContextAvailability = "persisted" | "pending" | "unavailable";

const isRecord = (value: unknown): value is Record<string, unknown> =>
  value != null && typeof value === "object";

export const parseContextAvailability = (data: unknown, httpStatus?: number): ContextAvailability => {
  if (httpStatus != null && httpStatus >= 500) {
    return "unavailable";
  }
  if (!isRecord(data)) {
    return "pending";
  }

  const status = data.structuredOutputStatus;
  if (isRecord(status)) {
    if (status.state === "UNAVAILABLE") return "unavailable";
    if (status.state === "PENDING") return "pending";
    if (status.state === "PERSISTED") return "persisted";
  }

  // Legacy executors persist no status marker: any structured content means it was persisted;
  // a bare `{}` means nothing has been written yet.
  const hasContent =
    data.planStructuredOutput != null ||
    data.applyStructuredOutput != null ||
    data.terraformOutputs != null ||
    data.terrakubeUI != null;
  return hasContent ? "persisted" : "pending";
};

/**
 * The plan step id of an explicitly persisted no-change plan, if the executor wrote the marker.
 * This is affirmative evidence that the associated standard apply is a valid no-op.
 */
export const parseNoChangePlanStepId = (data: unknown): string | undefined => {
  if (!isRecord(data) || !isRecord(data.noChangePlan)) {
    return undefined;
  }
  const planStepId = data.noChangePlan.planStepId;
  return typeof planStepId === "string" && planStepId.length > 0 ? planStepId : undefined;
};

// No UI metrics backend exists yet; this module-level counter records reconciliation outcomes for
// tests and future wiring of `terrakube_ui_structured_output_reconciliation_total`.
export type ReconciliationResult = "persisted" | "unavailable" | "expired";
const reconciliationCounts: Record<ReconciliationResult, number> = {
  persisted: 0,
  unavailable: 0,
  expired: 0,
};

export const recordReconciliationResult = (result: ReconciliationResult): void => {
  reconciliationCounts[result] += 1;
};

export const getReconciliationCounts = (): Record<ReconciliationResult, number> => ({
  ...reconciliationCounts,
});

export const resetReconciliationCounts = (): void => {
  reconciliationCounts.persisted = 0;
  reconciliationCounts.unavailable = 0;
  reconciliationCounts.expired = 0;
};
