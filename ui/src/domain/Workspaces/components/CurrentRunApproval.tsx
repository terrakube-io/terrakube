import { CheckOutlined, CloseOutlined } from "@ant-design/icons";
import { Button, Flex, message, Modal, Typography } from "antd";
import type { AxiosRequestConfig } from "axios";
import { useEffect, useRef, useState } from "react";
import axiosInstance, { axiosAuxiliary } from "../../../config/axiosConfig";
import { getPublicApiOrigin } from "../../Jobs/outputUrl";
import { hasBlockingPolicyViolations } from "../../Jobs/policyViolations";
import {
  computePlanTotals,
  formatPlanSummary,
  normalizeStructuredPlanOutput,
  PlanTotals,
} from "../../Jobs/structuredPlan";

type Props = {
  organizationId: string;
  jobId: string;
  /** Status of the run. The buttons exist only while it is `waitingApproval`. */
  status: string;
  /** The `approveJob` permission of the current user. */
  canApprove: boolean;
  /** False until the permissions of the user are known, so the "no permission" note does not flash. */
  permissionsLoaded: boolean;
  /** Team the template asks to approve, shown for information. */
  approvalTeam?: string | null;
  /** Called after the run was approved or discarded, so the page reloads its runs. */
  onChanged: () => void;
};

// "unavailable" means the result could not be read (yet); "needs-review" means it was read and has violations.
type PolicyCheck = "checking" | "clear" | "needs-review" | "unavailable";

// The context of a run can lag a moment behind its status, so a failed read is retried a few times.
const MAX_CONTEXT_ATTEMPTS = 6;

/**
 * Approve / Discard for the run shown in the workspace overview card. Applies the same rules as the
 * run details page: only users with the approve permission see the buttons, a run whose policy
 * evaluation has violations (or whose policy result cannot be read) cannot be approved from here, and
 * the confirmation says what the plan changes, including what it destroys. Discarding changes nothing in
 * the infrastructure, so it stays available either way.
 */
export const CurrentRunApproval = ({
  organizationId,
  jobId,
  status,
  canApprove,
  permissionsLoaded,
  approvalTeam,
  onChanged,
}: Props) => {
  const waiting = status === "waitingApproval";
  const [policyCheck, setPolicyCheck] = useState<PolicyCheck>("checking");
  const [planTotals, setPlanTotals] = useState<PlanTotals | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [confirm, setConfirm] = useState<"approve" | "discard" | null>(null);
  const [pending, setPending] = useState(false);
  const pendingRef = useRef(false);

  useEffect(() => {
    setPolicyCheck("checking");
    setPlanTotals(null);
    setAttempt(0);
    setConfirm(null);
  }, [jobId]);

  useEffect(() => {
    if (!canApprove || !waiting) return;
    const controller = new AbortController();
    let retry: ReturnType<typeof setTimeout> | undefined;
    const config: AxiosRequestConfig = { signal: controller.signal, auxClass: "context" };
    axiosAuxiliary
      .get(`${getPublicApiOrigin()}/context/v1/${jobId}`, config)
      .then((response) => {
        setPlanTotals(computePlanTotals(normalizeStructuredPlanOutput(response?.data?.planStructuredOutput)));
        setPolicyCheck(hasBlockingPolicyViolations(response?.data?.policyEvaluation) ? "needs-review" : "clear");
      })
      .catch((error) => {
        if (controller.signal.aborted || error?.name === "CanceledError") return;
        setPolicyCheck("unavailable");
        if (attempt + 1 < MAX_CONTEXT_ATTEMPTS) {
          retry = setTimeout(() => setAttempt(attempt + 1), Math.min(8000, 1000 * 2 ** attempt));
        }
      });
    return () => {
      controller.abort();
      if (retry) clearTimeout(retry);
    };
  }, [jobId, canApprove, waiting, attempt]);

  // The run moved on while the dialog was open (someone else approved, discarded or cancelled it).
  useEffect(() => {
    if (confirm == null || waiting) return;
    setConfirm(null);
    if (!pendingRef.current) message.info("This run changed status; nothing was sent.");
  }, [confirm, waiting]);

  if (!waiting) return null;
  if (!permissionsLoaded) return null;

  const teamNote = approvalTeam ? (
    <>
      {" "}
      Someone from <strong>{approvalTeam}</strong> must approve{canApprove ? "" : "; you are not a member"}.
    </>
  ) : null;

  if (!canApprove) {
    return (
      <Typography.Text type="secondary" className="latest-run-approval-note">
        This run is waiting for approval.
        {teamNote ?? " You do not have permission to approve runs on this workspace."}
      </Typography.Text>
    );
  }

  const send = (nextStatus: "approved" | "rejected", successText: string, failText: string) => {
    pendingRef.current = true;
    setPending(true);
    return axiosInstance
      .patch(
        `organization/${organizationId}/job/${jobId}`,
        { data: { type: "job", id: jobId, attributes: { status: nextStatus } } },
        { headers: { "Content-Type": "application/vnd.api+json" } }
      )
      .then(() => {
        message.success(successText);
        setConfirm(null);
        onChanged();
      })
      .catch((error) => {
        message.error(`${failText}: ${error?.response?.data?.errors?.[0]?.detail ?? error.message}`);
      })
      .finally(() => {
        pendingRef.current = false;
        setPending(false);
      });
  };

  const copy = {
    approve: {
      title: "Approve and apply this run?",
      okText: "Approve and apply",
      danger: false,
      body: planTotals ? (
        <>
          The plan is applied to the workspace: {formatPlanSummary(planTotals)}.
          {planTotals.delete > 0 && (
            <>
              {" "}
              <strong>
                {planTotals.delete} {planTotals.delete === 1 ? "resource is" : "resources are"} destroyed.
              </strong>
            </>
          )}
        </>
      ) : (
        "The plan is applied to the workspace. The plan summary is not available here, open the run first to review what it changes."
      ),
      onOk: () => send("approved", "Run approved", "Could not approve run"),
    },
    discard: {
      title: "Discard this run?",
      okText: "Discard run",
      danger: true,
      body: "The plan is not applied and the run is marked as discarded. Your infrastructure stays as it is.",
      onOk: () => send("rejected", "Run discarded", "Could not discard run"),
    },
  };

  return (
    <>
      <Flex gap="small" wrap align="center">
        {policyCheck === "needs-review" && (
          <Typography.Text type="secondary" className="latest-run-approval-note">
            This run cannot be approved from here until its plan and policy results are reviewed on the run page.
          </Typography.Text>
        )}
        {policyCheck === "unavailable" && (
          <Typography.Text type="secondary" className="latest-run-approval-note">
            {attempt + 1 < MAX_CONTEXT_ATTEMPTS ? (
              "Could not read the plan and policy results yet, trying again."
            ) : (
              <>
                Could not read the plan and policy results.{" "}
                <Button type="link" size="small" onClick={() => setAttempt(0)}>
                  Check again
                </Button>
              </>
            )}
          </Typography.Text>
        )}
        {(policyCheck === "checking" || policyCheck === "clear") && (
          <Button
            type="primary"
            icon={<CheckOutlined />}
            loading={pending}
            disabled={policyCheck === "checking"}
            onClick={() => setConfirm("approve")}
            data-testid="current-run-approve"
          >
            Approve
          </Button>
        )}
        <Button
          danger
          icon={<CloseOutlined />}
          disabled={pending}
          onClick={() => setConfirm("discard")}
          data-testid="current-run-discard"
        >
          Discard run
        </Button>
        {teamNote && (
          <Typography.Text type="secondary" className="latest-run-approval-note">
            {teamNote}
          </Typography.Text>
        )}
      </Flex>
      {confirm && (
        <Modal
          className="form-modal"
          open
          title={copy[confirm].title}
          closable={!pending}
          mask={{ closable: !pending }}
          keyboard={!pending}
          onCancel={() => setConfirm(null)}
          footer={
            <Flex gap="small">
              <Button
                type="primary"
                danger={copy[confirm].danger}
                loading={pending}
                onClick={() => void copy[confirm].onOk()}
                data-testid="current-run-confirm"
              >
                {copy[confirm].okText}
              </Button>
              <Button disabled={pending} onClick={() => setConfirm(null)}>
                Back
              </Button>
            </Flex>
          }
        >
          <Typography.Paragraph>{copy[confirm].body}</Typography.Paragraph>
        </Modal>
      )}
    </>
  );
};
