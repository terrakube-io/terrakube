export type PolicySetsViewMode = "cards" | "compact";

export const POLICY_SETS_VIEW_MODE_KEY = "terrakube.policySets.listViewMode";

export function getStoredPolicySetsViewMode(): PolicySetsViewMode {
  const stored = localStorage.getItem(POLICY_SETS_VIEW_MODE_KEY);
  return stored === "compact" ? "compact" : "cards";
}

export function setStoredPolicySetsViewMode(mode: PolicySetsViewMode): void {
  localStorage.setItem(POLICY_SETS_VIEW_MODE_KEY, mode);
}
