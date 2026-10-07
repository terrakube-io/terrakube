import { Button, Form, Input, InputNumber, Select, Space, Spin, Table, Tag, Typography, message } from "antd";
import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import SettingsSection from "@/components/settings/SettingsSection/SettingsSection";
import { SettingsForm } from "@/components/settings/SettingsForm";
import { IdField } from "@/components/settings/IdField";
import { EmptyState } from "@/components/feedback/EmptyState";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";
import "./Settings.css";
import "./VariableCollections.css";
import { DeleteOutlined, EditOutlined, PlusOutlined } from "@ant-design/icons";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { CollectionVariableModal, CollectionVariableFormValues } from "./components";
import { DangerZone } from "@/components/settings/DangerZone";
import { deleteCollection } from "./deleteCollection";

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
  const [pendingDelete, setPendingDelete] = useState<any>(null);
  const [collectionName, setCollectionName] = useState("");

  // Use either the prop or URL parameter for collection ID
  const collectionid = propCollectionId || urlCollectionId;

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
      ])
        .then(([workspacesRes, collectionRes, itemsRes, refsRes]) => {
          setWorkspaces(workspacesRes.data.data);

          const collectionData = collectionRes.data.data;
          setCollectionName(collectionData.attributes.name);
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
        })
        .catch((error) => {
          message.error(`Could not load the variable collection: ${getErrorMessage(error)}`);
          setLoading(false);
        });
    } else {
      // For create mode, just load workspaces
      axiosInstance
        .get(`organization/${orgid}/workspace`)
        .then((response) => {
          setWorkspaces(response.data.data);
          setVariables([]);
          setSelectedWorkspaces([]);
          setLoading(false);
        })
        .catch((error) => {
          message.error(`Could not load workspaces: ${getErrorMessage(error)}`);
          setLoading(false);
        });
    }
  }, [orgid, collectionid, mode, collectionForm]);

  const handleSave = async (values: { name: string; description?: string; priority?: number }) => {
    try {
      setSaveLoading(true);

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

        message.success("Variable collection created");
        // Variables can only be added to a saved collection.
        navigate(`/organizations/${orgid}/settings/collection/edit/${newCollectionId}`);
        return;
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

        message.success("Variable collection updated");
      }

      // Navigate back to collection list
      navigate(`/organizations/${orgid}/settings/collection`);
    } catch (error) {
      message.error(`Could not save the variable collection: ${getErrorMessage(error)}`);
    } finally {
      setSaveLoading(false);
    }
  };

  const handleDeleteCollection = async () => {
    try {
      setLoading(true);
      await deleteCollection(orgid, collectionid!);
      message.success("Variable collection deleted");
      navigate(`/organizations/${orgid}/settings/collection`);
    } catch (error) {
      message.error(`Could not delete the variable collection: ${getErrorMessage(error)}`);
      setLoading(false);
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
          message.success("Variable updated");
        } catch (error) {
          message.error(`Could not update the variable: ${getErrorMessage(error)}`);
        }
      }

      closeVariableModal();
    } catch (error) {
      message.error(`Could not update the variable: ${getErrorMessage(error)}`);
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
          message.success("Variable added");
        } catch (error) {
          message.error(`Could not add the variable: ${getErrorMessage(error)}`);
        }
      } else {
        message.success("Variable added to collection");
      }

      closeVariableModal();
    } catch (error) {
      message.error(`Could not add the variable: ${getErrorMessage(error)}`);
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
        message.success("Variable deleted");
        return;
      }

      // Delete from collection if in edit mode
      if (mode === "edit" && collectionid) {
        try {
          await axiosInstance.delete(`organization/${orgid}/collection/${collectionid}/item/${variableId}`);

          // Refresh variables
          const response = await axiosInstance.get(`organization/${orgid}/collection/${collectionid}/item`);
          setVariables(response.data.data);
          message.success("Variable deleted");
        } catch (error) {
          message.error(`Could not delete the variable: ${getErrorMessage(error)}`);
        }
      } else {
        setVariables(variables.filter((v) => v.id !== variableId));
        message.success("Variable deleted");
      }
    } finally {
      setLoading(false);
    }
  };

  const variableColumns = [
    {
      title: "Key",
      key: "key",
      render: (_: any, record: any) => (
        <>
          <span className="collection-mono">{record.attributes.key}</span>
          <span className="collection-variable-meta">
            {record.attributes.category === "ENV" ? "Environment" : "Terraform"}
            {record.attributes.hcl && <Tag>HCL</Tag>}
            {record.attributes.sensitive && <Tag>Sensitive</Tag>}
          </span>
        </>
      ),
    },
    {
      title: "Value",
      key: "value",
      render: (_: any, record: any) =>
        record.attributes.sensitive ? (
          <Typography.Text type="secondary" italic>
            Sensitive, write only
          </Typography.Text>
        ) : (
          <span className="collection-mono">{record.attributes.value}</span>
        ),
    },
    {
      title: "Actions",
      key: "actions",
      width: 96,
      render: (_: any, record: any) => (
        <Space size="small">
          <Button
            type="text"
            icon={<EditOutlined />}
            aria-label={`Edit variable ${record.attributes.key}`}
            onClick={() => handleEditVariable(record)}
            disabled={!managePermission}
          />
          <Button
            type="text"
            danger
            icon={<DeleteOutlined />}
            aria-label={`Delete variable ${record.attributes.key}`}
            onClick={() => setPendingDelete(record)}
            disabled={!managePermission}
          />
        </Space>
      ),
    },
  ];

  const openNewVariable = () => {
    setVariableMode("create");
    setEditingVariableId("");
    variableForm.resetFields();
    setAddingVariable(true);
  };

  const addVariableButton = (
    <Button icon={<PlusOutlined />} onClick={openNewVariable} disabled={!managePermission}>
      Add variable
    </Button>
  );

  return (
    <div className="setting">
      <Spin spinning={loading}>
        <SettingsPageHeader
          title={mode === "create" ? "Create a variable collection" : "Edit variable collection"}
          divider={false}
        />

        <SettingsForm
          form={collectionForm}
          name="collection"
          onFinish={handleSave}
          initialValues={{ name: "", description: "", priority: 10 }}
          saveLabel={mode === "create" ? "Create variable collection" : "Update variable collection"}
          saveDisabled={!managePermission}
          saving={saveLoading}
        >
          {mode === "edit" && collectionid && <IdField id="collection-id" value={collectionid} />}
          <Form.Item name="name" label="Name" rules={[{ required: true, message: "Enter a name for the collection" }]}>
            <Input />
          </Form.Item>
          <Form.Item name="description" label="Description">
            <Input.TextArea autoSize={{ minRows: 2, maxRows: 4 }} />
          </Form.Item>
          <Form.Item
            name="priority"
            label="Priority"
            rules={[{ required: true, message: "Enter a priority from 1 to 100" }]}
            extra="When several collections set the same variable, the one with the higher priority wins."
          >
            <InputNumber min={1} max={100} precision={0} />
          </Form.Item>
          <Form.Item
            label="Workspaces"
            htmlFor="collection-workspaces"
            extra="Only these workspaces receive the variables in this collection."
          >
            <Select
              id="collection-workspaces"
              mode="multiple"
              placeholder="Select workspaces"
              value={selectedWorkspaces}
              onChange={setSelectedWorkspaces}
              optionFilterProp="label"
              options={workspaces.map((workspace) => ({ value: workspace.id, label: workspace.attributes.name }))}
            />
          </Form.Item>
        </SettingsForm>

        {mode === "create" ? (
          <section className="collection-variables">
            <Typography.Title level={4} className="collections-heading">
              Variables
            </Typography.Title>
            <Typography.Text type="secondary">You can add variables once the collection is created.</Typography.Text>
          </section>
        ) : (
          <section className="collection-variables">
            <SettingsSection
              maxWidth={680}
              title={`Variables (${variables.length})`}
              description="Jobs in the selected workspaces receive these variables. Adding, editing or deleting a variable applies immediately."
              extra={variables.length > 0 && addVariableButton}
            >
              {variables.length === 0 ? (
                <EmptyState simple description="This collection has no variables yet.">
                  {addVariableButton}
                </EmptyState>
              ) : (
                <Table
                  dataSource={variables}
                  columns={variableColumns}
                  rowKey="id"
                  pagination={false}
                  scroll={{ x: 520 }}
                />
              )}
            </SettingsSection>
          </section>
        )}

        {mode === "edit" && collectionName && (
          <DangerZone
            actionName="Delete this variable collection"
            description="The collection, its variables and its workspace references are deleted. Its workspaces stop receiving these variables. This cannot be undone."
            disabled={!managePermission}
            confirmValue={collectionName}
            confirmMessage={
              <>
                The collection <strong>{collectionName}</strong>, its variables and its workspace references will be
                deleted, and its workspaces stop receiving these variables. This cannot be undone.
              </>
            }
            onConfirm={handleDeleteCollection}
          />
        )}
      </Spin>

      <CollectionVariableModal
        open={addingVariable}
        mode={variableMode}
        form={variableForm}
        confirmLoading={variableLoading}
        onCancel={closeVariableModal}
        onSubmit={variableMode === "edit" ? handleUpdateVariable : handleAddVariable}
      />

      <DeleteConfirmationModal
        open={pendingDelete !== null}
        title="Delete variable"
        message={
          <>
            Workspaces using this collection will no longer receive{" "}
            <span className="collection-mono">{pendingDelete?.attributes.key}</span>. This cannot be undone.
          </>
        }
        okText="Delete variable"
        onConfirm={() => {
          if (pendingDelete) handleRemoveVariable(pendingDelete.id);
          setPendingDelete(null);
        }}
        onCancel={() => setPendingDelete(null)}
      />
    </div>
  );
};
