// A job/step status is "terminal" when no further output will be produced for it.
const TERMINAL_STATUSES = new Set(["completed", "noChanges", "failed", "cancelled", "rejected", "notExecuted"]);

export const isTerminalStatus = (status?: string): boolean => status != null && TERMINAL_STATUSES.has(status);

export const isRunningStatus = (status?: string): boolean => status === "running";
