import { DeleteOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, Form, Input, List, message } from "antd";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { Agent } from "../types";
import "./Settings.css";
import { AccessDeniedAlert } from "@/components/AccessDeniedAlert";
import { SettingsPageHeader } from "@/components/SettingsPageHeader";
import LoadingFallback from "@/components/LoadingFallback";
import DeleteConfirmationModal from "@/components/DeleteConfirmationModal/DeleteConfirmationModal";
import AgentFormModal, { AddAgentFormValues, UpdateAgentFormValues } from "./components/AgentFormModal";

type Params = {
  orgid: string;
};

type AddAgentForm = AddAgentFormValues;

type UpdateAgentForm = UpdateAgentFormValues;

type Props = {
  managePermission?: boolean;
};

export const AgentSettings = ({ managePermission = true }: Props) => {
  const { orgid } = useParams<Params>();
  const [Agents, setAgents] = useState<Agent[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [visible, setVisible] = useState(false);
  const [AgentName, setAgentName] = useState<string>();
  const [mode, setMode] = useState("create");
  const [AgentId] = useState([]);
  const [pendingDelete, setPendingDelete] = useState<Agent | null>(null);
  const [form] = Form.useForm<AddAgentForm | UpdateAgentForm>();

  const onCancel = () => {
    setVisible(false);
  };

  const onNew = () => {
    form.resetFields();
    setVisible(true);
    setAgentName("");
    setMode("create");
  };

  const onDelete = (id: string) => {
    axiosInstance
      .delete(`organization/${orgid}/agent/${id}`)
      .then(() => {
        message.success("Agent pool deleted successfully");
        loadAgents();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      });
  };

  const onCreate = (values: AddAgentForm) => {
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

    axiosInstance
      .post(`organization/${orgid}/agent`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then((response) => {
        message.success("Agent pool created successfully");
        loadAgents();
        setVisible(false);
        form.resetFields();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      });
  };

  const onUpdate = (values: UpdateAgentForm) => {
    const body = {
      data: {
        type: "agent",
        id: AgentId,
        attributes: {
          description: values.description,
          url: values.url,
        },
      },
    };

    axiosInstance
      .patch(`organization/${orgid}/agent/${AgentId}`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then(() => {
        message.success("Agent pool updated successfully");
        loadAgents();
        setVisible(false);
        form.resetFields();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      });
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
          message.error("Failed to load agents");
        }
        setLoading(false);
      });
  };
  useEffect(() => {
    setLoading(true);
    loadAgents();
  }, [orgid]);

  return (
    <div className="setting">
      {error ? (
        <AccessDeniedAlert description={error} />
      ) : (
        <>
          <SettingsPageHeader
            docUrl="https://docs.terrakube.io/getting-started/deployment/self-hosted-agents"
            title="Agents"
            description="Terrakube uses these agents to execute terraform commands. Terrakube allow to have one or multiple agents to run jobs, you can have as many agents as you want for a single organization."
            actions={
              <Button
                type="primary"
                onClick={onNew}
                htmlType="button"
                icon={<PlusOutlined />}
                disabled={!managePermission}
              >
                Create agent pool
              </Button>
            }
          />
          <br></br>
          {loading ? (
            <LoadingFallback />
          ) : (
            <List
              itemLayout="horizontal"
              dataSource={Agents}
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

          <AgentFormModal
            open={visible}
            mode={mode === "create" ? "create" : "edit"}
            agentName={AgentName}
            form={form}
            onCancel={onCancel}
            onSubmit={(values) => {
              if (mode === "create") onCreate(values as AddAgentForm);
              else onUpdate(values);
            }}
          />

          <DeleteConfirmationModal
            open={pendingDelete !== null}
            title="Delete agent pool"
            message={
              <>
                Deleting the agent pool <strong>{pendingDelete?.attributes.name}</strong> cannot be undone.
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
