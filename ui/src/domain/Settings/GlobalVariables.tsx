import { DeleteOutlined, EditOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, Collapse, Form, message, Space, Table, Tag } from "antd";
import { Loading } from "@/components/feedback/Loading";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage, isPermissionError } from "../../config/axiosConfig";
import { CreateVariableForm, UpdateVariableForm, Variable } from "../types";
import "./Settings.css";
import { AccessDeniedAlert } from "@/components/feedback/AccessDeniedAlert";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import GlobalVariableFormModal from "./components/GlobalVariableFormModal";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";

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
  const [pendingDelete, setPendingDelete] = useState<Variable | null>(null);
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
            <Button
              danger
              type="link"
              icon={<DeleteOutlined />}
              disabled={!managePermission}
              onClick={() => setPendingDelete(record)}
            >
              Delete
            </Button>
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
        <AccessDeniedAlert description={error} />
      ) : (
        <>
          <SettingsPageHeader
            docUrl="https://docs.terrakube.io/user-guide/organizations/global-variables"
            title="Global Variables"
            description="Global Variables allow you to define and apply variables one time across multiple workspaces within an organization."
            actions={
              <Button
                type="primary"
                onClick={onNew}
                htmlType="button"
                icon={<PlusOutlined />}
                disabled={!managePermission}
              >
                Create global variable
              </Button>
            }
          />
          <Loading loading={loading} description="Loading Global Variables...">
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
          </Loading>

          <GlobalVariableFormModal
            open={visible}
            mode={mode === "create" ? "create" : "edit"}
            variableKey={variableKey}
            form={form}
            onCancel={onCancel}
            onSubmit={(values) => {
              if (mode === "create") onCreate(values);
              else onUpdate(values);
            }}
          />

          <DeleteConfirmationModal
            open={pendingDelete !== null}
            title="Delete global variable"
            message={
              <>
                Deleting the global variable <strong>{pendingDelete?.attributes.key}</strong> cannot be undone. It will
                no longer be used in future runs.
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
