// Completed-step logs are immutable, so once fetched they never need re-fetching. This module-level
// cache is keyed by step id and lives for the page session; the job-details poll refreshes step
// *status*, never a settled log.
const cache = new Map<string, string>();

export const stepLogCache = {
  get(stepId: string): string | undefined {
    return cache.get(stepId);
  },
  set(stepId: string, text: string): void {
    cache.set(stepId, text);
  },
  has(stepId: string): boolean {
    return cache.has(stepId);
  },
  clear(): void {
    cache.clear();
  },
};
