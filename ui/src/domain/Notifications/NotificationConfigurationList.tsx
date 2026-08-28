import { DeleteOutlined, EditOutlined, PlusOutlined } from "@ant-design/icons";
import { Avatar, Button, List, message, Spin, Tag, Typography } from "antd";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import axiosInstance, { getErrorMessage, isPermissionError } from "@/config/axiosConfig";
import { apiPost } from "@/modules/api/apiWrapper";
import { NotificationConfiguration } from "../types";
import { CHANNEL_META } from "./channelMeta";
import { EditNotificationConfiguration } from "./EditNotificationConfiguration";
import DeleteConfirmationModal from "@/components/DeleteConfirmationModal/DeleteConfirmationModal";
import SettingsSection from "@/components/SettingsSection/SettingsSection";
import { AccessDeniedAlert } from "@/components/AccessDeniedAlert";
import { SettingsPageHeader } from "@/components/SettingsPageHeader";

type Props = {
  orgId: string;
  workspaceId?: string;
  basePath?: string;
  editorMode?: "new" | "edit";
  editorId?: string;
  managePermission?: boolean;
};

export const NotificationConfigurationList = ({
  orgId,
  workspaceId,
  basePath,
  editorMode,
  editorId,
  managePermission = true,
}: Props) => {
  const [configurations, setConfigurations] = useState<NotificationConfiguration[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const navigate = useNavigate();
  const [localMode, setLocalMode] = useState<"list" | "create" | "edit">("list");
  const [localEditingId, setLocalEditingId] = useState<string>();
  const [pendingDelete, setPendingDelete] = useState<NotificationConfiguration | null>(null);
  const routed = basePath != null;
  const mode: "list" | "create" | "edit" = routed
    ? editorMode === "new"
      ? "create"
      : (editorMode ?? "list")
    : localMode;
  const editingId = routed ? editorId : localEditingId;
  const openCreate = () => (routed ? navigate(`${basePath}/new`) : setLocalMode("create"));
  const openEdit = (id: string) => {
    if (routed) {
      navigate(`${basePath}/edit/${id}`);
    } else {
      setLocalEditingId(id);
      setLocalMode("edit");
    }
  };
  const closeEditor = () => {
    if (routed) {
      navigate(basePath!);
    } else {
      setLocalMode("list");
      setLocalEditingId(undefined);
    }
  };

  const load = () => {
    setLoading(true);
    const body = {
      // workspace (and every other relationship in this schema) is exposed as a
      // Relay-style Connection in GraphQL, even for a to-one field - "workspace { id }"
      // is invalid and fails the whole query; it must be "workspace { edges { node { id } } }".
      query: `{
        organization(ids: ["${orgId}"]) {
          edges { node {
            notificationConfiguration {
              edges { node {
                id
                name
                description
                channelType
                destinationUrl
                active
                workspace { edges { node { id } } }
              } }
            }
          } }
        }
      }`,
    };
    apiPost<unknown, any>("/graphql/api/v1", body, { dataWrapped: true, contentType: "application/json" })
      .then((response: any) => {
        if (!response?.data) {
          // A GraphQL error still resolves this promise (HTTP 200 with an
          // "errors" array, no "data") - fail loudly instead of silently
          // rendering an empty list.
          message.error("Failed to load notification configurations");
          setLoading(false);
          return;
        }
        const edges = response.data?.organization?.edges?.[0]?.node?.notificationConfiguration?.edges || [];
        const all: NotificationConfiguration[] = edges.map((edge: any) => {
          const rawWorkspaceNode = edge.node.workspace?.edges?.[0]?.node ?? null;
          // Defensive: a lazily-fetched Workspace relationship could serialize its id as the
          // literal string "null" instead of a real UUID or JSON null (see NotificationConfiguration.workspace
          // for the root cause and fix) - treat that the same as no workspace at all rather than
          // let it silently fail both branches of the scope filter below.
          const workspaceNode = rawWorkspaceNode && rawWorkspaceNode.id !== "null" ? rawWorkspaceNode : null;
          return {
            id: edge.node.id,
            attributes: {
              name: edge.node.name,
              description: edge.node.description,
              channelType: edge.node.channelType,
              destinationUrl: edge.node.destinationUrl,
              active: edge.node.active,
            },
            relationships: { workspace: { data: workspaceNode ? { id: workspaceNode.id } : null } },
          };
        });
        const scoped = workspaceId
          ? all.filter(
              (c) => c.relationships?.workspace?.data === null || c.relationships?.workspace?.data?.id === workspaceId
            )
          : all.filter((c) => c.relationships?.workspace?.data === null);
        setConfigurations(scoped);
        setLoading(false);
      })
      .catch((err) => {
        if (isPermissionError(err)) {
          setError(getErrorMessage(err));
        } else {
          message.error("Failed to load notification configurations");
        }
        setLoading(false);
      });
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orgId, workspaceId]);

  // On the org-level page (no workspaceId), "configurations" is already org-scoped only (see
  // the "scoped" filter in load()), so the primary list there is simply all of them - there's no
  // "inherited" concept without a workspace to inherit into. Purely additive, no overrides: a
  // workspace gets both its own configs and every organization-wide default, together.
  const primaryConfigs = workspaceId
    ? configurations.filter((c) => c.relationships?.workspace?.data !== null)
    : configurations;
  const inheritedConfigs = workspaceId ? configurations.filter((c) => c.relationships?.workspace?.data === null) : [];

  const onDelete = (id: string) => {
    axiosInstance
      .delete(`notification_configuration/${id}`, { headers: { "Content-Type": undefined } })
      .then(() => {
        message.success("Notification configuration deleted successfully");
        load();
      })
      .catch((err) => message.error(getErrorMessage(err) || "Failed to delete notification configuration"));
  };

  if (mode !== "list") {
    return (
      <EditNotificationConfiguration
        orgId={orgId}
        workspaceId={workspaceId}
        mode={mode}
        configId={editingId}
        onDone={() => {
          closeEditor();
          load();
        }}
      />
    );
  }

  const renderChannelAvatar = (channelType: NotificationConfiguration["attributes"]["channelType"]) => {
    const meta = CHANNEL_META[channelType];
    const ChannelIcon = meta.icon;
    return (
      <Avatar style={{ backgroundColor: `${meta.color}1a` }} icon={<ChannelIcon style={{ color: meta.color }} />} />
    );
  };

  return (
    <div>
      {error ? (
        <AccessDeniedAlert description={error} />
      ) : (
        <>
          <SettingsPageHeader
            title="Notifications"
            description={
              workspaceId
                ? "Notifications configured specifically for this workspace, plus any organization-wide defaults - both apply together."
                : "Organization-wide defaults. These apply to every workspace in the organization, in addition to whatever that workspace configures for itself."
            }
            actions={
              <Button type="primary" icon={<PlusOutlined />} disabled={!managePermission} onClick={openCreate}>
                {workspaceId ? "Add notification for this workspace" : "Add organization-wide default"}
              </Button>
            }
          />
          <Spin spinning={loading}>
            {workspaceId && (
              <Typography.Title level={5} style={{ marginTop: 20, marginBottom: 4 }}>
                This workspace's notifications
              </Typography.Title>
            )}
            <List
              itemLayout="horizontal"
              dataSource={primaryConfigs}
              locale={{
                emptyText: workspaceId ? "No notifications configured specifically for this workspace." : " ",
              }}
              renderItem={(item) => (
                <List.Item
                  actions={[
                    <Button
                      icon={<EditOutlined />}
                      shape="round"
                      type="primary"
                      disabled={!managePermission}
                      onClick={() => openEdit(item.id)}
                    >
                      Edit
                    </Button>,
                    <Button
                      icon={<DeleteOutlined />}
                      shape="round"
                      type="primary"
                      danger
                      disabled={!managePermission}
                      onClick={() => setPendingDelete(item)}
                    >
                      Delete
                    </Button>,
                  ]}
                >
                  <List.Item.Meta
                    avatar={renderChannelAvatar(item.attributes.channelType)}
                    title={item.attributes.name}
                    description={
                      <>
                        <div>
                          <Tag
                            color={CHANNEL_META[item.attributes.channelType].color}
                            icon={(() => {
                              const Icon = CHANNEL_META[item.attributes.channelType].icon;
                              return <Icon />;
                            })()}
                          >
                            {CHANNEL_META[item.attributes.channelType].label}
                          </Tag>
                          {workspaceId && <Tag color="purple">This workspace</Tag>}
                          {!item.attributes.active && <Tag color="default">Disabled</Tag>}
                        </div>
                        {item.attributes.description && (
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            {item.attributes.description}
                          </Typography.Text>
                        )}
                      </>
                    }
                  />
                </List.Item>
              )}
            />

            {workspaceId && (
              <>
                <Typography.Title level={5} style={{ marginTop: 20, marginBottom: 4 }}>
                  Also applies here (organization-wide)
                </Typography.Title>
                <Typography.Text type="secondary" style={{ display: "block", marginBottom: 8, fontSize: 12 }}>
                  Managed at the organization level - edit these from the organization's notification settings.
                </Typography.Text>
                <List
                  itemLayout="horizontal"
                  dataSource={inheritedConfigs}
                  locale={{ emptyText: "No organization-wide defaults apply here." }}
                  renderItem={(item) => (
                    <List.Item>
                      <List.Item.Meta
                        avatar={renderChannelAvatar(item.attributes.channelType)}
                        title={item.attributes.name}
                        description={
                          <>
                            <div>
                              <Tag
                                color={CHANNEL_META[item.attributes.channelType].color}
                                icon={(() => {
                                  const Icon = CHANNEL_META[item.attributes.channelType].icon;
                                  return <Icon />;
                                })()}
                              >
                                {CHANNEL_META[item.attributes.channelType].label}
                              </Tag>
                              <Tag>Org default</Tag>
                              {!item.attributes.active && <Tag color="default">Disabled</Tag>}
                            </div>
                            {item.attributes.description && (
                              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                {item.attributes.description}
                              </Typography.Text>
                            )}
                          </>
                        }
                      />
                    </List.Item>
                  )}
                />
              </>
            )}
          </Spin>

          <DeleteConfirmationModal
            open={pendingDelete !== null}
            title="Delete notification configuration"
            message={`This will permanently delete the notification configuration "${pendingDelete?.attributes.name}".`}
            okText="Delete"
            onConfirm={() => {
              if (pendingDelete) {
                onDelete(pendingDelete.id);
              }
              setPendingDelete(null);
            }}
            onCancel={() => setPendingDelete(null)}
          />
        </>
      )}
    </div>
  );
};
