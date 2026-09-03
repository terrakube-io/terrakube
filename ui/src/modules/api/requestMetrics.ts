/**
 * Lightweight in-memory counters for request-failure classification. There is no UI metrics
 * backend yet; these are exposed for tests and for future wiring of:
 *  - `terrakube_ui_auxiliary_request_failures_total` (labeled by request class + HTTP/network outcome)
 *  - `terrakube_ui_global_backend_error_activations_total` (labeled by request class; must stay 0
 *    for auxiliary requests)
 */

const auxiliaryFailures: Record<string, number> = {};
const globalBackendErrorActivations: Record<string, number> = {};

const bump = (map: Record<string, number>, key: string): void => {
  map[key] = (map[key] ?? 0) + 1;
};

export const recordAuxiliaryRequestFailure = (requestClass: string, outcome: string): void => {
  bump(auxiliaryFailures, `${requestClass}:${outcome}`);
};

export const recordGlobalBackendErrorActivation = (requestClass: string): void => {
  bump(globalBackendErrorActivations, requestClass);
};

export const getAuxiliaryRequestFailureCounts = (): Record<string, number> => ({ ...auxiliaryFailures });

export const getGlobalBackendErrorActivations = (): Record<string, number> => ({
  ...globalBackendErrorActivations,
});

export const resetRequestMetrics = (): void => {
  for (const key of Object.keys(auxiliaryFailures)) delete auxiliaryFailures[key];
  for (const key of Object.keys(globalBackendErrorActivations)) delete globalBackendErrorActivations[key];
};

/** Classify an Axios error into a stable metric-safe outcome label. */
export const classifyRequestOutcome = (error: {
  response?: { status?: number };
  code?: string;
  message?: string;
}): string => {
  const status = error?.response?.status;
  if (status != null) {
    return String(status);
  }
  if (error?.code === "ECONNABORTED" || /timeout/i.test(error?.message ?? "")) {
    return "timeout";
  }
  return "network";
};
