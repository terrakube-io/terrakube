import { DeleteOutlined, EditOutlined, InfoCircleOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, Form, Input, Modal, Popconfirm, Select, Space, Switch, Table, Tag, Typography, Spin } from "antd";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance from "../../config/axiosConfig";
import { CreateVariableForm, UpdateVariableForm, Variable } from "../types";
import "./Settings.css";

export const GlobalVariablesSettings = () => {
  const { orgid } = useParams();
  const [globalVariables, setGlobalVariables] = useState<Variable[]>([]);
  const [loading, setLoading] = useState(false);
  const [visible, setVisible] = useState(false);
  const [variableKey, setVariableKey] = useState<string>();
  const [mode, setMode] = useState("create");
  const [variableId, setVariableId] = useState<string>();
  const [form] = Form.useForm<CreateVariableForm>();

  const VARIABLES_COLUMS = (onEdit: (id: string) => void) => [
    {
      title: "Key",
      dataIndex: "key",
      width: "40%",
      key: "key",
      sorter: (a: Variable, b: Variable) => a.attributes.key.localeCompare(b.attributes.key),
      defaultSortOrder: "ascend" as const,
      render: (_: any, record: Variable) => {
        return (
          <div>
            {record.attributes.key} &nbsp;&nbsp;&nbsp;&nbsp; <Tag visible={record.attributes.hcl}>HCL</Tag>{" "}
            <Tag visible={record.attributes.sensitive}>Sensitive</Tag>
          </div>
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
      render: (_: any, record: Variable) => {
        return (
          <div>
            <Button type="link" icon={<EditOutlined />} onClick={() => onEdit(record.id)}>
              Edit
            </Button>
            <Popconfirm
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
              <Button danger type="link" icon={<DeleteOutlined />}>
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
    axiosInstance.delete(`organization/${orgid}/globalvar/${id}`).then((response) => {
      loadGlobalVariables();
    });
  };

  const onCreate = (values: CreateVariableForm) => {
    const body = {
      data: {
        type: "globalvar",
        attributes: {
          key: values.key,
          value: values.value,
          sensitive: values.sensitive,
          description: values.description,
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
        loadGlobalVariables();
        setVisible(false);
        form.resetFields();
      });
  };

  const onUpdate = (values: UpdateVariableForm) => {
    const body = {
      data: {
        type: "globalvar",
        id: variableId,
        attributes: {
          key: values.key,
          value: values.value,
          description: values.description,
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
        loadGlobalVariables();
        setVisible(false);
        form.resetFields();
      });
  };

  const loadGlobalVariables = () => {
    axiosInstance.get(`organization/${orgid}/globalvar`).then((response) => {
      setGlobalVariables(response.data.data);
      setLoading(false);
    });
  };
  useEffect(() => {
    setLoading(true);
    loadGlobalVariables();
  }, [orgid]);

  return (
    <div className="setting">
      <h1>Global Variables</h1>
      <div>
        <Typography.Text type="secondary" className="App-text">
          Global Variables allow you to define and apply variables one time across multiple workspaces within an
          organization.
        </Typography.Text>
      </div>
      <Button type="primary" onClick={onNew} htmlType="button" icon={<PlusOutlined />}>
        Create global variable
      </Button>
      <br></br>

      <h3 style={{ marginTop: "30px" }}>Global Variables</h3>
      <Spin spinning={loading} tip="Loading Global Variables...">
        <Table dataSource={globalVariables} columns={VARIABLES_COLUMS(onEdit)} rowKey="key" />
      </Spin>

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
    </div>
  );
};
