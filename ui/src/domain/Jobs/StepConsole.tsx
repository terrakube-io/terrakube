import { Alert, Button, Spin } from "antd";
import parse from "html-react-parser";
import { useStepLog } from "../../hooks";
import { JobStep } from "../types";
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
};

type Props = {
  item: JobStep;
  jobId: string;
  organizationId: string;
  /** Incomplete-sensitive-variables guard step: render its pre-set message, never fetch. */
  guardMessage?: string;
  structured: StructuredData;
  uiType: "structured" | "console";
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

  // A completed plan/apply step whose console clearly shows changes but whose structured context
  // is missing or empty: the structured write is stale or failed. Show an explicit "temporarily
  // unavailable" state with the console as fallback and a retry - never a false "No changes".
  const hasRealStructuredContent =
    Boolean(structured.template) ||
    (structured.structuredChanges != null && structured.structuredChanges.length > 0) ||
    (structured.structuredApplyChanges != null && structured.structuredApplyChanges.length > 0);
  const consolePlanSummary = parseConsolePlanSummary(logText);
  const structuredUnavailable =
    terminal &&
    !hasRealStructuredContent &&
    consolePlanSummary.hasPlan &&
    consolePlanSummary.declaresChanges;

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
              This step produced changes, but the structured view could not be loaded. The full
              output is shown below.
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
