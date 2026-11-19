import { Button, Form, Input, InputNumber, Select, Space, Spin, Table, Tag, Typography, message } from "antd";
import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import SettingsSection from "@/components/settings/SettingsSection/SettingsSection";
import { SettingsForm } from "@/components/settings/SettingsForm";
import { RadioChoices } from "@/components/settings/RadioChoices";
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

type NamedResource = {
  id: string;
  attributes: {
    name: string;
  };
};

type CollectionReference = {
  id: string;
  relationships?: {
    workspace?: { data?: { id: string } | null };
    project?: { data?: { id: string } | null };
  };
};

type CollectionFormValues = { name: string; description?: string; priority?: number; global?: boolean };

const JSON_API_HEADERS = { headers: { "Content-Type": "application/vnd.api+json" } };

const referenceTarget = (ref: CollectionReference) => ({
  workspaceId: ref.relationships?.workspace?.data?.id,
  projectId: ref.relationships?.project?.data?.id,
});

// Makes the references of a collection match the selected workspaces and projects. References whose
// workspace or project no longer exists have no target left and are removed as well.
const syncReferences = async (
  base: string,
  existingRefs: CollectionReference[],
  workspaceIds: string[],
  projectIds: string[]
) => {
  const linkedWorkspaces = new Set<string>();
  const linkedProjects = new Set<string>();
  for (const ref of existingRefs) {
    const { workspaceId, projectId } = referenceTarget(ref);
    if (workspaceId != null && workspaceIds.includes(workspaceId)) {
      linkedWorkspaces.add(workspaceId);
    } else if (projectId != null && projectIds.includes(projectId)) {
      linkedProjects.add(projectId);
    } else {
      await axiosInstance.delete(`${base}/reference/${ref.id}`);
    }
  }

  const targets = [
    ...workspaceIds.filter((id) => !linkedWorkspaces.has(id)).map((id) => ({ type: "workspace", id })),
    ...projectIds.filter((id) => !linkedProjects.has(id)).map((id) => ({ type: "project", id })),
  ];
  for (const { type, id } of targets) {
    await axiosInstance.post(
      `${base}/reference`,
      {
        data: {
          type: "reference",
          attributes: { description: `Reference to ${type} ${id}` },
          relationships: { [type]: { data: { type, id } } },
        },
      },
      JSON_API_HEADERS
    );
  }
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
  const [workspaces, setWorkspaces] = useState<NamedResource[]>([]);
  const [selectedWorkspaces, setSelectedWorkspaces] = useState<string[]>([]);
  const [projects, setProjects] = useState<NamedResource[]>([]);
  const [selectedProjects, setSelectedProjects] = useState<string[]>([]);
  const [variables, setVariables] = useState<any[]>([]);
  const [variableForm] = Form.useForm<CollectionVariableFormValues>();
  const [collectionForm] = Form.useForm<CollectionFormValues>();
  const isGlobal = Form.useWatch("global", collectionForm);
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
    // Projects are only listed to teams that can see them, so a failure leaves the picker empty.
    const loadProjects = axiosInstance
      .get(`organization/${orgid}/project`)
      .then((response) => setProjects(response.data.data || []))
      .catch(() => setProjects([]));

    if (mode === "edit" && collectionid) {
      // Parallel load: workspaces, projects, collection data, collection items, and collection references
      Promise.all([
        axiosInstance.get(`organization/${orgid}/workspace`),
        axiosInstance.get(`organization/${orgid}/collection/${collectionid}`),
        axiosInstance.get(`organization/${orgid}/collection/${collectionid}/item`),
        axiosInstance.get(`organization/${orgid}/collection/${collectionid}/reference`),
        loadProjects,
      ])
        .then(([workspacesRes, collectionRes, itemsRes, refsRes]) => {
          setWorkspaces(workspacesRes.data.data);

          const collectionData = collectionRes.data.data;
          setCollectionName(collectionData.attributes.name);
          collectionForm.setFieldsValue({
            name: collectionData.attributes.name,
            description: collectionData.attributes.description,
            priority: collectionData.attributes.priority || 10,
            global: collectionData.attributes.global || false,
          });

          setVariables(itemsRes.data.data);

          const targets = (refsRes.data.data as CollectionReference[]).map(referenceTarget);
          setSelectedWorkspaces(targets.flatMap(({ workspaceId }) => (workspaceId != null ? [workspaceId] : [])));
          setSelectedProjects(targets.flatMap(({ projectId }) => (projectId != null ? [projectId] : [])));

          setLoading(false);
        })
        .catch((error) => {
          message.error(`Could not load the variable collection: ${getErrorMessage(error)}`);
          setLoading(false);
        });
    } else {
      // For create mode, just load workspaces and projects
      Promise.all([axiosInstance.get(`organization/${orgid}/workspace`), loadProjects])
        .then(([response]) => {
          setWorkspaces(response.data.data);
          setVariables([]);
          setSelectedWorkspaces([]);
          setSelectedProjects([]);
          setLoading(false);
        })
        .catch((error) => {
          message.error(`Could not load workspaces: ${getErrorMessage(error)}`);
          setLoading(false);
        });
    }
  }, [orgid, collectionid, mode, collectionForm]);

  const handleSave = async (values: CollectionFormValues) => {
    try {
      setSaveLoading(true);

      const attributes = {
        name: values.name,
        description: values.description || "",
        priority: values.priority || 10,
        global: values.global || false,
      };

      if (mode === "create") {
        const response = await axiosInstance.post(
          `organization/${orgid}/collection`,
          { data: { type: "collection", attributes } },
          JSON_API_HEADERS
        );

        const newCollectionId = response.data.data.id;
        // A global collection applies to every workspace, so it needs no references.
        if (!attributes.global) {
          await syncReferences(
            `organization/${orgid}/collection/${newCollectionId}`,
            [],
            selectedWorkspaces,
            selectedProjects
          );
        }

        message.success("Variable collection created");
        // Variables can only be added to a saved collection.
        navigate(`/organizations/${orgid}/settings/collection/edit/${newCollectionId}`);
        return;
      } else if (mode === "edit" && collectionid) {
        const base = `organization/${orgid}/collection/${collectionid}`;
        await axiosInstance.patch(
          base,
          { data: { type: "collection", id: collectionid, attributes } },
          JSON_API_HEADERS
        );

        // A global collection applies to every workspace; its references are kept for when it is narrowed again.
        if (!attributes.global) {
          const refsResponse = await axiosInstance.get(`${base}/reference`);
          await syncReferences(base, refsResponse.data.data, selectedWorkspaces, selectedProjects);
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
          initialValues={{ name: "", description: "", priority: 10, global: false }}
          saveLabel={mode === "create" ? "Create variable collection" : "Update variable collection"}
          saveDisabled={!managePermission}
          saving={saveLoading}
        >
          {mode === "edit" && collectionid && <IdField id="collection-id" value={collectionid} />}
          <Form.Item
            name="name"
            label="Name"
            rules={[{ required: true, message: "Enter a name for the collection" }, { max: 128 }]}
          >
            <Input maxLength={128} />
          </Form.Item>
          <Form.Item name="description" label="Description" rules={[{ max: 512 }]}>
            <Input.TextArea autoSize={{ minRows: 2, maxRows: 4 }} maxLength={512} showCount />
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
            name="global"
            label="Applies to"
            getValueProps={(value) => ({ value: value ? "all" : "selected" })}
            normalize={(value) => value === "all"}
          >
            <RadioChoices
              options={[
                {
                  value: "all",
                  label: "All workspaces",
                  help: "Every current and future workspace in the organization.",
                },
                {
                  value: "selected",
                  label: "Selected workspaces",
                  help: "Only the workspaces and projects chosen below.",
                },
              ]}
            />
          </Form.Item>
          {!isGlobal && (
            <>
              <Form.Item label="Workspaces" htmlFor="collection-workspaces">
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
              <Form.Item
                label="Projects"
                htmlFor="collection-projects"
                extra="Includes every current and future workspace in the project."
              >
                <Select
                  id="collection-projects"
                  mode="multiple"
                  placeholder="Select projects"
                  value={selectedProjects}
                  onChange={setSelectedProjects}
                  optionFilterProp="label"
                  options={projects.map((project) => ({ value: project.id, label: project.attributes.name }))}
                />
              </Form.Item>
            </>
          )}
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
              description="Jobs in the workspaces this collection applies to receive these variables. Adding, editing or deleting a variable applies immediately."
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
            description="The collection, its variables and its workspace and project references are deleted. Its workspaces stop receiving these variables. This cannot be undone."
            disabled={!managePermission}
            confirmValue={collectionName}
            confirmMessage={
              <>
                The collection <strong>{collectionName}</strong>, its variables and its workspace and project references
                will be deleted, and its workspaces stop receiving these variables. This cannot be undone.
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
