import React, { useEffect, useState } from "react";
import {
  Button,
  DatePicker,
  Form,
  Input,
  Modal,
  Radio,
  Select,
  Space,
  Switch,
  Typography,
  message,
} from "antd";
import {
  CalendarOutlined,
  ClockCircleOutlined,
  SafetyCertificateOutlined,
} from "@ant-design/icons";
import dayjs, { Dayjs } from "dayjs";
import axiosInstance, { getErrorMessage } from "../../../config/axiosConfig";

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
      title={
        <Space>
          <SafetyCertificateOutlined style={{ color: "#1677ff" }} />
          <span>{mode === "create" ? "Create Policy Exemption" : "Edit Policy Exemption"}</span>
        </Space>
      }
      open={visible}
      onCancel={onCancel}
      footer={null}
      destroyOnHidden
      width={640}
    >
      <Form form={form} layout="vertical" onFinish={onFinish} requiredMark="optional">
        <Form.Item
          name="policySetId"
          label="Policy Set"
          rules={[{ required: true, message: "Please select a policy set" }]}
        >
          <Select
            placeholder="Select a Policy Set"
            loading={loadingRefs}
            showSearch
            optionFilterProp="children"
            disabled={mode === "edit"}
          >
            {policySets.map((ps) => (
              <Select.Option key={ps.id} value={ps.id}>
                {ps.attributes?.name || ps.id}
              </Select.Option>
            ))}
          </Select>
        </Form.Item>

        <Form.Item
          name="ruleId"
          label="Rule ID"
          rules={[{ required: true, message: "Please specify the Rego rule ID" }]}
          extra="The unique rule name or identifier evaluated by OPA (e.g. aws_s3_bucket_no_public_access)."
        >
          <Input placeholder="e.g. aws_s3_bucket_no_public_access" disabled={mode === "edit"} />
        </Form.Item>

        <Form.Item
          name="scopeType"
          label="Exemption Scope"
          rules={[{ required: true, message: "Please select an exemption scope" }]}
        >
          <Radio.Group disabled={Boolean(lockedScope) || mode === "edit"}>
            <Radio value="ORGANIZATION">Organization-Wide</Radio>
            <Radio value="PROJECT">Project-Scoped</Radio>
            <Radio value="WORKSPACE">Workspace-Scoped</Radio>
          </Radio.Group>
        </Form.Item>

        {scopeType === "PROJECT" && (
          <Form.Item
            name="projectId"
            label="Target Project"
            rules={[{ required: true, message: "Please select a project" }]}
          >
            <Select
              placeholder="Select project"
              loading={loadingRefs}
              showSearch
              optionFilterProp="children"
              disabled={mode === "edit"}
            >
              {projects.map((proj) => (
                <Select.Option key={proj.id} value={proj.id}>
                  {proj.attributes?.name || proj.id}
                </Select.Option>
              ))}
            </Select>
          </Form.Item>
        )}

        {scopeType === "WORKSPACE" && (
          <Form.Item
            name="workspaceId"
            label="Target Workspace"
            rules={[{ required: true, message: "Please select a workspace" }]}
          >
            <Select
              placeholder="Select workspace"
              loading={loadingRefs}
              showSearch
              optionFilterProp="children"
              disabled={Boolean(lockedScope) || mode === "edit"}
            >
              {workspaces.map((ws) => (
                <Select.Option key={ws.id} value={ws.id}>
                  {ws.attributes?.name || ws.id}
                </Select.Option>
              ))}
            </Select>
          </Form.Item>
        )}

        <Form.Item
          name="ticketReference"
          label="Ticket Reference"
          rules={[{ required: true, message: "Please provide a ticket reference" }]}
          extra="Audited issue tracker or change ticket (e.g. SEC-8842 or Jira link)."
        >
          <Input placeholder="e.g. SEC-8842" />
        </Form.Item>

        <Form.Item
          name="justification"
          label="Justification"
          rules={[
            { required: true, message: "Please enter a justification" },
            { max: 2048, message: "Justification cannot exceed 2048 characters" },
          ]}
          extra="Detailed explanation of why this compliance waiver was granted."
        >
          <TextArea
            rows={3}
            placeholder="Describe the technical context and security review rationale..."
            maxLength={2048}
            showCount
          />
        </Form.Item>

        <div style={{ marginBottom: 16 }}>
          <Space align="center" style={{ marginBottom: 12 }}>
            <Form.Item name="isIndefinite" valuePropName="checked" noStyle>
              <Switch
                checked={isIndefinite}
                onChange={toggleIndefinite}
              />
            </Form.Item>
            <Text
              strong
              style={{ cursor: "pointer", userSelect: "none" }}
              onClick={() => toggleIndefinite(!isIndefinite)}
            >
              Permanent / Indefinite Exemption (No Expiration Date)
            </Text>
          </Space>

          {!isIndefinite && (
            <div>
              <Form.Item
                name="expiresAt"
                label="Expiration Date"
                rules={[{ required: !isIndefinite, message: "Please pick an expiration date" }]}
                style={{ marginBottom: 8 }}
              >
                <DatePicker
                  style={{ width: "100%" }}
                  showTime
                  disabledDate={(current) => current && current <= dayjs().startOf("day")}
                  placeholder="Select expiration date & time"
                  prefix={<CalendarOutlined />}
                />
              </Form.Item>

              <Space wrap style={{ marginTop: 4 }}>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  <ClockCircleOutlined /> Quick Presets:
                </Text>
                <Button size="small" onClick={() => handleQuickDuration(30)}>
                  +30 Days
                </Button>
                <Button size="small" onClick={() => handleQuickDuration(60)}>
                  +60 Days
                </Button>
                <Button size="small" onClick={() => handleQuickDuration(90)}>
                  +90 Days
                </Button>
                <Button size="small" onClick={() => handleQuickDuration(365)}>
                  +1 Year
                </Button>
              </Space>
            </div>
          )}
        </div>

        <div style={{ display: "flex", justifyContent: "flex-end", gap: 8, marginTop: 24 }}>
          <Button onClick={onCancel} disabled={submitting}>
            Cancel
          </Button>
          <Button type="primary" htmlType="submit" loading={submitting}>
            {mode === "create" ? "Create Exemption" : "Save Changes"}
          </Button>
        </div>
      </Form>
    </Modal>
  );
};
