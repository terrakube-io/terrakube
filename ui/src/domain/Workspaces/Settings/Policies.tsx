import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  ExclamationCircleOutlined,
  InfoCircleOutlined,
  PlayCircleOutlined,
  QuestionCircleOutlined,
  SafetyCertificateOutlined,
} from "@ant-design/icons";
import { Alert, Button, Card, Space, Tag, Tooltip, Typography, message } from "antd";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import axiosInstance from "../../../config/axiosConfig";
import { Workspace } from "../../types";
import SettingsSection from "@/components/settings/SettingsSection/SettingsSection";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";

const { Text, Paragraph } = Typography;

type Props = {
  workspace: Workspace;
  manageWorkspace: boolean;
  planJob?: boolean;
  onWorkspaceUpdate?: () => void;
};

export const WorkspacePolicies = ({ workspace, manageWorkspace, planJob = false, onWorkspaceUpdate }: Props) => {
  const navigate = useNavigate();
  const organizationId = workspace?.relationships?.organization?.data?.id;
  const workspaceId = workspace?.id;
  const isLocked = workspace?.attributes?.locked;
  const lastJobStatus = workspace?.attributes?.lastJobStatus;
  const complianceStatus = workspace?.attributes?.policyComplianceStatus || "UNKNOWN";

  const [loading, setLoading] = useState(false);

  const canEvaluate = manageWorkspace || planJob;
  const hasCompletedRun = Boolean(lastJobStatus && lastJobStatus !== "NeverExecuted");

  const getDisabledReason = (): string | undefined => {
    if (!canEvaluate) {
      return "You do not have permission to trigger policy evaluations on this workspace (requires manageWorkspace or planJob).";
    }
    if (isLocked) {
      return "This workspace is currently locked. Unlock it before evaluating policies.";
    }
    if (!hasCompletedRun) {
      return "Workspace has no completed runs with a Terraform plan to evaluate.";
    }
    return undefined;
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

  const renderComplianceBadge = () => {
    switch (complianceStatus) {
      case "COMPLIANT":
        return (
          <Tag color="success" icon={<CheckCircleOutlined />}>
            COMPLIANT
          </Tag>
        );
      case "NON_COMPLIANT":
        return (
          <Tag color="error" icon={<CloseCircleOutlined />}>
            NON-COMPLIANT
          </Tag>
        );
      case "EXEMPTED":
        return (
          <Tag color="processing" icon={<ExclamationCircleOutlined />}>
            EXEMPTED
          </Tag>
        );
      case "UNKNOWN":
      default:
        return (
          <Tag color="default" icon={<QuestionCircleOutlined />}>
            UNKNOWN
          </Tag>
        );
    }
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
        description="Manage and evaluate OPA policy governance compliance for this workspace."
      />

      <SettingsSection>
        <Card
          title={
            <Space orientation="horizontal">
              <SafetyCertificateOutlined />
              <span>Compliance Status</span>
            </Space>
          }
          style={{ marginBottom: 24 }}
        >
          <Space orientation="vertical" style={{ width: "100%" }}>
            <div>
              <Text strong style={{ marginRight: 8 }}>
                Current Status:
              </Text>
              {renderComplianceBadge()}
            </div>
            <Paragraph type="secondary" style={{ margin: 0 }}>
              {renderComplianceDescription()}
            </Paragraph>
          </Space>
        </Card>

        <Card
          title={
            <Space orientation="horizontal">
              <PlayCircleOutlined />
              <span>Trigger Policy Evaluation</span>
            </Space>
          }
        >
          <Paragraph>
            Trigger an on-demand compliance scan to evaluate all applicable OPA policies against the last completed
            Terraform plan for this workspace.
          </Paragraph>
          <Alert
            title="Headless Evaluation"
            description="Policy evaluation runs in-runner against the stored plan without creating or modifying live infrastructure."
            type="info"
            showIcon
            icon={<InfoCircleOutlined />}
            style={{ marginBottom: 24 }}
          />

          {isLocked && (
            <Alert
              title="Workspace Locked"
              description="This workspace is currently locked. You must unlock it before triggering a policy evaluation."
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
            />
          )}

          {!hasCompletedRun && (
            <Alert
              title="No Completed Runs"
              description="This workspace has not completed any runs with a Terraform plan yet. Run a plan before evaluating policies."
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
            />
          )}

          <Tooltip title={disabledReason}>
            <span>
              <Button
                type="primary"
                icon={<PlayCircleOutlined />}
                onClick={handleTriggerEvaluation}
                loading={loading}
                disabled={isDisabled}
              >
                Evaluate Policies Now
              </Button>
            </span>
          </Tooltip>
        </Card>
      </SettingsSection>
    </div>
  );
};
