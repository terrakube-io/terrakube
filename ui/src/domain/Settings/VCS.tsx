import { DeleteOutlined, EditOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, Tag, Tooltip, Typography, message } from "antd";
import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { LinkButton } from "@/components/navigation/LinkButton";
import { useOrganizationName } from "@/hooks/useOrganizationName";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { VcsModel, VcsStatus, VcsType } from "../types";
import { AddVCS } from "./AddVCS";
import { EditVCS } from "./EditVCS";
import "./Settings.css";
import "./VCS.css";
import { AccessDeniedAlert } from "@/components/feedback/AccessDeniedAlert";
import VcsLogo from "@/components/display/VcsLogo";
import { Loading } from "@/components/feedback/Loading";
import { EmptyState } from "@/components/feedback/EmptyState";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";
import ResourceCard from "./components/ResourceCard";
import { relativeTime } from "@/modules/utils/dates";
import { getCallbackUrl, getConnectUrl, getVcsTypeExtended, usesOAuthFlow, vcsLabel } from "./vcsProviders";

type Props = {
  vcsMode?: "new" | "edit" | "list";
  vcsId?: string;
  managePermission?: boolean;
};

const providerLabel = (item: VcsModel) =>
  vcsLabel(getVcsTypeExtended(item.attributes.vcsType, item.attributes.connectionType, item.attributes.endpoint));

const plural = (count: number, noun: string) => (count > 0 ? `${count} ${noun}${count === 1 ? "" : "s"}` : "");

const STATUS_TAGS: Record<VcsStatus, { color: string; label: string }> = {
  [VcsStatus.COMPLETED]: { color: "success", label: "Connected" },
  [VcsStatus.PENDING]: { color: "warning", label: "Not connected" },
  [VcsStatus.ERROR]: { color: "error", label: "Connection failed" },
};

export const VCSSettings = ({ vcsMode, vcsId, managePermission = true }: Props) => {
  const { orgid } = useParams();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [vcs, setVCS] = useState<VcsModel[]>([]);
  const [pendingDelete, setPendingDelete] = useState<VcsModel | null>(null);

  const mode: "list" | "new" | "edit" = vcsMode ?? "list";
  const closeEditor = () => navigate(`/organizations/${orgid}/settings/vcs`);
  const organizationName = useOrganizationName(orgid);

  const onDelete = (item: VcsModel) => {
    const count = (path: string) => axiosInstance.get(path).then((response) => response.data.data?.length ?? 0);
    Promise.all([
      axiosInstance
        .get(`organization/${orgid}/vcs/${item.id}?include=workspace`)
        .then((response) => response.data.included?.length ?? 0),
      count(`organization/${orgid}/module?filter[module]=vcs.id==${item.id}&fields[module]=name`),
      count(`policy_set?filter[policy_set]=vcs.id==${item.id}&fields[policy_set]=name`),
    ])
      .then(([workspaces, modules, policySets]) => {
        const usage = [
          plural(workspaces, "workspace"),
          plural(modules, "module"),
          plural(policySets, "policy set"),
        ].filter(Boolean);
        if (usage.length > 0) {
          message.error(
            `${item.attributes.name} is used by ${new Intl.ListFormat("en").format(usage)}. Move them to another VCS provider first.`
          );
          return;
        }
        return axiosInstance.delete(`organization/${orgid}/vcs/${item.id}`).then(() => {
          message.success("VCS provider deleted");
          loadVCS();
        });
      })
      .catch((err) => {
        // Shared repository webhooks also reference the provider but are not exposed by the API, so the
        // usage check above can't see them; the delete then fails with a 409.
        message.error(
          err?.response?.status === 409
            ? `Could not delete ${item.attributes.name}: it is still in use. A shared repository webhook created with this provider is not listed here; it is removed once no workspace on its repository uses a shared webhook.`
            : `Could not delete the VCS provider: ${getErrorMessage(err)}`
        );
      });
  };

  useEffect(() => {
    setLoading(true);
    loadVCS();
  }, [orgid]);

  const loadVCS = () => {
    axiosInstance
      .get(`organization/${orgid}/vcs`)
      .then((response) => {
        setVCS(response.data.data);
        setLoading(false);
      })
      .catch((err) => {
        if (isPermissionError(err)) {
          setError(getErrorMessage(err));
        } else {
          message.error(`Could not load VCS providers: ${getErrorMessage(err)}`);
        }
        setLoading(false);
      });
  };

  const renderConnection = (item: VcsModel) => {
    const attrs = item.attributes;
    const provider = providerLabel(item);
    if (!usesOAuthFlow(attrs.vcsType, attrs.connectionType)) return null;
    if (attrs.status === VcsStatus.COMPLETED) {
      return (
        <Typography.Text className="resource-card-meta">
          Connected through OAuth by <strong>{attrs.createdBy}</strong>. Every Terrakube user in{" "}
          {organizationName ?? "this organization"} uses this token for {provider} API calls.
        </Typography.Text>
      );
    }
    return (
      <div className="vcs-connect">
        <Typography.Text className="resource-card-meta">
          Connecting signs you in to {provider}. Your token is then used for every {provider} API call in{" "}
          {organizationName ?? "this organization"}.
        </Typography.Text>
        <Button
          size="small"
          target="_blank"
          disabled={!managePermission}
          href={getConnectUrl(attrs.vcsType, attrs.clientId, getCallbackUrl(attrs.callback ?? item.id), attrs.endpoint)}
        >
          Connect to {provider}
        </Button>
      </div>
    );
  };

  const renderList = () => {
    if (loading) return <Loading loading description="Loading VCS providers..." />;
    if (vcs.length === 0) {
      return (
        <EmptyState simple description="No VCS providers yet. Workspaces and modules need one to read repositories.">
          {managePermission && (
            <LinkButton to={`/organizations/${orgid}/settings/vcs/new`} icon={<PlusOutlined />}>
              Add a VCS provider
            </LinkButton>
          )}
        </EmptyState>
      );
    }
    return (
      <>
        <Typography.Title level={4} className="resource-list-title">
          VCS providers ({vcs.length})
        </Typography.Title>
        <div className="resource-list">
          {vcs.map((item) => {
            const attrs = item.attributes;
            const status = usesOAuthFlow(attrs.vcsType, attrs.connectionType) ? STATUS_TAGS[attrs.status] : undefined;
            return (
              <ResourceCard
                key={item.id}
                icon={
                  // VcsLogo has no managed identity variant; it is still Azure DevOps.
                  <VcsLogo
                    type={attrs.vcsType === VcsType.AZURE_SP_MI ? VcsType.AZURE_DEVOPS : attrs.vcsType}
                    size={20}
                  />
                }
                name={attrs.name}
                tags={
                  <>
                    <Tag>{providerLabel(item)}</Tag>
                    {status && <Tag color={status.color}>{status.label}</Tag>}
                  </>
                }
                actions={
                  <>
                    <Tooltip title="Edit">
                      <LinkButton
                        to={`/organizations/${orgid}/settings/vcs/edit/${item.id}`}
                        icon={<EditOutlined />}
                        disabled={!managePermission}
                        aria-label={`Edit VCS provider ${attrs.name}`}
                      />
                    </Tooltip>
                    <Tooltip title="Delete">
                      <Button
                        icon={<DeleteOutlined />}
                        disabled={!managePermission}
                        aria-label={`Delete VCS provider ${attrs.name}`}
                        onClick={() => setPendingDelete(item)}
                      />
                    </Tooltip>
                  </>
                }
              >
                <dl className="resource-card-fields">
                  <dt>Callback URL</dt>
                  <dd>
                    <Typography.Text className="resource-mono" copyable>
                      {getCallbackUrl(attrs.callback ?? item.id)}
                    </Typography.Text>
                  </dd>
                  {attrs.apiUrl && (
                    <>
                      <dt>API URL</dt>
                      <dd className="resource-mono">{attrs.apiUrl}</dd>
                    </>
                  )}
                  <dt>Created</dt>
                  <dd>
                    {relativeTime(attrs.createdDate) ?? "Unknown"}
                    {attrs.createdBy && ` by ${attrs.createdBy}`}
                  </dd>
                </dl>
                {renderConnection(item)}
              </ResourceCard>
            );
          })}
        </div>
      </>
    );
  };

  return (
    <div className="setting">
      {error ? (
        <AccessDeniedAlert description={error} />
      ) : mode === "list" ? (
        <div>
          <SettingsPageHeader
            docUrl="https://docs.terrakube.io/user-guide/vcs-providers"
            title="VCS providers"
            description="Let workspaces and modules read your Git repositories."
            divider={false}
            actions={
              <LinkButton
                to={`/organizations/${orgid}/settings/vcs/new`}
                type="primary"
                icon={<PlusOutlined />}
                disabled={!managePermission}
              >
                Add a VCS provider
              </LinkButton>
            }
          />
          {renderList()}
          <DeleteConfirmationModal
            open={pendingDelete !== null}
            title="Delete VCS provider"
            message={
              <>
                Deleting <strong>{pendingDelete?.attributes.name}</strong> removes its stored credentials. It only works
                when no workspace, module, policy set or shared repository webhook uses this provider. This cannot be
                undone.
              </>
            }
            confirmValue={pendingDelete?.attributes.name ?? ""}
            okText="Delete VCS provider"
            onConfirm={() => {
              if (pendingDelete) onDelete(pendingDelete);
              setPendingDelete(null);
            }}
            onCancel={() => setPendingDelete(null)}
          />
        </div>
      ) : mode === "new" ? (
        <AddVCS setMode={closeEditor} loadVCS={loadVCS} />
      ) : (
        <EditVCS vcsId={vcsId!} setMode={closeEditor} loadVCS={loadVCS} />
      )}
    </div>
  );
};
