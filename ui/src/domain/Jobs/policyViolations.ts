import { PolicyEvaluationContext } from "../types";

/**
 * True when the run's policy evaluation has hard- or soft-mandatory violations. Either one must be
 * reviewed on the run page: a soft violation needs an override, and a hard one cannot be approved at all
 * (the plan normally stops before it reaches approval, this keeps the approval buttons safe regardless).
 */
export function hasBlockingPolicyViolations(policyEvaluation?: PolicyEvaluationContext): boolean {
  if (!policyEvaluation) return false;
  if ((policyEvaluation.hardMandatoryViolations ?? 0) > 0 || (policyEvaluation.softMandatoryViolations ?? 0) > 0) {
    return true;
  }
  return Boolean(
    policyEvaluation.results?.some((r) => (r.hardMandatoryViolations ?? 0) > 0 || (r.softMandatoryViolations ?? 0) > 0)
  );
}
