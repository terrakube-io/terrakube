import { DeleteOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, Form, List, message } from "antd";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { SshKey } from "../types";
import "./Settings.css";
import { AccessDeniedAlert } from "@/components/feedback/AccessDeniedAlert";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { Loading } from "@/components/feedback/Loading";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";
import SshKeyFormModal, { AddSshKeyFormValues, UpdateSshKeyFormValues } from "./components/SshKeyFormModal";

type Params = {
  orgid: string;
};

type AddSshKeyForm = AddSshKeyFormValues;

type UpdateSshKeyForm = UpdateSshKeyFormValues;

type Props = {
  managePermission?: boolean;
};

export const SSHKeysSettings = ({ managePermission = true }: Props) => {
  const { orgid } = useParams<Params>();
  const [sshKeys, setSSHKeys] = useState<SshKey[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [visible, setVisible] = useState(false);
  const [sshKeyName, setSSHKeyName] = useState<string>();
  const [mode, setMode] = useState("create");
  const [sshKeyId] = useState([]);
  const [pendingDelete, setPendingDelete] = useState<SshKey | null>(null);
  const [form] = Form.useForm<AddSshKeyForm | UpdateSshKeyForm>();

  const onCancel = () => {
    setVisible(false);
  };

  const onNew = () => {
    form.resetFields();
    setVisible(true);
    setSSHKeyName("");
    setMode("create");
  };

  const onDelete = (id: string) => {
    axiosInstance
      .delete(`organization/${orgid}/ssh/${id}`)
      .then(() => {
        loadSSHKeys();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      });
  };

  const onCreate = (values: AddSshKeyForm) => {
    const body = {
      data: {
        type: "ssh",
        attributes: {
          name: values.name,
          description: values.description,
          sshType: values.sshType,
          privateKey: values.privateKey,
        },
      },
    };

    axiosInstance
      .post(`organization/${orgid}/ssh`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then((response) => {
        loadSSHKeys();
        setVisible(false);
        form.resetFields();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      });
  };

  const onUpdate = (values: UpdateSshKeyForm) => {
    const body = {
      data: {
        type: "ssh",
        id: sshKeyId,
        attributes: {
          description: values.description,
          sshType: values.sshType,
          privateKey: values.privateKey,
        },
      },
    };

    axiosInstance
      .patch(`organization/${orgid}/ssh/${sshKeyId}`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then(() => {
        loadSSHKeys();
        setVisible(false);
        form.resetFields();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      });
  };

  const loadSSHKeys = () => {
    axiosInstance
      .get(`organization/${orgid}/ssh`)
      .then((response) => {
        setSSHKeys(response.data.data);
        setLoading(false);
      })
      .catch((err) => {
        if (isPermissionError(err)) {
          setError(getErrorMessage(err));
        } else {
          message.error("Failed to load SSH keys");
        }
        setLoading(false);
      });
  };
  useEffect(() => {
    setLoading(true);
    loadSSHKeys();
  }, [orgid]);

  return (
    <div className="setting">
      {error ? (
        <AccessDeniedAlert description={error} />
      ) : (
        <>
          <SettingsPageHeader
            docUrl="https://docs.terrakube.io/user-guide/vcs-providers/ssh"
            title="SSH Keys"
            description="Terrakube uses these private SSH keys for downloading private Terraform modules with Git-based sources during a Terraform run. SSH keys for downloading modules are assigned per-workspace."
            actions={
              <Button
                type="primary"
                onClick={onNew}
                htmlType="button"
                icon={<PlusOutlined />}
                disabled={!managePermission}
              >
                Add a Private SSH Key
              </Button>
            }
          />
          {loading ? (
            <Loading loading description="Loading SSH keys..." />
          ) : (
            <List
              itemLayout="horizontal"
              dataSource={sshKeys}
              renderItem={(item) => (
                <List.Item
                  actions={[
                    <Button
                      icon={<DeleteOutlined />}
                      type="link"
                      danger
                      disabled={!managePermission}
                      onClick={() => setPendingDelete(item)}
                    >
                      Delete
                    </Button>,
                  ]}
                >
                  <List.Item.Meta description={item.attributes.description} title={item.attributes.name} />
                </List.Item>
              )}
            />
          )}

          <SshKeyFormModal
            open={visible}
            mode={mode === "create" ? "create" : "edit"}
            sshKeyName={sshKeyName}
            form={form}
            onCancel={onCancel}
            onSubmit={(values) => {
              if (mode === "create") onCreate(values as AddSshKeyForm);
              else onUpdate(values);
            }}
          />

          <DeleteConfirmationModal
            open={pendingDelete !== null}
            title="Delete SSH key"
            message={
              <>
                Deleting the SSH key <strong>{pendingDelete?.attributes.name}</strong> cannot be undone. Any workspaces
                configured with this SSH key will no longer use it to download Terraform modules.
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
