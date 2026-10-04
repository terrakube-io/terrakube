import { DeleteOutlined, KeyOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, Form, Tag, Tooltip, Typography, message } from "antd";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { SshKey } from "../types";
import "./Settings.css";
import { AccessDeniedAlert } from "@/components/feedback/AccessDeniedAlert";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { Loading } from "@/components/feedback/Loading";
import { EmptyState } from "@/components/feedback/EmptyState";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";
import SshKeyFormModal, { AddSshKeyFormValues } from "./components/SshKeyFormModal";
import ResourceCard from "./components/ResourceCard";

const plural = (count: number, noun: string) => (count > 0 ? `${count} ${noun}${count === 1 ? "" : "s"}` : "");

type Params = {
  orgid: string;
};

type Props = {
  managePermission?: boolean;
};

export const SSHKeysSettings = ({ managePermission = true }: Props) => {
  const { orgid } = useParams<Params>();
  const [sshKeys, setSSHKeys] = useState<SshKey[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [visible, setVisible] = useState(false);
  const [saving, setSaving] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<SshKey | null>(null);
  const [form] = Form.useForm<AddSshKeyFormValues>();

  const onNew = () => {
    form.resetFields();
    setVisible(true);
  };

  const onDelete = (key: SshKey) => {
    const count = (path: string) => axiosInstance.get(path).then((response) => response.data.data?.length ?? 0);
    Promise.all([
      count(`organization/${orgid}/workspace?filter[workspace]=ssh.id==${key.id}&fields[workspace]=name`),
      count(`organization/${orgid}/module?filter[module]=ssh.id==${key.id}&fields[module]=name`),
    ])
      .then(([workspaces, modules]) => {
        const usage = [plural(workspaces, "workspace"), plural(modules, "module")].filter(Boolean);
        if (usage.length > 0) {
          message.error(
            `${key.attributes.name} is used by ${new Intl.ListFormat("en").format(usage)}. Move them to another SSH key first.`
          );
          return;
        }
        return axiosInstance.delete(`organization/${orgid}/ssh/${key.id}`).then(() => {
          message.success(`SSH key ${key.attributes.name} deleted`);
          loadSSHKeys();
        });
      })
      .catch((err) => {
        message.error(`Could not delete the SSH key: ${getErrorMessage(err)}`);
      });
  };

  const onCreate = (values: AddSshKeyFormValues) => {
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

    setSaving(true);
    axiosInstance
      .post(`organization/${orgid}/ssh`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then(() => {
        message.success(`SSH key ${values.name} added`);
        loadSSHKeys();
        setVisible(false);
        form.resetFields();
      })
      .catch((err) => {
        message.error(`Could not add the SSH key: ${getErrorMessage(err)}`);
      })
      .finally(() => setSaving(false));
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
          message.error(`Could not load SSH keys: ${getErrorMessage(err)}`);
        }
        setLoading(false);
      });
  };
  useEffect(() => {
    setLoading(true);
    loadSSHKeys();
  }, [orgid]);

  const renderList = () => {
    if (loading) return <Loading loading description="Loading SSH keys..." />;
    if (sshKeys.length === 0) {
      return (
        <EmptyState simple description="No SSH keys yet. Add one to download modules from private Git repositories.">
          {managePermission && (
            <Button icon={<PlusOutlined />} onClick={onNew}>
              Add an SSH key
            </Button>
          )}
        </EmptyState>
      );
    }
    return (
      <>
        <Typography.Title level={4} className="resource-list-title">
          SSH keys ({sshKeys.length})
        </Typography.Title>
        <div className="resource-list">
          {sshKeys.map((item) => (
            <ResourceCard
              key={item.id}
              icon={<KeyOutlined />}
              name={item.attributes.name}
              tags={
                item.attributes.sshType && <Tag className="resource-mono">{item.attributes.sshType.toUpperCase()}</Tag>
              }
              actions={
                <Tooltip title="Delete">
                  <Button
                    icon={<DeleteOutlined />}
                    disabled={!managePermission}
                    aria-label={`Delete SSH key ${item.attributes.name}`}
                    onClick={() => setPendingDelete(item)}
                  />
                </Tooltip>
              }
            >
              {item.attributes.description && (
                <Typography.Text className="resource-card-meta">{item.attributes.description}</Typography.Text>
              )}
            </ResourceCard>
          ))}
        </div>
      </>
    );
  };

  return (
    <div className="setting">
      {error ? (
        <AccessDeniedAlert description={error} />
      ) : (
        <>
          <SettingsPageHeader
            docUrl="https://docs.terrakube.io/user-guide/vcs-providers/ssh"
            title="SSH keys"
            description="Workspaces use these keys to download modules from private Git repositories."
            divider={false}
            actions={
              <Button type="primary" onClick={onNew} icon={<PlusOutlined />} disabled={!managePermission}>
                Add an SSH key
              </Button>
            }
          />
          {renderList()}

          <SshKeyFormModal
            open={visible}
            form={form}
            saving={saving}
            onCancel={() => setVisible(false)}
            onSubmit={onCreate}
          />

          <DeleteConfirmationModal
            open={pendingDelete !== null}
            title="Delete SSH key"
            message={
              <>
                <strong>{pendingDelete?.attributes.name}</strong> can only be deleted when no workspace or module uses
                it. This cannot be undone.
              </>
            }
            confirmValue={pendingDelete?.attributes.name ?? ""}
            okText="Delete SSH key"
            onConfirm={() => {
              if (pendingDelete) onDelete(pendingDelete);
              setPendingDelete(null);
            }}
            onCancel={() => setPendingDelete(null)}
          />
        </>
      )}
    </div>
  );
};
