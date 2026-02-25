import { DeleteOutlined, PlusOutlined } from "@ant-design/icons";
import { Alert, Button, Form, Input, List, message, Modal, Popconfirm, Select, Space, Typography, theme } from "antd";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { SshKey } from "../types";
import "./Settings.css";
const { TextArea } = Input;

type Params = {
  orgid: string;
};

type AddSshKeyForm = {
  name: string;
} & UpdateSshKeyForm;

type UpdateSshKeyForm = {
  description: string;
  sshType: string;
  privateKey: string;
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
  const [sshKeyName, setSSHKeyName] = useState<string>();
  const [mode, setMode] = useState("create");
  const [sshKeyId] = useState([]);
  const [form] = Form.useForm<AddSshKeyForm | UpdateSshKeyForm>();
  const { token } = theme.useToken();

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
        <Alert message="Access Denied" description={error} type="error" showIcon />
      ) : (
        <>
          <h1>SSH Keys</h1>
          <div>
            <Typography.Text type="secondary" className="App-text">
              Terrakube uses these private SSH keys for downloading private Terraform modules with Git-based sources
              during a Terraform run. SSH keys for downloading modules are assigned per-workspace.
            </Typography.Text>
          </div>
          <Button type="primary" onClick={onNew} htmlType="button" icon={<PlusOutlined />} disabled={!managePermission}>
            Add a Private SSH Key
          </Button>
          <br></br>

          <h3 style={{ marginTop: "30px" }}>SSH Keys</h3>
          {loading ? (
            <p>Data loading...</p>
          ) : (
            <List
              itemLayout="horizontal"
              dataSource={sshKeys}
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
                          This will permanently delete this SSH Key <br />
                          Any workspaces configured with this SSH key will no longer use it to download Terraform
                          modules. <br />
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
            title={mode === "edit" ? "Edit Private SSH Key " + sshKeyName : "Add a new Private SSH Key"}
            okText="Save SSH Key"
            onCancel={onCancel}
            cancelText="Cancel"
            onOk={() => {
              form
                .validateFields()
                .then((values) => {
                  if (mode === "create") onCreate(values as AddSshKeyForm);
                  else onUpdate(values);
                })
                .catch((info) => {
                  console.log("Validate Failed:", info);
                });
            }}
          >
            <Space style={{ width: "100%" }} direction="vertical">
              <Form name="sshKey" form={form} layout="vertical">
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
                <Form.Item name="sshType" label="SSH Type" rules={[{ required: true }]}>
                  <Select placeholder="Please select a ssh type">
                    <Select.Option value="rsa">RSA</Select.Option>
                    <Select.Option value="ed25519">ED25519</Select.Option>
                  </Select>
                </Form.Item>
                <Form.Item
                  name="privateKey"
                  rules={[{ required: true }]}
                  label="Private SSH Key"
                  extra={
                    <p>
                      Generate a new key with{" "}
                      <code style={{ backgroundColor: token.colorBgContainer }}>ssh-keygen -t rsa -m PEM</code>, make
                      sure the private key starts with{" "}
                      <code style={{ backgroundColor: token.colorBgContainer }}>-----BEGIN RSA PRIVATE KEY-----</code>
                    </p>
                  }
                >
                  <TextArea rows={6} />
                </Form.Item>
              </Form>
            </Space>
          </Modal>
        </>
      )}
    </div>
  );
};
