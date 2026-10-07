import React, { useEffect, useState } from "react";
import { Form, Input, Select, Tabs, Typography, message } from "antd";
import { useNavigate, useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { SettingsForm } from "@/components/settings/SettingsForm";
import SettingsSection from "@/components/settings/SettingsSection/SettingsSection";
import { RadioChoices } from "@/components/settings/RadioChoices";
import { DangerZone } from "@/components/settings/DangerZone";
import LoadingFallback from "@/components/feedback/LoadingFallback";
import { PolicySetParameters } from "./components";
import "./PolicySets.css";

const { Option } = Select;
const { TextArea } = Input;

type Props = {
  mode: "create" | "edit";
  policySetId?: string;
  managePermission?: boolean;
};

export const CreateEditPolicySet: React.FC<Props> = ({ mode, policySetId, managePermission = true }) => {
  const { orgid } = useParams();
  const navigate = useNavigate();
  const [form] = Form.useForm();

  const [loading, setLoading] = useState(mode === "edit");
  const [submitting, setSubmitting] = useState(false);
  const [vcsProviders, setVcsProviders] = useState<any[]>([]);
  const [workspaces, setWorkspaces] = useState<any[]>([]);
  const [projects, setProjects] = useState<any[]>([]);
  const [tags, setTags] = useState<any[]>([]);
  const [notificationConfigs, setNotificationConfigs] = useState<any[]>([]);
  const [teams, setTeams] = useState<any[]>([]);
  const [loadingTeams, setLoadingTeams] = useState(false);
  const [policySetName, setPolicySetName] = useState("");
  const [activeTab, setActiveTab] = useState("settings");
  const isGlobal = Form.useWatch("global", form);

  const backUrl = `/organizations/${orgid}/settings/policies`;

  useEffect(() => {
    // Load reference data
    const loadReferences = async () => {
      try {
        setLoadingTeams(true);
        const [vcsRes, wsRes, projRes, tagsRes, notifRes, teamsRes] = await Promise.all([
          axiosInstance.get(`organization/${orgid}/vcs`).catch(() => ({ data: { data: [] } })),
          axiosInstance.get(`organization/${orgid}/workspace`).catch(() => ({ data: { data: [] } })),
          axiosInstance.get(`organization/${orgid}/project`).catch(() => ({ data: { data: [] } })),
          axiosInstance.get(`organization/${orgid}/tag`).catch(() => ({ data: { data: [] } })),
          axiosInstance.get(`organization/${orgid}/notificationConfiguration`).catch(() => ({ data: { data: [] } })),
          axiosInstance.get(`organization/${orgid}/team`).catch(() => ({ data: { data: [] } })),
        ]);

        setVcsProviders(vcsRes.data?.data || []);
        setWorkspaces(wsRes.data?.data || []);
        setProjects(projRes.data?.data || []);
        setTags(tagsRes.data?.data || []);
        setNotificationConfigs(notifRes.data?.data || []);
        setTeams(
          (teamsRes.data?.data || []).map((t: any) => ({
            id: t.id,
            name: t.attributes?.name || t.name || t.id,
          }))
        );
      } catch (e) {
        console.error("Failed to load reference data", e);
      } finally {
        setLoadingTeams(false);
      }
    };

    void loadReferences();

    if (mode === "edit" && policySetId) {
      axiosInstance
        .get(`policy_set/${policySetId}?include=vcs,attachments,notificationConfiguration`)
        .then(async (res) => {
          const item = res.data.data;
          const attrs = item.attributes;
          setPolicySetName(attrs.name);

          const attachedWorkspaces: string[] = [];
          const attachedProjects: string[] = [];
          const attachedTags: string[] = [];

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
            opaVersion: attrs.opaVersion,
            vcsId: item.relationships?.vcs?.data?.id || undefined,
            notificationConfigurationId: item.relationships?.notificationConfiguration?.data?.id || undefined,
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
            opaVersion: values.opaVersion ? values.opaVersion.trim() : null,
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

      if (values.notificationConfigurationId) {
        payload.data.relationships.notificationConfiguration = {
          data: {
            type: "notification_configuration",
            id: values.notificationConfigurationId,
          },
        };
      } else if (mode === "edit") {
        payload.data.relationships.notificationConfiguration = {
          data: null,
        };
      }

      let savedId = policySetId;

      if (mode === "create") {
        const createRes = await axiosInstance.post("policy_set", payload, {
          headers: { "Content-Type": "application/vnd.api+json" },
        });
        savedId = createRes.data?.data?.id;
        message.success("Policy set created");
      } else {
        payload.data.id = policySetId;
        await axiosInstance.patch(`policy_set/${policySetId}`, payload, {
          headers: { "Content-Type": "application/vnd.api+json" },
        });
        message.success("Policy set updated");
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

  const currentOverrideTeam = Form.useWatch("overrideTeam", form);
  const teamOptions = [...teams];
  if (currentOverrideTeam && !teamOptions.some((t) => t.name === currentOverrideTeam)) {
    teamOptions.push({
      id: currentOverrideTeam,
      name: currentOverrideTeam,
    });
  }

  if (loading) {
    return <LoadingFallback />;
  }

  const onDelete = () => {
    axiosInstance
      .delete(`policy_set/${policySetId}`)
      .then(() => {
        message.success("Policy set deleted");
        navigate(backUrl);
      })
      .catch((err) => message.error(getErrorMessage(err)));
  };

  const settingsForm = (
    <>
      <SettingsForm
        form={form}
        onFinish={onFinish}
        saveLabel={mode === "create" ? "Create policy set" : "Update policy set"}
        saveDisabled={!managePermission}
        saving={submitting}
      >
        <SettingsSection title="Identity">
          <Form.Item name="name" label="Name" rules={[{ required: true, message: "Enter a name for the policy set" }]}>
            <Input placeholder="security-baseline" />
          </Form.Item>
          <Form.Item name="description" label="Description">
            <TextArea autoSize={{ minRows: 2, maxRows: 4 }} placeholder="What these rules check" />
          </Form.Item>
        </SettingsSection>

        <SettingsSection title="Enforcement" description="What happens to a run that violates a rule in this set.">
          <Form.Item
            name="enforcementLevel"
            label="Enforcement level"
            rules={[{ required: true, message: "Choose an enforcement level" }]}
          >
            <RadioChoices
              options={[
                { value: "HARD_MANDATORY", label: "Hard mandatory", help: "The run stops before apply." },
                {
                  value: "SOFT_MANDATORY",
                  label: "Soft mandatory",
                  help: "The run waits until a member of the override team approves it.",
                },
                { value: "ADVISORY", label: "Advisory", help: "The violation is reported and the run continues." },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="overrideTeam"
            label="Override team"
            extra="Members of this team can approve soft-mandatory violations."
          >
            <Select
              showSearch
              allowClear
              placeholder="No override team"
              data-testid="policy-set-override-team-select"
              loading={loadingTeams}
              filterOption={(input, option) =>
                String(option?.value ?? "")
                  .toLowerCase()
                  .includes(input.toLowerCase())
              }
            >
              {teamOptions.map((t) => (
                <Option key={t.id} value={t.name}>
                  {t.name}
                </Option>
              ))}
            </Select>
          </Form.Item>
          <Form.Item
            name="shadowEnforcementLevel"
            label="Shadow enforcement"
            extra="Also evaluates the rules at this level and records the result, without changing the run."
          >
            <Select allowClear placeholder="Off">
              <Option value="HARD_MANDATORY">Hard mandatory</Option>
              <Option value="SOFT_MANDATORY">Soft mandatory</Option>
              <Option value="ADVISORY">Advisory</Option>
            </Select>
          </Form.Item>
          <Form.Item
            name="opaVersion"
            label="OPA version"
            extra={
              <>
                Leave empty to use the default version. See{" "}
                <Typography.Link
                  href="https://github.com/open-policy-agent/opa/releases"
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  OPA releases
                </Typography.Link>
                .
              </>
            }
          >
            <Input
              className="policy-mono"
              placeholder="Inherit system default (e.g. 1.20.2)"
              data-testid="policy-set-opa-version-input"
            />
          </Form.Item>
          <Form.Item
            name="notificationConfigurationId"
            label="Notification"
            extra="Sends a message to this channel when a run violates a rule in this set."
          >
            <Select
              allowClear
              placeholder="No notification"
              data-testid="policy-set-notification-select"
              options={notificationConfigs.map((nc) => ({
                value: nc.id,
                label: nc.attributes?.name || nc.id,
              }))}
            />
          </Form.Item>
        </SettingsSection>

        <SettingsSection title="Source" description="The repository folder that holds the Rego files.">
          <Form.Item name="vcsId" label="VCS provider">
            <Select
              allowClear
              placeholder="No VCS provider"
              options={vcsProviders.map((v) => ({ value: v.id, label: v.attributes?.name || v.id }))}
            />
          </Form.Item>
          <Form.Item
            name="repository"
            label="Repository"
            rules={[{ required: true, message: "Enter the repository URL" }]}
          >
            <Input className="policy-mono" placeholder="https://github.com/org/opa-policies.git" />
          </Form.Item>
          <Form.Item name="branch" label="Branch">
            <Input className="policy-mono" placeholder="main" />
          </Form.Item>
          <Form.Item name="folder" label="Folder">
            <Input className="policy-mono" placeholder="/" />
          </Form.Item>
        </SettingsSection>

        <SettingsSection title="Scope" description="The workspaces checked against this policy set.">
          <Form.Item
            name="global"
            label="Applies to"
            getValueProps={(value) => ({ value: value ? "all" : "selected" })}
            normalize={(value) => value === "all"}
          >
            <RadioChoices
              options={[
                { value: "all", label: "All workspaces", help: "Every workspace in the organization." },
                {
                  value: "selected",
                  label: "Selected workspaces",
                  help: "Only the workspaces, projects and tags chosen below.",
                },
              ]}
            />
          </Form.Item>

          {!isGlobal && (
            <>
              <Form.Item name="workspaces" label="Workspaces">
                <Select
                  mode="multiple"
                  placeholder="Choose workspaces"
                  allowClear
                  optionFilterProp="label"
                  options={workspaces.map((ws) => ({ value: ws.id, label: ws.attributes?.name || ws.id }))}
                />
              </Form.Item>
              <Form.Item name="projects" label="Projects" extra="Includes every workspace in the project.">
                <Select
                  mode="multiple"
                  placeholder="Choose projects"
                  allowClear
                  optionFilterProp="label"
                  options={projects.map((p) => ({ value: p.id, label: p.attributes?.name || p.id }))}
                />
              </Form.Item>
              <Form.Item name="tags" label="Tags" extra="Includes every workspace with the tag.">
                <Select
                  mode="multiple"
                  placeholder="Choose tags"
                  allowClear
                  optionFilterProp="label"
                  options={tags.map((t) => ({ value: t.id, label: t.attributes?.name || t.id }))}
                />
              </Form.Item>
            </>
          )}
        </SettingsSection>
      </SettingsForm>

      {mode === "edit" && policySetId && (
        <DangerZone
          actionName="Delete this policy set"
          description="Workspaces stop being checked against these rules. Its attachments, parameters and exemptions are deleted too. This cannot be undone."
          disabled={!managePermission}
          onConfirm={onDelete}
          confirmValue={policySetName}
          confirmMessage={`Workspaces will no longer be checked against ${policySetName}. Its attachments, parameters and exemptions are deleted too. This cannot be undone.`}
        />
      )}
    </>
  );

  return (
    <div>
      <SettingsPageHeader
        title={mode === "create" ? "Create policy set" : "Edit policy set"}
        description="Choose the rules to enforce, how strictly, and on which workspaces."
        divider={mode === "create"}
      />

      {mode === "edit" && policySetId ? (
        <Tabs
          activeKey={activeTab}
          onChange={setActiveTab}
          items={[
            {
              key: "settings",
              label: "Settings",
              children: settingsForm,
            },
            {
              key: "parameters",
              label: "Parameters",
              children: <PolicySetParameters policySetId={policySetId} managePermission={managePermission} />,
            },
          ]}
        />
      ) : (
        settingsForm
      )}
    </div>
  );
};
