const listeners = new Set<() => void>();
let backendErrorStatus: number | null = null;

export function setBackendError(status: number | null) {
  if (backendErrorStatus === status) {
    return;
  }
  backendErrorStatus = status;
  listeners.forEach((listener) => listener());
}

export function getBackendError() {
  return backendErrorStatus;
}

export function subscribeBackendStatus(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export const FATAL_API_STATUSES = [500, 502, 503, 504];
