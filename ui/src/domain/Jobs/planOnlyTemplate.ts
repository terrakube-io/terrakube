const PLAN_ONLY_FLOW_TYPES = new Set(["terraformPlan", "terraformPlanDestroy"]);

/**
 * Whether a template only plans: every step of its flow is a plan. Anything else, including a template whose
 * flow cannot be read, counts as one that can change infrastructure.
 *
 * Only a convenience for the run dialog. The API makes the real decision when the run is created.
 */
export const isPlanOnlyTemplate = (tcl?: string): boolean => {
  if (!tcl) {
    return false;
  }
  let flow: string;
  try {
    flow = decodeURIComponent(escape(window.atob(tcl)));
  } catch {
    return false;
  }
  const types = [...flow.matchAll(/^\s*(?:-\s*)?type:\s*["']?([A-Za-z]+)["']?\s*$/gm)].map((match) => match[1]);
  return types.length > 0 && types.every((type) => PLAN_ONLY_FLOW_TYPES.has(type));
};

/** The branch runs use when none is given: the first entry of the workspace branch, without a refs/ prefix. */
export const configuredBranch = (branch?: string): string => {
  const first = (branch ?? "").split(",")[0].trim();
  return first.replace(/^refs\/(heads|tags)\//, "");
};
