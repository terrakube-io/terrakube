import { DeleteOutlined, PlusOutlined } from "@ant-design/icons";
import { Alert, Button, Form, Input, List, message, Modal, Popconfirm, Select, Space, Typography, theme } from "antd";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { Agent } from "../types";
import "./Settings.css";

type Params = {
  orgid: string;
};

type AddAgentForm = {
  name?: string;
} & UpdateAgentForm;

type UpdateAgentForm = {
  description: string;
  url: string;
};

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
        <Alert message="Access Denied" description={error} type="error" showIcon />
      ) : (
        <>
          <h1>Agents</h1>
          <div>
            <Typography.Text type="secondary" className="App-text">
              Terrakube uses these agents to execute terraform commands. Terrakube allow to have one or multiple agents
              to run jobs, you can have as many agents as you want for a single organization.
            </Typography.Text>
          </div>
          <Button type="primary" onClick={onNew} htmlType="button" icon={<PlusOutlined />} disabled={!managePermission}>
            Create agent pool
          </Button>
          <br></br>

          <h3 style={{ marginTop: "30px" }}>Agents</h3>
          {loading ? (
            <p>Data loading...</p>
          ) : (
            <List
              itemLayout="horizontal"
              dataSource={Agents}
              renderItem={(item) => (
                <List.Item
                  actions={[
                    <Popconfirm
                      onConfirm={() => {
                        onDelete(item.id);
                      }}
                      style={{ width: "20px" }}
                      title={
                        <p>
                          This will permanently delete this Terrakube Agent <br />
                          <br />
                          Are you sure?
                        </p>
                      }
                      okText="Yes"
                      cancelText="No"
                    >
                      {" "}
                      <Button icon={<DeleteOutlined />} type="link" danger disabled={!managePermission}>
                        Delete
                      </Button>
                    </Popconfirm>,
                  ]}
                >
                  <List.Item.Meta description={item.attributes.description} title={item.attributes.name} />
                </List.Item>
              )}
            />
          )}

          <Modal
            width="650px"
            open={visible}
            title={mode === "edit" ? "Edit Terrakube Agent  " + AgentName : "Add a new Terrakube Agent"}
            okText="Save Terrakube Agent "
            onCancel={onCancel}
            cancelText="Cancel"
            onOk={() => {
              form
                .validateFields()
                .then((values) => {
                  if (mode === "create") onCreate(values as AddAgentForm);
                  else onUpdate(values);
                })
                .catch((info) => {
                  console.log("Validate Failed:", info);
                });
            }}
          >
            <Space style={{ width: "100%" }} direction="vertical">
              <Form name="Agent" form={form} layout="vertical">
                {mode === "create" ? (
                  <Form.Item name="name" label="Name" rules={[{ required: true }]}>
                    <Input />
                  </Form.Item>
                ) : (
                  ""
                )}

                <Form.Item name="description" label="Description" rules={[{ required: true }]}>
                  <Input />
                </Form.Item>
                <Form.Item name="url" label="Url" rules={[{ required: true }]}>
                  <Input />
                </Form.Item>
              </Form>
            </Space>
          </Modal>
        </>
      )}
    </div>
  );
};
