import { DeleteOutlined, EditOutlined, InfoCircleOutlined, PlusOutlined } from "@ant-design/icons";
import {
  Alert,
  Button,
  Collapse,
  Form,
  Input,
  message,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  Spin,
} from "antd";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { CreateVariableForm, UpdateVariableForm, Variable } from "../types";
import SettingsSection from "@/modules/layout/SettingsSection/SettingsSection";
import "./Settings.css";

type Props = {
  managePermission?: boolean;
};

export const GlobalVariablesSettings = ({ managePermission = true }: Props) => {
  const { orgid } = useParams();
  const [globalVariables, setGlobalVariables] = useState<Variable[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [visible, setVisible] = useState(false);
  const [variableKey, setVariableKey] = useState<string>();
  const [mode, setMode] = useState("create");
  const [variableId, setVariableId] = useState<string>();
  const [form] = Form.useForm<CreateVariableForm>();

  const VARIABLES_COLUMS = (onEdit: (id: string) => void) => [
    {
      title: "Key",
      dataIndex: "key",
      width: "35%",
      key: "key",
      sorter: (a: Variable, b: Variable) => a.attributes.key.localeCompare(b.attributes.key),
      defaultSortOrder: "ascend" as const,
      render: (_: any, record: Variable) => {
        return (
          <Space>
            {record.attributes.key}
            {record.attributes.hcl && <Tag color="blue">HCL</Tag>}
            {record.attributes.sensitive && <Tag color="orange">Sensitive</Tag>}
          </Space>
        );
      },
    },
    {
      title: "Value",
      dataIndex: "value",
      key: "value",
      width: "40%",
      render: (_: any, record: Variable) => {
        return record.attributes.sensitive ? <i>Sensitive - write only</i> : <div>{record.attributes.value}</div>;
      },
    },
    {
      title: "Actions",
      key: "action",
      width: "25%",
      render: (_: any, record: Variable) => {
        return (
          <div>
            <Button type="link" icon={<EditOutlined />} onClick={() => onEdit(record.id)} disabled={!managePermission}>
              Edit
            </Button>
            <Popconfirm
              okButtonProps={{ danger: true }}
              onConfirm={() => {
                onDelete(record.id);
              }}
              title={
                <p>
                  This will permanently delete this global variable <br />
                  and it will no longer be used in future runs. <br />
                  Are you sure?
                </p>
              }
              okText="Yes"
              cancelText="No"
            >
              {" "}
              <Button danger type="link" icon={<DeleteOutlined />} disabled={!managePermission}>
                Delete
              </Button>
            </Popconfirm>
          </div>
        );
      },
    },
  ];
  const onCancel = () => {
    setVisible(false);
  };
  const onEdit = (id: string) => {
    setMode("edit");
    setVariableId(id);
    setVisible(true);
    axiosInstance.get(`organization/${orgid}/globalvar/${id}`).then((response) => {
      setVariableKey(response.data.data.attributes.key);
      form.setFieldsValue({
        key: response.data.data.attributes.key,
        value: response.data.data.attributes.value,
        hcl: response.data.data.attributes.hcl,
        sensitive: response.data.data.attributes.sensitive,
        category: response.data.data.attributes.category,
        description: response.data.data.attributes.description,
      });
    });
  };

  const onNew = () => {
    form.resetFields();
    setVisible(true);
    setVariableKey("");
    setMode("create");
  };

  const onDelete = (id: string) => {
    axiosInstance
      .delete(`organization/${orgid}/globalvar/${id}`)
      .then((response) => {
        message.success("Global variable deleted successfully");
        loadGlobalVariables();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      });
  };

  const onCreate = (values: CreateVariableForm) => {
    const body = {
      data: {
        type: "globalvar",
        attributes: {
          key: values.key?.trim(),
          value: typeof values.value === "string" ? values.value.trim() : values.value,
          sensitive: values.sensitive,
          description: values.description?.trim(),
          hcl: values.hcl,
          category: values.category,
        },
      },
    };

    axiosInstance
      .post(`organization/${orgid}/globalvar`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then((response) => {
        message.success("Global variable created successfully");
        loadGlobalVariables();
        setVisible(false);
        form.resetFields();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      });
  };

  const onUpdate = (values: UpdateVariableForm) => {
    const body = {
      data: {
        type: "globalvar",
        id: variableId,
        attributes: {
          key: values.key?.trim(),
          value: typeof values.value === "string" ? values.value.trim() : values.value,
          description: values.description?.trim(),
          hcl: values.hcl,
          category: values.category,
        },
      },
    };

    axiosInstance
      .patch(`organization/${orgid}/globalvar/${variableId}`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then((response) => {
        message.success("Global variable updated successfully");
        loadGlobalVariables();
        setVisible(false);
        form.resetFields();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      });
  };

  const loadGlobalVariables = () => {
    axiosInstance
      .get(`organization/${orgid}/globalvar`)
      .then((response) => {
        setGlobalVariables(response.data.data);
        setLoading(false);
      })
      .catch((err) => {
        if (isPermissionError(err)) {
          setError(getErrorMessage(err));
        } else {
          message.error("Failed to load global variables");
        }
        setLoading(false);
      });
  };
  useEffect(() => {
    setLoading(true);
    loadGlobalVariables();
  }, [orgid]);

  const terraformVariables = globalVariables.filter((v) => v.attributes.category === "TERRAFORM");
  const envVariables = globalVariables.filter((v) => v.attributes.category !== "TERRAFORM");

  return (
    <div className="setting">
      {error ? (
        <Alert message="Access Denied" description={error} type="error" showIcon />
      ) : (
        <>
          <Typography.Title level={1} style={{ margin: 0 }}>
            Global Variables
          </Typography.Title>
          <div>
            <Typography.Text type="secondary" className="App-text">
              Global Variables allow you to define and apply variables one time across multiple workspaces within an
              organization.
            </Typography.Text>
          </div>
          <SettingsSection maxWidth="100%">
            <Button
              type="primary"
              onClick={onNew}
              htmlType="button"
              icon={<PlusOutlined />}
              disabled={!managePermission}
              style={{ marginBottom: 16 }}
            >
              Create global variable
            </Button>

            <Spin spinning={loading} tip="Loading Global Variables...">
              <Collapse
                defaultActiveKey={["TERRAFORM", "ENV"]}
                items={[
                  {
                    key: "TERRAFORM",
                    label: `Terraform Variables (${terraformVariables.length})`,
                    children: (
                      <Table
                        dataSource={terraformVariables}
                        columns={VARIABLES_COLUMS(onEdit)}
                        rowKey="key"
                        pagination={false}
                        locale={{ emptyText: "No terraform variables defined yet." }}
                      />
                    ),
                  },
                  {
                    key: "ENV",
                    label: `Environment Variables (${envVariables.length})`,
                    children: (
                      <Table
                        dataSource={envVariables}
                        columns={VARIABLES_COLUMS(onEdit)}
                        rowKey="key"
                        pagination={false}
                        locale={{ emptyText: "No environment variables defined yet." }}
                      />
                    ),
                  },
                ]}
              />
            </Spin>
          </SettingsSection>

          <Modal
            width="600px"
            open={visible}
            title={mode === "edit" ? "Edit global variable " + variableKey : "Create new global variable"}
            okText="Save global variable"
            onCancel={onCancel}
            cancelText="Cancel"
            onOk={() => {
              form
                .validateFields()
                .then((values) => {
                  if (mode === "create") onCreate(values);
                  else onUpdate(values);
                })
                .catch((info) => {
                  console.log("Validate Failed:", info);
                });
            }}
          >
            <Space style={{ width: "100%" }} direction="vertical">
              <Form name="globalVariable" form={form} layout="vertical">
                <Form.Item name="key" label="Key" rules={[{ required: true }]}>
                  <Input />
                </Form.Item>
                <Form.Item name="value" label="Value" rules={[{ required: true }]}>
                  <Input.TextArea rows={1} autoSize={{ maxRows: 5 }} />
                </Form.Item>
                <Form.Item name="category" label="Category" rules={[{ required: true }]}>
                  <Select placeholder="Please select a category">
                    <Select.Option value="TERRAFORM">Terraform Variable</Select.Option>
                    <Select.Option value="ENV">Environment Variable</Select.Option>
                  </Select>
                </Form.Item>
                <Form.Item name="description" rules={[{ required: true }]} label="Description">
                  <Input.TextArea style={{ width: "800px" }} />
                </Form.Item>
                <Form.Item
                  name="hcl"
                  valuePropName="checked"
                  label="HCL"
                  tooltip={{
                    title:
                      "Parse this field as HashiCorp Configuration Language (HCL). This allows you to interpolate values at runtime.",
                    icon: <InfoCircleOutlined />,
                  }}
                >
                  <Switch />
                </Form.Item>
                {mode === "create" ? (
                  <Form.Item
                    name="sensitive"
                    valuePropName="checked"
                    label="Sensitive"
                    tooltip={{
                      title:
                        "Sensitive variables are never shown in the UI or API. They may appear in Terraform logs if your configuration is designed to output them.",
                      icon: <InfoCircleOutlined />,
                    }}
                  >
                    <Switch />
                  </Form.Item>
                ) : (
                  ""
                )}
              </Form>
            </Space>
          </Modal>
        </>
      )}
    </div>
  );
};
