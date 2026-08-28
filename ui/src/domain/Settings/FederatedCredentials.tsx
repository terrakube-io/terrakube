import { DeleteOutlined, EditOutlined, PlusOutlined, SafetyOutlined } from "@ant-design/icons";
import { Avatar, Button, List, message, Spin, Tag, Typography, theme } from "antd";
import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { Federated } from "../types";
import { EditFederatedCredential } from "./EditFederatedCredential";
import "./Settings.css";
import { AccessDeniedAlert } from "@/components/AccessDeniedAlert";
import { SettingsPageHeader } from "@/components/SettingsPageHeader";
import DeleteConfirmationModal from "@/components/DeleteConfirmationModal/DeleteConfirmationModal";

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
  const { token } = theme.useToken();

  const onDelete = async (id: string) => {
    try {
      // Delete all claims first, then the federated credential
      const claimsRes = await axiosInstance.get(`federated/${id}/claims`);
      const claimsData = claimsRes.data.data || [];
      await Promise.all(claimsData.map((c: any) => axiosInstance.delete(`federated/${id}/claims/${c.id}`)));
      await axiosInstance.delete(`federated/${id}`);
      message.success("Federated credential deleted successfully");
      loadFederated();
    } catch (err: any) {
      message.error(getErrorMessage(err));
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
          message.error("Failed to load federated credentials");
        }
        setLoading(false);
      });
  };

  useEffect(() => {
    setLoading(true);
    loadFederated();
  }, [orgid]);

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
            title="Federated Credentials"
            description="Federated credentials allow you to establish a trust relationship between terrakube and external identity providers, such as GitHub Actions."
            actions={
              <Link to={`/organizations/${orgid}/settings/federated-credentials/new`}>
                <Button type="primary" htmlType="button" icon={<PlusOutlined />} disabled={!managePermission}>
                  Create federated credential
                </Button>
              </Link>
            }
          />
          <Spin spinning={loading} description="Loading Federated Credentials...">
            <List
              itemLayout="horizontal"
              dataSource={federated}
              renderItem={(item) => (
                <List.Item
                  actions={[
                    <Button icon={<EditOutlined />} shape="round" type="primary" disabled={!managePermission}>
                      <Link to={`/organizations/${orgid}/settings/federated-credentials/edit/${item.id}`}>Edit</Link>
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
                    avatar={<Avatar style={{ backgroundColor: token.colorPrimary }} icon={<SafetyOutlined />} />}
                    title={item.attributes.name}
                    description={
                      <>
                        <Typography.Text type="secondary">{item.attributes.issuerUrl}</Typography.Text>
                        <br />
                        <Typography.Text type="secondary">{item.attributes.audience}</Typography.Text>
                        <br />
                        {claimCounts[item.id] > 0 ? (
                          <Tag color="blue" style={{ marginTop: 4 }}>
                            {claimCounts[item.id]} claim condition{claimCounts[item.id] !== 1 ? "s" : ""}
                          </Tag>
                        ) : (
                          <Tag style={{ marginTop: 4 }}>No claim conditions</Tag>
                        )}
                      </>
                    }
                  />
                </List.Item>
              )}
            />
          </Spin>

          <DeleteConfirmationModal
            open={pendingDelete !== null}
            title="Delete federated credential"
            message={
              <>
                Deleting the federated credential <strong>{pendingDelete?.attributes.name}</strong> cannot be undone.
              </>
            }
            okText="Delete"
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
