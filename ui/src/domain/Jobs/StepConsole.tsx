import { CheckCircleOutlined } from "@ant-design/icons";
import { Alert, Button, Spin } from "antd";
import parse from "html-react-parser";
import { useStepLog } from "../../hooks";
import { JobStep } from "../types";
import { ContextAvailability } from "./contextAvailability";
import { parseConsolePlanSummary } from "./consolePlanSummary";
import { LiveTerminalOutput } from "./LiveTerminalOutput";
import { isRunningStatus, isTerminalStatus } from "./stepStatus";
import { StructuredPlanOutput } from "./StructuredPlanOutput";
import { TerminalOutput } from "./TerminalOutput";
import {
  JobDiagnosticsByStep,
  StructuredApplyOutputByStep,
  StructuredOutputsByStep,
  StructuredPlanOutputByStep,
} from "./structuredPlan";

type StructuredData = {
  template?: string;
  structuredChanges?: StructuredPlanOutputByStep[string];
  structuredApplyChanges?: StructuredApplyOutputByStep[string];
  stepOutputs?: StructuredOutputsByStep[string];
  stepJobDiagnostics?: JobDiagnosticsByStep[string];
  hasStructuredView: boolean;
  /** A persisted context (with a real snapshot, even an empty one) has been seen at least once. */
  contextEverPersisted?: boolean;
  /** The associated plan is explicitly a no-change plan (marker or confirmed all-empty). */
  associatedPlanIsNoChange?: boolean;
  /** Some plan step has resource rows - a standard apply here is expected to produce apply rows. */
  anyPlanHasRows?: boolean;
};

type Props = {
  item: JobStep;
  jobId: string;
  organizationId: string;
  /** Incomplete-sensitive-variables guard step: render its pre-set message, never fetch. */
  guardMessage?: string;
  structured: StructuredData;
  uiType: "structured" | "console";
  /** How the API reports the structured context: persisted / pending / unavailable. */
  contextAvailability?: ContextAvailability;
  /** Re-fetch the structured context (used by the "temporarily unavailable" retry control). */
  onRetryStructured?: () => void;
};

/**
 * Owns one step's log acquisition and rendering:
 *  - running step: live SSE stream (LiveTerminalOutput)
 *  - terminal step: archived log fetched once via useStepLog, with retry / empty / truncated states
 *  - not-yet-started step: a "waiting for output" placeholder
 * When a structured view exists it shares the same log text (for terraform-version extraction etc.)
 * and the console sits behind the Structured/Console toggle.
 */
export const StepConsole = ({
  item,
  jobId,
  organizationId,
  guardMessage,
  structured,
  uiType,
  contextAvailability = "persisted",
  onRetryStructured,
}: Props) => {
  const running = isRunningStatus(item.status);
  const terminal = isTerminalStatus(item.status);

  const { state, text, truncated, retry } = useStepLog({
    stepId: item.id,
    output: item.output,
    jobId,
    organizationId,
    enabled: guardMessage == null && terminal,
    isTerminal: terminal,
  });

  const logText = guardMessage ?? text;

  const renderConsole = () => {
    if (guardMessage != null) {
      return <TerminalOutput outputLog={guardMessage} stepName={item.name} isRunning={false} />;
    }
    if (running) {
      return <LiveTerminalOutput jobId={jobId} organizationId={organizationId} item={item} />;
    }
    if (!terminal) {
      return <TerminalOutput outputLog="" stepName={item.name} isRunning={false} emptyLabel="Waiting for output…" />;
    }
    if (state === "loading") {
      return (
        <Spin spinning tip="Loading logs...">
          <div style={{ minHeight: 120 }} />
        </Spin>
      );
    }
    return (
      <TerminalOutput
        outputLog={state === "error" ? "" : text}
        stepName={item.name}
        isRunning={false}
        truncated={truncated}
        error={state === "error"}
        onRetry={retry}
      />
    );
  };

  const hasRealStructuredContent =
    Boolean(structured.template) ||
    (structured.structuredChanges != null && structured.structuredChanges.length > 0) ||
    (structured.structuredApplyChanges != null && structured.structuredApplyChanges.length > 0);
  const consolePlanSummary = parseConsolePlanSummary(logText);

  const stepNameLower = item.name.toLowerCase();
  const isApplyStep = /\bapply\b/.test(stepNameLower);
  const isPlanStep = /\bplan\b/.test(stepNameLower);
  const isDestroyStep = /\bdestroy\b/.test(stepNameLower);
  const isPlanOrApplyStep = isApplyStep || isPlanStep || isDestroyStep;
  const stepFailed =
    item.status === "failed" ||
    item.status === "cancelled" ||
    /(^|\n)\s*Error: /.test(logText);

  // This phase's own snapshot has been loaded from a persisted context (even an explicit empty
  // array is authoritative evidence, not a persistence failure).
  const ownPhaseSnapshot = isApplyStep || isDestroyStep ? structured.structuredApplyChanges : structured.structuredChanges;
  const ownSnapshotConfirmed = ownPhaseSnapshot != null && Boolean(structured.contextEverPersisted);

  // A standard apply whose plan was explicitly empty is a valid no-op: absent applyStructuredOutput
  // is expected there. Console/job status stays authoritative for a real execution error.
  const applyIsNoOp =
    isApplyStep &&
    terminal &&
    !stepFailed &&
    !(structured.structuredApplyChanges != null && structured.structuredApplyChanges.length > 0) &&
    Boolean(structured.associatedPlanIsNoChange) &&
    !consolePlanSummary.declaresChanges;

  // "Structured output temporarily unavailable" is now phase/step-specific: only when this phase
  // expected structured output but we can neither confirm an explicit empty result nor load its
  // snapshot. A confirmed snapshot, an explicit no-change plan, a console "no changes", or a real
  // step failure all rule it out.
  const structuredUnavailable =
    terminal &&
    isPlanOrApplyStep &&
    !hasRealStructuredContent &&
    !applyIsNoOp &&
    !stepFailed &&
    !ownSnapshotConfirmed &&
    !consolePlanSummary.declaresNoChanges &&
    !(isApplyStep && Boolean(structured.associatedPlanIsNoChange)) &&
    ((consolePlanSummary.hasPlan && consolePlanSummary.declaresChanges) ||
      (isApplyStep && Boolean(structured.anyPlanHasRows)) ||
      contextAvailability !== "persisted");

  if (applyIsNoOp) {
    return (
      <>
        <div className="structured-plan-noChanges" style={{ marginBottom: 12 }}>
          <CheckCircleOutlined className="structured-plan-noChangesIcon" />
          <span>Apply completed with no changes.</span>
        </div>
        {renderConsole()}
      </>
    );
  }

  if (structuredUnavailable) {
    return (
      <>
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="Structured output temporarily unavailable"
          description={
            <>
              The structured view for this step could not be loaded yet. The full console output is
              shown below and the page keeps retrying automatically.
              {onRetryStructured != null && (
                <>
                  {" "}
                  <Button size="small" onClick={onRetryStructured}>
                    Retry
                  </Button>
                </>
              )}
            </>
          }
        />
        {renderConsole()}
      </>
    );
  }

  if (!structured.hasStructuredView) {
    return renderConsole();
  }

  const structuredContent = structured.structuredApplyChanges ? (
    <StructuredPlanOutput
      changes={structured.structuredApplyChanges}
      outputLog={logText}
      applyMode
      outputs={structured.stepOutputs}
      jobDiagnostics={structured.stepJobDiagnostics}
      isStepRunning={!terminal}
    />
  ) : structured.structuredChanges ? (
    <StructuredPlanOutput
      changes={structured.structuredChanges}
      outputLog={logText}
      jobDiagnostics={structured.stepJobDiagnostics}
      isStepRunning={!terminal}
    />
  ) : (
    <div>{parse(structured.template ?? "")}</div>
  );

  // Both views stay mounted; CSS toggles visibility so flipping the switch never resets
  // StructuredPlanOutput's internal expanded-row state.
  return (
    <>
      <div style={{ display: uiType === "structured" ? "block" : "none" }}>{structuredContent}</div>
      <div style={{ display: uiType === "structured" ? "none" : "block" }}>{renderConsole()}</div>
    </>
  );
};
