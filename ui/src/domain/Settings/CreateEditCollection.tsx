import { Button, Col, Form, Input, Row, Select, Space, Spin, Table, Tag, Typography, message } from "antd";
import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import axiosInstance from "../../config/axiosConfig";
import SettingsSection from "@/components/settings/SettingsSection/SettingsSection";
import "./Settings.css";
import { DeleteOutlined, EditOutlined, PlusOutlined } from "@ant-design/icons";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { CollectionVariableModal, CollectionVariableFormValues } from "./components";

// Type definitions
type Collection = {
  id: string;
  attributes: {
    name: string;
    description: string;
    priority: number;
  };
};

type Workspace = {
  id: string;
  attributes: {
    name: string;
  };
};

type CreateEditCollectionProps = {
  mode: "create" | "edit";
  collectionId?: string;
  managePermission?: boolean;
};

export const CreateEditCollection = ({
  mode,
  collectionId: propCollectionId,
  managePermission = true,
}: CreateEditCollectionProps) => {
  const { orgid, collectionid: urlCollectionId } = useParams();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [saveLoading, setSaveLoading] = useState(false);
  const [variableLoading, setVariableLoading] = useState(false);
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [selectedWorkspaces, setSelectedWorkspaces] = useState<string[]>([]);
  const [variables, setVariables] = useState<any[]>([]);
  const [variableForm] = Form.useForm<CollectionVariableFormValues>();
  const [collectionForm] = Form.useForm();
  const [addingVariable, setAddingVariable] = useState(false);
  const [variableMode, setVariableMode] = useState<"create" | "edit">("create");
  const [editingVariableId, setEditingVariableId] = useState<string>("");

  // Use either the prop or URL parameter for collection ID
  const collectionid = propCollectionId || urlCollectionId;

  // Load collection data if in edit mode
  // Load collection data if in edit mode
  useEffect(() => {
    setLoading(true);

    if (mode === "edit" && collectionid) {
      // Parallel load: workspaces, collection data, collection items, and collection references
      Promise.all([
        axiosInstance.get(`organization/${orgid}/workspace`),
        axiosInstance.get(`organization/${orgid}/collection/${collectionid}`),
        axiosInstance.get(`organization/${orgid}/collection/${collectionid}/item`),
        axiosInstance.get(`organization/${orgid}/collection/${collectionid}/reference`),
      ]).then(([workspacesRes, collectionRes, itemsRes, refsRes]) => {
        setWorkspaces(workspacesRes.data.data);

        const collectionData = collectionRes.data.data;
        collectionForm.setFieldsValue({
          name: collectionData.attributes.name,
          description: collectionData.attributes.description,
          priority: collectionData.attributes.priority || 10,
        });

        setVariables(itemsRes.data.data);

        const workspaceIds = refsRes.data.data
          .filter((ref: any) => ref.relationships?.workspace?.data?.id != null)
          .map((ref: any) => ref.relationships.workspace.data.id);
        setSelectedWorkspaces(workspaceIds);

        setLoading(false);
      });
    } else {
      // For create mode, just load workspaces
      axiosInstance.get(`organization/${orgid}/workspace`).then((response) => {
        setWorkspaces(response.data.data);
        setVariables([]);
        setSelectedWorkspaces([]);
        setLoading(false);
      });
    }
  }, [orgid, collectionid, mode, collectionForm]);

  const handleSave = async () => {
    try {
      setSaveLoading(true);
      const values = await collectionForm.validateFields();

      // Match exact payload format shown in example - without global field
      const collectionData = {
        data: {
          type: "collection",
          attributes: {
            name: values.name,
            description: values.description || "",
            priority: values.priority || 10,
          },
        },
      };

      console.log("Collection data to send:", JSON.stringify(collectionData));

      if (mode === "create") {
        // Create collection - use the format from the example
        const response = await axiosInstance.post(`organization/${orgid}/collection`, collectionData, {
          headers: { "Content-Type": "application/vnd.api+json" },
        });

        const newCollectionId = response.data.data.id;

        // Add workspace references
        for (const workspaceId of selectedWorkspaces) {
          await axiosInstance.post(
            `organization/${orgid}/collection/${newCollectionId}/reference`,
            {
              data: {
                type: "reference",
                attributes: {
                  description: `Reference to workspace ${workspaceId}`,
                },
                relationships: {
                  workspace: {
                    data: {
                      type: "workspace",
                      id: workspaceId,
                    },
                  },
                },
              },
            },
            { headers: { "Content-Type": "application/vnd.api+json" } }
          );
        }

        message.success("Collection created successfully");
      } else if (mode === "edit" && collectionid) {
        // Update collection - match exact format without global field
        await axiosInstance.patch(
          `organization/${orgid}/collection/${collectionid}`,
          {
            data: {
              type: "collection",
              id: collectionid,
              attributes: {
                name: values.name,
                description: values.description || "",
                priority: values.priority || 10,
              },
            },
          },
          { headers: { "Content-Type": "application/vnd.api+json" } }
        );

        // Handle workspace references
        // First get current references
        const refsResponse = await axiosInstance.get(`organization/${orgid}/collection/${collectionid}/reference`);

        const existingRefs = refsResponse.data.data;
        const existingWorkspaceIds = existingRefs
          .filter((ref: any) => ref.relationships?.workspace?.data?.id != null)
          .map((ref: any) => ref.relationships.workspace.data.id);

        // Delete references that are not in the new selection or where the workspace is null because it was deleted
        for (const ref of existingRefs) {
          const workspaceId = ref.relationships?.workspace?.data?.id;
          if (workspaceId == null) {
            await axiosInstance.delete(`organization/${orgid}/collection/${collectionid}/reference/${ref.id}`);
          } else if (!selectedWorkspaces.includes(workspaceId)) {
            await axiosInstance.delete(`organization/${orgid}/collection/${collectionid}/reference/${ref.id}`);
          }
        }

        // Add new references
        for (const workspaceId of selectedWorkspaces) {
          if (!existingWorkspaceIds.includes(workspaceId)) {
            await axiosInstance.post(
              `organization/${orgid}/collection/${collectionid}/reference`,
              {
                data: {
                  type: "reference",
                  attributes: {
                    description: `Reference to workspace ${workspaceId}`,
                  },
                  relationships: {
                    workspace: {
                      data: {
                        type: "workspace",
                        id: workspaceId,
                      },
                    },
                  },
                },
              },
              { headers: { "Content-Type": "application/vnd.api+json" } }
            );
          }
        }

        message.success("Collection updated successfully");
      }

      // Navigate back to collection list
      navigate(`/organizations/${orgid}/settings/collection`);
    } catch (error) {
      console.error("Failed to save collection:", error);
      message.error("Failed to save collection");
    } finally {
      setSaveLoading(false);
    }
  };

  const closeVariableModal = () => {
    setAddingVariable(false);
    setVariableMode("create");
    setEditingVariableId("");
    variableForm.resetFields();
  };

  const handleUpdateVariable = async (values: CollectionVariableFormValues) => {
    try {
      setVariableLoading(true);

      // Update local state for temp variables
      if (editingVariableId.startsWith("temp-")) {
        setVariables(
          variables.map((v) =>
            v.id === editingVariableId
              ? {
                  ...v,
                  attributes: {
                    key: values.key?.trim(),
                    value: typeof values.value === "string" ? values.value.trim() : values.value,
                    sensitive: values.sensitive,
                    description: values.description?.trim(),
                    hcl: values.hcl,
                    category: values.category,
                  },
                }
              : v
          )
        );
        message.success("Variable updated");
      } else if (mode === "edit" && collectionid) {
        // Update variable in collection via API
        try {
          await axiosInstance.patch(
            `organization/${orgid}/collection/${collectionid}/item/${editingVariableId}`,
            {
              data: {
                type: "item",
                id: editingVariableId,
                attributes: {
                  key: values.key?.trim(),
                  value: typeof values.value === "string" ? values.value.trim() : values.value,
                  sensitive: values.sensitive,
                  description: values.description?.trim(),
                  hcl: values.hcl,
                  category: values.category,
                },
              },
            },
            { headers: { "Content-Type": "application/vnd.api+json" } }
          );

          // Refresh variables
          const response = await axiosInstance.get(`organization/${orgid}/collection/${collectionid}/item`);
          setVariables(response.data.data);
          message.success("Variable updated successfully");
        } catch (error) {
          console.error("Failed to update variable:", error);
          message.error("Failed to update variable");
        }
      }

      closeVariableModal();
    } catch (error) {
      console.error("Failed to update variable:", error);
      message.error("Failed to update variable");
    } finally {
      setVariableLoading(false);
    }
  };

  const handleAddVariable = async (values: CollectionVariableFormValues) => {
    try {
      setVariableLoading(true);

      // Add variable to local state
      const newVariable = {
        id: `temp-${Date.now()}`,
        attributes: {
          key: values.key?.trim(),
          value: typeof values.value === "string" ? values.value.trim() : values.value,
          category: values.category,
          description: values.description?.trim(),
          hcl: values.hcl,
          sensitive: values.sensitive,
        },
      };

      setVariables([...variables, newVariable]);

      // Add to collection if in edit mode and id exists
      if (mode === "edit" && collectionid) {
        try {
          await axiosInstance.post(
            `organization/${orgid}/collection/${collectionid}/item`,
            {
              data: {
                type: "item",
                attributes: {
                  key: values.key?.trim(),
                  value: typeof values.value === "string" ? values.value.trim() : values.value,
                  sensitive: values.sensitive,
                  description: values.description?.trim(),
                  hcl: values.hcl,
                  category: values.category,
                },
              },
            },
            { headers: { "Content-Type": "application/vnd.api+json" } }
          );

          // Refresh variables
          const response = await axiosInstance.get(`organization/${orgid}/collection/${collectionid}/item`);
          setVariables(response.data.data);
          message.success("Variable added successfully");
        } catch (error) {
          console.error("Failed to add variable:", error);
          message.error("Failed to add variable");
        }
      } else {
        message.success("Variable added to collection");
      }

      closeVariableModal();
    } catch (error) {
      console.error("Failed to add variable:", error);
      message.error("Failed to add variable");
    } finally {
      setVariableLoading(false);
    }
  };

  const handleEditVariable = (record: any) => {
    setVariableMode("edit");
    setEditingVariableId(record.id);
    setAddingVariable(true);
    variableForm.setFieldsValue({
      key: record.attributes.key?.trim(),
      value: typeof record.attributes.value === "string" ? record.attributes.value.trim() : record.attributes.value,
      category: record.attributes.category,
      description: record.attributes.description?.trim(),
      hcl: record.attributes.hcl,
      sensitive: record.attributes.sensitive,
    });
  };

  const handleRemoveVariable = async (variableId: string) => {
    try {
      setLoading(true);
      // Remove from local state if it's a temp variable
      if (variableId.startsWith("temp-")) {
        setVariables(variables.filter((v) => v.id !== variableId));
        message.success("Variable removed");
        return;
      }

      // Delete from collection if in edit mode
      if (mode === "edit" && collectionid) {
        try {
          await axiosInstance.delete(`organization/${orgid}/collection/${collectionid}/item/${variableId}`);

          // Refresh variables
          const response = await axiosInstance.get(`organization/${orgid}/collection/${collectionid}/item`);
          setVariables(response.data.data);
          message.success("Variable removed successfully");
        } catch (error) {
          console.error("Failed to remove variable:", error);
          message.error("Failed to remove variable");
        }
      } else {
        setVariables(variables.filter((v) => v.id !== variableId));
        message.success("Variable removed");
      }
    } finally {
      setLoading(false);
    }
  };

  const variableColumns = [
    {
      title: "Key",
      dataIndex: "key",
      key: "key",
      render: (_: any, record: any) => (
        <div>
          {record.attributes.key}
          <span style={{ marginLeft: "10px" }}>
            <Tag color="blue">{record.attributes.category === "ENV" ? "Environment" : "Terraform"}</Tag>
            {record.attributes.hcl && <Tag color="green">HCL</Tag>}
            {record.attributes.sensitive && <Tag color="red">Sensitive</Tag>}
          </span>
        </div>
      ),
    },
    {
      title: "Value",
      dataIndex: "value",
      key: "value",
      render: (_: any, record: any) =>
        record.attributes.sensitive ? <i>Sensitive - write only</i> : record.attributes.value,
    },
    {
      title: "Category",
      dataIndex: "category",
      key: "category",
      render: (_: any, record: any) => (record.attributes.category === "ENV" ? "Environment" : "Terraform"),
    },
    {
      title: "Actions",
      key: "actions",
      render: (_: any, record: any) => (
        <Space>
          <Button icon={<EditOutlined />} type="link" onClick={() => handleEditVariable(record)}>
            Edit
          </Button>
          <Button icon={<DeleteOutlined />} type="link" danger onClick={() => handleRemoveVariable(record.id)}>
            Delete
          </Button>
        </Space>
      ),
    },
  ];

  const variableListing = (
    <div>
      <div style={{ marginBottom: "15px" }}>
        <Typography.Text>
          You can add any number of variables. Terrakube will use these variables for jobs in the specified workspaces.
        </Typography.Text>
      </div>

      <div style={{ marginBottom: "30px" }}>
        <Table
          dataSource={variables}
          columns={variableColumns}
          rowKey="id"
          pagination={false}
          locale={{ emptyText: "There are no variables added." }}
          style={{ marginBottom: "20px" }}
          bordered
        />

        <Button
          icon={<PlusOutlined />}
          onClick={() => {
            setVariableMode("create");
            setEditingVariableId("");
            variableForm.resetFields();
            setAddingVariable(true);
          }}
          style={{ marginBottom: "20px" }}
        >
          Add variable
        </Button>

        <CollectionVariableModal
          open={addingVariable}
          mode={variableMode}
          form={variableForm}
          confirmLoading={variableLoading}
          onCancel={closeVariableModal}
          onSubmit={variableMode === "edit" ? handleUpdateVariable : handleAddVariable}
        />
      </div>
    </div>
  );

  return (
    <div className="setting">
      <Spin spinning={loading}>
        <SettingsPageHeader
          title={
            mode === "create"
              ? "Create a new organization variable collection"
              : "Edit organization variable collection"
          }
          description="Variable collections allow you to define and apply variables one time across multiple workspaces within an organization."
        />

        <Form
          form={collectionForm}
          layout="vertical"
          initialValues={{
            name: "",
            description: "",
            priority: 10,
            scope: "specific",
          }}
        >
          <SettingsSection title="Configure settings" maxWidth={960}>
            <Row gutter={16}>
              <Col xs={24} md={16}>
                <Form.Item
                  name="name"
                  label="Name"
                  rules={[{ required: true, message: "Please enter a name for the collection" }]}
                >
                  <Input placeholder="Collection name" />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item
                  name="priority"
                  label="Priority"
                  rules={[{ required: true, message: "Please enter a priority" }]}
                  tooltip="Higher number means higher priority. When variables with the same name exist in multiple collections, the one with higher priority will be used."
                >
                  <Input type="number" min={1} max={100} defaultValue={10} />
                </Form.Item>
              </Col>
            </Row>

            <Form.Item name="description" label="Description (Optional)">
              <Input.TextArea rows={3} placeholder="Describe the purpose of this collection" />
            </Form.Item>
          </SettingsSection>

          <SettingsSection title="Variable collection scope" maxWidth={960}>
            <div style={{ marginBottom: "10px" }}>
              <Typography.Text strong>Apply to workspaces</Typography.Text>
            </div>
            <div style={{ color: "rgba(0,0,0,0.45)", fontSize: "14px", marginBottom: "10px" }}>
              Only the selected workspaces will access this variable collection.
            </div>
            <Select
              mode="multiple"
              style={{ width: "100%" }}
              placeholder="Select workspaces"
              value={selectedWorkspaces}
              onChange={setSelectedWorkspaces}
              optionFilterProp="children"
            >
              {workspaces.map((workspace) => (
                <Select.Option key={workspace.id} value={workspace.id}>
                  {workspace.attributes.name}
                </Select.Option>
              ))}
            </Select>
          </SettingsSection>

          <SettingsSection title="Variables" maxWidth="100%">
            {mode === "create" ? (
              <div style={{ marginBottom: "15px" }}>
                <Typography.Text>Create the collection first. Then you can add variables to it.</Typography.Text>
              </div>
            ) : (
              variableListing
            )}
          </SettingsSection>

          <div style={{ display: "flex", justifyContent: "flex-end", marginTop: "30px" }}>
            <Space>
              <Button>
                <Link to={`/organizations/${orgid}/settings/collection`}>Cancel</Link>
              </Button>
              <Button type="primary" onClick={handleSave} loading={saveLoading} disabled={!managePermission}>
                {mode === "create" ? "Create variable collection" : "Save Variable Collection"}
              </Button>
            </Space>
          </div>
        </Form>
      </Spin>
    </div>
  );
};
