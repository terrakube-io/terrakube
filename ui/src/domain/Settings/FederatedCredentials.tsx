import { DeleteOutlined, EditOutlined, PlusOutlined, SafetyOutlined } from "@ant-design/icons";
import { Button, message, Tag, Tooltip, Typography } from "antd";
import { Loading } from "@/components/feedback/Loading";
import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { LinkButton } from "@/components/navigation/LinkButton";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { Federated } from "../types";
import { EditFederatedCredential } from "./EditFederatedCredential";
import "./Settings.css";
import { AccessDeniedAlert } from "@/components/feedback/AccessDeniedAlert";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";
import { EmptyState } from "@/components/feedback/EmptyState";
import ResourceCard from "./components/ResourceCard";

type Props = {
  editorMode?: "new" | "edit";
  editorId?: string;
  managePermission?: boolean;
};

export const FederatedCredentials = ({ editorMode, editorId, managePermission = true }: Props) => {
  const { orgid } = useParams();
  const [federated, setFederated] = useState<Federated[]>([]);
  const [claimCounts, setClaimCounts] = useState<Record<string, number>>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [pendingDelete, setPendingDelete] = useState<Federated | null>(null);
  const navigate = useNavigate();
  const mode: "list" | "edit" | "create" = editorMode === "new" ? "create" : (editorMode ?? "list");
  const federatedId = editorId;
  const closeEditor = () => navigate(`/organizations/${orgid}/settings/federated-credentials`);

  const onDelete = async (id: string) => {
    try {
      // Delete all claims first, then the federated credential
      const claimsRes = await axiosInstance.get(`federated/${id}/claims`);
      const claimsData = claimsRes.data.data || [];
      await Promise.all(claimsData.map((c: any) => axiosInstance.delete(`federated/${id}/claims/${c.id}`)));
      await axiosInstance.delete(`federated/${id}`);
      message.success("Federated credential deleted");
      loadFederated();
    } catch (err: any) {
      message.error(`Could not delete the federated credential: ${getErrorMessage(err)}`);
    }
  };

  const loadFederated = () => {
    axiosInstance
      .get(`federated`)
      .then(async (response) => {
        const items: Federated[] = response.data.data;
        setFederated(items);

        // Load claim counts for each federated credential
        const counts: Record<string, number> = {};
        await Promise.all(
          items.map(async (item) => {
            try {
              const claimsRes = await axiosInstance.get(`federated/${item.id}/claims`);
              counts[item.id] = (claimsRes.data.data || []).length;
            } catch {
              counts[item.id] = 0;
            }
          })
        );
        setClaimCounts(counts);
        setLoading(false);
      })
      .catch((err) => {
        if (isPermissionError(err)) {
          setError(getErrorMessage(err));
        } else {
          message.error(`Could not load federated credentials: ${getErrorMessage(err)}`);
        }
        setLoading(false);
      });
  };

  useEffect(() => {
    setLoading(true);
    loadFederated();
  }, [orgid]);

  const renderList = () => {
    if (loading) return <Loading loading description="Loading federated credentials..." />;
    if (federated.length === 0) {
      return (
        <EmptyState simple description="No federated credentials yet. Pipelines need one to sign in without a token.">
          {managePermission && (
            <LinkButton to={`/organizations/${orgid}/settings/federated-credentials/new`} icon={<PlusOutlined />}>
              Add a federated credential
            </LinkButton>
          )}
        </EmptyState>
      );
    }
    return (
      <>
        <Typography.Title level={4} className="resource-list-title">
          Federated credentials ({federated.length})
        </Typography.Title>
        <div className="resource-list">
          {federated.map((item) => {
            const claims = claimCounts[item.id] ?? 0;
            return (
              <ResourceCard
                key={item.id}
                icon={<SafetyOutlined />}
                name={item.attributes.name}
                tags={
                  claims > 0 ? (
                    <Tag>
                      {claims} claim condition{claims !== 1 ? "s" : ""}
                    </Tag>
                  ) : (
                    <Tag color="warning">No claim conditions</Tag>
                  )
                }
                actions={
                  <>
                    <Tooltip title="Edit">
                      <LinkButton
                        to={`/organizations/${orgid}/settings/federated-credentials/edit/${item.id}`}
                        icon={<EditOutlined />}
                        disabled={!managePermission}
                        aria-label={`Edit federated credential ${item.attributes.name}`}
                      />
                    </Tooltip>
                    <Tooltip title="Delete">
                      <Button
                        icon={<DeleteOutlined />}
                        disabled={!managePermission}
                        aria-label={`Delete federated credential ${item.attributes.name}`}
                        onClick={() => setPendingDelete(item)}
                      />
                    </Tooltip>
                  </>
                }
              >
                <dl className="resource-card-fields">
                  <dt>Issuer URL</dt>
                  <dd className="resource-mono">{item.attributes.issuerUrl}</dd>
                  <dt>Audience</dt>
                  <dd className="resource-mono">{item.attributes.audience}</dd>
                </dl>
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
      ) : mode !== "list" ? (
        <EditFederatedCredential
          mode={mode}
          setMode={closeEditor}
          federatedId={federatedId}
          loadFederated={loadFederated}
        />
      ) : (
        <>
          <SettingsPageHeader
            docUrl="https://docs.terrakube.io/user-guide/workspaces/dynamic-provider-credentials"
            title="Federated credentials"
            description="Let CI pipelines and other identity providers act as a team, without stored secrets."
            divider={false}
            actions={
              <LinkButton
                to={`/organizations/${orgid}/settings/federated-credentials/new`}
                type="primary"
                icon={<PlusOutlined />}
                disabled={!managePermission}
              >
                Add a federated credential
              </LinkButton>
            }
          />
          {renderList()}

          <DeleteConfirmationModal
            open={pendingDelete !== null}
            title="Delete federated credential"
            message={
              <>
                Tokens from <span className="resource-mono">{pendingDelete?.attributes.issuerUrl}</span> can no longer
                act as team <strong>{pendingDelete?.attributes.name}</strong>, so pipelines that use it fail to sign in.
                Its claim conditions are deleted too. This cannot be undone.
              </>
            }
            confirmValue={pendingDelete?.attributes.name ?? ""}
            okText="Delete federated credential"
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
