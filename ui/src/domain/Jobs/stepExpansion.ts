import type { JobStep } from "../types";

const CLOSED_BY_DEFAULT_STEP_STATUSES = new Set(["waitingApproval", "notExecuted", "pending"]);
const APPROVAL_STEP_PATTERNS = [/\bmanual approval\b/, /\bapprove\b/, /\bapproval\b/];
const APPLY_STEP_PATTERNS = [/\bapply\b/];

const normalizeStepName = (name: string) => {
  return name.trim().toLowerCase();
};

const matchesAnyPattern = (value: string, patterns: RegExp[]) => {
  return patterns.some((pattern) => {
    return pattern.test(value);
  });
};

const isApprovalStep = (item: Pick<JobStep, "name" | "status">) => {
  if (item.status === "waitingApproval") {
    return true;
  }

  return matchesAnyPattern(normalizeStepName(item.name), APPROVAL_STEP_PATTERNS);
};

const isApplyStep = (item: Pick<JobStep, "name" | "status">) => {
  return matchesAnyPattern(normalizeStepName(item.name), APPLY_STEP_PATTERNS);
};

export const shouldStepBeCollapsible = (item: Pick<JobStep, "name" | "status">) => {
  if (!isApprovalStep(item)) {
    return true;
  }

  return !CLOSED_BY_DEFAULT_STEP_STATUSES.has(item.status);
};

export const shouldStepBeExpandedByDefault = (item: Pick<JobStep, "name" | "status">) => {
  if (isApprovalStep(item)) {
    return false;
  }

  if (isApplyStep(item)) {
    return !CLOSED_BY_DEFAULT_STEP_STATUSES.has(item.status);
  }

  return true;
};
