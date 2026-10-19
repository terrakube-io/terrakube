import {
  ApplyChange,
  Diagnostic,
  JobDiagnosticsByStep,
  PlanChange,
  StructuredApplyOutputByStep,
  StructuredPlanOutputByStep,
} from "./structuredPlan";

/** One error or warning in the run-level view, with where it came from. */
export type RunDiagnostic = {
  /** Identifies the diagnostic: the same key is the same diagnostic. */
  key: string;
  severity: Diagnostic["severity"];
  summary: string;
  detail?: string;
  /** The `file:line` the diagnostic points at, when Terraform reported one. */
  location?: string;
  /** The resource it belongs to; absent for a diagnostic that is about the run or its configuration. */
  address?: string;
  /** Names of the steps that reported it. */
  stepNames: string[];
};

type StepRef = { id: string; stepNumber: number; name: string };

type CollectInput = {
  steps: StepRef[];
  planStructuredOutput: StructuredPlanOutputByStep;
  applyStructuredOutput: StructuredApplyOutputByStep;
  jobDiagnostics: JobDiagnosticsByStep;
};

const keyOf = (diagnostic: Diagnostic, address?: string) =>
  JSON.stringify([
    diagnostic.severity,
    diagnostic.summary,
    diagnostic.detail ?? "",
    diagnostic.location ?? "",
    address ?? "",
  ]);

const lookup = <T>(byStep: Record<string, T>, step: StepRef): T | undefined =>
  byStep[step.id] ?? byStep[String(step.stepNumber)];

/**
 * Every error and warning captured for the run, whatever the resource filters hide and whether or
 * not a row is expanded. A diagnostic repeated by the plan and apply of the same run, or by a
 * snapshot that was stored twice, appears once. The resource and the source location are part of
 * the identity, so two diagnostics with the same text about different resources or different lines
 * stay separate. Errors come first.
 */
export const collectRunDiagnostics = ({
  steps,
  planStructuredOutput,
  applyStructuredOutput,
  jobDiagnostics,
}: CollectInput): RunDiagnostic[] => {
  const collected = new Map<string, RunDiagnostic>();

  const add = (diagnostic: Diagnostic, step: StepRef, address?: string) => {
    // The stored output is not under our control: a null or malformed entry is skipped, not fatal.
    if (diagnostic == null || typeof diagnostic !== "object") {
      return;
    }
    const key = keyOf(diagnostic, address);
    const existing = collected.get(key);
    if (existing) {
      if (!existing.stepNames.includes(step.name)) {
        existing.stepNames.push(step.name);
      }
      return;
    }
    collected.set(key, {
      key,
      severity: diagnostic.severity,
      summary: diagnostic.summary,
      detail: diagnostic.detail,
      location: diagnostic.location,
      address,
      stepNames: [step.name],
    });
  };

  const addChanges = (changes: Array<PlanChange | ApplyChange> | undefined, step: StepRef) => {
    changes?.forEach((change) => {
      change.diagnostics?.forEach((diagnostic) => add(diagnostic, step, change.address));
    });
  };

  steps.forEach((step) => {
    lookup(jobDiagnostics, step)?.forEach((diagnostic) => add(diagnostic, step));
    addChanges(lookup(planStructuredOutput, step), step);
    addChanges(lookup(applyStructuredOutput, step), step);
  });

  return [...collected.values()].sort((a, b) => Number(b.severity === "error") - Number(a.severity === "error"));
};

export const countBySeverity = (diagnostics: RunDiagnostic[]) => ({
  errors: diagnostics.filter((diagnostic) => diagnostic.severity === "error").length,
  warnings: diagnostics.filter((diagnostic) => diagnostic.severity === "warning").length,
});
