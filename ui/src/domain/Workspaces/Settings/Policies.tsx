import { PlayCircleOutlined, PlusOutlined } from "@ant-design/icons";
import { Alert, Button, Tooltip, message } from "antd";
import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import axiosInstance, { getErrorMessage } from "../../../config/axiosConfig";
import { ORGANIZATION_ARCHIVE } from "../../../config/actionTypes";
import { Workspace } from "../../types";
import SettingsSection from "@/components/settings/SettingsSection/SettingsSection";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import PolicyStatusTag from "@/components/display/PolicyStatusTag";
import { useOrgPermissions } from "@/modules/permissions/useOrgPermissions";
import { ExemptionRecord, PolicyExemptionModal, PolicyExemptionTable } from "../../Settings/components";
import "../Workspaces.css";

type Props = {
  workspace: Workspace;
  manageWorkspace: boolean;
  planJob?: boolean;
  onWorkspaceUpdate?: () => void;
};

export const WorkspacePolicies = ({ workspace, manageWorkspace, planJob = false, onWorkspaceUpdate }: Props) => {
  const navigate = useNavigate();
  const organizationId =
    workspace?.relationships?.organization?.data?.id || sessionStorage.getItem(ORGANIZATION_ARCHIVE);
  const workspaceId = workspace?.id;
  const workspaceProjectId = workspace?.relationships?.project?.data?.id;
  const isLocked = workspace?.attributes?.locked;
  const lastJobStatus = workspace?.attributes?.lastJobStatus;
  const complianceStatus = workspace?.attributes?.policyComplianceStatus || "UNKNOWN";

  const { permissions: orgPermissions } = useOrgPermissions(organizationId || undefined);
  const canManagePolicies = orgPermissions.managePolicies;

  const [loading, setLoading] = useState(false);
  const [rawExemptions, setRawExemptions] = useState<any[]>([]);
  const [includedMap, setIncludedMap] = useState<Record<string, Record<string, any>>>({});
  const [exemptionsLoading, setExemptionsLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [modalMode, setModalMode] = useState<"create" | "edit">("create");
  const [editingExemption, setEditingExemption] = useState<any | null>(null);

  const loadExemptions = () => {
    if (!organizationId) return;
    setExemptionsLoading(true);
    axiosInstance
      .get(`organization/${organizationId}/policyExemption?include=policySet,workspace,project`)
      .then((res) => {
        const items = res.data?.data || [];
        const included = res.data?.included || [];

        const incMap: Record<string, Record<string, any>> = {};
        included.forEach((inc: any) => {
          if (!incMap[inc.type]) {
            incMap[inc.type] = {};
          }
          incMap[inc.type][inc.id] = inc.attributes;
        });

        setIncludedMap(incMap);
        setRawExemptions(items);
      })
      .catch(() => {})
      .finally(() => setExemptionsLoading(false));
  };

  useEffect(() => {
    loadExemptions();
  }, [organizationId, workspaceId]);

  const workspaceExemptions = useMemo<ExemptionRecord[]>(() => {
    return rawExemptions
      .filter((item: any) => {
        const wsId = item.relationships?.workspace?.data?.id;
        const projId = item.relationships?.project?.data?.id;

        if (wsId && wsId === workspaceId) return true;
        if (projId && projId === workspaceProjectId) return true;
        if (!wsId && !projId) return true;
        return false;
      })
      .map((item: any) => {
        const attrs = item.attributes || {};
        const rels = item.relationships || {};
        const psId = rels.policySet?.data?.id;
        const wsId = rels.workspace?.data?.id;
        const projId = rels.project?.data?.id;

        let scopeType: "ORGANIZATION" | "PROJECT" | "WORKSPACE" = "ORGANIZATION";
        if (wsId) scopeType = "WORKSPACE";
        else if (projId) scopeType = "PROJECT";

        return {
          id: item.id,
          ruleId: attrs.ruleId || "",
          policySetId: psId,
          policySetName: includedMap["policy_set"]?.[psId]?.name || psId || "Policy Set",
          ticketReference: attrs.ticketReference || "",
          justification: attrs.justification || "",
          expiresAt: attrs.expiresAt || null,
          scopeType,
          workspaceId: wsId,
          workspaceName: includedMap["workspace"]?.[wsId]?.name,
          projectId: projId,
          projectName: includedMap["project"]?.[projId]?.name,
          createdDate: attrs.createdDate,
          createdBy: attrs.createdBy,
          isInherited: wsId !== workspaceId,
        };
      });
  }, [rawExemptions, includedMap, workspaceId, workspaceProjectId]);

  const handleCreateExemption = () => {
    setModalMode("create");
    setEditingExemption(null);
    setModalVisible(true);
  };

  const handleEditExemption = (rec: ExemptionRecord) => {
    setModalMode("edit");
    setEditingExemption(rec);
    setModalVisible(true);
  };

  const handleDeleteExemption = async (rec: ExemptionRecord) => {
    try {
      await axiosInstance.delete(`policy_exemption/${rec.id}`);
      message.success(`Policy exemption for ${rec.ruleId} revoked successfully`);
      loadExemptions();
      onWorkspaceUpdate?.();
    } catch (err: any) {
      message.error(getErrorMessage(err) || "Failed to revoke exemption");
    }
  };

  const canEvaluate = manageWorkspace || planJob;
  const hasCompletedRun = Boolean(lastJobStatus && lastJobStatus !== "NeverExecuted");

  const getDisabledReason = (): string | undefined => {
    if (!canEvaluate) {
      return "You need permission to manage this workspace or plan runs to evaluate policies.";
    }
    const blockers = [
      isLocked && "This workspace is locked. Unlock it to evaluate policies.",
      !hasCompletedRun && "This workspace has no completed run with a plan yet. Run a plan first.",
    ].filter(Boolean);
    return blockers.length > 0 ? blockers.join(" ") : undefined;
  };

  const disabledReason = getDisabledReason();
  const isDisabled = Boolean(disabledReason);

  const handleTriggerEvaluation = () => {
    if (isDisabled) return;

    setLoading(true);
    const origin = new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin;
    axiosInstance
      .post(`${origin}/policy/v1/organization/${organizationId}/workspace/${workspaceId}/evaluation`)
      .then((response) => {
        message.success("Policy evaluation dispatched successfully");
        if (onWorkspaceUpdate) {
          onWorkspaceUpdate();
        }
        if (response?.data?.jobId) {
          navigate(`/organizations/${organizationId}/workspaces/${workspaceId}/runs/${response.data.jobId}`);
        }
      })
      .catch((error) => {
        const errorMsg = error?.response?.data?.message || error?.message || "Failed to dispatch policy evaluation";
        message.error("Policy evaluation failed: " + errorMsg);
      })
      .finally(() => {
        setLoading(false);
      });
  };

  const renderComplianceDescription = () => {
    switch (complianceStatus) {
      case "COMPLIANT":
        return "All evaluated OPA policy checks passed without any blocking violations.";
      case "NON_COMPLIANT":
        return "Violations were detected during policy checks. Review recent runs for policy violation details.";
      case "EXEMPTED":
        return "Violations exist but have been waived by active, unexpired policy exemptions.";
      case "UNKNOWN":
      default:
        return "No policy evaluation has been recorded yet for this workspace.";
    }
  };

  return (
    <div className="generalSettings">
      <SettingsPageHeader
        title="Policies"
        description="Check this workspace against your organization's OPA policies and see which rules are waived."
      />

      <SettingsSection title="Compliance status" description={renderComplianceDescription()}>
        <PolicyStatusTag status={complianceStatus} />
      </SettingsSection>

      <SettingsSection
        title="Evaluate policies"
        description="Checks the last completed plan against every policy that applies to this workspace. Nothing is planned or applied, and no infrastructure changes."
      >
        {disabledReason && canEvaluate && (
          <Alert title={disabledReason} type="warning" showIcon className="workspace-policies-alert" />
        )}
        <Tooltip title={canEvaluate ? undefined : disabledReason}>
          <span>
            <Button
              type="primary"
              icon={<PlayCircleOutlined />}
              onClick={handleTriggerEvaluation}
              loading={loading}
              disabled={isDisabled}
            >
              Evaluate policies now
            </Button>
          </span>
        </Tooltip>
      </SettingsSection>

      <SettingsSection
        maxWidth="100%"
        title="Policy exemptions"
        description="Rules waived for this workspace, including exemptions inherited from the organization and project."
        extra={
          <Tooltip title={!canManagePolicies ? "Requires policy management permission" : undefined}>
            <span>
              <Button
                icon={<PlusOutlined />}
                onClick={handleCreateExemption}
                disabled={!canManagePolicies}
                data-testid="add-workspace-exemption-btn"
              >
                Add exemption
              </Button>
            </span>
          </Tooltip>
        }
      >
        <PolicyExemptionTable
          items={workspaceExemptions}
          loading={exemptionsLoading}
          managePermission={canManagePolicies}
          currentWorkspaceId={workspaceId}
          onEdit={handleEditExemption}
          onDelete={handleDeleteExemption}
          pageSize={5}
        />
      </SettingsSection>

      {organizationId && (
        <PolicyExemptionModal
          visible={modalVisible}
          mode={modalMode}
          lockedScope={{
            scopeType: "WORKSPACE",
            workspaceId: workspaceId,
            workspaceName: workspace?.attributes?.name,
          }}
          initialData={
            editingExemption
              ? {
                  id: editingExemption.id,
                  policySetId: editingExemption.policySetId,
                  ruleId: editingExemption.ruleId,
                  scopeType: editingExemption.scopeType,
                  workspaceId: editingExemption.workspaceId,
                  projectId: editingExemption.projectId,
                  ticketReference: editingExemption.ticketReference,
                  justification: editingExemption.justification,
                  expiresAt: editingExemption.expiresAt,
                }
              : undefined
          }
          organizationId={organizationId}
          onCancel={() => setModalVisible(false)}
          onSuccess={() => {
            setModalVisible(false);
            loadExemptions();
            onWorkspaceUpdate?.();
          }}
        />
      )}
    </div>
  );
};
