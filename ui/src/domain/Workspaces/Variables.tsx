import { DeleteOutlined, EditOutlined, InfoCircleOutlined, PlusOutlined } from "@ant-design/icons";
import {
  Button,
  Form,
  Input,
  Modal,
  Popconfirm,
  Radio,
  Table,
  Tag,
  Tooltip,
  Typography,
  Checkbox,
} from "antd";
import { useState, useMemo } from "react";
import { ORGANIZATION_ARCHIVE, WORKSPACE_ARCHIVE } from "../../config/actionTypes";
import axiosInstance from "../../config/axiosConfig";
import { CreateVariableForm, FlatVariable } from "../types";

const VARIABLES_COLUMNS = (
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
          <div
            style={{
              maxWidth: 2000,
              maxHeight: 100,
              overflow: "auto",
              cursor: manageWorkspace ? "pointer" : "default",
            }}
            onClick={() => {
              if (manageWorkspace) onEdit(record);
            }}
          >
            {record.value}
          </div>
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

const VARIABLE_SET_COLUMNS = () => [
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
    title: "Source",
    dataIndex: "collectionName",
    width: "20%",
    key: "source",
    render: (_: string, record: any) => {
      return <Tag>{record.collectionName || "Global"}</Tag>;
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
  const workspaceVariables = useMemo(() => [...vars, ...env], [vars, env]);

  // Combine all variable sets (collections + global) for unified display
  const variableSets = useMemo(
    () => [...collectionVars, ...collectionEnvVars, ...globalVariables, ...globalEnvVariables],
    [collectionVars, collectionEnvVars, globalVariables, globalEnvVariables]
  );

  return (
    <div>
      <h1>Variables</h1>
      <div>
        <Typography.Text type="secondary" className="App-text">
          Terraform uses all{" "}
          <a href="https://developer.hashicorp.com/terraform/language/values/variables" target="_blank" rel="noopener noreferrer">
            Terraform variables
          </a>{" "}
          and{" "}
          <a href="https://developer.hashicorp.com/terraform/cli/config/environment-variables" target="_blank" rel="noopener noreferrer">
            Environment variables
          </a>{" "}
          for all plans and applies in this workspace. Workspaces using Terraform 0.10.0 or later can also load default
          values from any <code>*.auto.tfvars</code> files in the configuration. You may want to use the HCP Terraform
          Provider or the variables API to add multiple variables at once.
        </Typography.Text>
      </div>

      <h2 style={{ marginTop: 32 }}>Sensitive variables</h2>
      <div>
        <Typography.Text type="secondary" className="App-text">
          Sensitive variables are never shown in the UI or API, and can't be edited. They may appear in Terraform logs
          if your configuration is designed to output them. To change a sensitive variable, delete and replace it.
        </Typography.Text>
      </div>

      <h2 style={{ marginTop: 32 }}>Workspace variables ({workspaceVariables.length})</h2>
      <div style={{ marginBottom: 16 }}>
        <Typography.Text type="secondary" className="App-text">
          Variables defined within a workspace always overwrite variables from variable sets that have the same type and
          the same key. Learn more about variable set{" "}
          <a href="https://developer.hashicorp.com/terraform/cloud-docs/workspaces/variables/managing-variables#variable-precedence" target="_blank" rel="noopener noreferrer">
            precedence
          </a>
          .
        </Typography.Text>
      </div>

      <Table
        dataSource={workspaceVariables}
        columns={VARIABLES_COLUMNS(organizationId!, workspaceId!, onEdit, manageWorkspace)}
        rowKey="key"
        pagination={false}
        locale={{ emptyText: "There are no variables added." }}
      />
      <Button
        type="default"
        htmlType="button"
        style={{ marginTop: 16 }}
        onClick={() => {
          setMode("create");
          form.resetFields();
          setCategory("TERRAFORM");
          setVisible(true);
        }}
        disabled={!manageWorkspace}
        icon={<PlusOutlined />}
      >
        Add variable
      </Button>

      <div style={{ marginTop: 48 }}>
        <h2>Variable sets ({variableSets.length})</h2>
        <div style={{ marginBottom: 16 }}>
          <Typography.Text type="secondary" className="App-text">
            <a href="https://developer.hashicorp.com/terraform/cloud-docs/workspaces/variables/managing-variables#variable-sets" target="_blank" rel="noopener noreferrer">
              Variable sets
            </a>{" "}
            allow you to reuse variables across multiple workspaces within your organization. We recommend creating a
            variable set for variables used in more than one workspace.
          </Typography.Text>
        </div>

        {variableSets.length === 0 ? (
          <div
            style={{
              background: "#fafafa",
              borderRadius: 8,
              padding: "40px 24px",
              textAlign: "center",
            }}
          >
            <Typography.Text strong>No variable sets have been applied to this workspace.</Typography.Text>
            <br />
            <Button type="primary" style={{ marginTop: 16 }} href={`/organizations/${organizationId}/settings/variableSets`}>
              Apply variable set
            </Button>
          </div>
        ) : (
          <Table
            dataSource={variableSets}
            columns={VARIABLE_SET_COLUMNS()}
            rowKey={(record) => `${record.collectionName || "global"}-${record.key}`}
            pagination={false}
          />
        )}
      </div>

      <Modal
        width="600px"
        open={visible}
        title={mode === "edit" ? "Edit variable " + variableName : "Add variable"}
        okText={mode === "edit" ? "Save variable" : "Add variable"}
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
        <Form name="create-var" form={form} layout="vertical" validateMessages={validateMessages}>
          <div style={{ fontWeight: 600, fontSize: 14, marginBottom: 12 }}>Select variable category</div>

          <Form.Item name="category" style={{ marginBottom: 16 }}>
            <Radio.Group value={category} onChange={(e) => setCategory(e.target.value)}>
              <div style={{ display: "flex", flexDirection: "column", gap: "12px" }}>
                <Radio value="TERRAFORM" style={{ display: "flex", alignItems: "flex-start" }}>
                  <div>
                    <div style={{ fontWeight: 500 }}>Terraform variable</div>
                    <div style={{ color: "rgba(0,0,0,0.45)", fontSize: 13 }}>
                      These variables should match the declarations in your configuration. Click the HCL box to use
                      interpolation or set a non-string value.
                    </div>
                  </div>
                </Radio>

                <Radio value="ENV" style={{ display: "flex", alignItems: "flex-start" }}>
                  <div>
                    <div style={{ fontWeight: 500 }}>Environment variable</div>
                    <div style={{ color: "rgba(0,0,0,0.45)", fontSize: 13 }}>
                      These variables are available in the Terraform runtime environment.
                    </div>
                  </div>
                </Radio>
              </div>
            </Radio.Group>
          </Form.Item>

          <Form.Item name="key" label="Key" rules={[{ required: true }]} style={{ marginBottom: 8 }}>
            <Input placeholder="key" />
          </Form.Item>

          <Form.Item name="value" label="Value" rules={[{ required: true }]} style={{ marginBottom: 8 }}>
            <Input.TextArea placeholder="value" rows={1} autoSize={{ minRows: 1, maxRows: 6 }} />
          </Form.Item>

          <div style={{ display: "flex", gap: "24px", marginBottom: 16 }}>
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

          <Form.Item
            name="description"
            label={<span>Description <span style={{ fontWeight: 400, color: "rgba(0,0,0,0.45)" }}>(Optional)</span></span>}
          >
            <Input.TextArea placeholder="description (optional)" style={{ width: "100%" }} rows={3} />
          </Form.Item>
        </Form>
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
