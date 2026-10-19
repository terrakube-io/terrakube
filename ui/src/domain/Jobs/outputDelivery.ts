import { StepDeliveryState } from "../types";
import { StructuredOutputMarker } from "./contextAvailability";
import { isTerminalStatus } from "./stepStatus";

/**
 * How far a step's structured result can be trusted (#3693). It answers a different question from
 * the step's own status: a step can have succeeded while its final output has not arrived yet.
 *
 * - `in-progress`    the step is still running, so any rows are live progress.
 * - `awaiting-final` the step is over and its final output is expected; the page is still checking.
 * - `recovering`     the executor handed the output to storage and the API has not applied it yet.
 * - `recovery-stalled` the same, but the page has stopped checking: it will not change until the page is reloaded.
 * - `unavailable`    the structured context cannot be read right now.
 * - `partial`        the step is over but only a progress snapshot was ever stored.
 * - `expired`        the output could not be recovered and will not arrive.
 * - `final`          the final output was accepted (or recovered).
 * - `unknown`        nothing says either way, for example a run from before delivery was tracked.
 *                    Shown exactly as before, with no notice.
 */
export type OutputCompleteness =
  | "in-progress"
  | "awaiting-final"
  | "recovering"
  | "recovery-stalled"
  | "unavailable"
  | "partial"
  | "expired"
  | "final"
  | "unknown";

export type OutputCompletenessInput = {
  stepId: string;
  stepStatus: string;
  deliveryState?: StepDeliveryState;
  /** What the executor last wrote; only counts for the step it names. */
  marker?: StructuredOutputMarker;
  contextAvailability: "persisted" | "pending" | "unavailable";
  /** The page is still re-checking after the run ended. */
  reconciling: boolean;
  /** The page stopped re-checking while the output was still being recovered from storage. */
  recoveryGaveUp?: boolean;
  /** The executor wrote an explicit no-change marker for this step, which only a final plan does. */
  explicitNoChange?: boolean;
};

/** Plan, apply and destroy steps are the ones that produce a structured result. */
export const isStructuredOutputStep = (stepName: string): boolean => /\b(plan|apply|destroy)\b/i.test(stepName);

/**
 * Whether a step is one that produces a structured result. A template does not have to name its
 * steps (they show up as "Running Step100"), so the name is only one of three signals:
 * - the name says plan, apply or destroy;
 * - the executor recorded a delivery outcome for it, which only plan, apply and destroy steps ever do
 *   (`NOT_DURABLE` is the default of every step and proves nothing);
 * - the stored progress marker names it.
 */
export const producesStructuredOutput = (
  step: { id: string; name: string; deliveryState?: StepDeliveryState },
  marker?: StructuredOutputMarker
): boolean =>
  isStructuredOutputStep(step.name) ||
  (step.deliveryState != null && step.deliveryState !== "NOT_DURABLE") ||
  marker?.stepId === step.id;

export const parseDeliveryState = (value: unknown): StepDeliveryState | undefined => {
  switch (value) {
    case "ACKED":
    case "SPOOLED":
    case "RECOVERED":
    case "EXPIRED":
    case "NOT_DURABLE":
      return value;
    default:
      return undefined;
  }
};

export const getOutputCompleteness = ({
  stepId,
  stepStatus,
  deliveryState,
  marker,
  contextAvailability,
  reconciling,
  recoveryGaveUp = false,
  explicitNoChange = false,
}: OutputCompletenessInput): OutputCompleteness => {
  if (!isTerminalStatus(stepStatus)) {
    return "in-progress";
  }
  // Explicit delivery outcomes win over everything inferred from the stored content.
  if (deliveryState === "EXPIRED") return "expired";
  if (explicitNoChange) return "final";
  // A spinner would say that something is happening, so it only shows while the page is checking.
  if (deliveryState === "SPOOLED") return recoveryGaveUp ? "recovery-stalled" : "recovering";
  if (deliveryState === "ACKED" || deliveryState === "RECOVERED") return "final";

  const ownMarker = marker?.stepId === stepId ? marker : undefined;
  if (ownMarker?.finalSnapshot) return "final";
  if (contextAvailability === "unavailable") return "unavailable";
  if (reconciling && (contextAvailability === "pending" || ownMarker != null)) return "awaiting-final";
  // Every step starts as NOT_DURABLE, including all steps that ran before delivery was tracked, so
  // that value alone proves nothing. Only a progress snapshot that is the last thing stored for
  // this step is evidence that its final output never arrived.
  if (ownMarker != null) return "partial";
  return "unknown";
};

/** The notice the page shows for this state; `null` means nothing to say. */
export type OutputCompletenessNotice = {
  tone: "info" | "warning" | "error" | "success";
  label: string;
  description: string;
};

export const describeOutputCompleteness = (completeness: OutputCompleteness): OutputCompletenessNotice | null => {
  switch (completeness) {
    case "awaiting-final":
      return {
        tone: "info",
        label: "Waiting for final output",
        description:
          "The step has finished, but its final structured output has not been confirmed yet. What is shown may still change.",
      };
    case "recovering":
      return {
        tone: "info",
        label: "Recovering final output",
        description:
          "The final output was saved by the executor and is being applied now. What is shown may be incomplete until it lands.",
      };
    case "recovery-stalled":
      return {
        tone: "warning",
        label: "Recovery is taking longer than expected",
        description:
          "The final output was saved by the executor and has not been applied yet. This page stopped checking, reload it to check again. The console output is complete.",
      };
    case "unavailable":
      return {
        tone: "warning",
        label: "Output unavailable",
        description: "The structured output cannot be read right now. The console output is complete.",
      };
    case "partial":
      return {
        tone: "warning",
        label: "Partial results",
        description:
          "Only a progress snapshot was saved for this step and its final output was not confirmed. The console output is complete.",
      };
    case "expired":
      return {
        tone: "error",
        label: "Final output lost",
        description:
          "The final structured output could not be recovered. What is shown is the last progress that was saved. The console output is complete.",
      };
    case "final":
      return {
        tone: "success",
        label: "Final results",
        description: "The final output for this step was confirmed.",
      };
    default:
      return null;
  }
};

/**
 * True once nothing more is expected for the step, so the page can stop re-checking it. Ask it
 * with `reconciling: true`, which is what separates "still on its way" from "gave up".
 */
export const isOutputSettled = (completeness: OutputCompleteness): boolean =>
  completeness === "final" ||
  completeness === "expired" ||
  completeness === "recovery-stalled" ||
  completeness === "unknown";
