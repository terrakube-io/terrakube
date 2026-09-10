import React, { useMemo, useState } from "react";
import {
  Button,
  Card,
  Drawer,
  Empty,
  Form,
  Input,
  Popconfirm,
  Radio,
  Space,
  Tag,
  Tooltip,
  Typography,
  message,
  notification,
} from "antd";
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  CloseOutlined,
  ExclamationCircleOutlined,
  EyeOutlined,
  PlayCircleOutlined,
  SafetyCertificateOutlined,
  WarningOutlined,
  UnlockOutlined,
  LinkOutlined,
} from "@ant-design/icons";
import axiosInstance, { axiosRegistry, getErrorMessage } from "../../config/axiosConfig";
import { ORGANIZATION_ARCHIVE } from "../../config/actionTypes";
import { PolicyEvaluationContext, PolicyViolationItem } from "../types";
import { useOrgPermissions } from "@/modules/permissions/useOrgPermissions";
import { PolicyExemptionModal } from "../Settings/components";
import "./PolicyChecksOutput.css";

const { Text, Paragraph } = Typography;
const { TextArea } = Input;

type FlattenedRule = {
  key: string;
  policySetId?: string;
  policySetName: string;
  enforcementLevel: string;
  ruleId: string;
  address: string;
  severityType: "hard" | "soft" | "exempted" | "warning" | "passed";
  message?: string;
  ticketReference?: string;
  justification?: string;
  expiresAt?: string;
  suggestedFix?: string;
};

type Props = {
  policyEvaluation?: PolicyEvaluationContext;
  jobId?: string;
  organizationId?: string;
  workspaceId?: string;
  status?: string;
  approvalTeam?: string;
  onOverrideSuccess?: () => void;
  onRejectSuccess?: () => void;
};

export const PolicyChecksOutput: React.FC<Props> = ({
  policyEvaluation,
  jobId,
  organizationId,
  workspaceId,
  status,
  approvalTeam,
  onOverrideSuccess,
  onRejectSuccess,
}) => {
  const [filter, setFilter] = useState<"all" | "violations" | "exempted" | "warnings">("all");
  const [searchQuery, setSearchQuery] = useState("");
  const [overrideDrawerOpen, setOverrideDrawerOpen] = useState(false);
  const [overrideSubmitting, setOverrideSubmitting] = useState(false);
  const [rejectSubmitting, setRejectSubmitting] = useState(false);
  const [form] = Form.useForm();

  const effectiveOrgId = organizationId || sessionStorage.getItem(ORGANIZATION_ARCHIVE) || "";
  const { permissions: orgPermissions } = useOrgPermissions(effectiveOrgId || undefined);
  const canManagePolicies = orgPermissions.managePolicies;

  const [exemptionModalVisible, setExemptionModalVisible] = useState(false);
  const [selectedExemptionRule, setSelectedExemptionRule] = useState<FlattenedRule | null>(null);

  const handleOpenExemptionModal = (rule: FlattenedRule) => {
    setSelectedExemptionRule(rule);
    setExemptionModalVisible(true);
  };

  const handleTriggerEvaluationNow = () => {
    if (!effectiveOrgId || !workspaceId) return;
    const origin = new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin;
    axiosInstance
      .post(`${origin}/policy/v1/organization/${effectiveOrgId}/workspace/${workspaceId}/evaluation`)
      .then(() => {
        message.success("Policy evaluation dispatched successfully");
        if (onOverrideSuccess) onOverrideSuccess();
      })
      .catch((err) => {
        message.error("Failed to trigger evaluation: " + (getErrorMessage(err) || "Unknown error"));
      });
  };

  const handleExemptionSuccess = () => {
    setExemptionModalVisible(false);
    notification.success({
      message: "Exemption Created",
      description: (
        <div>
          <div>
            Policy exemption for rule <Text code>{selectedExemptionRule?.ruleId}</Text> was created successfully.
          </div>
          {workspaceId && (
            <div style={{ marginTop: 8 }}>
              <Button
                type="primary"
                size="small"
                icon={<PlayCircleOutlined />}
                onClick={handleTriggerEvaluationNow}
              >
                Evaluate Policies Now
              </Button>
            </div>
          )}
        </div>
      ),
      duration: 8,
    });
    if (onOverrideSuccess) {
      onOverrideSuccess();
    }
  };

  // Aggregate stats
  const stats = useMemo(() => {
    let passed = policyEvaluation?.passedRules ?? 0;
    let warning = policyEvaluation?.warningRules ?? 0;
    let soft = policyEvaluation?.softMandatoryViolations ?? 0;
    let hard = policyEvaluation?.hardMandatoryViolations ?? 0;
    let exempted = 0;

    if (policyEvaluation?.results) {
      for (const res of policyEvaluation.results) {
        if (res.exemptedViolations) {
          exempted += res.exemptedViolations.length;
        }
        if (!policyEvaluation.hardMandatoryViolations && res.hardMandatoryViolations) {
          hard += res.hardMandatoryViolations;
        }
        if (!policyEvaluation.softMandatoryViolations && res.softMandatoryViolations) {
          soft += res.softMandatoryViolations;
        }
        if (!policyEvaluation.warningRules && res.warningRules) {
          warning += res.warningRules;
        }
        if (!policyEvaluation.passedRules && res.passedRules) {
          passed += res.passedRules;
        }
      }
    }

    return { passed, warning, soft, hard, exempted };
  }, [policyEvaluation]);

  // Flatten all rules
  const allRules = useMemo<FlattenedRule[]>(() => {
    if (!policyEvaluation?.results || policyEvaluation.results.length === 0) {
      return [];
    }

    const rules: FlattenedRule[] = [];

    policyEvaluation.results.forEach((res, pIdx) => {
      const setName = res.policySetName || res.policySetId || "Default Policy Set";
      const enf = res.enforcementLevel || "hard-mandatory";

      // Exempted violations
      if (res.exemptedViolations) {
        res.exemptedViolations.forEach((ev, vIdx) => {
          rules.push({
            key: `exempt-${pIdx}-${vIdx}-${ev.ruleId}`,
            policySetId: res.policySetId,
            policySetName: setName,
            enforcementLevel: enf,
            ruleId: ev.ruleId,
            address: ev.address || "-",
            severityType: "exempted",
            message: ev.message,
            ticketReference: ev.ticketReference,
            justification: ev.justification,
            expiresAt: ev.expiresAt,
            suggestedFix: ev.suggestedFix,
          });
        });
      }

      // Violations
      if (res.violations) {
        res.violations.forEach((v, vIdx) => {
          const normalizedEnf = enf.toLowerCase().replace(/_/g, "-");
          let sevType: "hard" | "soft" | "warning" = "hard";
          if (normalizedEnf === "advisory") {
            sevType = "warning";
          } else if (normalizedEnf === "soft-mandatory") {
            sevType = "soft";
          }

          rules.push({
            key: `violation-${pIdx}-${vIdx}-${v.ruleId}`,
            policySetId: res.policySetId,
            policySetName: setName,
            enforcementLevel: enf,
            ruleId: v.ruleId,
            address: v.address || "-",
            severityType: sevType,
            message: v.message,
            ticketReference: v.ticketReference,
            justification: v.justification,
            expiresAt: v.expiresAt,
            suggestedFix: v.suggestedFix,
          });
        });
      }
    });

    return rules;
  }, [policyEvaluation]);

  // Filtered rules
  const filteredRules = useMemo(() => {
    return allRules.filter((rule) => {
      if (filter === "violations") {
        if (rule.severityType !== "hard" && rule.severityType !== "soft") return false;
      } else if (filter === "exempted") {
        if (rule.severityType !== "exempted") return false;
      } else if (filter === "warnings") {
        if (rule.severityType !== "warning") return false;
      }

      if (searchQuery.trim()) {
        const q = searchQuery.toLowerCase();
        return (
          rule.ruleId.toLowerCase().includes(q) ||
          rule.address.toLowerCase().includes(q) ||
          rule.policySetName.toLowerCase().includes(q) ||
          (rule.message && rule.message.toLowerCase().includes(q)) ||
          (rule.ticketReference && rule.ticketReference.toLowerCase().includes(q))
        );
      }

      return true;
    });
  }, [allRules, filter, searchQuery]);

  const handleDeepLinkToResource = (address: string) => {
    if (!address || address === "-") return;
    const cleanAddress = address.replace(/^`+|`+$/g, "");
    const target =
      document.getElementById(`resource-${cleanAddress}`) ||
      document.querySelector(`[data-resource-address="${cleanAddress}"]`);

    if (target) {
      target.scrollIntoView({ behavior: "smooth", block: "center" });
      target.classList.add("highlight-resource");
      setTimeout(() => target.classList.remove("highlight-resource"), 2000);
    } else {
      message.info(`Navigating to plan diff for ${cleanAddress}...`);
    }
  };

  const handleOverrideSubmit = async (values: { justification: string }) => {
    setOverrideSubmitting(true);
    try {
      const orgId = organizationId || sessionStorage.getItem(ORGANIZATION_ARCHIVE);

      // 1. Try to record policy check override in Remote TFE API if policy checks exist
      try {
        const runChecksRes = await axiosRegistry.get(`/remote/tfe/v2/runs/${jobId}/policy-checks`);
        const checks = runChecksRes?.data?.data;
        if (Array.isArray(checks)) {
          for (const check of checks) {
            if (check.id) {
              await axiosRegistry.post(`/remote/tfe/v2/policy-checks/${check.id}/actions/override`, {
                justification: values.justification,
              }).catch(() => {
                // Ignore individual check override failure if already overridden or not supported
              });
            }
          }
        }
      } catch {
        // Non-fatal if Remote TFE policy check endpoint is unavailable
      }

      // 2. Approve the job with Elide JSON:API headers so execution proceeds to Apply
      if (orgId) {
        const approveBody = {
          data: {
            type: "job",
            id: jobId,
            attributes: {
              status: "approved",
            },
          },
        };

        await axiosInstance.patch(`organization/${orgId}/job/${jobId}`, approveBody, {
          headers: {
            "Content-Type": "application/vnd.api+json",
          },
        });
      }

      message.success("Policy override submitted and run approved successfully!");
      setOverrideDrawerOpen(false);
      form.resetFields();
      if (onOverrideSuccess) {
        onOverrideSuccess();
      }
    } catch (err: any) {
      message.error(getErrorMessage(err) || "Failed to submit override");
    } finally {
      setOverrideSubmitting(false);
    }
  };

  const handleRejectSubmit = async () => {
    setRejectSubmitting(true);
    try {
      const orgId = organizationId || sessionStorage.getItem(ORGANIZATION_ARCHIVE);
      const justification = form.getFieldValue("justification");

      if (orgId && jobId) {
        const rejectBody: { data: { type: string; id: string; attributes: Record<string, any> } } = {
          data: {
            type: "job",
            id: jobId,
            attributes: {
              status: "rejected",
            },
          },
        };

        if (justification) {
          rejectBody.data.attributes.comments = justification;
        }

        await axiosInstance.patch(`organization/${orgId}/job/${jobId}`, rejectBody, {
          headers: {
            "Content-Type": "application/vnd.api+json",
          },
        });
      }

      message.success("Policy override rejected. Run marked as rejected.");
      setOverrideDrawerOpen(false);
      form.resetFields();
      if (onOverrideSuccess) {
        onOverrideSuccess();
      }
      if (onRejectSuccess) {
        onRejectSuccess();
      }
    } catch (err: any) {
      message.error(getErrorMessage(err) || "Failed to reject run");
    } finally {
      setRejectSubmitting(false);
    }
  };

  const renderExpirationBadge = (expiresAt?: string) => {
    if (!expiresAt) return null;
    try {
      const expDate = new Date(expiresAt);
      const now = new Date();
      const diffMs = expDate.getTime() - now.getTime();
      const diffDays = Math.ceil(diffMs / (1000 * 60 * 60 * 24));

      let countdownText = "";
      if (diffDays < 0) {
        countdownText = "Expired";
      } else if (diffDays === 0) {
        countdownText = "Expires today";
      } else {
        countdownText = `${diffDays} days remaining`;
      }

      return (
        <Tag color={diffDays < 7 ? "volcano" : "purple"}>
          Expires: {expiresAt.slice(0, 10)} ({countdownText})
        </Tag>
      );
    } catch {
      return <Tag color="purple">Expires: {expiresAt.slice(0, 10)}</Tag>;
    }
  };

  const hasSoftViolations = stats.soft > 0;
  const isWaitingApproval = status === "waitingApproval" || status === undefined;
  const canOverride =
    hasSoftViolations &&
    isWaitingApproval &&
    status !== "approved" &&
    status !== "rejected" &&
    status !== "completed" &&
    status !== "running" &&
    status !== "failed" &&
    status !== "cancelled";

  return (
    <div className="policy-checks-container">
      <div className="policy-checks-header">
        <div className="policy-checks-summary-pills">
          <div className="policy-pill policy-pill--passed" data-testid="pill-passed">
            <CheckCircleOutlined />
            <span>{stats.passed} Passed</span>
          </div>
          {stats.exempted > 0 && (
            <div className="policy-pill policy-pill--exempted" data-testid="pill-exempted">
              <SafetyCertificateOutlined />
              <span>{stats.exempted} Exempted</span>
            </div>
          )}
          {stats.warning > 0 && (
            <div className="policy-pill policy-pill--warning" data-testid="pill-warning">
              <WarningOutlined />
              <span>{stats.warning} Warnings</span>
            </div>
          )}
          {stats.soft > 0 && (
            <div className="policy-pill policy-pill--soft" data-testid="pill-soft">
              <ExclamationCircleOutlined />
              <span>{stats.soft} Soft Mandatory</span>
            </div>
          )}
          {stats.hard > 0 && (
            <div className="policy-pill policy-pill--hard" data-testid="pill-hard">
              <CloseCircleOutlined />
              <span>{stats.hard} Hard Mandatory</span>
            </div>
          )}
        </div>

        {canOverride && (
          <Space>
            <Popconfirm
              title="Reject Run"
              description="Are you sure you want to reject this run due to policy violations?"
              onConfirm={handleRejectSubmit}
              okText="Yes, Reject"
              cancelText="No"
              okButtonProps={{ danger: true }}
            >
              <Button
                danger
                icon={<CloseOutlined />}
                loading={rejectSubmitting}
                data-testid="reject-button"
              >
                Reject Run
              </Button>
            </Popconfirm>
            <Button
              type="primary"
              icon={<UnlockOutlined />}
              onClick={() => setOverrideDrawerOpen(true)}
              data-testid="override-button"
            >
              Override Policy Checks
            </Button>
          </Space>
        )}
      </div>

      <div className="policy-checks-controls">
        <Radio.Group
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          buttonStyle="solid"
          size="middle"
        >
          <Radio.Button value="all">All ({allRules.length})</Radio.Button>
          <Radio.Button value="violations">
            Violations ({stats.hard + stats.soft})
          </Radio.Button>
          <Radio.Button value="exempted">Exempted ({stats.exempted})</Radio.Button>
          <Radio.Button value="warnings">Warnings ({stats.warning})</Radio.Button>
        </Radio.Group>

        <Input.Search
          placeholder="Filter by rule, resource, or ticket..."
          allowClear
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          style={{ maxWidth: 320 }}
        />
      </div>

      <div className="policy-checks-list">
        {filteredRules.length > 0 ? (
          filteredRules.map((rule) => {
            const cardClass = `policy-rule-card policy-rule-card--${rule.severityType}`;

            return (
              <div key={rule.key} className={cardClass} data-testid="rule-card">
                <div className="policy-rule-header">
                  <Space align="center">
                    <span className="policy-rule-id">{rule.ruleId}</span>
                    <Tag>{rule.policySetName}</Tag>
                  </Space>
                  <div>
                    {rule.severityType === "hard" && (
                      <Tag color="error" icon={<CloseCircleOutlined />}>
                        Hard Mandatory
                      </Tag>
                    )}
                    {rule.severityType === "soft" && (
                      <Tag color="warning" icon={<ExclamationCircleOutlined />}>
                        Soft Mandatory
                      </Tag>
                    )}
                    {rule.severityType === "exempted" && (
                      <Tag color="purple" icon={<SafetyCertificateOutlined />}>
                        🛡️ EXEMPTED
                      </Tag>
                    )}
                    {rule.severityType === "warning" && (
                      <Tag color="blue" icon={<WarningOutlined />}>
                        Advisory
                      </Tag>
                    )}
                    {rule.severityType === "passed" && (
                      <Tag color="success" icon={<CheckCircleOutlined />}>
                        Passed
                      </Tag>
                    )}
                  </div>
                </div>

                {(rule.address && rule.address !== "-") || rule.severityType === "hard" || rule.severityType === "soft" ? (
                  <div className="policy-resource-row">
                    {rule.address && rule.address !== "-" ? (
                      <span className="policy-resource-address">{rule.address}</span>
                    ) : (
                      <span />
                    )}
                    <Space size={8}>
                      {rule.address && rule.address !== "-" && (
                        <Button
                          type="link"
                          size="small"
                          icon={<EyeOutlined />}
                          onClick={() => handleDeepLinkToResource(rule.address)}
                          data-testid={`deep-link-${rule.ruleId}`}
                        >
                          View in Plan Diff
                        </Button>
                      )}
                      {(rule.severityType === "hard" || rule.severityType === "soft") && (
                        <Tooltip
                          title={
                            !canManagePolicies
                              ? "Requires Policy Management permission to add exemptions"
                              : undefined
                          }
                        >
                          <span>
                            <Button
                              type="link"
                              size="small"
                              icon={<SafetyCertificateOutlined />}
                              onClick={() => handleOpenExemptionModal(rule)}
                              disabled={!canManagePolicies}
                              data-testid={`add-exemption-${rule.ruleId}`}
                            >
                              Add Exemption
                            </Button>
                          </span>
                        </Tooltip>
                      )}
                    </Space>
                  </div>
                ) : null}

                {rule.severityType === "exempted" && (
                  <div className="policy-exemption-box" data-testid="exemption-card">
                    <div className="policy-exemption-title">
                      <SafetyCertificateOutlined />
                      <span>Active Policy Exemption</span>
                      {rule.ticketReference && (
                        <Tag color="blue" icon={<LinkOutlined />}>
                          Ticket: {rule.ticketReference}
                        </Tag>
                      )}
                      {renderExpirationBadge(rule.expiresAt)}
                    </div>
                    {rule.justification && (
                      <div className="policy-exemption-details">
                        <i>"{rule.justification}"</i>
                      </div>
                    )}
                  </div>
                )}

                {rule.message && (
                  <div className="policy-message-box">
                    <Text>{rule.message}</Text>
                  </div>
                )}

                {rule.suggestedFix && (
                  <div className="policy-suggested-fix">
                    <b>Suggested Fix:</b> {rule.suggestedFix}
                  </div>
                )}
              </div>
            );
          })
        ) : (
          <Empty
            description={
              allRules.length === 0
                ? "All policy guardrails passed cleanly! No violations or warnings."
                : "No policy results match your filter."
            }
          />
        )}
      </div>

      {/* Soft-Mandatory Override Drawer */}
      <Drawer
        title="Override Soft-Mandatory Policy Checks"
        placement="right"
        width={580}
        onClose={() => setOverrideDrawerOpen(false)}
        open={overrideDrawerOpen}
      >
        <Paragraph>
          Soft-mandatory policy checks allow authorized teams ({approvalTeam || "configured override team"})
          to approve a run by providing an audit justification.
        </Paragraph>

        <Form form={form} layout="vertical" onFinish={handleOverrideSubmit}>
          <Form.Item
            name="justification"
            label="Override Justification"
            rules={[{ required: true, message: "Please provide a justification for this override" }]}
          >
            <TextArea
              rows={4}
              placeholder="E.g. Approved by SecOps for emergency mitigation (ticket SEC-9102)..."
            />
          </Form.Item>

          <Form.Item style={{ marginTop: 24 }}>
            <Space style={{ width: "100%", justifyContent: "flex-end" }} size="middle" wrap>
              <Button
                danger
                loading={rejectSubmitting}
                onClick={handleRejectSubmit}
                data-testid="reject-override-btn"
              >
                Reject Policy Override
              </Button>
              <Button
                type="primary"
                htmlType="submit"
                loading={overrideSubmitting}
                data-testid="submit-override-btn"
              >
                Approve Exception & Proceed to Apply
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Drawer>

      {effectiveOrgId && (
        <PolicyExemptionModal
          visible={exemptionModalVisible}
          mode="create"
          organizationId={effectiveOrgId}
          lockedScope={
            workspaceId
              ? {
                  scopeType: "WORKSPACE",
                  workspaceId: workspaceId,
                }
              : undefined
          }
          initialData={
            selectedExemptionRule
              ? {
                  policySetId: selectedExemptionRule.policySetId,
                  ruleId: selectedExemptionRule.ruleId,
                  scopeType: workspaceId ? "WORKSPACE" : "ORGANIZATION",
                  workspaceId: workspaceId,
                  justification:
                    selectedExemptionRule.address && selectedExemptionRule.address !== "-"
                      ? `Exemption approved for resource ${selectedExemptionRule.address}`
                      : "",
                }
              : undefined
          }
          onCancel={() => setExemptionModalVisible(false)}
          onSuccess={handleExemptionSuccess}
        />
      )}
    </div>
  );
};
