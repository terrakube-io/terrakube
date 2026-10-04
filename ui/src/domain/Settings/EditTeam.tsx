import { Alert, Button, Flex, Form, Input, Spin, message } from "antd";
import CreatePatModal from "@/components/modals/CreatePatModal";
import { CreateTokenForm } from "@/modules/token/types";
import TokenGrid from "@/modules/token/TokenGrid";
import { apiDelete, apiGet, apiPost } from "@/modules/api/apiWrapper";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import { TeamToken } from "../types";
import SettingsSection from "@/components/settings/SettingsSection/SettingsSection";
import "./Settings.css";
import "./TeamsTagsVariables.css";
import { TeamPermissionsV2 } from "./TeamPermissionsV2";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { SettingsForm } from "@/components/settings/SettingsForm";
import { IdField } from "@/components/settings/IdField";
import { DangerZone } from "@/components/settings/DangerZone";

type Props = {
  mode: "edit" | "create";
  setMode: React.Dispatch<React.SetStateAction<"list" | "edit" | "create">>;
  teamId?: string;
  loadTeams: () => void;
  onDeleteTeam: (id: string) => void;
  managePermission?: boolean;
};

type CreateTeamForm = {
  name: string;
} & UpdateTeamForm;

type UpdateTeamForm = {
  manageCollection: boolean;
  manageJob: boolean;
  manageModule: boolean;
  manageProvider: boolean;
  manageState: boolean;
  manageTemplate: boolean;
  manageVcs: boolean;
  manageWorkspace: boolean;
  role?: string;
  planJob?: boolean;
  approveJob?: boolean;
};

export const EditTeam = ({ mode, setMode, teamId, loadTeams, onDeleteTeam, managePermission = true }: Props) => {
  const { orgid } = useParams();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [loadingTokens, setLoadingTokens] = useState(true);
  const [form] = Form.useForm();
  const [teamName, setTeamName] = useState<string>();
  const [tokens, setTokens] = useState<TeamToken[]>([]);
  const [visible, setVisible] = useState(false);
  const [createTokenDisabled, setCreateTokenDisabled] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (mode === "edit" && teamId) {
      setLoading(true);
      setLoadingTokens(true);
      loadTeam(teamId);
    } else {
      form.resetFields();
      setLoading(false);
    }
  }, [teamId]);

  const loadTeam = (id: string) => {
    axiosInstance
      .get(`organization/${orgid}/team/${id}`)
      .then((response) => {
        const name = response.data.data.attributes.name;
        setTeamName(name);
        const attrs = response.data.data.attributes;
        form.setFieldsValue({
          manageState: attrs.manageState,
          manageProvider: attrs.manageProvider,
          manageModule: attrs.manageModule,
          manageWorkspace: attrs.manageWorkspace,
          manageVcs: attrs.manageVcs,
          manageTemplate: attrs.manageTemplate,
          manageCollection: attrs.manageCollection,
          manageJob: attrs.manageJob,
          role: attrs.role || "custom",
          planJob: attrs.planJob ?? attrs.manageJob,
          approveJob: attrs.approveJob ?? attrs.manageJob,
        });
        setError(null);
        if (name) {
          loadTokens(name);
          loadUserTeams(name);
        }
      })
      .catch((err) => {
        setError(getErrorMessage(err));
      })
      .finally(() => {
        setLoading(false);
      });
  };

  const onCreate = (values: CreateTeamForm) => {
    // Keep manageJob in sync with planJob/approveJob for backward compatibility
    // with V1 backends that only read the manageJob field.
    const manageJob = values.planJob || values.approveJob || false;
    const body = {
      data: {
        type: "team",
        attributes: {
          name: values.name,
          manageState: values.manageState,
          manageWorkspace: values.manageWorkspace,
          manageModule: values.manageModule,
          manageProvider: values.manageProvider,
          manageVcs: values.manageVcs,
          manageTemplate: values.manageTemplate,
          manageCollection: values.manageCollection,
          manageJob: manageJob,
          role: values.role || "custom",
          planJob: values.planJob,
          approveJob: values.approveJob,
        },
      },
    };

    setSaving(true);
    axiosInstance
      .post(`organization/${orgid}/team`, body, {
        headers: { "Content-Type": "application/vnd.api+json" },
      })
      .then(() => {
        message.success("Team created successfully");
        loadTeams();
        setMode("list");
        form.resetFields();
      })
      .catch((err) => {
        message.error(`Could not create the team: ${getErrorMessage(err)}`);
      })
      .finally(() => setSaving(false));
  };

  const onUpdate = (values: UpdateTeamForm) => {
    // Keep manageJob in sync with planJob/approveJob for backward compatibility
    // with V1 backends that only read the manageJob field.
    const manageJob = values.planJob || values.approveJob || false;
    const body = {
      data: {
        type: "team",
        id: teamId,
        attributes: {
          manageState: values.manageState,
          manageWorkspace: values.manageWorkspace,
          manageModule: values.manageModule,
          manageProvider: values.manageProvider,
          manageVcs: values.manageVcs,
          manageTemplate: values.manageTemplate,
          manageCollection: values.manageCollection,
          manageJob: manageJob,
          role: values.role || "custom",
          planJob: values.planJob,
          approveJob: values.approveJob,
        },
      },
    };

    setSaving(true);
    axiosInstance
      .patch(`organization/${orgid}/team/${teamId}`, body, {
        headers: { "Content-Type": "application/vnd.api+json" },
      })
      .then(() => {
        message.success("Team updated successfully");
        loadTeams();
        setMode("list");
        form.resetFields();
      })
      .catch((err) => {
        message.error(`Could not save the team: ${getErrorMessage(err)}`);
      })
      .finally(() => setSaving(false));
  };

  const onFinish = (values: CreateTeamForm | UpdateTeamForm) => {
    if (mode === "edit") {
      onUpdate(values);
    } else {
      onCreate(values as CreateTeamForm);
    }
  };

  const onCancel = () => {
    setMode("list");
    form.resetFields();
  };

  const onNewToken = () => {
    setVisible(true);
  };

  const onDeleteToken = async (id: string) => {
    const response = await apiDelete(`/access-token/v1/teams/${id}`);
    if (response.isError) {
      message.error(`Could not delete the token: ${getErrorMessage(response.error)}`);
    } else {
      message.success("Token deleted successfully");
    }
    loadTokens(teamName);
    return response;
  };

  const onCreateToken = async (values: CreateTokenForm) => {
    return await apiPost("/access-token/v1/teams", { ...values, group: teamName });
  };

  const loadTokens = async (teamName?: string) => {
    if (!teamName) return;
    const response = await apiGet("/access-token/v1/teams");
    if (response.isError) {
      console.error("Failed to load team tokens:", response.error);
    } else {
      setTokens(response.data.filter((token: any) => token.group === teamName));
    }
    setLoadingTokens(false);
  };

  const loadUserTeams = async (teamName: string) => {
    const response = await apiGet("/access-token/v1/teams/current-teams");
    if (!response.isError && response.data?.groups?.includes(teamName)) {
      setCreateTokenDisabled(false);
    }
  };

  const onDelete = () => {
    if (teamId) onDeleteTeam(teamId);
    setMode("list");
  };

  return (
    <div className="setting">
      <SettingsPageHeader
        docUrl="https://docs.terrakube.io/user-guide/organizations/team-management"
        title={mode === "edit" ? (teamName ?? "Team") : "Create a team"}
        description={
          mode === "edit"
            ? "Choose what members of this team can do in the organization."
            : "A team gives the members of an identity provider group a role in this organization."
        }
        divider={false}
      />

      {error ? (
        <Alert title="Could not load the team" description={error} type="error" showIcon />
      ) : (
        <Spin spinning={loading}>
          <SettingsForm name="team" form={form} onFinish={onFinish} showSave={false}>
            <SettingsSection title="Identity">
              {mode === "edit" ? (
                <IdField id="team-id" value={teamId ?? ""} copiedMessage="Team ID copied" />
              ) : (
                <Form.Item
                  name="name"
                  label="Name"
                  extra="Must match a group name in your identity provider (AD, LDAP or OIDC); it cannot be changed later."
                  rules={[{ required: true, message: "Enter the identity provider group name" }]}
                >
                  <Input placeholder="e.g. ENGINEERING_TEAM" />
                </Form.Item>
              )}
            </SettingsSection>

            <TeamPermissionsV2 managePermissions={true} />

            <Flex gap="small">
              <Button type="primary" htmlType="submit" loading={saving}>
                {mode === "edit" ? "Save changes" : "Create team"}
              </Button>
              <Button onClick={onCancel}>Cancel</Button>
            </Flex>
          </SettingsForm>
        </Spin>
      )}

      {mode === "edit" && !loading && !error && (
        <>
          <div className="team-tokens">
            <SettingsSection
              maxWidth={680}
              title="Team API tokens"
              description={
                createTokenDisabled
                  ? "Tokens act with this team's permissions, for CI/CD pipelines and automation. Only members of this team can create them."
                  : "Tokens act with this team's permissions, for CI/CD pipelines and automation."
              }
              extra={
                <Button disabled={createTokenDisabled} onClick={onNewToken} htmlType="button">
                  Create a team token
                </Button>
              }
            >
              {loadingTokens ? (
                <Spin className="team-tokens-loading" />
              ) : (
                <TokenGrid hideTitle tokens={tokens} action={onDeleteToken} onDeleted={() => loadTokens(teamName)} />
              )}

              <CreatePatModal
                open={visible}
                onCancel={() => setVisible(false)}
                onCreated={() => loadTokens(teamName)}
                action={onCreateToken}
                shortlivedTokens={true}
              />
            </SettingsSection>
          </div>

          <DangerZone
            actionName="Delete this team"
            description={`Members of the ${teamName} group lose the organization permissions this team grants. Team access granted on individual workspaces and the group in your identity provider are not changed. This cannot be undone.`}
            disabled={!managePermission}
            confirmValue={teamName ?? ""}
            confirmMessage={
              <>
                The team <strong>{teamName}</strong> and its organization permissions will be deleted. This cannot be
                undone.
              </>
            }
            onConfirm={onDelete}
          />
        </>
      )}
    </div>
  );
};
