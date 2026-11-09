// The step types a template may have and still only preview changes. Same rule as TclService.isTemplatePlanOnly.
const PLAN_ONLY_STEPS = new Set(["terraformPlan", "terraformPlanDestroy"]);

/**
 * True when every step of the template's flow is a plan. A template that cannot be read, has no steps, or has any
 * other step (apply, destroy, scripts, approval) is not plan-only: when unsure, it is not offered for a bulk plan.
 */
export function isPlanOnlyTemplate(tcl: string | undefined | null): boolean {
  if (!tcl) return false;
  const steps = [...tcl.matchAll(/^\s*-\s*type:\s*["']?([A-Za-z]+)["']?\s*(?:#.*)?$/gm)].map((match) => match[1]);
  return steps.length > 0 && steps.every((step) => PLAN_ONLY_STEPS.has(step));
}
