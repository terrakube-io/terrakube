import { DeleteOutlined, EditOutlined, PlusOutlined, TeamOutlined } from "@ant-design/icons";
import { Avatar, Button, List, message, Popconfirm, Space, Tag, Typography, theme, Spin } from "antd";
import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { Team, TeamRole } from "../types";
import { EditTeam } from "./EditTeam";
import "./Settings.css";
import { AccessDeniedAlert } from "@/components/AccessDeniedAlert";
import { SettingsPageHeader } from "@/components/SettingsPageHeader";

const roleColors: Record<string, string> = {
  admin: "red",
  write: "orange",
  plan: "blue",
  read: "default",
  custom: "purple",
};

const roleLabels: Record<string, string> = {
  admin: "Admin",
  write: "Write",
  plan: "Plan",
  read: "Read",
  custom: "Custom",
};

const roleDescriptions: Record<string, string> = {
  admin: "Full control over all resources",
  write: "Can plan, apply runs, and manage resources",
  plan: "Can queue plans but cannot apply changes",
  read: "Read-only access to workspaces and runs",
  custom: "Fine-grained custom permissions",
};

type Props = {
  editorMode?: "new" | "edit";
  editorId?: string;
  managePermission?: boolean;
};

export const TeamSettings = ({ editorMode, editorId, managePermission = true }: Props) => {
  const { orgid } = useParams();
  const [teams, setTeams] = useState<Team[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const navigate = useNavigate();
  const mode: "list" | "edit" | "create" = editorMode === "new" ? "create" : (editorMode ?? "list");
  const teamId = editorId;
  const closeEditor = () => navigate(`/organizations/${orgid}/settings/teams`);
  const { token } = theme.useToken();

  const onDelete = (id: string) => {
    axiosInstance
      .delete(`organization/${orgid}/team/${id}`)
      .then(() => {
        message.success("Team deleted successfully");
        loadTeams();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      });
  };

  const loadTeams = () => {
    axiosInstance
      .get(`organization/${orgid}/team`)
      .then((response) => {
        setTeams(response.data.data);
        setLoading(false);
      })
      .catch((err) => {
        if (isPermissionError(err)) {
          setError(getErrorMessage(err));
        } else {
          message.error("Failed to load teams");
        }
        setLoading(false);
      });
  };

  useEffect(() => {
    setLoading(true);
    loadTeams();
  }, [orgid]);

  const getTeamDescription = (item: Team) => {
    const role = item.attributes.role || "custom";
    const desc = roleDescriptions[role] || "Custom permissions";

    if (role !== "custom") {
      return <Typography.Text type="secondary">{desc}</Typography.Text>;
    }

    // For custom role, show which permissions are enabled
    const enabledPermissions: string[] = [];
    if (item.attributes.manageWorkspace) enabledPermissions.push("Workspaces");
    if (item.attributes.manageState) enabledPermissions.push("State");
    if (item.attributes.manageModule) enabledPermissions.push("Modules");
    if (item.attributes.manageProvider) enabledPermissions.push("Providers");
    if (item.attributes.manageTemplate) enabledPermissions.push("Templates");
    if (item.attributes.manageVcs) enabledPermissions.push("VCS");
    if (item.attributes.manageCollection) enabledPermissions.push("Collections");
    if (item.attributes.planJob) enabledPermissions.push("Plan Runs");
    if (item.attributes.approveJob) enabledPermissions.push("Apply Runs");

    if (enabledPermissions.length === 0) {
      return <Typography.Text type="secondary">No permissions granted</Typography.Text>;
    }

    return <Typography.Text type="secondary">Can manage: {enabledPermissions.join(", ")}</Typography.Text>;
  };

  return (
    <div className="setting">
      {error ? (
        <AccessDeniedAlert description={error} />
      ) : mode !== "list" ? (
        <EditTeam mode={mode} setMode={closeEditor} teamId={teamId} loadTeams={loadTeams} />
      ) : (
        <>
          <SettingsPageHeader
            docUrl="https://docs.terrakube.io/user-guide/organizations/team-management"
            title="Team Management"
            description="Teams let you group users into specific categories to enable finer grained access control policies. Each team is assigned a role that determines what actions its members can perform within the organization."
            actions={
              <Link to={`/organizations/${orgid}/settings/teams/new`}>
                <Button type="primary" htmlType="button" icon={<PlusOutlined />} disabled={!managePermission}>
                  Create team
                </Button>
              </Link>
            }
          />
          <Spin spinning={loading} description="Loading Teams...">
            <List
              itemLayout="horizontal"
              dataSource={teams}
              renderItem={(item) => {
                const role = (item.attributes.role || "custom") as TeamRole;
                return (
                  <List.Item
                    actions={[
                      <Button icon={<EditOutlined />} type="link" disabled={!managePermission}>
                        <Link to={`/organizations/${orgid}/settings/teams/edit/${item.id}`}>Edit</Link>
                      </Button>,
                      <Popconfirm
                        okButtonProps={{ danger: true }}
                        onConfirm={() => onDelete(item.id)}
                        title={
                          <p>
                            This will permanently delete this team <br />
                            and any permissions associated with it. <br />
                            Are you sure?
                          </p>
                        }
                        okText="Yes"
                        cancelText="No"
                      >
                        <Button icon={<DeleteOutlined />} type="link" danger disabled={!managePermission}>
                          Delete
                        </Button>
                      </Popconfirm>,
                    ]}
                  >
                    <List.Item.Meta
                      avatar={<Avatar style={{ backgroundColor: token.colorPrimary }} icon={<TeamOutlined />} />}
                      title={
                        <Space>
                          {item.attributes.name}
                          <Tag color={roleColors[role] || "default"}>{roleLabels[role] || role}</Tag>
                        </Space>
                      }
                      description={getTeamDescription(item)}
                    />
                  </List.Item>
                );
              }}
            />
          </Spin>
        </>
      )}
    </div>
  );
};
