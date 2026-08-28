import { DeleteOutlined, EditOutlined, PlusOutlined, SafetyOutlined } from "@ant-design/icons";
import { Avatar, Button, List, message, Popconfirm, Spin, Tag, Typography, theme } from "antd";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { Federated } from "../types";
import { EditFederatedCredential } from "./EditFederatedCredential";
import SettingsSection from "@/modules/layout/SettingsSection/SettingsSection";
import "./Settings.css";
import { AccessDeniedAlert } from "@/components/AccessDeniedAlert";
import { SettingsPageHeader } from "@/modules/layout/SettingsPageHeader";

type Props = {
  managePermission?: boolean;
};

export const FederatedCredentials = ({ managePermission = true }: Props) => {
  const { orgid } = useParams();
  const [federated, setFederated] = useState<Federated[]>([]);
  const [claimCounts, setClaimCounts] = useState<Record<string, number>>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [mode, setMode] = useState<"list" | "edit" | "create">("list");
  const [federatedId, setFederatedId] = useState<string>();
  const { token } = theme.useToken();

  const onEdit = (id: string) => {
    setMode("edit");
    setFederatedId(id);
  };

  const onNew = () => {
    setMode("create");
  };

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
          setMode={setMode}
          federatedId={federatedId}
          loadFederated={loadFederated}
        />
      ) : (
        <>
          <SettingsPageHeader
            title="Federated Credentials"
            description="Federated credentials allow you to establish a trust relationship between terrakube and external identity providers, such as GitHub Actions."
          />
          <SettingsSection maxWidth="100%">
            <Button
              type="primary"
              onClick={onNew}
              htmlType="button"
              icon={<PlusOutlined />}
              disabled={!managePermission}
            >
              Create federated credential
            </Button>

            <Typography.Title level={3} style={{ marginTop: 30 }}>
              Federated Credentials
            </Typography.Title>
            <Spin spinning={loading} tip="Loading Federated Credentials...">
              <List
                itemLayout="horizontal"
                dataSource={federated}
                renderItem={(item) => (
                  <List.Item
                    actions={[
                      <Button
                        onClick={() => onEdit(item.id)}
                        icon={<EditOutlined />}
                        shape="round"
                        type="primary"
                        disabled={!managePermission}
                      >
                        Edit
                      </Button>,
                      <Popconfirm
                        okButtonProps={{ danger: true }}
                        onConfirm={() => onDelete(item.id)}
                        title={
                          <p>
                            This will permanently delete this federated credential. <br />
                            Are you sure?
                          </p>
                        }
                        okText="Yes"
                        cancelText="No"
                      >
                        <Button
                          icon={<DeleteOutlined />}
                          shape="round"
                          type="primary"
                          danger
                          disabled={!managePermission}
                        >
                          Delete
                        </Button>
                      </Popconfirm>,
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
          </SettingsSection>
        </>
      )}
    </div>
  );
};
