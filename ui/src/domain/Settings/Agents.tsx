import { CloudServerOutlined, DeleteOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, Form, Tooltip, Typography, message } from "antd";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { Agent } from "../types";
import "./Settings.css";
import { AccessDeniedAlert } from "@/components/feedback/AccessDeniedAlert";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { Loading } from "@/components/feedback/Loading";
import { EmptyState } from "@/components/feedback/EmptyState";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";
import AgentFormModal, { AddAgentFormValues } from "./components/AgentFormModal";
import ResourceCard from "./components/ResourceCard";

type Params = {
  orgid: string;
};

type Props = {
  managePermission?: boolean;
};

export const AgentSettings = ({ managePermission = true }: Props) => {
  const { orgid } = useParams<Params>();
  const [agents, setAgents] = useState<Agent[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [visible, setVisible] = useState(false);
  const [saving, setSaving] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<Agent | null>(null);
  const [form] = Form.useForm<AddAgentFormValues>();

  const onNew = () => {
    form.resetFields();
    setVisible(true);
  };

  const onDelete = (agent: Agent) => {
    axiosInstance
      .get(`organization/${orgid}/workspace?filter[workspace]=agent.id==${agent.id}&fields[workspace]=name`)
      .then((response) => {
        const workspaces = response.data.data?.length ?? 0;
        if (workspaces > 0) {
          message.error(
            `${agent.attributes.name} is used by ${workspaces} workspace${workspaces === 1 ? "" : "s"}. Move them to another agent pool first.`
          );
          return;
        }
        return axiosInstance.delete(`organization/${orgid}/agent/${agent.id}`).then(() => {
          message.success(`Agent pool ${agent.attributes.name} deleted`);
          loadAgents();
        });
      })
      .catch((err) => {
        message.error(`Could not delete the agent pool: ${getErrorMessage(err)}`);
      });
  };

  const onCreate = (values: AddAgentFormValues) => {
    const body = {
      data: {
        type: "agent",
        attributes: {
          name: values.name,
          description: values.description,
          url: values.url,
        },
      },
    };

    setSaving(true);
    axiosInstance
      .post(`organization/${orgid}/agent`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then(() => {
        message.success(`Agent pool ${values.name} added`);
        loadAgents();
        setVisible(false);
        form.resetFields();
      })
      .catch((err) => {
        message.error(`Could not add the agent pool: ${getErrorMessage(err)}`);
      })
      .finally(() => setSaving(false));
  };

  const loadAgents = () => {
    axiosInstance
      .get(`organization/${orgid}/agent`)
      .then((response) => {
        setAgents(response.data.data);
        setLoading(false);
      })
      .catch((err) => {
        if (isPermissionError(err)) {
          setError(getErrorMessage(err));
        } else {
          message.error(`Could not load agent pools: ${getErrorMessage(err)}`);
        }
        setLoading(false);
      });
  };
  useEffect(() => {
    setLoading(true);
    loadAgents();
  }, [orgid]);

  const renderList = () => {
    if (loading) return <Loading loading description="Loading agent pools..." />;
    if (agents.length === 0) {
      return (
        <EmptyState simple description="No agent pools yet. Jobs run on the default executor.">
          {managePermission && (
            <Button icon={<PlusOutlined />} onClick={onNew}>
              Add an agent pool
            </Button>
          )}
        </EmptyState>
      );
    }
    return (
      <>
        <Typography.Title level={4} className="resource-list-title">
          Agent pools ({agents.length})
        </Typography.Title>
        <div className="resource-list">
          {agents.map((item) => (
            <ResourceCard
              key={item.id}
              icon={<CloudServerOutlined />}
              name={item.attributes.name}
              actions={
                <Tooltip title="Delete">
                  <Button
                    icon={<DeleteOutlined />}
                    disabled={!managePermission}
                    aria-label={`Delete agent pool ${item.attributes.name}`}
                    onClick={() => setPendingDelete(item)}
                  />
                </Tooltip>
              }
            >
              {item.attributes.description && (
                <Typography.Text className="resource-card-meta">{item.attributes.description}</Typography.Text>
              )}
              {item.attributes.url && (
                <Typography.Text className="resource-mono resource-card-meta" copyable>
                  {item.attributes.url}
                </Typography.Text>
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
            docUrl="https://docs.terrakube.io/getting-started/deployment/self-hosted-agents"
            title="Agents"
            description="Agent pools run jobs for the workspaces assigned to them."
            divider={false}
            actions={
              <Button type="primary" onClick={onNew} icon={<PlusOutlined />} disabled={!managePermission}>
                Add an agent pool
              </Button>
            }
          />
          {renderList()}

          <AgentFormModal
            open={visible}
            form={form}
            saving={saving}
            onCancel={() => setVisible(false)}
            onSubmit={onCreate}
          />

          <DeleteConfirmationModal
            open={pendingDelete !== null}
            title="Delete agent pool"
            message={
              <>
                <strong>{pendingDelete?.attributes.name}</strong> can only be deleted when no workspace is assigned to
                it. The agent itself keeps running until you shut it down. This cannot be undone.
              </>
            }
            okText="Delete agent pool"
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
