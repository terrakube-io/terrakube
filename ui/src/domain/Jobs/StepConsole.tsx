import { Spin } from "antd";
import parse from "html-react-parser";
import { useStepLog } from "../../hooks";
import { JobStep } from "../types";
import { LiveTerminalOutput } from "./LiveTerminalOutput";
import { StructuredPlanOutput } from "./StructuredPlanOutput";
import { TerminalOutput } from "./TerminalOutput";
import {
  JobDiagnosticsByStep,
  StructuredApplyOutputByStep,
  StructuredOutputsByStep,
  StructuredPlanOutputByStep,
} from "./structuredPlan";

const TERMINAL_STEP_STATUSES = new Set(["completed", "noChanges", "failed", "cancelled", "rejected", "notExecuted"]);

const isTerminalStep = (status?: string) => (status ? TERMINAL_STEP_STATUSES.has(status) : false);

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
};

/**
 * Owns one step's log acquisition (`useStepLog`) and rendering. A running step streams via
 * LiveTerminalOutput; a terminal step's archived log is fetched once, cached, and shown with
 * retry / empty / truncated affordances. The structured view, when present, gets the same log
 * text so its terraform-version extraction and raw-output section keep working.
 */
export const StepConsole = ({ item, jobId, organizationId, guardMessage, structured, uiType }: Props) => {
  const isRunning = !isTerminalStep(item.status);

  const { state, text, truncated, retry } = useStepLog({
    stepId: item.id,
    output: item.output,
    jobId,
    organizationId,
    enabled: guardMessage == null && !isRunning,
    isTerminal: isTerminalStep(item.status),
  });

  const logText = guardMessage ?? (isRunning ? "" : text);

  const consoleView = isRunning ? (
    <LiveTerminalOutput
      jobId={jobId}
      organizationId={organizationId}
      item={{ ...item, outputLog: item.outputLog || "" }}
    />
  ) : state === "loading" ? (
    <Spin spinning tip="Loading logs...">
      <div style={{ minHeight: 120 }} />
    </Spin>
  ) : (
    <TerminalOutput
      outputLog={state === "error" ? "" : logText}
      stepName={item.name}
      isRunning={false}
      truncated={truncated}
      error={state === "error"}
      onRetry={retry}
    />
  );

  if (guardMessage != null || !structured.hasStructuredView) {
    return consoleView;
  }

  const isStepRunning = isRunning;
  const structuredContent = structured.structuredApplyChanges ? (
    <StructuredPlanOutput
      changes={structured.structuredApplyChanges}
      outputLog={logText}
      applyMode
      outputs={structured.stepOutputs}
      jobDiagnostics={structured.stepJobDiagnostics}
      isStepRunning={isStepRunning}
    />
  ) : structured.structuredChanges ? (
    <StructuredPlanOutput
      changes={structured.structuredChanges}
      outputLog={logText}
      jobDiagnostics={structured.stepJobDiagnostics}
      isStepRunning={isStepRunning}
    />
  ) : (
    <div>{parse(structured.template ?? "")}</div>
  );

  // Both views stay mounted; CSS toggles visibility so flipping the switch never resets
  // StructuredPlanOutput's internal expanded-row state.
  return (
    <>
      <div style={{ display: uiType === "structured" ? "block" : "none" }}>{structuredContent}</div>
      <div style={{ display: uiType === "structured" ? "none" : "block" }}>{consoleView}</div>
    </>
  );
};
