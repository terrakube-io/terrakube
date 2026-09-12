import React, { useMemo, useState } from "react";
import {
  Button,
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
  FileTextOutlined,
  PlayCircleOutlined,
  SafetyCertificateOutlined,
  WarningOutlined,
  UnlockOutlined,
  LinkOutlined,
} from "@ant-design/icons";
import axiosInstance, { axiosRegistry, getErrorMessage } from "../../config/axiosConfig";
import { ORGANIZATION_ARCHIVE } from "../../config/actionTypes";
import { PolicyEvaluationContext } from "../types";
import { useOrgPermissions } from "@/modules/permissions/useOrgPermissions";
import { PolicyExemptionModal } from "../Settings/components";
import "./PolicyChecksOutput.css";

const { Text, Paragraph } = Typography;
const { TextArea } = Input;

// eslint-disable-next-line no-control-regex
const ANSI_REGEX = /\u001B\[[0-9;]*m/g;

const formatEnforcementLevel = (level?: string) => {
  if (!level) return "";
  const clean = level.toLowerCase().replace(/[-_]/g, " ");
  if (clean === "hard mandatory") return "Hard Mandatory";
  if (clean === "soft mandatory") return "Soft Mandatory";
  if (clean === "advisory") return "Advisory";
  return clean.replace(/\b\w/g, (c) => c.toUpperCase());
};

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
  expiresAt?: string | number;
  suggestedFix?: string;
  logs?: string[];
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
  const [filter, setFilter] = useState<"all" | "passed" | "violations" | "exempted" | "warnings">("all");
  const [expandedLogs, setExpandedLogs] = useState<Record<string, boolean>>({});
  const [searchQuery, setSearchQuery] = useState("");
  const [overrideDrawerOpen, setOverrideDrawerOpen] = useState(false);
  const [overrideSubmitting, setOverrideSubmitting] = useState(false);
  const [rejectSubmitting, setRejectSubmitting] = useState(false);
  const [form] = Form.useForm();

  const toggleLogs = (key: string) => {
    setExpandedLogs((prev) => ({
      ...prev,
      [key]: !prev[key],
    }));
  };

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
              <Button type="primary" size="small" icon={<PlayCircleOutlined />} onClick={handleTriggerEvaluationNow}>
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
        if (!policyEvaluation.passedRules) {
          if (res.passedRules) {
            passed += res.passedRules;
          } else if (res.status?.toUpperCase() === "PASSED") {
            passed += 1;
          }
        }
      }
    }

    return { passed, warning, soft, hard, exempted };
  }, [policyEvaluation]);

  // Flatten all rules
  const allRules = useMemo<FlattenedRule[]>(() => {
    const rules: FlattenedRule[] = [];

    if (policyEvaluation?.results && policyEvaluation.results.length > 0) {
      policyEvaluation.results.forEach((res, pIdx) => {
        const setName = res.policySetName || res.policySetId || "Default Policy Set";
        const enf = res.enforcementLevel || "hard-mandatory";
        const normalizedEnf = enf.toLowerCase().replace(/_/g, "-");

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

        let warningRulesCountInViolations = 0;

        // Violations and Warnings
        const rawViolations = [...(res.violations || []), ...((res as any).warnings || [])];
        if (rawViolations.length > 0) {
          rawViolations.forEach((v, vIdx) => {
            let sevType: "hard" | "soft" | "warning" = "hard";
            const isWarningStatus = v.status?.toUpperCase() === "WARNING";
            if (isWarningStatus || normalizedEnf === "advisory") {
              sevType = "warning";
              warningRulesCountInViolations++;
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

        // Passed Policy Check
        const hasViolations =
          rawViolations.length > 0 || (res.hardMandatoryViolations ?? 0) > 0 || (res.softMandatoryViolations ?? 0) > 0;
        const hasWarnings = (res.warningRules ?? 0) > 0 || warningRulesCountInViolations > 0;
        const isCleanPassed =
          res.status?.toUpperCase() === "PASSED" ||
          (!hasViolations &&
            !hasWarnings &&
            ((res.passedRules ?? 0) > 0 || !res.exemptedViolations || res.exemptedViolations.length === 0));

        if (isCleanPassed && !hasViolations && !hasWarnings) {
          rules.push({
            key: `passed-${pIdx}-${res.policySetId || setName}`,
            policySetId: res.policySetId,
            policySetName: setName,
            enforcementLevel: enf,
            ruleId: setName,
            address: "-",
            severityType: "passed",
            message: "All policy guardrails evaluated and passed cleanly with zero violations.",
            logs: res.bufferedLogs,
          });
        }

        // Resilience / Fallback for already-evaluated jobs where warning details weren't saved in violations
        const expectedWarnings = res.warningRules || 0;
        if (expectedWarnings > warningRulesCountInViolations) {
          let extractedFromLogs = 0;
          if (res.bufferedLogs && Array.isArray(res.bufferedLogs)) {
            res.bufferedLogs.forEach((logLine, lIdx) => {
              const cleanLine = logLine.replace(ANSI_REGEX, "").trim();
              const match = cleanLine.match(/\[WARN\]\s+Rule\s+'([^']+)'(?:\s+on\s+'([^']+)')?:\s*(.*)/i);
              if (match) {
                const ruleId = match[1];
                const address = match[2] || "-";
                const msg = match[3];
                if (
                  !rules.some((r) => r.ruleId === ruleId && r.policySetName === setName && r.severityType === "warning")
                ) {
                  rules.push({
                    key: `fallback-warn-${pIdx}-${lIdx}-${ruleId}`,
                    policySetId: res.policySetId,
                    policySetName: setName,
                    enforcementLevel: enf,
                    ruleId: ruleId,
                    address: address,
                    severityType: "warning",
                    message: msg,
                  });
                  extractedFromLogs++;
                }
              }
            });
          }

          if (warningRulesCountInViolations + extractedFromLogs < expectedWarnings) {
            const remaining = expectedWarnings - (warningRulesCountInViolations + extractedFromLogs);
            for (let i = 0; i < remaining; i++) {
              rules.push({
                key: `fallback-placeholder-${pIdx}-${i}`,
                policySetId: res.policySetId,
                policySetName: setName,
                enforcementLevel: enf,
                ruleId: `${setName}_advisory_warning`,
                address: "-",
                severityType: "warning",
                message: "Advisory warning detected during evaluation. See execution logs for details.",
              });
            }
          }
        }
      });
    }

    // Ensure all reported warnings from stats are accounted for
    const currentWarningCount = rules.filter((r) => r.severityType === "warning").length;
    if (stats.warning > currentWarningCount) {
      const missing = stats.warning - currentWarningCount;
      for (let i = 0; i < missing; i++) {
        rules.push({
          key: `root-fallback-warning-${i}`,
          policySetName: "Advisory Policies",
          enforcementLevel: "advisory",
          ruleId: `advisory_warning_${i + 1}`,
          address: "-",
          severityType: "warning",
          message: "Advisory warning detected during evaluation.",
        });
      }
    }

    // Ensure all reported passed rules from stats are accounted for
    const currentPassedCount = rules.filter((r) => r.severityType === "passed").length;
    if (stats.passed > currentPassedCount) {
      const missing = stats.passed - currentPassedCount;
      for (let i = 0; i < missing; i++) {
        rules.push({
          key: `root-fallback-passed-${i}`,
          policySetName: `Policy Set ${i + 1}`,
          enforcementLevel: "hard-mandatory",
          ruleId: `policy_passed_${i + 1}`,
          address: "-",
          severityType: "passed",
          message: "All policy guardrails evaluated and passed cleanly with zero violations.",
        });
      }
    }

    return rules;
  }, [policyEvaluation, stats.warning, stats.passed]);

  // Filtered rules
  const filteredRules = useMemo(() => {
    return allRules.filter((rule) => {
      if (filter === "passed") {
        if (rule.severityType !== "passed") return false;
      } else if (filter === "violations") {
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
              await axiosRegistry
                .post(`/remote/tfe/v2/policy-checks/${check.id}/actions/override`, {
                  justification: values.justification,
                })
                .catch(() => {
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

  const renderExpirationBadge = (expiresAt?: string | number) => {
    if (!expiresAt) return null;
    try {
      const expDate = new Date(expiresAt);
      const now = new Date();
      const diffMs = expDate.getTime() - now.getTime();
      const diffDays = Math.ceil(diffMs / (1000 * 60 * 60 * 24));
      const formattedDate = !isNaN(expDate.getTime())
        ? expDate.toISOString().slice(0, 10)
        : String(expiresAt).slice(0, 10);

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
          Expires: {formattedDate} ({countdownText})
        </Tag>
      );
    } catch {
      const fallbackStr = String(expiresAt || "").slice(0, 10);
      return <Tag color="purple">Expires: {fallbackStr}</Tag>;
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
          <div
            className={`policy-pill policy-pill--passed policy-pill--clickable ${filter === "passed" ? "policy-pill--active" : ""}`}
            data-testid="pill-passed"
            onClick={() => setFilter((prev) => (prev === "passed" ? "all" : "passed"))}
            onKeyDown={(e) => {
              if (e.key === "Enter" || e.key === " ") {
                e.preventDefault();
                setFilter((prev) => (prev === "passed" ? "all" : "passed"));
              }
            }}
            role="button"
            tabIndex={0}
          >
            <CheckCircleOutlined />
            <span>{stats.passed} Passed</span>
          </div>
          {stats.exempted > 0 && (
            <div
              className={`policy-pill policy-pill--exempted policy-pill--clickable ${filter === "exempted" ? "policy-pill--active" : ""}`}
              data-testid="pill-exempted"
              onClick={() => setFilter((prev) => (prev === "exempted" ? "all" : "exempted"))}
              onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === " ") {
                  e.preventDefault();
                  setFilter((prev) => (prev === "exempted" ? "all" : "exempted"));
                }
              }}
              role="button"
              tabIndex={0}
            >
              <SafetyCertificateOutlined />
              <span>{stats.exempted} Exempted</span>
            </div>
          )}
          {stats.warning > 0 && (
            <div
              className={`policy-pill policy-pill--warning policy-pill--clickable ${filter === "warnings" ? "policy-pill--active" : ""}`}
              data-testid="pill-warning"
              onClick={() => setFilter((prev) => (prev === "warnings" ? "all" : "warnings"))}
              onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === " ") {
                  e.preventDefault();
                  setFilter((prev) => (prev === "warnings" ? "all" : "warnings"));
                }
              }}
              role="button"
              tabIndex={0}
            >
              <WarningOutlined />
              <span>{stats.warning} Warnings</span>
            </div>
          )}
          {stats.soft > 0 && (
            <div
              className={`policy-pill policy-pill--soft policy-pill--clickable ${filter === "violations" ? "policy-pill--active" : ""}`}
              data-testid="pill-soft"
              onClick={() => setFilter((prev) => (prev === "violations" ? "all" : "violations"))}
              onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === " ") {
                  e.preventDefault();
                  setFilter((prev) => (prev === "violations" ? "all" : "violations"));
                }
              }}
              role="button"
              tabIndex={0}
            >
              <ExclamationCircleOutlined />
              <span>{stats.soft} Soft Mandatory</span>
            </div>
          )}
          {stats.hard > 0 && (
            <div
              className={`policy-pill policy-pill--hard policy-pill--clickable ${filter === "violations" ? "policy-pill--active" : ""}`}
              data-testid="pill-hard"
              onClick={() => setFilter((prev) => (prev === "violations" ? "all" : "violations"))}
              onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === " ") {
                  e.preventDefault();
                  setFilter((prev) => (prev === "violations" ? "all" : "violations"));
                }
              }}
              role="button"
              tabIndex={0}
            >
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
              <Button danger icon={<CloseOutlined />} loading={rejectSubmitting} data-testid="reject-button">
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
        <Radio.Group value={filter} onChange={(e) => setFilter(e.target.value)} buttonStyle="solid" size="middle">
          <Radio.Button value="all">All ({allRules.length})</Radio.Button>
          <Radio.Button value="passed">Passed ({stats.passed})</Radio.Button>
          <Radio.Button value="violations">Violations ({stats.hard + stats.soft})</Radio.Button>
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
            const isLogsExpanded = Boolean(expandedLogs[rule.key]);

            return (
              <div key={rule.key} className={cardClass} data-testid="rule-card">
                <div className="policy-rule-header">
                  <Space align="center">
                    <span className="policy-rule-id">{rule.ruleId}</span>
                    {rule.policySetName && rule.policySetName !== rule.ruleId && <Tag>{rule.policySetName}</Tag>}
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
                      <Space size={6}>
                        {rule.enforcementLevel && (
                          <Tag color="default">{formatEnforcementLevel(rule.enforcementLevel)}</Tag>
                        )}
                        <Tag color="success" icon={<CheckCircleOutlined />}>
                          Passed
                        </Tag>
                      </Space>
                    )}
                  </div>
                </div>

                {(rule.address && rule.address !== "-") ||
                rule.severityType === "hard" ||
                rule.severityType === "soft" ? (
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
                            !canManagePolicies ? "Requires Policy Management permission to add exemptions" : undefined
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
                        <i>&ldquo;{rule.justification}&rdquo;</i>
                      </div>
                    )}
                  </div>
                )}

                {rule.message && (
                  <div className="policy-message-box">
                    <Text>{rule.message}</Text>
                  </div>
                )}

                {rule.logs && rule.logs.length > 0 && (
                  <div className="policy-logs-container">
                    <Button
                      type="link"
                      size="small"
                      icon={<FileTextOutlined />}
                      onClick={() => toggleLogs(rule.key)}
                      data-testid={`toggle-logs-${rule.ruleId}`}
                    >
                      {isLogsExpanded ? "Hide Execution Logs" : "View Execution Logs"}
                    </Button>
                    {isLogsExpanded && (
                      <div className="policy-logs-content" data-testid={`logs-content-${rule.ruleId}`}>
                        {rule.logs.map((line, idx) => (
                          <div key={idx}>{line.replace(ANSI_REGEX, "")}</div>
                        ))}
                      </div>
                    )}
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
          Soft-mandatory policy checks allow authorized teams ({approvalTeam || "configured override team"}) to approve
          a run by providing an audit justification.
        </Paragraph>

        <Form form={form} layout="vertical" onFinish={handleOverrideSubmit}>
          <Form.Item
            name="justification"
            label="Override Justification"
            rules={[{ required: true, message: "Please provide a justification for this override" }]}
          >
            <TextArea rows={4} placeholder="E.g. Approved by SecOps for emergency mitigation (ticket SEC-9102)..." />
          </Form.Item>

          <Form.Item style={{ marginTop: 24 }}>
            <Space style={{ width: "100%", justifyContent: "flex-end" }} size="middle" wrap>
              <Button danger loading={rejectSubmitting} onClick={handleRejectSubmit} data-testid="reject-override-btn">
                Reject Policy Override
              </Button>
              <Button type="primary" htmlType="submit" loading={overrideSubmitting} data-testid="submit-override-btn">
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
