import { InfoCircleOutlined } from "@ant-design/icons";
import { Alert, Button, Divider, Form, Input, Space, Spin, Tooltip, Typography, message, theme } from "antd";
import CreatePatModal from "@/modules/token/modals/CreatePatModal";
import { CreateTokenForm } from "@/modules/user/types";
import TokenGrid from "@/modules/token/TokenGrid";
import { apiDelete, apiGet, apiPost } from "@/modules/api/apiWrapper";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import { TeamToken } from "../types";
import "./Settings.css";
import { TeamPermissionsV2 } from "./TeamPermissionsV2";

type Props = {
  mode: "edit" | "create";
  setMode: React.Dispatch<React.SetStateAction<"list" | "edit" | "create">>;
  teamId?: string;
  loadTeams: () => void;
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

export const EditTeam = ({ mode, setMode, teamId, loadTeams }: Props) => {
  const { orgid } = useParams();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [loadingTokens, setLoadingTokens] = useState(true);
  const [form] = Form.useForm();
  const [teamName, setTeamName] = useState<string>();
  const [tokens, setTokens] = useState<TeamToken[]>([]);
  const [visible, setVisible] = useState(false);
  const [createTokenDisabled, setCreateTokenDisabled] = useState(true);

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
        message.error(getErrorMessage(err));
      });
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
        message.error(getErrorMessage(err));
      });
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
      message.error("Failed to delete token");
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

  return (
    <div className="setting">
      <h1>{mode === "edit" ? `Team: ${teamName}` : "New Team"}</h1>
      <Typography.Text type="secondary">
        {mode === "edit"
          ? "Update this team's role and permissions to control what its members can do within the organization."
          : "Create a new team and assign a role to control what its members can do. The team name must match a valid identity provider group name."}
      </Typography.Text>

      {loading ? (
        <Spin style={{ marginTop: 24, display: "block" }} />
      ) : error ? (
        <Alert message="Error" description={error} type="error" showIcon style={{ marginTop: 16 }} />
      ) : (
        <Form name="team" form={form} onFinish={onFinish} layout="vertical" style={{ marginTop: 24 }}>
          {mode === "create" && (
            <Form.Item
              name="name"
              tooltip={{
                title: "Must match a valid identity provider (AD/LDAP/OIDC) group name",
                icon: <InfoCircleOutlined />,
              }}
              label="Team Name"
              rules={[{ required: true, message: "Team name is required" }]}
              extra="The team name must correspond to a group in your identity provider."
            >
              <Input placeholder="e.g. ENGINEERING_TEAM" />
            </Form.Item>
          )}

          <Divider />

          <TeamPermissionsV2 managePermissions={true} />

          <Divider />

          <Space direction="horizontal">
            <Button type="primary" htmlType="submit">
              {mode === "edit" ? "Update team" : "Create team"}
            </Button>
            <Button onClick={onCancel} type="default">
              Cancel
            </Button>
          </Space>
        </Form>
      )}

      {mode === "edit" && !loading && !error && (
        <>
          <Divider />

          <h2>Team API Tokens</h2>
          <Typography.Text type="secondary">
            Team API tokens inherit the team's access level. Use them for CI/CD pipelines and automation.
          </Typography.Text>

          <div style={{ marginTop: 16 }}>
            <Tooltip title={createTokenDisabled ? "You must be a member of this team to create tokens" : ""}>
              <Button type="primary" disabled={createTokenDisabled} onClick={onNewToken} htmlType="button">
                Create a Team Token
              </Button>
            </Tooltip>
          </div>

          <h4 style={{ marginTop: 24 }}>Existing Tokens</h4>
          {loadingTokens ? (
            <Spin style={{ display: "block", marginTop: 16 }} />
          ) : (
            <TokenGrid tokens={tokens} action={onDeleteToken} onDeleted={() => loadTokens(teamName)} />
          )}

          <CreatePatModal
            visible={visible}
            onCancel={() => setVisible(false)}
            onCreated={() => loadTokens(teamName)}
            action={onCreateToken}
            shortlivedTokens={true}
          />
        </>
      )}

      {mode === "edit" && (
        <Button style={{ marginTop: 40 }} onClick={onCancel} type="default">
          Go back to Teams list
        </Button>
      )}
    </div>
  );
};
