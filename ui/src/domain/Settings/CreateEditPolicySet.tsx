import React, { useEffect, useState } from "react";
import {
  Button,
  Card,
  Col,
  Divider,
  Form,
  Input,
  Row,
  Select,
  Space,
  Switch,
  Typography,
  message,
} from "antd";
import {
  ArrowLeftOutlined,
  BranchesOutlined,
  FolderOutlined,
  SafetyCertificateOutlined,
  SaveOutlined,
  TeamOutlined,
} from "@ant-design/icons";
import { useNavigate, useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import SettingsSection from "@/components/settings/SettingsSection/SettingsSection";
import LoadingFallback from "@/components/feedback/LoadingFallback";

const { Title, Text, Paragraph } = Typography;
const { Option } = Select;
const { TextArea } = Input;

type Props = {
  mode: "create" | "edit";
  policySetId?: string;
  managePermission?: boolean;
};

export const CreateEditPolicySet: React.FC<Props> = ({
  mode,
  policySetId,
  managePermission = true,
}) => {
  const { orgid } = useParams();
  const navigate = useNavigate();
  const [form] = Form.useForm();

  const [loading, setLoading] = useState(mode === "edit");
  const [submitting, setSubmitting] = useState(false);
  const [vcsProviders, setVcsProviders] = useState<any[]>([]);
  const [workspaces, setWorkspaces] = useState<any[]>([]);
  const [projects, setProjects] = useState<any[]>([]);
  const [tags, setTags] = useState<any[]>([]);
  const [isGlobal, setIsGlobal] = useState(false);

  const backUrl = `/organizations/${orgid}/settings/policies`;

  useEffect(() => {
    // Load reference data
    const loadReferences = async () => {
      try {
        const [vcsRes, wsRes, projRes, tagsRes] = await Promise.all([
          axiosInstance.get(`organization/${orgid}/vcs`).catch(() => ({ data: { data: [] } })),
          axiosInstance.get(`organization/${orgid}/workspace`).catch(() => ({ data: { data: [] } })),
          axiosInstance.get(`organization/${orgid}/project`).catch(() => ({ data: { data: [] } })),
          axiosInstance.get(`organization/${orgid}/tag`).catch(() => ({ data: { data: [] } })),
        ]);

        setVcsProviders(vcsRes.data?.data || []);
        setWorkspaces(wsRes.data?.data || []);
        setProjects(projRes.data?.data || []);
        setTags(tagsRes.data?.data || []);
      } catch (e) {
        console.error("Failed to load reference data", e);
      }
    };

    void loadReferences();

    if (mode === "edit" && policySetId) {
      axiosInstance
        .get(`policy_set/${policySetId}?include=vcs,attachments`)
        .then(async (res) => {
          const item = res.data.data;
          const attrs = item.attributes;
          setIsGlobal(attrs.global || false);

          let attachedWorkspaces: string[] = [];
          let attachedProjects: string[] = [];
          let attachedTags: string[] = [];

          try {
            const attachRes = await axiosInstance.get(
              `policy_set/${policySetId}/attachments?include=workspace,project,tag`
            );
            const attachments = attachRes.data.data || [];
            attachments.forEach((att: any) => {
              const rels = att.relationships || {};
              if (rels.workspace?.data?.id) attachedWorkspaces.push(rels.workspace.data.id);
              if (rels.project?.data?.id) attachedProjects.push(rels.project.data.id);
              if (rels.tag?.data?.id) attachedTags.push(rels.tag.data.id);
            });
          } catch {
            // attachments query fallback
          }

          form.setFieldsValue({
            name: attrs.name,
            description: attrs.description,
            enforcementLevel: attrs.enforcementLevel || "HARD_MANDATORY",
            shadowEnforcementLevel: attrs.shadowEnforcementLevel || undefined,
            overrideTeam: attrs.overrideTeam,
            global: attrs.global || false,
            repository: attrs.repository,
            branch: attrs.branch || "main",
            folder: attrs.folder || "/",
            vcsId: item.relationships?.vcs?.data?.id || undefined,
            workspaces: attachedWorkspaces,
            projects: attachedProjects,
            tags: attachedTags,
          });
        })
        .catch((err) => {
          message.error(getErrorMessage(err));
        })
        .finally(() => {
          setLoading(false);
        });
    } else {
      form.setFieldsValue({
        enforcementLevel: "HARD_MANDATORY",
        branch: "main",
        folder: "/",
        global: false,
      });
    }
  }, [form, mode, orgid, policySetId]);

  const onFinish = async (values: any) => {
    setSubmitting(true);
    try {
      const payload: any = {
        data: {
          type: "policy_set",
          attributes: {
            name: values.name,
            description: values.description,
            enforcementLevel: values.enforcementLevel,
            shadowEnforcementLevel: values.shadowEnforcementLevel || null,
            overrideTeam: values.overrideTeam || null,
            global: values.global || false,
            repository: values.repository,
            branch: values.branch,
            folder: values.folder,
          },
          relationships: {
            organization: {
              data: {
                type: "organization",
                id: orgid,
              },
            },
          },
        },
      };

      if (values.vcsId) {
        payload.data.relationships.vcs = {
          data: {
            type: "vcs",
            id: values.vcsId,
          },
        };
      }

      let savedId = policySetId;

      if (mode === "create") {
        const createRes = await axiosInstance.post("policy_set", payload, {
          headers: { "Content-Type": "application/vnd.api+json" },
        });
        savedId = createRes.data?.data?.id;
        message.success("Policy Set created successfully");
      } else {
        payload.data.id = policySetId;
        await axiosInstance.patch(`policy_set/${policySetId}`, payload, {
          headers: { "Content-Type": "application/vnd.api+json" },
        });
        message.success("Policy Set updated successfully");
      }

      // Sync attachments if not global
      if (savedId && !values.global) {
        try {
          // Fetch existing attachments and remove
          const existingRes = await axiosInstance.get(`policy_set/${savedId}/attachments`);
          const existing = existingRes.data.data || [];
          await Promise.all(
            existing.map((att: any) => axiosInstance.delete(`policy_set/${savedId}/attachments/${att.id}`))
          );

          // Add new workspace attachments
          const wsPromises = (values.workspaces || []).map((wsId: string) =>
            axiosInstance.post(
              `policy_set/${savedId}/attachments`,
              {
                data: {
                  type: "policy_attachment",
                  relationships: {
                    policySet: { data: { type: "policy_set", id: savedId } },
                    workspace: { data: { type: "workspace", id: wsId } },
                  },
                },
              },
              { headers: { "Content-Type": "application/vnd.api+json" } }
            )
          );

          // Add new project attachments
          const projPromises = (values.projects || []).map((projId: string) =>
            axiosInstance.post(
              `policy_set/${savedId}/attachments`,
              {
                data: {
                  type: "policy_attachment",
                  relationships: {
                    policySet: { data: { type: "policy_set", id: savedId } },
                    project: { data: { type: "project", id: projId } },
                  },
                },
              },
              { headers: { "Content-Type": "application/vnd.api+json" } }
            )
          );

          // Add new tag attachments
          const tagPromises = (values.tags || []).map((tagId: string) =>
            axiosInstance.post(
              `policy_set/${savedId}/attachments`,
              {
                data: {
                  type: "policy_attachment",
                  relationships: {
                    policySet: { data: { type: "policy_set", id: savedId } },
                    tag: { data: { type: "tag", id: tagId } },
                  },
                },
              },
              { headers: { "Content-Type": "application/vnd.api+json" } }
            )
          );

          await Promise.all([...wsPromises, ...projPromises, ...tagPromises]);
        } catch (attErr) {
          console.error("Failed to sync attachments", attErr);
        }
      }

      navigate(backUrl);
    } catch (err: any) {
      message.error(getErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) {
    return <LoadingFallback />;
  }

  return (
    <div style={{ maxWidth: 900, margin: "0 auto" }}>
      <SettingsPageHeader
        title={mode === "create" ? "Create Policy Set" : "Edit Policy Set"}
        description="Configure Open Policy Agent (OPA) guardrails, enforcement levels, and repository source."
        action={
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(backUrl)}>
            Back to Policy Sets
          </Button>
        }
      />

      <Form form={form} layout="vertical" onFinish={onFinish} requiredMark="optional">
        <Card title="General Settings" style={{ marginBottom: 24 }}>
          <Row gutter={16}>
            <Col span={24}>
              <Form.Item
                name="name"
                label="Policy Set Name"
                rules={[{ required: true, message: "Please enter a policy set name" }]}
              >
                <Input placeholder="e.g. enterprise-security-baseline" />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item name="description" label="Description">
                <TextArea rows={3} placeholder="Brief description of the rules enforced by this policy set" />
              </Form.Item>
            </Col>
          </Row>
        </Card>

        <Card title="Enforcement & Governance" style={{ marginBottom: 24 }}>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="enforcementLevel"
                label="Enforcement Level"
                rules={[{ required: true, message: "Please select an enforcement level" }]}
                tooltip="Hard-mandatory halts execution on violation. Soft-mandatory requires authorized override. Advisory prints warnings."
              >
                <Select>
                  <Option value="HARD_MANDATORY">Hard Mandatory (Blocks Apply)</Option>
                  <Option value="SOFT_MANDATORY">Soft Mandatory (Requires Override)</Option>
                  <Option value="ADVISORY">Advisory (Warning Only)</Option>
                </Select>
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="shadowEnforcementLevel"
                label="Shadow Mode (Telemetry Only)"
                tooltip="Evaluates rules against incoming plans for metrics without altering pass/fail status."
              >
                <Select allowClear placeholder="Disabled">
                  <Option value="HARD_MANDATORY">Shadow Hard-Mandatory</Option>
                  <Option value="SOFT_MANDATORY">Shadow Soft-Mandatory</Option>
                  <Option value="ADVISORY">Shadow Advisory</Option>
                </Select>
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item
                name="overrideTeam"
                label="Authorized Override Team"
                tooltip="RBAC team authorized to approve soft-mandatory violations in the UI or API."
              >
                <Input prefix={<TeamOutlined />} placeholder="e.g. security-admins" />
              </Form.Item>
            </Col>
          </Row>
        </Card>

        <Card title="Source Repository" style={{ marginBottom: 24 }}>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="vcsId" label="VCS Provider">
                <Select allowClear placeholder="Select VCS Provider">
                  {vcsProviders.map((v) => (
                    <Option key={v.id} value={v.id}>
                      {v.attributes?.name || v.id}
                    </Option>
                  ))}
                </Select>
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="repository"
                label="Repository / Source"
                rules={[{ required: true, message: "Please specify repository URL or identifier" }]}
              >
                <Input placeholder="e.g. https://github.com/org/opa-policies.git" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="branch" label="Branch">
                <Input prefix={<BranchesOutlined />} placeholder="main" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="folder" label="Policy Directory Path">
                <Input prefix={<FolderOutlined />} placeholder="/" />
              </Form.Item>
            </Col>
          </Row>
        </Card>

        <Card title="Scope & Attachments" style={{ marginBottom: 24 }}>
          <Form.Item
            name="global"
            label="Global Scope"
            valuePropName="checked"
            extra="When enabled, this policy set automatically evaluates against every workspace in the organization."
          >
            <Switch checked={isGlobal} onChange={(checked) => setIsGlobal(checked)} />
          </Form.Item>

          {!isGlobal && (
            <>
              <Divider />
              <Paragraph type="secondary">
                Attach this policy set to specific workspaces, projects, or tags across the organization.
              </Paragraph>
              <Row gutter={16}>
                <Col span={24}>
                  <Form.Item name="workspaces" label="Attached Workspaces">
                    <Select mode="multiple" placeholder="Select Workspaces" allowClear style={{ width: "100%" }}>
                      {workspaces.map((ws) => (
                        <Option key={ws.id} value={ws.id}>
                          {ws.attributes?.name || ws.id}
                        </Option>
                      ))}
                    </Select>
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item name="projects" label="Attached Projects">
                    <Select mode="multiple" placeholder="Select Projects" allowClear style={{ width: "100%" }}>
                      {projects.map((p) => (
                        <Option key={p.id} value={p.id}>
                          {p.attributes?.name || p.id}
                        </Option>
                      ))}
                    </Select>
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item name="tags" label="Attached Tags">
                    <Select mode="multiple" placeholder="Select Tags" allowClear style={{ width: "100%" }}>
                      {tags.map((t) => (
                        <Option key={t.id} value={t.id}>
                          {t.attributes?.name || t.id}
                        </Option>
                      ))}
                    </Select>
                  </Form.Item>
                </Col>
              </Row>
            </>
          )}
        </Card>

        <Form.Item>
          <Space>
            <Button
              type="primary"
              htmlType="submit"
              icon={<SaveOutlined />}
              loading={submitting}
              disabled={!managePermission}
            >
              {mode === "create" ? "Create Policy Set" : "Save Changes"}
            </Button>
            <Button onClick={() => navigate(backUrl)}>Cancel</Button>
          </Space>
        </Form.Item>
      </Form>
    </div>
  );
};
