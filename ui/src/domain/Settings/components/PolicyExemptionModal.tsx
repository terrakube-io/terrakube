import React, { useEffect, useState } from "react";
import { Button, DatePicker, Flex, Form, Input, Modal, Select, Space, Switch, Typography, message } from "antd";
import dayjs, { Dayjs } from "dayjs";
import axiosInstance, { getErrorMessage } from "../../../config/axiosConfig";
import { RadioChoices } from "@/components/settings/RadioChoices";
import "@/components/modals/CrudFormModal/CrudFormModal.css";
import "../PolicySets.css";
import "./PolicyComponents.css";

const { TextArea } = Input;
const { Text } = Typography;

export type ExemptionScopeType = "ORGANIZATION" | "PROJECT" | "WORKSPACE";

export type ExemptionFormData = {
  policySetId: string;
  ruleId: string;
  scopeType: ExemptionScopeType;
  workspaceId?: string;
  projectId?: string;
  ticketReference: string;
  justification: string;
  isIndefinite: boolean;
  expiresAt?: Dayjs;
};

export type PolicyExemptionModalProps = {
  visible: boolean;
  mode: "create" | "edit";
  initialData?: {
    id?: string;
    policySetId?: string;
    ruleId?: string;
    ticketReference?: string;
    justification?: string;
    expiresAt?: string | null;
    scopeType?: ExemptionScopeType;
    workspaceId?: string;
    projectId?: string;
  };
  lockedScope?: {
    scopeType: "WORKSPACE";
    workspaceId: string;
    workspaceName?: string;
  };
  organizationId: string;
  onCancel: () => void;
  onSuccess: (savedExemption?: any) => void;
};

export const PolicyExemptionModal: React.FC<PolicyExemptionModalProps> = ({
  visible,
  mode,
  initialData,
  lockedScope,
  organizationId,
  onCancel,
  onSuccess,
}) => {
  const [form] = Form.useForm<ExemptionFormData>();
  const [submitting, setSubmitting] = useState(false);
  const [policySets, setPolicySets] = useState<any[]>([]);
  const [workspaces, setWorkspaces] = useState<any[]>([]);
  const [projects, setProjects] = useState<any[]>([]);
  const [loadingRefs, setLoadingRefs] = useState(false);

  const scopeType = Form.useWatch("scopeType", form) || lockedScope?.scopeType || "ORGANIZATION";
  const [isIndefinite, setIsIndefinite] = useState<boolean>(false);

  useEffect(() => {
    if (visible && organizationId) {
      setLoadingRefs(true);
      Promise.all([
        axiosInstance
          .get(`organization/${organizationId}/policySet`)
          .then((res) => res.data?.data || [])
          .catch(() => []),
        axiosInstance
          .get(`organization/${organizationId}/workspace`)
          .then((res) => res.data?.data || [])
          .catch(() => []),
        axiosInstance
          .get(`organization/${organizationId}/project`)
          .then((res) => res.data?.data || [])
          .catch(() => []),
      ])
        .then(([sets, wsList, projList]) => {
          setPolicySets(sets);
          setWorkspaces(wsList);
          setProjects(projList);
        })
        .finally(() => setLoadingRefs(false));
    }
  }, [visible, organizationId]);

  useEffect(() => {
    if (visible) {
      if (mode === "edit" && initialData) {
        const indefinite = !initialData.expiresAt;
        setIsIndefinite(indefinite);
        form.setFieldsValue({
          policySetId: initialData.policySetId,
          ruleId: initialData.ruleId,
          scopeType: initialData.scopeType || "ORGANIZATION",
          workspaceId: initialData.workspaceId,
          projectId: initialData.projectId,
          ticketReference: initialData.ticketReference,
          justification: initialData.justification,
          isIndefinite: indefinite,
          expiresAt: initialData.expiresAt ? dayjs(initialData.expiresAt) : undefined,
        });
      } else {
        setIsIndefinite(false);
        form.resetFields();
        form.setFieldsValue({
          policySetId: initialData?.policySetId,
          ruleId: initialData?.ruleId || "",
          scopeType: lockedScope ? "WORKSPACE" : initialData?.scopeType || "ORGANIZATION",
          workspaceId: lockedScope?.workspaceId || initialData?.workspaceId,
          projectId: initialData?.projectId,
          ticketReference: initialData?.ticketReference || "",
          justification: initialData?.justification || "",
          isIndefinite: false,
          expiresAt: dayjs().add(90, "day"),
        });
      }
    }
  }, [visible, mode, initialData, lockedScope, form]);

  const toggleIndefinite = (checked: boolean) => {
    setIsIndefinite(checked);
    form.setFieldsValue({ isIndefinite: checked });
    if (checked) {
      form.setFieldsValue({ expiresAt: undefined });
    } else if (!form.getFieldValue("expiresAt")) {
      form.setFieldsValue({ expiresAt: dayjs().add(90, "day") });
    }
  };

  const handleQuickDuration = (days: number) => {
    setIsIndefinite(false);
    form.setFieldsValue({
      isIndefinite: false,
      expiresAt: dayjs().add(days, "day"),
    });
  };

  const onFinish = async (values: ExemptionFormData) => {
    setSubmitting(true);
    try {
      const formattedExpiresAt =
        isIndefinite || values.isIndefinite || !values.expiresAt ? null : values.expiresAt.toISOString();

      const payload: any = {
        data: {
          type: "policy_exemption",
          ...(mode === "edit" && initialData?.id ? { id: initialData.id } : {}),
          attributes: {
            ruleId: values.ruleId.trim(),
            ticketReference: values.ticketReference.trim(),
            justification: values.justification.trim(),
            expiresAt: formattedExpiresAt,
          },
          relationships: {
            organization: {
              data: {
                type: "organization",
                id: organizationId,
              },
            },
            policySet: {
              data: {
                type: "policy_set",
                id: values.policySetId,
              },
            },
          },
        },
      };

      if (values.scopeType === "WORKSPACE" && values.workspaceId) {
        payload.data.relationships.workspace = {
          data: {
            type: "workspace",
            id: values.workspaceId,
          },
        };
        payload.data.relationships.project = { data: null };
      } else if (values.scopeType === "PROJECT" && values.projectId) {
        payload.data.relationships.project = {
          data: {
            type: "project",
            id: values.projectId,
          },
        };
        payload.data.relationships.workspace = { data: null };
      } else {
        payload.data.relationships.workspace = { data: null };
        payload.data.relationships.project = { data: null };
      }

      let response;
      if (mode === "create") {
        response = await axiosInstance.post("policy_exemption", payload, {
          headers: { "Content-Type": "application/vnd.api+json" },
        });
        message.success("Policy exemption created successfully");
      } else {
        response = await axiosInstance.patch(`policy_exemption/${initialData?.id}`, payload, {
          headers: { "Content-Type": "application/vnd.api+json" },
        });
        message.success("Policy exemption updated successfully");
      }

      onSuccess(response.data?.data);
      form.resetFields();
    } catch (err: any) {
      message.error(getErrorMessage(err) || "Failed to save policy exemption");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      className="form-modal"
      title={mode === "create" ? "Create exemption" : "Edit exemption"}
      open={visible}
      onCancel={onCancel}
      destroyOnHidden
      width={600}
      footer={
        <Flex gap="small">
          <Button type="primary" loading={submitting} onClick={() => form.submit()}>
            {mode === "create" ? "Create exemption" : "Save exemption"}
          </Button>
          <Button onClick={onCancel} disabled={submitting}>
            Cancel
          </Button>
        </Flex>
      }
    >
      <Form form={form} layout="vertical" onFinish={onFinish} requiredMark={false}>
        <Form.Item name="policySetId" label="Policy set" rules={[{ required: true, message: "Choose a policy set" }]}>
          <Select
            placeholder="Choose a policy set"
            loading={loadingRefs}
            showSearch
            optionFilterProp="label"
            disabled={mode === "edit"}
            options={policySets.map((ps) => ({ value: ps.id, label: ps.attributes?.name || ps.id }))}
          />
        </Form.Item>

        <Form.Item
          name="ruleId"
          label="Rule ID"
          rules={[{ required: true, message: "Enter the rule ID to waive" }]}
          extra="The rule name as OPA reports it in policy check results."
        >
          <Input className="policy-mono" placeholder="aws_s3_bucket_no_public_access" disabled={mode === "edit"} />
        </Form.Item>

        <Form.Item name="scopeType" label="Scope" rules={[{ required: true, message: "Choose a scope" }]}>
          <RadioChoices
            disabled={Boolean(lockedScope) || mode === "edit"}
            options={[
              { value: "ORGANIZATION", label: "Organization-wide", help: "Waives the rule in every workspace." },
              { value: "PROJECT", label: "Project", help: "Waives the rule in the workspaces of one project." },
              { value: "WORKSPACE", label: "Workspace", help: "Waives the rule in one workspace." },
            ]}
          />
        </Form.Item>

        {scopeType === "PROJECT" && (
          <Form.Item name="projectId" label="Project" rules={[{ required: true, message: "Choose a project" }]}>
            <Select
              placeholder="Choose a project"
              loading={loadingRefs}
              showSearch
              optionFilterProp="label"
              disabled={mode === "edit"}
              options={projects.map((proj) => ({ value: proj.id, label: proj.attributes?.name || proj.id }))}
            />
          </Form.Item>
        )}

        {scopeType === "WORKSPACE" && (
          <Form.Item name="workspaceId" label="Workspace" rules={[{ required: true, message: "Choose a workspace" }]}>
            <Select
              placeholder="Choose a workspace"
              loading={loadingRefs}
              showSearch
              optionFilterProp="label"
              disabled={Boolean(lockedScope) || mode === "edit"}
              options={workspaces.map((ws) => ({ value: ws.id, label: ws.attributes?.name || ws.id }))}
            />
          </Form.Item>
        )}

        <Form.Item
          name="ticketReference"
          label="Ticket reference"
          rules={[{ required: true, message: "Enter the ticket that approved this exemption" }]}
          extra="The issue or change ticket that approved this exemption."
        >
          <Input placeholder="SEC-8842" />
        </Form.Item>

        <Form.Item
          name="justification"
          label="Justification"
          rules={[
            { required: true, message: "Explain why the rule is waived" },
            { max: 2048, message: "Keep the justification under 2048 characters" },
          ]}
        >
          <TextArea rows={3} placeholder="Why this rule does not apply here" maxLength={2048} showCount />
        </Form.Item>

        <Space align="center" className="policy-exemption-indefinite">
          <Form.Item name="isIndefinite" valuePropName="checked" noStyle>
            <Switch checked={isIndefinite} onChange={toggleIndefinite} aria-label="Never expires" />
          </Form.Item>
          <Text className="policy-exemption-indefinite-label" onClick={() => toggleIndefinite(!isIndefinite)}>
            Never expires
          </Text>
        </Space>

        {!isIndefinite && (
          <Form.Item
            name="expiresAt"
            label="Expires on"
            rules={[{ required: !isIndefinite, message: "Pick an expiration date" }]}
            extra={
              <Space wrap size={4} className="policy-exemption-durations">
                {[
                  [30, "30 days"],
                  [60, "60 days"],
                  [90, "90 days"],
                  [365, "1 year"],
                ].map(([days, label]) => (
                  <Button key={days} size="small" onClick={() => handleQuickDuration(days as number)}>
                    {label}
                  </Button>
                ))}
              </Space>
            }
          >
            <DatePicker
              className="policy-exemption-expiry"
              showTime
              disabledDate={(current) => current && current <= dayjs().startOf("day")}
              placeholder="Choose a date and time"
            />
          </Form.Item>
        )}
      </Form>
    </Modal>
  );
};
