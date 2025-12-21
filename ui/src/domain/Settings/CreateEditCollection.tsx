import {
  Button,
  Checkbox,
  Form,
  Input,
  Modal,
  Radio,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
  message,
} from "antd";
import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import axiosInstance from "../../config/axiosConfig";
import "./Settings.css";
import { DeleteOutlined, InfoCircleOutlined, PlusOutlined, CloseCircleOutlined } from "@ant-design/icons";

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
};

export const CreateEditCollection = ({ mode, collectionId: propCollectionId }: CreateEditCollectionProps) => {
  const { orgid, collectionid: urlCollectionId } = useParams();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [saveLoading, setSaveLoading] = useState(false);
  const [variableLoading, setVariableLoading] = useState(false);
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [selectedWorkspaces, setSelectedWorkspaces] = useState<string[]>([]);
  const [variables, setVariables] = useState<any[]>([]);
  const [variableForm] = Form.useForm();
  const [collectionForm] = Form.useForm();
  const [addingVariable, setAddingVariable] = useState(false);

  // Use either the prop or URL parameter for collection ID
  const collectionid = propCollectionId || urlCollectionId;

  // Load collection data if in edit mode
  useEffect(() => {
    setLoading(true);

    // Load workspaces
    axiosInstance.get(`organization/${orgid}/workspace`).then((response) => {
      setWorkspaces(response.data.data);
    });

    if (mode === "edit" && collectionid) {
      // Load collection data
      axiosInstance.get(`organization/${orgid}/collection/${collectionid}`).then((response) => {
        const collectionData = response.data.data;

        collectionForm.setFieldsValue({
          name: collectionData.attributes.name,
          description: collectionData.attributes.description,
          priority: collectionData.attributes.priority || 10,
        });

        // Load collection variables
        axiosInstance.get(`organization/${orgid}/collection/${collectionid}/item`).then((response) => {
          setVariables(response.data.data);
        });

        // Load collection workspace references
        axiosInstance.get(`organization/${orgid}/collection/${collectionid}/reference`).then((response) => {
          const workspaceIds = response.data.data
              .filter((ref: any) => ref.relationships?.workspace?.data?.id != null)
              .map((ref: any) => ref.relationships.workspace.data.id);
          setSelectedWorkspaces(workspaceIds);
        });

        setLoading(false);
      });
    } else {
      // For create mode, initialize with empty variables
      setVariables([]);
      setSelectedWorkspaces([]);
      setLoading(false);
    }
  }, [orgid, collectionid, mode]);

  const handleCancel = () => {
    navigate(`/organizations/${orgid}/settings/collection`);
  };

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

  const handleAddVariable = async () => {
    try {
      setVariableLoading(true);
      const values = await variableForm.validateFields();

      // Add variable to local state
      const newVariable = {
        id: `temp-${Date.now()}`,
        attributes: {
          key: values.key,
          value: values.value,
          category: values.category,
          description: values.description,
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
                  key: values.key,
                  value: values.value,
                  sensitive: values.sensitive,
                  description: values.description,
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

      variableForm.resetFields();
      setAddingVariable(false);
    } catch (error) {
      console.error("Failed to add variable:", error);
      message.error("Failed to add variable");
    } finally {
      setVariableLoading(false);
    }
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
        <Button icon={<DeleteOutlined />} type="link" danger onClick={() => handleRemoveVariable(record.id)}>
          Delete
        </Button>
      ),
    },
  ];

  // Replace the Card for adding a variable with a Modal
  const addVariableModal = (
    <Modal
      title="Add variable"
      open={addingVariable}
      onCancel={() => setAddingVariable(false)}
      footer={[
        <Button key="cancel" onClick={() => setAddingVariable(false)}>
          Cancel
        </Button>,
        <Button key="submit" type="primary" onClick={handleAddVariable} loading={variableLoading}>
          Add variable
        </Button>,
      ]}
      width={600}
      closeIcon={<CloseCircleOutlined />}
    >
      <Form
        form={variableForm}
        layout="vertical"
        initialValues={{
          category: "TERRAFORM",
          hcl: false,
          sensitive: false,
        }}
      >
        <Typography.Title level={5} style={{ margin: "20px 0 15px 0" }}>
          Select variable category
        </Typography.Title>

        <Form.Item name="category">
          <Radio.Group>
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

        <Form.Item name="key" label="Key" rules={[{ required: true, message: "Please enter a key" }]}>
          <Input placeholder="Enter key name" />
        </Form.Item>

        <Form.Item name="value" label="Value" rules={[{ required: true, message: "Please enter a value" }]}>
          <Input.TextArea rows={3} placeholder="Enter variable value" />
        </Form.Item>

        <div style={{ display: "flex", gap: "30px", marginBottom: "15px" }}>
          <Form.Item name="hcl" valuePropName="checked" style={{ marginBottom: 0 }}>
            <Checkbox>
              <Space>
                HCL
                <InfoCircleOutlined
                  style={{ color: "rgba(0,0,0,0.45)" }}
                  title="When enabled, this field will be processed as HCL code, allowing for variable interpolation and complex data structures during runtime execution."
                />
              </Space>
            </Checkbox>
          </Form.Item>

          <Form.Item name="sensitive" valuePropName="checked" style={{ marginBottom: 0 }}>
            <Checkbox>
              <Space>
                Sensitive
                <InfoCircleOutlined
                  style={{ color: "rgba(0,0,0,0.45)" }}
                  title="Mark as sensitive to hide values in the user interface and API responses. Note that these values might still be visible in OpenTofu/Terraform logs if explicitly output by your configuration."
                />
              </Space>
            </Checkbox>
          </Form.Item>
        </div>

        <Form.Item name="description" label="Description (Optional)">
          <Input.TextArea rows={3} placeholder="Enter description (optional)" />
        </Form.Item>
      </Form>
    </Modal>
  );

  const variableListing = (
    <div>
      <div style={{ marginBottom: "15px" }}>
        <Typography.Text>
          You can add any number of variables. Terrakube will use these variables for jobs in the specified
          workspaces.
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

        <Button icon={<PlusOutlined />} onClick={() => setAddingVariable(true)} style={{ marginBottom: "20px" }}>
          Add variable
        </Button>

        {addVariableModal}
      </div>
    </div>
  );

  return (
    <div className="setting">
      <Spin spinning={loading}>
        <div style={{ marginBottom: "20px" }}>
          <h1>
            {mode === "create"
              ? "Create a new organization variable collection"
              : "Edit organization variable collection"}
          </h1>
        </div>

        <div style={{ marginBottom: "20px" }}>
          <Typography.Text type="secondary" className="App-text">
            Variable collections allow you to define and apply variables one time across multiple workspaces within an
            organization.
          </Typography.Text>
        </div>

        <h2 style={{ fontSize: "20px", fontWeight: "bold", marginBottom: "20px" }}>Configure settings</h2>

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
          <Form.Item
            name="name"
            label="Name"
            rules={[{ required: true, message: "Please enter a name for the collection" }]}
          >
            <Input placeholder="Collection name" />
          </Form.Item>

          <Form.Item name="description" label="Description (Optional)">
            <Input.TextArea rows={3} placeholder="Describe the purpose of this collection" />
          </Form.Item>

          <Form.Item
            name="priority"
            label="Priority"
            rules={[{ required: true, message: "Please enter a priority" }]}
            help="Higher number means higher priority. When variables with the same name exist in multiple collections, the one with higher priority will be used."
          >
            <Input type="number" min={1} max={100} defaultValue={10} />
          </Form.Item>

          <div style={{ marginTop: "50px", marginBottom: "10px" }}>
            <h2 style={{ fontSize: "20px", fontWeight: "bold" }}>Variable collection scope</h2>
          </div>

          <div style={{ marginBottom: "30px" }}>
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
          </div>

          <div style={{ marginTop: "50px", marginBottom: "10px" }}>
            <h2 style={{ fontSize: "20px", fontWeight: "bold" }}>Variables</h2>
          </div>

          {mode === "create" ? (
            <div style={{ marginBottom: "15px" }}>
              <Typography.Text>
                Create the collection first. Then you can add variables to it.
              </Typography.Text>
            </div>
          ) : (
            variableListing
          )}
          <div style={{ display: "flex", justifyContent: "flex-start", marginTop: "30px" }}>
            <Space>
              <Button onClick={handleCancel}>Cancel</Button>
              <Button type="primary" onClick={handleSave} loading={saveLoading}>
                {mode === "create" ? "Create variable collection" : "Save Variable Collection"}
              </Button>
            </Space>
          </div>
        </Form>
      </Spin>
    </div>
  );
};
