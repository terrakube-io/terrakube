import { DeleteOutlined, EditOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, Flex, Grid, message, Table, Tag, Tooltip, Typography } from "antd";
import { Loading } from "@/components/feedback/Loading";
import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { LinkButton } from "@/components/navigation/LinkButton";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { Team, TeamRole } from "../types";
import { EditTeam } from "./EditTeam";
import { teamRoles } from "./TeamPermissionsV2";
import "./Settings.css";
import "./TeamsTagsVariables.css";
import { AccessDeniedAlert } from "@/components/feedback/AccessDeniedAlert";
import { EmptyState } from "@/components/feedback/EmptyState";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";

const customPermissionLabels: [keyof Team["attributes"], string][] = [
  ["manageWorkspace", "workspaces"],
  ["manageState", "state"],
  ["manageModule", "modules"],
  ["manageProvider", "providers"],
  ["manageTemplate", "templates"],
  ["manageVcs", "VCS"],
  ["manageCollection", "collections"],
  ["planJob", "plan runs"],
  ["approveJob", "apply runs"],
];

// Preset roles explain themselves; a custom role lists what it grants.
function permissionSummary(team: Team): string {
  const role = team.attributes.role || "custom";
  if (role !== "custom") return teamRoles[role]?.description ?? "";
  const granted = customPermissionLabels.filter(([key]) => team.attributes[key]).map(([, label]) => label);
  return granted.length === 0 ? "No permissions granted." : `Can manage ${granted.join(", ")}.`;
}

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
  const [pendingDelete, setPendingDelete] = useState<Team | null>(null);
  const navigate = useNavigate();
  const screens = Grid.useBreakpoint();
  const mode: "list" | "edit" | "create" = editorMode === "new" ? "create" : (editorMode ?? "list");
  const teamId = editorId;
  const closeEditor = () => navigate(`/organizations/${orgid}/settings/teams`);
  const newTeamPath = `/organizations/${orgid}/settings/teams/new`;
  const editPath = (id: string) => `/organizations/${orgid}/settings/teams/edit/${id}`;

  const onDelete = (id: string) => {
    axiosInstance
      .delete(`organization/${orgid}/team/${id}`)
      .then(() => {
        message.success("Team deleted successfully");
        loadTeams();
      })
      .catch((err) => {
        message.error(`Could not delete the team: ${getErrorMessage(err)}`);
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
          message.error(`Could not load teams: ${getErrorMessage(err)}`);
        }
        setLoading(false);
      });
  };

  useEffect(() => {
    setLoading(true);
    loadTeams();
  }, [orgid]);

  const columns = [
    {
      title: "Name",
      key: "name",
      width: "30%",
      ellipsis: true,
      render: (_: unknown, team: Team) =>
        managePermission ? (
          <Link to={editPath(team.id)}>{team.attributes.name}</Link>
        ) : (
          <Typography.Text>{team.attributes.name}</Typography.Text>
        ),
    },
    {
      title: "Role",
      key: "role",
      width: 140,
      render: (_: unknown, team: Team) => {
        const role = (team.attributes.role || "custom") as TeamRole;
        return <Tag color={teamRoles[role]?.color ?? "default"}>{teamRoles[role]?.label ?? role}</Tag>;
      },
    },
    {
      title: "Permissions",
      key: "permissions",
      ellipsis: { showTitle: false },
      render: (_: unknown, team: Team) => (
        <Tooltip title={permissionSummary(team)} placement="topLeft">
          <Typography.Text type="secondary" className="team-row-meta">
            {permissionSummary(team)}
          </Typography.Text>
        </Tooltip>
      ),
    },
    {
      title: <span className="settings-list-sr-only">Actions</span>,
      key: "actions",
      width: 104,
      align: "right" as const,
      render: (_: unknown, team: Team) => (
        <Flex gap="small" justify="flex-end">
          <Tooltip title="Edit team">
            <Button
              icon={<EditOutlined />}
              aria-label={`Edit team ${team.attributes.name}`}
              disabled={!managePermission}
              onClick={() => navigate(editPath(team.id))}
            />
          </Tooltip>
          <Tooltip title="Delete team">
            <Button
              danger
              icon={<DeleteOutlined />}
              aria-label={`Delete team ${team.attributes.name}`}
              disabled={!managePermission}
              onClick={() => setPendingDelete(team)}
            />
          </Tooltip>
        </Flex>
      ),
    },
  ];

  return (
    <div className="setting">
      {error ? (
        <AccessDeniedAlert description={error} />
      ) : mode !== "list" ? (
        <EditTeam
          mode={mode}
          setMode={closeEditor}
          teamId={teamId}
          loadTeams={loadTeams}
          onDeleteTeam={onDelete}
          managePermission={managePermission}
        />
      ) : (
        <>
          <SettingsPageHeader
            docUrl="https://docs.terrakube.io/user-guide/organizations/team-management"
            title="Teams"
            description="A team gives the members of an identity provider group a role in this organization."
            divider={false}
            actions={
              <LinkButton to={newTeamPath} type="primary" icon={<PlusOutlined />} disabled={!managePermission}>
                Create team
              </LinkButton>
            }
          />
          <Loading loading={loading} description="Loading teams...">
            {teams.length === 0 ? (
              <EmptyState simple description="No teams yet. Create one to give a group access to this organization.">
                {managePermission && (
                  <LinkButton to={newTeamPath} icon={<PlusOutlined />}>
                    Create team
                  </LinkButton>
                )}
              </EmptyState>
            ) : (
              <section>
                <Typography.Title level={4} className="settings-list-count">
                  Teams ({teams.length})
                </Typography.Title>
                <Table
                  dataSource={teams}
                  columns={columns}
                  rowKey="id"
                  pagination={false}
                  tableLayout="fixed"
                  // Columns fit the content width from lg up; narrower screens scroll the table instead.
                  scroll={screens.lg ? undefined : { x: "max-content" }}
                />
              </section>
            )}
          </Loading>

          <DeleteConfirmationModal
            open={pendingDelete !== null}
            title="Delete team"
            message={
              <>
                Members of <strong>{pendingDelete?.attributes.name}</strong> lose the organization permissions this team
                grants. Team access granted on individual workspaces is not changed. This cannot be undone.
              </>
            }
            confirmValue={pendingDelete?.attributes.name}
            okText="Delete team"
            onConfirm={() => {
              if (pendingDelete) onDelete(pendingDelete.id);
              setPendingDelete(null);
            }}
            onCancel={() => setPendingDelete(null)}
          />
        </>
      )}
    </div>
  );
};
