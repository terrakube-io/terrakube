import { DeleteOutlined, EditOutlined, PlusOutlined } from "@ant-design/icons";
import { Avatar, Button, List, message, Tag, Typography } from "antd";
import "./Notifications.css";
import { Loading } from "@/components/feedback/Loading";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import axiosInstance, { getErrorMessage, isPermissionError } from "@/config/axiosConfig";
import { apiPost } from "@/modules/api/apiWrapper";
import { NotificationConfiguration } from "../types";
import { CHANNEL_META } from "./channelMeta";
import { EditNotificationConfiguration } from "./EditNotificationConfiguration";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";
import { AccessDeniedAlert } from "@/components/feedback/AccessDeniedAlert";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { EmptyState } from "@/components/feedback/EmptyState";

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
        message.success("Notification deleted");
        load();
      })
      .catch((err) => message.error(getErrorMessage(err) || "Could not delete the notification"));
  };

  if (mode !== "list") {
    return (
      <EditNotificationConfiguration
        orgId={orgId}
        workspaceId={workspaceId}
        mode={mode}
        configId={editingId}
        managePermission={managePermission}
        onDone={() => {
          closeEditor();
          load();
        }}
      />
    );
  }

  const renderItem = (item: NotificationConfiguration, inherited: boolean) => {
    const meta = CHANNEL_META[item.attributes.channelType];
    const ChannelIcon = meta.icon;
    const name = item.attributes.name;
    return (
      <List.Item
        actions={
          inherited
            ? undefined
            : [
                <Button
                  key="edit"
                  icon={<EditOutlined />}
                  disabled={!managePermission}
                  aria-label={`Edit ${name}`}
                  onClick={() => openEdit(item.id)}
                >
                  Edit
                </Button>,
                <Button
                  key="delete"
                  icon={<DeleteOutlined />}
                  disabled={!managePermission}
                  aria-label={`Delete ${name}`}
                  onClick={() => setPendingDelete(item)}
                >
                  Delete
                </Button>,
              ]
        }
      >
        <List.Item.Meta
          avatar={<Avatar shape="square" className="notification-channel-avatar" icon={<ChannelIcon />} />}
          title={name}
          description={
            <>
              <div>
                <Tag>{meta.label}</Tag>
                {!item.attributes.active && <Tag color="warning">Disabled</Tag>}
              </div>
              {item.attributes.description && (
                <Typography.Text type="secondary" className="notification-meta-text">
                  {item.attributes.description}
                </Typography.Text>
              )}
            </>
          }
        />
      </List.Item>
    );
  };

  const addButton = (primary: boolean) => (
    <Button
      type={primary ? "primary" : "default"}
      icon={<PlusOutlined />}
      disabled={!managePermission}
      onClick={openCreate}
    >
      Add notification
    </Button>
  );

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
                ? "Messages sent when this workspace's runs change state."
                : "Messages sent when runs in any workspace of this organization change state."
            }
            divider={false}
            actions={addButton(true)}
          />
          <Loading loading={loading} description="Loading notifications...">
            {workspaceId ? (
              <>
                <section>
                  <Typography.Title level={4}>This workspace ({primaryConfigs.length})</Typography.Title>
                  {primaryConfigs.length === 0 ? (
                    <Typography.Paragraph type="secondary">
                      No notifications set for this workspace only.
                    </Typography.Paragraph>
                  ) : (
                    <List
                      itemLayout="horizontal"
                      dataSource={primaryConfigs}
                      renderItem={(item) => renderItem(item, false)}
                    />
                  )}
                </section>
                <section className="notification-inherited-section">
                  <Typography.Title level={4}>Organization-wide ({inheritedConfigs.length})</Typography.Title>
                  {inheritedConfigs.length === 0 ? (
                    <Typography.Paragraph type="secondary">No organization-wide notifications.</Typography.Paragraph>
                  ) : (
                    <>
                      <Typography.Paragraph type="secondary">
                        These also apply here. Change them in the organization&apos;s notification settings.
                      </Typography.Paragraph>
                      <List
                        itemLayout="horizontal"
                        dataSource={inheritedConfigs}
                        renderItem={(item) => renderItem(item, true)}
                      />
                    </>
                  )}
                </section>
              </>
            ) : primaryConfigs.length === 0 ? (
              <EmptyState simple description="No notifications yet. Add one to get a message when runs change state.">
                {managePermission && addButton(false)}
              </EmptyState>
            ) : (
              <section>
                <Typography.Title level={4}>Organization-wide ({primaryConfigs.length})</Typography.Title>
                <List
                  itemLayout="horizontal"
                  dataSource={primaryConfigs}
                  renderItem={(item) => renderItem(item, false)}
                />
              </section>
            )}
          </Loading>

          <DeleteConfirmationModal
            open={pendingDelete !== null}
            title="Delete notification"
            message={`Runs stop sending messages for ${pendingDelete?.attributes.name}. This cannot be undone.`}
            okText="Delete notification"
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
