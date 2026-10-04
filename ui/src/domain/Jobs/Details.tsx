import { ApprovalAttribution } from "@/components/display/ApprovalAttribution";
import { CheckOutlined, CloseOutlined, SafetyCertificateOutlined, StopOutlined, UserOutlined } from "@ant-design/icons";
import {
  Alert,
  Avatar,
  Button,
  Collapse,
  Flex,
  message,
  Modal,
  Radio,
  RadioChangeEvent,
  Spin,
  Tag,
  Typography,
} from "antd";
import { AxiosResponse } from "axios";
import { cloneElement, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { ORGANIZATION_ARCHIVE } from "../../config/actionTypes";
import axiosInstance, { axiosAuxiliary } from "../../config/axiosConfig";
import { useAbortController, usePolling, useStructuredOutputStream } from "../../hooks";
import WorkspaceStatusTag from "@/components/display/WorkspaceStatusTag";
import { getWorkspaceStatusIcon } from "../../modules/workspaces/utils/workspaceStatusIcon";
import { getWorkspaceStatusText } from "../../modules/workspaces/utils/workspaceStatusText";
import { formatJobVia, IncludedItem, Job, JobStep, Workspace } from "../types";
import {
  ContextAvailability,
  parseContextAvailability,
  parseNoChangePlanStepId,
  recordReconciliationResult,
} from "./contextAvailability";
import { getPublicApiOrigin } from "./outputUrl";
import { shouldStepBeCollapsible, shouldStepBeExpandedByDefault } from "./stepExpansion";
import { isTerminalStatus } from "./stepStatus";
import { StepConsole } from "./StepConsole";
import {
  JobDiagnosticsByStep,
  StructuredApplyOutputByStep,
  StructuredOutputsByStep,
  StructuredPlanOutputByStep,
  getPlanChangeActionLabel,
  normalizeJobDiagnostics,
  normalizeStructuredApplyOutput,
  normalizeStructuredOutputs,
  normalizeStructuredPlanOutput,
  normalizeUITemplates,
} from "./structuredPlan";
import { relativeTime } from "@/modules/utils/dates";
import { PolicyChecksOutput } from "./PolicyChecksOutput";
import { PolicyEvaluationContext } from "../types";
import "./runTokens.css";
import "./Details.css";

type Props = {
  jobId: string;
  /** Shown in the run headline so the operator knows which workspace they are approving for. */
  workspaceName?: string;
  /** The `approveJob` permission of the current user; without it the approve and discard actions are not offered. */
  canApprove?: boolean;
};

const INCOMPLETE_VARIABLE_GUARD_STEP_NAME = "Incomplete sensitive variables";

const UI_TYPE_STORAGE_KEY = "terrakube.jobDetails.uiType";

const getStoredUIType = (): "structured" | "console" => {
  try {
    return localStorage.getItem(UI_TYPE_STORAGE_KEY) === "console" ? "console" : "structured";
  } catch {
    // localStorage can throw in private-browsing/storage-restricted contexts - fall back silently.
    return "structured";
  }
};

type IncompleteVariableGuard = {
  title: string;
  variables: string[];
  footer?: string;
  rawMessage: string;
};

export const DetailsJob = ({ jobId, workspaceName, canApprove = false }: Props) => {
  const organizationId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);
  const [loading, setLoading] = useState(false);
  // One flag for approve, discard and cancel: a second click while the PATCH is in flight is a bug, not intent.
  const [actionPending, setActionPending] = useState(false);
  const [job, setJob] = useState<AxiosResponse<Job>>();
  const [workspaceSource, setWorkspaceSource] = useState<string>();
  const [workspaceDefaultBranch, setWorkspaceDefaultBranch] = useState<string>();
  const [workspaceVcsName, setWorkspaceVcsName] = useState<string>();
  const [steps, setSteps] = useState<JobStep[]>([]);
  const [triggeredBy, setTriggeredBy] = useState<{
    jobId: number;
    workspaceId: string;
    workspaceName: string;
  } | null>(null);
  // Controlled per-step Collapse open/closed state, keyed by step id. Was previously driven by
  // Collapse's uncontrolled defaultActiveKey with `${item.id}-${item.status}` as the element key -
  // every status transition (pending -> running -> completed) therefore remounted the whole step
  // subtree, silently closing any row/attribute the user had expanded in StructuredPlanOutput and
  // resetting its filters. Controlled state keyed by id alone survives status changes; the effect
  // below still auto-opens a step the moment it starts running, same as before, but only if the
  // user hasn't already closed it themselves.
  const [activeStepKeys, setActiveStepKeys] = useState<Record<string, string[]>>({});
  const initializedStepIds = useRef<Set<string>>(new Set());
  const userToggledStepIds = useRef<Set<string>>(new Set());

  useEffect(() => {
    setActiveStepKeys((previous) => {
      let changed = false;
      const next = { ...previous };

      for (const item of steps) {
        if (!initializedStepIds.current.has(item.id)) {
          initializedStepIds.current.add(item.id);
          next[item.id] = shouldStepBeExpandedByDefault(item) ? ["2"] : [];
          changed = true;
        } else if (
          item.status === "running" &&
          (next[item.id]?.length ?? 0) === 0 &&
          !userToggledStepIds.current.has(item.id)
        ) {
          next[item.id] = ["2"];
          changed = true;
        }
      }

      return changed ? next : previous;
    });
  }, [steps]);

  const [uiType, setUIType] = useState<"structured" | "console">(getStoredUIType);
  const [uiTemplates, setUITemplates] = useState<Record<string, string>>({});
  const [planStructuredOutput, setPlanStructuredOutput] = useState<StructuredPlanOutputByStep>({});
  const [applyStructuredOutput, setApplyStructuredOutput] = useState<StructuredApplyOutputByStep>({});
  const [terraformOutputs, setTerraformOutputs] = useState<StructuredOutputsByStep>({});
  const [jobDiagnostics, setJobDiagnostics] = useState<JobDiagnosticsByStep>({});
  const [policyEvaluation, setPolicyEvaluation] = useState<PolicyEvaluationContext | undefined>(undefined);
  const hasPolicySoftViolations = useMemo(() => {
    if (!policyEvaluation) return false;
    if ((policyEvaluation.softMandatoryViolations ?? 0) > 0) return true;
    return Boolean(policyEvaluation.results?.some((r) => (r.softMandatoryViolations ?? 0) > 0));
  }, [policyEvaluation]);
  const [contextAvailability, setContextAvailability] = useState<ContextAvailability>("pending");
  // Sticky: once a persisted context has been seen, a later transient 503 does not un-see it.
  const [contextEverPersisted, setContextEverPersisted] = useState(false);
  // Plan step id of an explicitly persisted no-change plan (sticky once observed).
  const [noChangePlanStepId, setNoChangePlanStepId] = useState<string | undefined>(undefined);
  // Non-null while we keep polling context after a terminal job status (bounded reconciliation).
  const [reconcileUntil, setReconcileUntil] = useState<number | null>(null);
  const reconcileAttemptRef = useRef(0);
  const previousJobStatusRef = useRef<string | undefined>(undefined);
  const { getSignal: getJobSignal, abort: abortJobRequests } = useAbortController();
  const { getSignal: getContextSignal, abort: abortContextRequests } = useAbortController();
  const jobRequestRef = useRef(0);
  const contextRequestRef = useRef(0);
  const pollRequestRef = useRef(0);

  const isAbortError = (error: unknown) => {
    return error instanceof Error && (error.name === "AbortError" || error.name === "CanceledError");
  };

  const parseIncompleteVariableGuard = (jobOutput?: string): IncompleteVariableGuard | null => {
    if (jobOutput == null) {
      return null;
    }

    const lines = jobOutput
      .split("\n")
      .map((line) => line.trim())
      .filter((line) => line !== "");

    if (lines.length === 0) {
      return null;
    }

    const variables = lines
      .filter((line) => line.startsWith("- "))
      .map((line) => line.slice(2).trim())
      .filter((line) => line !== "");

    const footer = lines.find((line) => line.startsWith("Open the workspace Variables page"));

    if (variables.length === 0 || footer == null) {
      return null;
    }

    return {
      title: lines[0],
      variables,
      footer,
      rawMessage: jobOutput,
    };
  };

  const isIncompleteVariableGuardStep = (stepName?: string) => {
    return stepName === INCOMPLETE_VARIABLE_GUARD_STEP_NAME;
  };

  const renderIncompleteVariableAlert = (guard: IncompleteVariableGuard) => {
    return (
      <Alert
        type="error"
        showIcon
        title="Run stopped before execution"
        description={
          <div className="run-guard">
            <Typography.Text>{guard.title}</Typography.Text>
            {guard.variables.length > 0 && (
              <div className="run-guard-variables">
                {guard.variables.map((variable) => (
                  <Tag key={variable}>
                    <code>{variable}</code>
                  </Tag>
                ))}
              </div>
            )}
            {guard.footer != null && <Typography.Text type="secondary">{guard.footer}</Typography.Text>}
          </div>
        }
      />
    );
  };

  const renderPrCommentErrorAlert = (prCommentError: string, prNumber?: number) => {
    return (
      <Alert
        type="warning"
        showIcon
        title={`Failed to post output to pull request${prNumber ? ` #${prNumber}` : ""}`}
        description={prCommentError}
      />
    );
  };

  const onChange = (e: RadioChangeEvent) => {
    const nextUIType = e.target.value as "structured" | "console";
    setUIType(nextUIType);
    try {
      localStorage.setItem(UI_TYPE_STORAGE_KEY, nextUIType);
    } catch {
      // ignore storage errors (private browsing, quota, etc.) - preference just won't persist.
    }
  };

  // Phase/step-aware structured-output evidence, so a transient job-wide 503 does not turn a
  // no-op apply (whose Plan was explicitly empty) into a false "temporarily unavailable".
  const planEvidence = (() => {
    const planEntries = Object.values(planStructuredOutput);
    const anyPlanHasRows = planEntries.some((rows) => Array.isArray(rows) && rows.length > 0);
    const markedNoChange =
      noChangePlanStepId != null &&
      !(Array.isArray(planStructuredOutput[noChangePlanStepId]) && planStructuredOutput[noChangePlanStepId].length > 0);
    const inferredNoChange =
      contextEverPersisted &&
      planEntries.length > 0 &&
      planEntries.every((rows) => Array.isArray(rows) && rows.length === 0);
    return { anyPlanHasRows, associatedPlanIsNoChange: markedNoChange || inferredNoChange };
  })();

  const getStepStructuredData = (item: JobStep) => {
    const template = uiTemplates[item.id] || uiTemplates[String(item.stepNumber)];
    const structuredChanges = planStructuredOutput[item.id] || planStructuredOutput[String(item.stepNumber)];
    const structuredApplyChanges = applyStructuredOutput[item.id] || applyStructuredOutput[String(item.stepNumber)];
    const stepOutputs = terraformOutputs[item.id] || terraformOutputs[String(item.stepNumber)];
    const stepJobDiagnostics = jobDiagnostics[item.id] || jobDiagnostics[String(item.stepNumber)];
    const hasStructuredView = Boolean(template) || Boolean(structuredChanges) || Boolean(structuredApplyChanges);

    return {
      template,
      structuredChanges,
      structuredApplyChanges,
      stepOutputs,
      stepJobDiagnostics,
      hasStructuredView,
      contextEverPersisted,
      associatedPlanIsNoChange: planEvidence.associatedPlanIsNoChange,
      anyPlanHasRows: planEvidence.anyPlanHasRows,
    };
  };

  const renderStepExtra = (item: JobStep) => {
    const guard = parseIncompleteVariableGuard(job?.data?.attributes.output);
    if (guard != null && isIncompleteVariableGuardStep(item.name)) {
      return null;
    }

    if (!getStepStructuredData(item).hasStructuredView) {
      return null;
    }

    // guard so a click inside this toggle never reaches the Collapse header's own click-to-toggle handler.
    return (
      <div onClick={(event) => event.stopPropagation()}>
        <Radio.Group onChange={onChange} value={uiType} size="small">
          <Radio.Button value="structured">Structured</Radio.Button>
          <Radio.Button value="console">Console</Radio.Button>
        </Radio.Group>
      </div>
    );
  };

  const renderStepContent = (item: JobStep) => {
    const guard = parseIncompleteVariableGuard(job?.data?.attributes.output);
    const isGuardStep = guard != null && isIncompleteVariableGuardStep(item.name);

    return (
      <StepConsole
        item={item}
        jobId={jobId}
        organizationId={organizationId ?? ""}
        guardMessage={isGuardStep ? item.outputLog : undefined}
        structured={getStepStructuredData(item)}
        uiType={uiType}
        contextAvailability={contextAvailability}
        onRetryStructured={() => void loadContext()}
      />
    );
  };

  const renderStepLabel = (item: JobStep) => {
    return (
      <span className="run-panel-label">
        {getIconStatus(item)}
        <h3 className="run-panel-title">{item.name}</h3>
        <span className="run-panel-status">{getWorkspaceStatusText(item.status)}</span>
      </span>
    );
  };

  const patchStatus = (status: "approved" | "rejected" | "cancelled", successText: string, failText: string) => {
    setActionPending(true);
    return axiosInstance
      .patch(
        `organization/${organizationId}/job/${jobId}`,
        { data: { type: "job", id: jobId, attributes: { status } } },
        { headers: { "Content-Type": "application/vnd.api+json" } }
      )
      .then(async () => {
        message.success(successText);
        await refreshJobDetails();
      })
      .catch((error) => {
        message.error(`${failText}: ${error?.response?.data?.errors?.[0]?.detail ?? error.message}`);
      })
      .finally(() => setActionPending(false));
  };

  const handleCancel = () => patchStatus("cancelled", "Run cancelled", "Could not cancel run");

  // Same icon as WorkspaceStatusTag for the status; its colour comes from .run-status-icon[data-status].
  const getIconStatus = (item: JobStep) => {
    return cloneElement(getWorkspaceStatusIcon(item.status), {
      className: "run-status-icon",
      "data-status": item.status,
      "aria-hidden": true,
    } as Record<string, unknown>);
  };

  const handleApprove = () => patchStatus("approved", "Run approved", "Could not approve run");

  const handleDiscard = () => patchStatus("rejected", "Run discarded", "Could not discard run");

  const sortbyName = (a: JobStep, b: JobStep) => {
    if (a.stepNumber < b.stepNumber) return -1;
    if (a.stepNumber > b.stepNumber) return 1;
    return 0;
  };

  // The upstream run of a job started by a run trigger. Resolved separately because the job
  // only carries the id, and the link needs the workspace it belongs to. Run triggers never
  // cross organizations, so the current one is always the right place to look.
  const upstreamJobId = job?.data?.attributes?.triggeredByJobId;
  useEffect(() => {
    if (!upstreamJobId || !organizationId) {
      setTriggeredBy(null);
      return;
    }
    axiosInstance
      .get(`organization/${organizationId}/job/${upstreamJobId}?include=workspace`)
      .then((response) => {
        const workspace = (response.data.included ?? []).find((item: any) => item.type === "workspace");
        setTriggeredBy({
          jobId: upstreamJobId,
          workspaceId: workspace?.id ?? "",
          workspaceName: workspace?.attributes?.name ?? "",
        });
      })
      // The upstream run may have been pruned from history; the id alone is still worth showing.
      .catch(() => setTriggeredBy({ jobId: upstreamJobId, workspaceId: "", workspaceName: "" }));
  }, [upstreamJobId, organizationId]);

  const loadJob = useCallback(async () => {
    const requestId = ++jobRequestRef.current;
    const signal = getJobSignal();

    try {
      const response = await axiosInstance.get(`organization/${organizationId}/job/${jobId}?include=step,workspace`, {
        signal,
      });
      if (requestId !== jobRequestRef.current) {
        return;
      }

      setJob(response.data);

      const included = response.data.included ?? [];
      const stepEntries = included.filter((item: any) => item.type === "step");
      const workspaceEntry: Workspace | undefined = included.find(
        (item: IncludedItem<Workspace>) => item.type === "workspace"
      );
      const incompleteVariableGuard = parseIncompleteVariableGuard(response.data.data.attributes.output);

      // Steps render immediately from their entity data; each StepConsole fetches its own log
      // lazily (on expand) via useStepLog, so first paint never blocks on log fetches and the
      // 5s poll below refreshes step *status* only.
      const stepsPromise = Promise.resolve(
        stepEntries.map((stepItem: any) => ({
          id: stepItem.id,
          stepNumber: stepItem.attributes.stepNumber,
          status: stepItem.attributes.status,
          output: stepItem.attributes.output,
          name: stepItem.attributes.name,
          outputLog:
            incompleteVariableGuard != null && isIncompleteVariableGuardStep(stepItem.attributes.name)
              ? incompleteVariableGuard.rawMessage
              : "",
        }))
      );

      const workspacePromise = workspaceEntry
        ? (async () => {
            const workspaceResponse = await axiosInstance.get(
              `organization/${organizationId}/workspace/${workspaceEntry.id}`,
              { signal }
            );
            const vcsId = workspaceResponse.data.data.relationships.vcs.data?.id;

            if (!vcsId) {
              return {
                source: workspaceEntry.attributes.source,
                branch: workspaceEntry.attributes.branch,
                vcsName: undefined,
              };
            }

            const vcsDataResponse = await axiosInstance.get(`organization/${organizationId}/vcs/${vcsId}`, {
              signal,
            });

            return {
              source: workspaceEntry.attributes.source,
              branch: workspaceEntry.attributes.branch,
              vcsName: vcsDataResponse.data.data.attributes.name,
            };
          })()
        : Promise.resolve(undefined);

      const [jobSteps, workspaceData] = await Promise.all([stepsPromise, workspacePromise]);
      if (requestId !== jobRequestRef.current) {
        return;
      }

      if (workspaceData) {
        setWorkspaceSource(workspaceData.source);
        setWorkspaceDefaultBranch(workspaceData.branch);
        setWorkspaceVcsName(workspaceData.vcsName);
      } else {
        setWorkspaceSource(undefined);
        setWorkspaceDefaultBranch(undefined);
        setWorkspaceVcsName(undefined);
      }

      setSteps(jobSteps.sort(sortbyName));
    } catch (error) {
      if (isAbortError(error)) return;
    }
  }, [getJobSignal, jobId, organizationId]);

  const loadContext = useCallback(async () => {
    const requestId = ++contextRequestRef.current;
    const signal = getContextSignal();
    const apiOrigin = getPublicApiOrigin();

    try {
      const response = await axiosAuxiliary.get(`${apiOrigin}/context/v1/${jobId}`, {
        signal,
        auxClass: "context",
      });
      if (requestId !== contextRequestRef.current) {
        return;
      }
      const availability = parseContextAvailability(response?.data, response?.status);
      setContextAvailability(availability);
      if (availability === "persisted") {
        setContextEverPersisted(true);
      }
      const noChangeStepId = parseNoChangePlanStepId(response?.data);
      if (noChangeStepId != null) {
        setNoChangePlanStepId(noChangeStepId);
      }
      setUITemplates(normalizeUITemplates(response?.data?.terrakubeUI));
      // Merge (not replace) plan/apply/diagnostics - this REST snapshot can lag behind the live
      // SSE stream (useStructuredOutputStream's effect below), which pushes per-step updates as
      // soon as the executor emits them. Replacing wholesale on every 5s poll would intermittently
      // wipe out a step's just-pushed live data with a stale snapshot that hasn't caught up yet.
      setPlanStructuredOutput((previous) => ({
        ...previous,
        ...normalizeStructuredPlanOutput(response?.data?.planStructuredOutput),
      }));
      setApplyStructuredOutput((previous) => ({
        ...previous,
        ...normalizeStructuredApplyOutput(response?.data?.applyStructuredOutput),
      }));
      setTerraformOutputs(normalizeStructuredOutputs(response?.data?.terraformOutputs));
      setJobDiagnostics((previous) => ({ ...previous, ...normalizeJobDiagnostics(response?.data?.jobDiagnostics) }));
      if (response?.data?.policyEvaluation) {
        setPolicyEvaluation(response.data.policyEvaluation);
      }
    } catch (error) {
      if (isAbortError(error)) return;
      if (requestId !== contextRequestRef.current) return;
      // A failed/controlled context response must not clear already-loaded structured state and
      // must never bounce the whole page back to its loading spinner.
      const httpStatus = (error as { response?: { status?: number } })?.response?.status;
      setContextAvailability(httpStatus != null && httpStatus >= 500 ? "unavailable" : "pending");
    }
  }, [getContextSignal, jobId]);

  const refreshJobDetails = useCallback(async () => {
    const requestId = ++pollRequestRef.current;
    await Promise.all([loadJob(), loadContext()]);
    if (requestId === pollRequestRef.current) {
      setLoading(false);
    }
  }, [loadContext, loadJob]);

  useEffect(() => {
    setLoading(true);
    abortJobRequests();
    abortContextRequests();

    if (!jobId) {
      setLoading(false);
      return;
    }

    void refreshJobDetails();
  }, [abortContextRequests, abortJobRequests, jobId, refreshJobDetails]);

  usePolling(
    () => {
      void refreshJobDetails();
    },
    {
      interval: 5000,
      enabled: Boolean(jobId) && !isTerminalStatus(job?.data?.attributes.status),
      immediate: false,
    }
  );

  // A terminal job status does not prove its final structured context is persisted yet. On the
  // terminal transition (and on any status transition) re-fetch context, and keep a bounded,
  // context-only reconciliation running - job metadata and console stay untouched by this.
  useEffect(() => {
    const status = job?.data?.attributes.status;
    if (status === previousJobStatusRef.current) {
      return;
    }
    const becameTerminal = !isTerminalStatus(previousJobStatusRef.current) && isTerminalStatus(status);
    previousJobStatusRef.current = status;

    if (!jobId) {
      return;
    }
    void loadContext();
    if (becameTerminal && contextAvailability !== "persisted") {
      reconcileAttemptRef.current = 0;
      setReconcileUntil(Date.now() + 60_000);
    }
  }, [job?.data?.attributes.status, contextAvailability, jobId, loadContext]);

  // Bounded exponential backoff while reconciling. Re-runs whenever loadContext updates
  // contextAvailability, so it schedules the next retry or stops.
  useEffect(() => {
    if (reconcileUntil == null) {
      return;
    }
    if (contextAvailability === "persisted") {
      recordReconciliationResult("persisted");
      setReconcileUntil(null);
      return;
    }
    if (Date.now() >= reconcileUntil) {
      recordReconciliationResult(contextAvailability === "unavailable" ? "unavailable" : "expired");
      setReconcileUntil(null);
      return;
    }
    const delay = Math.min(8000, 1000 * 2 ** reconcileAttemptRef.current);
    const timer = setTimeout(() => {
      reconcileAttemptRef.current += 1;
      void loadContext();
    }, delay);
    return () => clearTimeout(timer);
  }, [reconcileUntil, contextAvailability, loadContext]);

  type LiveStructuredOutput = {
    phase: "plan" | "apply";
    changes: Record<string, unknown>;
    jobDiagnostics: Record<string, unknown>;
  };

  const isJobRunning = job?.data?.attributes.status === "running";
  const liveStructuredOutput = useStructuredOutputStream<LiveStructuredOutput | null>({
    url: `${getPublicApiOrigin()}/context/v1/${jobId}/stream`,
    enabled: Boolean(jobId) && (isJobRunning || reconcileUntil != null),
    initial: null,
  });

  useEffect(() => {
    if (liveStructuredOutput == null) {
      return;
    }

    // A live event means the executor just wrote context - re-fetch the authoritative snapshot too.
    void loadContext();

    // Each push only carries the one step (plan or apply) that just changed, keyed by that
    // step's id - merge it into the existing per-step maps rather than replacing them wholesale,
    // otherwise a later push would wipe out an earlier step's already-loaded data.
    setJobDiagnostics((previous) => ({ ...previous, ...normalizeJobDiagnostics(liveStructuredOutput.jobDiagnostics) }));

    if (liveStructuredOutput.phase === "plan") {
      setPlanStructuredOutput((previous) => ({
        ...previous,
        ...normalizeStructuredPlanOutput(liveStructuredOutput.changes),
      }));
    } else {
      setApplyStructuredOutput((previous) => ({
        ...previous,
        ...normalizeStructuredApplyOutput(liveStructuredOutput.changes),
      }));
    }
  }, [liveStructuredOutput, loadContext]);

  // What the approver is signing off on, from the structured plan of every plan step on this run.
  const planTotals = useMemo(() => {
    const totals = { create: 0, update: 0, delete: 0, replace: 0 };
    let seen = false;
    for (const changes of Object.values(planStructuredOutput)) {
      for (const change of changes) {
        seen = true;
        const label = getPlanChangeActionLabel(change.actions, change.action);
        if (label in totals) totals[label as keyof typeof totals] += 1;
      }
    }
    return seen ? totals : null;
  }, [planStructuredOutput]);

  const planSummary = planTotals
    ? `${planTotals.create} to add, ${planTotals.update} to change, ${planTotals.delete} to destroy` +
      (planTotals.replace ? `, ${planTotals.replace} to replace` : "")
    : null;

  // Approve, discard and cancel all ask first, and say what will happen to the infrastructure.
  const [confirmAction, setConfirmAction] = useState<"approve" | "discard" | "cancel" | null>(null);
  const confirmCopy = {
    approve: {
      title: "Approve and apply this run?",
      okText: "Approve and apply",
      danger: false,
      body: planSummary ? (
        <>
          The plan is applied to the workspace: {planSummary}.
          {planTotals && planTotals.delete > 0 && (
            <>
              {" "}
              <strong>
                {planTotals.delete} {planTotals.delete === 1 ? "resource is" : "resources are"} destroyed.
              </strong>
            </>
          )}
        </>
      ) : (
        "The plan is applied to the workspace."
      ),
      onOk: handleApprove,
    },
    discard: {
      title: "Discard this run?",
      okText: "Discard run",
      danger: true,
      body: "The plan is not applied and the run is marked as discarded. Your infrastructure stays as it is.",
      onOk: handleDiscard,
    },
    cancel: {
      title: "Cancel this run?",
      okText: "Cancel run",
      danger: true,
      body: "The run stops where it is. Resources it already changed stay changed, so the next plan may show them as drift.",
      onOk: handleCancel,
    },
  };
  // The confirm modal only stands while the run still allows its action (same conditions as the buttons).
  const currentStatus = job?.data?.attributes.status;
  const isConfirmActionAllowed = (action: "approve" | "discard" | "cancel") =>
    action === "cancel"
      ? currentStatus === "running" || currentStatus === "pending"
      : currentStatus === "waitingApproval" && !hasPolicySoftViolations && canApprove;
  const confirmActionAllowed = confirmAction != null && isConfirmActionAllowed(confirmAction);
  const pendingConfirm = confirmAction && confirmActionAllowed ? confirmCopy[confirmAction] : null;

  useEffect(() => {
    if (confirmAction == null || confirmActionAllowed) return;
    setConfirmAction(null);
    // While our own PATCH is in flight the status change is the expected result, not a surprise.
    if (!actionPending) message.info("This run changed status; nothing was sent.");
  }, [confirmAction, confirmActionAllowed, actionPending]);

  useEffect(() => {
    setConfirmAction(null);
  }, [jobId]);

  const renderActionBar = () => {
    const status = job?.data?.attributes.status;
    const approvalTeam = job?.data?.attributes.approvalTeam;

    if (status === "waitingApproval" && !hasPolicySoftViolations) {
      return (
        <div className="job-action-bar" role="region" aria-label="Run approval">
          <div className="job-action-bar-text">
            <strong>Waiting for approval.</strong>{" "}
            {planSummary ? (
              <span className="job-action-bar-summary">{planSummary}.</span>
            ) : (
              "The plan is ready to review."
            )}{" "}
            {approvalTeam ? (
              <>
                Someone from <strong>{approvalTeam}</strong> must approve
                {canApprove ? "" : "; you are not a member"}.
              </>
            ) : canApprove ? (
              ""
            ) : (
              "You do not have permission to approve runs on this workspace."
            )}
          </div>
          {canApprove && (
            <Flex gap="small" wrap>
              <Button
                icon={<CheckOutlined />}
                type="primary"
                loading={actionPending}
                onClick={() => setConfirmAction("approve")}
                data-testid="approve-run"
              >
                Approve
              </Button>
              <Button
                icon={<CloseOutlined />}
                danger
                disabled={actionPending}
                onClick={() => setConfirmAction("discard")}
                data-testid="discard-run"
              >
                Discard run
              </Button>
            </Flex>
          )}
        </div>
      );
    }

    if (status === "running" || status === "pending") {
      return (
        <div className="job-action-bar" role="region" aria-label="Run controls">
          <div className="job-action-bar-text">
            <strong>{status === "running" ? "Running." : "Queued."}</strong> Cancel the run to stop it before it
            finishes.
          </div>
          <Button
            icon={<StopOutlined />}
            danger
            loading={actionPending}
            onClick={() => setConfirmAction("cancel")}
            data-testid="cancel-run"
          >
            Cancel run
          </Button>
        </div>
      );
    }

    return null;
  };

  const renderSourceFacts = (attributes: Job["attributes"]) => {
    if (workspaceDefaultBranch === "remote-content") {
      return <p className="run-facts-note">CLI-driven workflow: no VCS source for this run.</p>;
    }
    const overrideBranch = (attributes as { overrideBranch?: string }).overrideBranch;
    const facts = [
      { label: "Source", value: workspaceSource, mono: true },
      { label: "Default branch", value: workspaceDefaultBranch, mono: true },
      {
        label: "Run branch",
        value: overrideBranch !== workspaceDefaultBranch ? overrideBranch : undefined,
        mono: true,
      },
      { label: "Commit", value: attributes.commitId, mono: true },
      { label: "VCS", value: workspaceVcsName, mono: false },
    ].filter((fact) => fact.value);
    if (facts.length === 0) return null;
    return (
      <dl className="run-facts">
        {facts.map((fact) => (
          <div key={fact.label}>
            <dt>{fact.label}</dt>
            <dd>{fact.mono ? <code>{fact.value}</code> : fact.value}</dd>
          </div>
        ))}
      </dl>
    );
  };

  const policyVerdict = !policyEvaluation
    ? null
    : (policyEvaluation.hardMandatoryViolations ?? 0) > 0
      ? { color: "error", text: "Failed" }
      : (policyEvaluation.softMandatoryViolations ?? 0) > 0
        ? { color: "warning", text: "Action required" }
        : { color: "success", text: "Compliant" };

  if (loading || !job?.data || !steps) {
    return (
      <Spin spinning description="Loading run...">
        <div className="run-detail-loading" />
      </Spin>
    );
  }

  const attributes = job.data.attributes;
  const commitId = attributes.commitId && attributes.commitId !== "000000000" ? attributes.commitId : undefined;
  const guard = parseIncompleteVariableGuard(attributes.output);

  return (
    <div className="run-detail">
      {guard != null && renderIncompleteVariableAlert(guard)}
      {attributes.prCommentError ? renderPrCommentErrorAlert(attributes.prCommentError, attributes.prNumber) : null}
      <header className="run-header">
        <div className="job-headline">
          <WorkspaceStatusTag status={attributes.status} />
          <h2 className="job-headline-title">Run #{job.data.id}</h2>
          {(workspaceName || commitId) && (
            <span className="job-headline-meta">
              {workspaceName}
              {workspaceName && commitId && " · "}
              {commitId && <code>{commitId.slice(0, 7)}</code>}
            </span>
          )}
        </div>
        <p className="run-byline">
          <Avatar size={20} shape="square" icon={<UserOutlined />} />
          <span>
            <strong>{attributes.createdBy}</strong> triggered a run from <strong>{formatJobVia(attributes.via)}</strong>{" "}
            {attributes.createdDate ? relativeTime(attributes.createdDate) : ""}
            <ApprovalAttribution approvedBy={attributes.approvedBy} approvedAt={attributes.approvedAt} />
          </span>
        </p>
        {renderSourceFacts(attributes)}
      </header>
      {renderActionBar()}
      {triggeredBy && (
        <Alert
          type="info"
          showIcon
          description={
            <>
              This run started because{" "}
              {triggeredBy.workspaceId ? (
                <Link
                  to={`/organizations/${organizationId}/workspaces/${triggeredBy.workspaceId}/runs/${triggeredBy.jobId}`}
                >
                  run #{triggeredBy.jobId} on {triggeredBy.workspaceName}
                </Link>
              ) : (
                <b>run #{triggeredBy.jobId}</b>
              )}{" "}
              changed state.
              {(attributes.cascadeDepth ?? 0) > 1 && ` It is ${attributes.cascadeDepth} triggers deep in the chain.`}
            </>
          }
        />
      )}

      {policyEvaluation && policyVerdict && (
        <Collapse
          className="run-panel"
          defaultActiveKey={["policy-guardrails"]}
          items={[
            {
              key: "policy-guardrails",
              label: (
                <span className="run-panel-label">
                  <SafetyCertificateOutlined className="run-panel-icon" aria-hidden />
                  <h3 className="run-panel-title">Policy checks</h3>
                  <Tag color={policyVerdict.color}>{policyVerdict.text}</Tag>
                </span>
              ),
              children: (
                <PolicyChecksOutput
                  policyEvaluation={policyEvaluation}
                  jobId={jobId}
                  organizationId={organizationId || job.data.relationships?.organization?.data?.id}
                  workspaceId={job.data.relationships?.workspace?.data?.id}
                  status={attributes.status}
                  approvalTeam={attributes.approvalTeam}
                  canApprove={canApprove}
                  onOverrideSuccess={() => {
                    void refreshJobDetails();
                  }}
                  onRejectSuccess={() => {
                    void refreshJobDetails();
                  }}
                />
              ),
            },
          ]}
        />
      )}

      {steps.map((item) => {
        // Steps with nothing to show yet (e.g. a pending approval step) still render through
        // Collapse rather than a bare Card - a disabled panel keeps the same arrow/label/extra
        // grid as every expandable step, so rows stay in one aligned column.
        const isCollapsible = shouldStepBeCollapsible(item);

        return (
          <Collapse
            key={item.id}
            className="run-panel"
            activeKey={isCollapsible ? (activeStepKeys[item.id] ?? []) : []}
            onChange={(keys) => {
              userToggledStepIds.current.add(item.id);
              setActiveStepKeys((previous) => ({
                ...previous,
                [item.id]: Array.isArray(keys) ? keys : [keys],
              }));
            }}
            items={[
              {
                key: "2",
                label: renderStepLabel(item),
                collapsible: isCollapsible ? undefined : "disabled",
                extra: isCollapsible ? renderStepExtra(item) : undefined,
                children: isCollapsible ? renderStepContent(item) : undefined,
              },
            ]}
          />
        );
      })}

      {pendingConfirm && (
        <Modal
          className="form-modal"
          open
          title={pendingConfirm.title}
          onCancel={() => setConfirmAction(null)}
          footer={
            <Flex gap="small">
              <Button
                type="primary"
                danger={pendingConfirm.danger}
                loading={actionPending}
                onClick={() => {
                  void pendingConfirm.onOk().then(() => setConfirmAction(null));
                }}
                data-testid="confirm-run-action"
              >
                {pendingConfirm.okText}
              </Button>
              <Button onClick={() => setConfirmAction(null)}>Back</Button>
            </Flex>
          }
        >
          <Typography.Paragraph>{pendingConfirm.body}</Typography.Paragraph>
        </Modal>
      )}
    </div>
  );
};
