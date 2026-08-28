import { DeleteOutlined, EditOutlined, PlusOutlined } from "@ant-design/icons";
import {
  Alert,
  Button,
  Collapse,
  Form,
  message,
  Popconfirm,
  Space,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
} from "antd";
import { useState } from "react";
import { ORGANIZATION_ARCHIVE, WORKSPACE_ARCHIVE } from "../../config/actionTypes";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import { CreateVariableForm, FlatVariable, VariableCategory } from "../types";
import SettingsSection from "@/components/SettingsSection/SettingsSection";
import { CrudFormModal } from "@/components/CrudFormModal";
import { VariableFormFields } from "@/components/VariableFormFields";

const VARIABLES_COLUMS = (
  onEdit: (variable: FlatVariable) => void,
  onDelete: (variableId: string) => void,
  manageWorkspace: boolean
) => [
  {
    title: "Key",
    dataIndex: "key",
    width: "35%",
    key: "key",
    sorter: (a: FlatVariable, b: FlatVariable) => a.key.localeCompare(b.key),
    defaultSortOrder: "ascend" as const,
    render: (_: string, record: FlatVariable) => {
      return (
        <Space>
          {record.key}
          {record.hcl && <Tag color="blue">HCL</Tag>}
          {record.sensitive && <Tag color="orange">Sensitive</Tag>}
          {record.incomplete && <Tag color="red">Incomplete</Tag>}
        </Space>
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
          styles={{ root: { width: 400, wordBreak: "break-word" } }}
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
            okButtonProps={{ danger: true }}
            onConfirm={() => {
              onDelete(record.id);
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
          {record.key} &nbsp;&nbsp;&nbsp;&nbsp; {record.hcl && <Tag color="blue">HCL</Tag>}{" "}
          {record.sensitive && <Tag color="orange">Sensitive</Tag>}
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
          styles={{ root: { width: 400, wordBreak: "break-word" } }}
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
    sorter: (a: any, b: any) => (a.category ?? "").localeCompare(b.category ?? ""),
    render: (_: string, record: any) => {
      return record.category === "TERRAFORM" ? "terraform" : record.category === "ENV" ? "env" : "unset";
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
          {record.key} &nbsp;&nbsp;&nbsp;&nbsp; {record.hcl && <Tag color="blue">HCL</Tag>}{" "}
          {record.sensitive && <Tag color="orange">Sensitive</Tag>}
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
          styles={{ root: { width: 400, wordBreak: "break-word" } }}
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
    sorter: (a: FlatVariable, b: FlatVariable) => (a.category ?? "").localeCompare(b.category ?? ""),
    render: (_: string, record: FlatVariable) => {
      return record.category === "TERRAFORM" ? "terraform" : record.category === "ENV" ? "env" : "unset";
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
  reload: () => void;
};

export const Variables = ({
  vars,
  env,
  manageWorkspace,
  collectionVars,
  collectionEnvVars,
  globalVariables,
  globalEnvVariables,
  reload,
}: Props) => {
  const workspaceId = sessionStorage.getItem(WORKSPACE_ARCHIVE);
  const organizationId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);
  const [form] = Form.useForm<CreateVariableForm>();
  const [visible, setVisible] = useState(false);
  const [variableName, setVariableName] = useState("");
  const [category, setCategory] = useState<VariableCategory | null>("TERRAFORM");
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
      category: variable.category ?? undefined,
    });
    setVisible(true);
    setCategory(variable.category);
  };

  const onCreate = (values: CreateVariableForm) => {
    const body = {
      data: {
        type: "variable",
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
      .post(`organization/${organizationId}/workspace/${workspaceId}/variable`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then(() => {
        message.success("Variable created successfully");
        setVisible(false);
        form.resetFields();
        reload();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      });
  };

  const onUpdate = (values: CreateVariableForm) => {
    const body = {
      data: {
        type: "variable",
        id: variableId,
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
      .patch(`organization/${organizationId}/workspace/${workspaceId}/variable/${variableId}`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then(() => {
        message.success("Variable updated successfully");
        setVisible(false);
        form.resetFields();
        reload();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      });
  };

  const onDelete = (deleteId: string) => {
    axiosInstance
      .delete(`organization/${organizationId}/workspace/${workspaceId}/variable/${deleteId}`, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then(() => {
        message.success("Variable deleted successfully");
        reload();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      });
  };

  // Combine Terraform and Environment variables
  const workspaceVariables = [...vars, ...env];
  const incompleteWorkspaceVariables = workspaceVariables.filter((variable) => {
    return variable.incomplete;
  });

  // Combine Collection Terraform and Environment variables
  const collectionVariables = [...collectionVars, ...collectionEnvVars];

  // Combine Global Terraform and Environment variables
  const globalVars = [...globalVariables, ...globalEnvVariables];

  return (
    <div>
      <Typography.Title level={3} style={{ margin: 0 }}>
        Variables
      </Typography.Title>
      <div>
        <Typography.Text type="secondary" className="App-text">
          <p>
            These variables are used for all plans and applies in this workspace. Workspaces using Terraform 0.10.0 or
            later can also load default values from any *.auto.tfvars files in the configuration.
          </p>
          <p>
            Sensitive variables are hidden from view in the UI and API. Saving a new value replaces the previous one.
            Sensitive variables can still appear in Terraform logs if your configuration is designed to output them.
          </p>
        </Typography.Text>
      </div>
      {incompleteWorkspaceVariables.length > 0 && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: "16px" }}
          message="Some sensitive variables are incomplete"
          description="Complete or delete the highlighted variables before starting a new run."
        />
      )}

      <SettingsSection
        title={`Workspace variables (${workspaceVariables.length})`}
        description="These Terraform variables are set using a terraform.tfvars file. To use interpolation or set a non-string value for a variable, click its HCL checkbox."
        maxWidth="100%"
      >
        <Collapse
          defaultActiveKey={["TERRAFORM", "ENV"]}
          style={{ marginBottom: 16 }}
          items={[
            {
              key: "TERRAFORM",
              label: `Terraform Variables (${vars.length})`,
              children: (
                <Table
                  dataSource={vars}
                  columns={VARIABLES_COLUMS(onEdit, onDelete, manageWorkspace)}
                  rowKey="key"
                  pagination={false}
                  locale={{ emptyText: "No terraform variables defined yet." }}
                />
              ),
            },
            {
              key: "ENV",
              label: `Environment Variables (${env.length})`,
              children: (
                <Table
                  dataSource={env}
                  columns={VARIABLES_COLUMS(onEdit, onDelete, manageWorkspace)}
                  rowKey="key"
                  pagination={false}
                  locale={{ emptyText: "No environment variables defined yet." }}
                />
              ),
            },
          ]}
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
      </SettingsSection>

      <SettingsSection
        title={`Collection Variables (${collectionVariables.length})`}
        description="The following values are taken from the collection used by this workspace, these values are injected inside the Terrakube remote jobs."
        maxWidth="100%"
      >
        <Table dataSource={collectionVariables} columns={COLLECTION_VARIABLES_COLUMNS()} rowKey="key" />
      </SettingsSection>

      <SettingsSection
        title={`Global Variables (${globalVars.length})`}
        description="The following values are taken from the organization global variables, these values are injected inside the Terrakube remote jobs."
        maxWidth="100%"
      >
        <Table dataSource={globalVars} columns={GLOBAL_VARIABLES_COLUMNS()} rowKey="key" />
      </SettingsSection>

      <CrudFormModal
        open={visible}
        title={mode === "edit" ? "Edit variable " + variableName : "Add variable"}
        okText="Save variable"
        form={form}
        formName="create-org"
        onCancel={onCancel}
        onSubmit={(values) => {
          if (mode === "create") onCreate(values);
          else onUpdate(values);
        }}
        validateMessages={validateMessages}
      >
        <Typography.Title level={5} style={{ margin: "0 0 15px 0" }}>
          Select variable category
        </Typography.Title>

        <VariableFormFields
          category={category ?? undefined}
          onCategoryChange={(value) => setCategory(value as VariableCategory)}
        />
      </CrudFormModal>
    </div>
  );
};
