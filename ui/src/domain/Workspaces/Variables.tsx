import { DeleteOutlined, EditOutlined, InfoCircleOutlined, PlusOutlined } from "@ant-design/icons";
import {
  Button,
  Form,
  Input,
  Modal,
  Popconfirm,
  Radio,
  Space,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
  Checkbox,
} from "antd";
import { useState } from "react";
import { ORGANIZATION_ARCHIVE, WORKSPACE_ARCHIVE } from "../../config/actionTypes";
import axiosInstance from "../../config/axiosConfig";
import { CreateVariableForm, FlatVariable } from "../types";

const VARIABLES_COLUMS = (
  organizationId: string,
  workspaceId: string,
  onEdit: (variable: FlatVariable) => void,
  manageWorkspace: boolean
) => [
  {
    title: "Key",
    dataIndex: "key",
    width: "30%",
    key: "key",
    sorter: (a: FlatVariable, b: FlatVariable) => a.key.localeCompare(b.key),
    defaultSortOrder: "ascend" as const,
    render: (_: string, record: FlatVariable) => {
      return (
        <div>
          {record.key} &nbsp;&nbsp;&nbsp;&nbsp; {record.hcl && <Tag>HCL</Tag>}{" "}
          {record.sensitive && <Tag>Sensitive</Tag>}
        </div>
      );
    },
  },
  {
    title: "Value",
    dataIndex: "value",
    key: "value",
    width: "35%",
    render: (_: string, record: FlatVariable) => {
      return record.sensitive ? (
        <i>Sensitive - write only</i>
      ) : (
        <Tooltip
          title={record.description}
          placement="topLeft"
          overlayStyle={{ width: 400, wordBreak: "break-word" }}
          overlayClassName="tooltip"
          trigger={["hover"]}
        >
          <div style={{ maxWidth: 2000, maxHeight: 100, overflow: "auto" }}>{record.value}</div>
        </Tooltip>
      );
    },
  },
  {
    title: "Category",
    dataIndex: "category",
    key: "category",
    width: "15%",
    sorter: (a: FlatVariable, b: FlatVariable) => a.category.localeCompare(b.category),
    render: (_: string, record: FlatVariable) => {
      return record.category === "TERRAFORM" ? "terraform" : "env";
    },
  },
  {
    title: "Actions",
    key: "action",
    width: "20%",
    render: (_: string, record: FlatVariable) => {
      return (
        <div>
          <Button type="link" icon={<EditOutlined />} onClick={() => onEdit(record)} disabled={!manageWorkspace}>
            Edit
          </Button>
          <Popconfirm
            onConfirm={() => {
              deleteVariable(record.id, organizationId, workspaceId);
            }}
            title={
              <p>
                This will permanently delete this variable <br />
                and it will no longer be used in future runs. <br />
                Are you sure?
              </p>
            }
            okText="Yes"
            cancelText="No"
          >
            {" "}
            <Button danger type="link" icon={<DeleteOutlined />} disabled={!manageWorkspace}>
              Delete
            </Button>
          </Popconfirm>
        </div>
      );
    },
  },
];

const COLLECTION_VARIABLES_COLUMNS = () => [
  {
    title: "Key",
    dataIndex: "key",
    width: "25%",
    key: "key",
    sorter: (a: any, b: any) => a.key.localeCompare(b.key),
    defaultSortOrder: "ascend" as const,
    render: (_: string, record: any) => {
      return (
        <div>
          {record.key} &nbsp;&nbsp;&nbsp;&nbsp; {record.hcl && <Tag>HCL</Tag>}{" "}
          {record.sensitive && <Tag>Sensitive</Tag>}
        </div>
      );
    },
  },
  {
    title: "Value",
    dataIndex: "value",
    key: "value",
    width: "25%",
    render: (_: string, record: any) => {
      return record.sensitive ? (
        <i>Sensitive - write only</i>
      ) : (
        <Tooltip
          title={record.description}
          placement="topLeft"
          overlayStyle={{ width: 400, wordBreak: "break-word" }}
          overlayClassName="tooltip"
          trigger={["hover"]}
        >
          <div style={{ maxWidth: 2000, maxHeight: 100, overflow: "auto" }}>{record.value}</div>
        </Tooltip>
      );
    },
  },
  {
    title: "Category",
    dataIndex: "category",
    width: "15%",
    key: "category",
    sorter: (a: any, b: any) => a.category.localeCompare(b.category),
    render: (_: string, record: any) => {
      return record.category === "TERRAFORM" ? "terraform" : "env";
    },
  },
  {
    title: "Priority",
    dataIndex: "priority",
    width: "10%",
    key: "priority",
    render: (_: string, record: any) => {
      return <div>{record.priority}</div>;
    },
  },
  {
    title: "Collection",
    dataIndex: "collectionName",
    width: "25%",
    key: "collectionName",
    render: (_: string, record: any) => {
      return <div>{record.collectionName}</div>;
    },
  },
];

const GLOBAL_VARIABLES_COLUMNS = () => [
  {
    title: "Key",
    dataIndex: "key",
    width: "40%",
    key: "key",
    sorter: (a: FlatVariable, b: FlatVariable) => a.key.localeCompare(b.key),
    defaultSortOrder: "ascend" as const,
    render: (_: string, record: FlatVariable) => {
      return (
        <div>
          {record.key} &nbsp;&nbsp;&nbsp;&nbsp; {record.hcl && <Tag>HCL</Tag>}{" "}
          {record.sensitive && <Tag>Sensitive</Tag>}
        </div>
      );
    },
  },
  {
    title: "Value",
    dataIndex: "value",
    key: "value",
    width: "40%",
    render: (_: string, record: FlatVariable) => {
      return record.sensitive ? (
        <i>Sensitive - write only</i>
      ) : (
        <Tooltip
          title={record.description}
          placement="topLeft"
          overlayStyle={{ width: 400, wordBreak: "break-word" }}
          overlayClassName="tooltip"
          trigger={["hover"]}
        >
          <div style={{ maxWidth: 2000, maxHeight: 100, overflow: "auto" }}>{record.value}</div>
        </Tooltip>
      );
    },
  },
  {
    title: "Category",
    dataIndex: "category",
    width: "20%",
    key: "category",
    sorter: (a: FlatVariable, b: FlatVariable) => a.category.localeCompare(b.category),
    render: (_: string, record: FlatVariable) => {
      return record.category === "TERRAFORM" ? "terraform" : "env";
    },
  },
];

const validateMessages = {
  required: "${label} is required!",
};

type Props = {
  vars: FlatVariable[];
  env: FlatVariable[];
  manageWorkspace: boolean;
  collectionVars: any[];
  collectionEnvVars: any[];
  globalVariables: FlatVariable[];
  globalEnvVariables: FlatVariable[];
};

export const Variables = ({
  vars,
  env,
  manageWorkspace,
  collectionVars,
  collectionEnvVars,
  globalVariables,
  globalEnvVariables,
}: Props) => {
  const workspaceId = sessionStorage.getItem(WORKSPACE_ARCHIVE);
  const organizationId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);
  const [form] = Form.useForm<CreateVariableForm>();
  const [visible, setVisible] = useState(false);
  const [variableName, setVariableName] = useState("");
  const [category, setCategory] = useState("TERRAFORM");
  const [mode, setMode] = useState("create");
  const [variableId, setVariableId] = useState("");
  const onCancel = () => {
    setVisible(false);
  };
  const onEdit = (variable: FlatVariable) => {
    setMode("edit");
    setVariableId(variable.id);
    setVariableName(variable.key);
    form.setFieldsValue({
      key: variable.key,
      value: variable.value,
      sensitive: variable.sensitive,
      hcl: variable.hcl,
      description: variable.description,
      category: variable.category,
    });
    setVisible(true);
    setCategory(variable.category);
  };

  const onCreate = (values: CreateVariableForm) => {
    const body = {
      data: {
        type: "variable",
        attributes: {
          key: values.key,
          value: values.value,
          sensitive: values.sensitive,
          description: values.description,
          hcl: values.hcl,
          category: category,
        },
      },
    };

    axiosInstance
      .post(`organization/${organizationId}/workspace/${workspaceId}/variable`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then((response) => {
        setVisible(false);
        form.resetFields();
      });
  };

  const onUpdate = (values: CreateVariableForm) => {
    const body = {
      data: {
        type: "variable",
        id: variableId,
        attributes: {
          key: values.key,
          value: values.value,
          sensitive: values.sensitive,
          description: values.description,
          hcl: values.hcl,
          category: category,
        },
      },
    };

    axiosInstance
      .patch(`organization/${organizationId}/workspace/${workspaceId}/variable/${variableId}`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then((response) => {
        setVisible(false);
        form.resetFields();
      });
  };

  // Combine Terraform and Environment variables
  const workspaceVariables = [...vars, ...env];

  // Combine Collection Terraform and Environment variables
  const collectionVariables = [...collectionVars, ...collectionEnvVars];

  // Combine Global Terraform and Environment variables
  const globalVars = [...globalVariables, ...globalEnvVariables];

  return (
    <div>
      <h1>Variables</h1>
      <div>
        <Typography.Text type="secondary" className="App-text">
          <p>
            These variables are used for all plans and applies in this workspace. Workspaces using Terraform 0.10.0 or
            later can also load default values from any *.auto.tfvars files in the configuration.
          </p>
          <p>
            Sensitive variables are hidden from view in the UI and API, and can't be edited. (To change a sensitive
            variable, delete and replace it.) Sensitive variables can still appear in Terraform logs if your
            configuration is designed to output them.
          </p>
        </Typography.Text>
      </div>
      <h2>Workspace variables ({workspaceVariables.length})</h2>
      <div>
        <Typography.Text type="secondary" className="App-text">
          These Terraform variables are set using a terraform.tfvars file. To use interpolation or set a non-string
          value for a variable, click its HCL checkbox.
        </Typography.Text>
      </div>

      <Table
        dataSource={workspaceVariables}
        columns={VARIABLES_COLUMS(organizationId!, workspaceId!, onEdit, manageWorkspace)}
        rowKey="key"
      />
      <Button
        type="primary"
        htmlType="button"
        onClick={() => {
          setMode("create");
          form.resetFields();
          setCategory("TERRAFORM"); // Default to Terraform
          setVisible(true);
        }}
        disabled={!manageWorkspace}
        icon={<PlusOutlined />}
      >
        Add variable
      </Button>

      <div className="envVariables">
        <h2>Collection Variables ({collectionVariables.length})</h2>
        <div>
          <Typography.Text type="secondary" className="App-text">
            <p>
              The following values are taken from the collection used by this workspace, these values are injected
              inside the Terrakube remote jobs.
            </p>
          </Typography.Text>
        </div>
        <Table dataSource={collectionVariables} columns={COLLECTION_VARIABLES_COLUMNS()} rowKey="key" />
      </div>

      <div className="envVariables">
        <h2>Global Variables ({globalVars.length})</h2>
        <div>
          <Typography.Text type="secondary" className="App-text">
            <p>
              The following values are taken from the organization global variables, these values are injected inside
              the Terrakube remote jobs.
            </p>
          </Typography.Text>
        </div>
        <Table dataSource={globalVars} columns={GLOBAL_VARIABLES_COLUMNS()} rowKey="key" />
      </div>

      <Modal
        width="600px"
        open={visible}
        title={mode === "edit" ? "Edit variable " + variableName : "Add variable"}
        okText="Save variable"
        cancelText="Cancel"
        onCancel={onCancel}
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
          <Form name="create-org" form={form} layout="vertical" validateMessages={validateMessages}>
            <Typography.Title level={5} style={{ margin: "0 0 15px 0" }}>
              Select variable category
            </Typography.Title>

            <Form.Item name="category">
              <Radio.Group value={category} onChange={(e) => setCategory(e.target.value)}>
                <div style={{ display: "flex", flexDirection: "column", gap: "15px" }}>
                  <Radio value="TERRAFORM" style={{ display: "flex", alignItems: "flex-start" }}>
                    <div>
                      <div>Terraform variable</div>
                      <div style={{ color: "rgba(0,0,0,0.45)", fontSize: "14px" }}>
                        These variables should match the declarations in your configuration. Click the HCL box to use
                        interpolation or set a non-string value.
                      </div>
                    </div>
                  </Radio>

                  <Radio value="ENV" style={{ display: "flex", alignItems: "flex-start" }}>
                    <div>
                      <div>Environment variable</div>
                      <div style={{ color: "rgba(0,0,0,0.45)", fontSize: "14px" }}>
                        These variables are available in the Terraform runtime environment.
                      </div>
                    </div>
                  </Radio>
                </div>
              </Radio.Group>
            </Form.Item>

            <Form.Item name="key" label="Key" rules={[{ required: true }]}>
              <Input />
            </Form.Item>

            <Form.Item name="value" label="Value" rules={[{ required: true }]}>
              <Input.TextArea rows={3} autoSize={{ minRows: 3, maxRows: 6 }} />
            </Form.Item>

            <div style={{ display: "flex", gap: "30px", marginBottom: "15px" }}>
              <Form.Item
                name="hcl"
                valuePropName="checked"
                style={{ marginBottom: 0 }}
                tooltip={{
                  title:
                    "Parse this field as HashiCorp Configuration Language (HCL). This allows you to interpolate values at runtime.",
                  icon: <InfoCircleOutlined />,
                }}
              >
                <Checkbox>HCL</Checkbox>
              </Form.Item>

              <Form.Item
                name="sensitive"
                valuePropName="checked"
                style={{ marginBottom: 0 }}
                tooltip={{
                  title:
                    "Sensitive variables are never shown in the UI or API. They may appear in Terraform logs if your configuration is designed to output them.",
                  icon: <InfoCircleOutlined />,
                }}
              >
                <Checkbox>Sensitive</Checkbox>
              </Form.Item>
            </div>

            <Form.Item name="description" label="Description">
              <Input.TextArea placeholder="Description (optional)" style={{ width: "100%" }} rows={3} />
            </Form.Item>
          </Form>
        </Space>
      </Modal>
    </div>
  );
};

const deleteVariable = (variableId: string, organizationId: string, workspaceId: string) => {
  axiosInstance
    .delete(`organization/${organizationId}/workspace/${workspaceId}/variable/${variableId}`, {
      headers: {
        "Content-Type": "application/vnd.api+json",
      },
    })
    .then((response) => {
      console.log(response);
    });
};
