import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  Button,
  Card,
  Empty,
  Form,
  Input,
  Modal,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from "antd";
import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  SearchOutlined,
} from "@ant-design/icons";
import axiosInstance, { getErrorMessage } from "../../../config/axiosConfig";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";

const { Paragraph, Text } = Typography;
const { TextArea } = Input;

export interface PolicyParameterItem {
  id: string;
  key: string;
  value: string;
  description?: string;
}

type Props = {
  policySetId: string;
  managePermission?: boolean;
};

export const PolicySetParameters: React.FC<Props> = ({
  policySetId,
  managePermission = true,
}) => {
  const [parameters, setParameters] = useState<PolicyParameterItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState("");

  // Modal states for Create / Edit
  const [modalVisible, setModalVisible] = useState(false);
  const [modalMode, setModalMode] = useState<"create" | "edit">("create");
  const [editingParam, setEditingParam] = useState<PolicyParameterItem | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  // Delete state
  const [pendingDelete, setPendingDelete] = useState<PolicyParameterItem | null>(null);
  const [deleting, setDeleting] = useState(false);

  const loadParameters = useCallback(async () => {
    setLoading(true);
    try {
      const res = await axiosInstance.get(`policy_set/${policySetId}/parameters`);
      const rawData = res.data?.data || [];
      const parsed: PolicyParameterItem[] = rawData.map((item: any) => ({
        id: item.id,
        key: item.attributes?.key || "",
        value: item.attributes?.value || "",
        description: item.attributes?.description || "",
      }));
      setParameters(parsed);
    } catch (err: any) {
      message.error(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [policySetId]);

  useEffect(() => {
    void loadParameters();
  }, [loadParameters]);

  const handleOpenCreate = () => {
    setModalMode("create");
    setEditingParam(null);
    form.resetFields();
    setModalVisible(true);
  };

  const handleOpenEdit = (record: PolicyParameterItem) => {
    setModalMode("edit");
    setEditingParam(record);
    form.setFieldsValue({
      key: record.key,
      value: record.value,
      description: record.description,
    });
    setModalVisible(true);
  };

  const handleSubmit = async (values: any) => {
    setSubmitting(true);
    try {
      if (modalMode === "create") {
        const payload = {
          data: {
            type: "policy_set_parameter",
            attributes: {
              key: values.key.trim(),
              value: values.value,
              description: values.description ? values.description.trim() : null,
            },
            relationships: {
              policySet: {
                data: {
                  type: "policy_set",
                  id: policySetId,
                },
              },
            },
          },
        };
        await axiosInstance.post(`policy_set/${policySetId}/parameters`, payload, {
          headers: { "Content-Type": "application/vnd.api+json" },
        });
        message.success("Parameter added successfully");
      } else if (editingParam) {
        const payload = {
          data: {
            type: "policy_set_parameter",
            id: editingParam.id,
            attributes: {
              key: values.key.trim(),
              value: values.value,
              description: values.description ? values.description.trim() : null,
            },
          },
        };
        await axiosInstance.patch(`policy_set/${policySetId}/parameters/${editingParam.id}`, payload, {
          headers: { "Content-Type": "application/vnd.api+json" },
        });
        message.success("Parameter updated successfully");
      }
      setModalVisible(false);
      form.resetFields();
      await loadParameters();
    } catch (err: any) {
      message.error(getErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async () => {
    if (!pendingDelete) return;
    setDeleting(true);
    try {
      await axiosInstance.delete(`policy_set/${policySetId}/parameters/${pendingDelete.id}`);
      message.success("Parameter deleted successfully");
      setPendingDelete(null);
      await loadParameters();
    } catch (err: any) {
      message.error(getErrorMessage(err));
    } finally {
      setDeleting(false);
    }
  };

  const filteredParameters = useMemo(() => {
    if (!searchQuery.trim()) return parameters;
    const q = searchQuery.toLowerCase();
    return parameters.filter(
      (p) =>
        p.key.toLowerCase().includes(q) ||
        (p.description && p.description.toLowerCase().includes(q))
    );
  }, [parameters, searchQuery]);

  const columns = [
    {
      title: "Key",
      dataIndex: "key",
      key: "key",
      width: "28%",
      render: (text: string) => (
        <Space orientation="horizontal">
          <Tag color="geekblue" style={{ fontFamily: "monospace", fontSize: 13 }}>
            {text}
          </Tag>
        </Space>
      ),
    },
    {
      title: "Value",
      dataIndex: "value",
      key: "value",
      width: "37%",
      render: (text: string) => {
        const isLong = text && text.length > 50;
        const displayValue = isLong ? `${text.slice(0, 50)}...` : text;
        return (
          <Tooltip title={isLong ? text : undefined}>
            <Text code style={{ fontSize: 12, wordBreak: "break-all" }}>
              {displayValue}
            </Text>
          </Tooltip>
        );
      },
    },
    {
      title: "Description",
      dataIndex: "description",
      key: "description",
      width: "23%",
      render: (text?: string) => (
        <Text type="secondary" style={{ fontSize: 13 }}>
          {text || "—"}
        </Text>
      ),
    },
    {
      title: "Actions",
      key: "actions",
      width: "12%",
      align: "right" as const,
      render: (_: any, record: PolicyParameterItem) => (
        <Space size="small">
          <Button
            type="text"
            icon={<EditOutlined />}
            onClick={() => handleOpenEdit(record)}
            disabled={!managePermission}
            title="Edit Parameter"
          />
          <Button
            type="text"
            danger
            icon={<DeleteOutlined />}
            onClick={() => setPendingDelete(record)}
            disabled={!managePermission}
            title="Delete Parameter"
          />
        </Space>
      ),
    },
  ];

  return (
    <Card
      title={`Policy Parameters (${parameters.length})`}
      extra={
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={handleOpenCreate}
          disabled={!managePermission}
          data-testid="add-parameter-btn"
        >
          Add Parameter
        </Button>
      }
    >
      <Paragraph type="secondary" style={{ marginBottom: 16, fontSize: 13 }}>
        Parameters defined here are injected directly into this policy set during OPA evaluation and accessible in Rego via <Text code>data.terrakube.inputs.&lt;key&gt;</Text>.
      </Paragraph>

      <div style={{ marginBottom: 16, display: "flex", justifyContent: "space-between" }}>
        <Input
          placeholder="Search parameters by key or description..."
          prefix={<SearchOutlined />}
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          allowClear
          style={{ maxWidth: 350 }}
        />
      </div>

      <Table
        dataSource={filteredParameters}
        columns={columns}
        rowKey="id"
        loading={loading}
        pagination={filteredParameters.length > 10 ? { pageSize: 10 } : false}
        locale={{
          emptyText: (
            <Empty
              description="No parameters configured for this policy set"
              image={Empty.PRESENTED_IMAGE_SIMPLE}
            >
              {managePermission && (
                <Button type="primary" icon={<PlusOutlined />} onClick={handleOpenCreate}>
                  Add Parameter
                </Button>
              )}
            </Empty>
          ),
        }}
      />

      {/* Add / Edit Parameter Modal */}
      <Modal
        title={modalMode === "create" ? "Add Policy Parameter" : `Edit Parameter: ${editingParam?.key}`}
        open={modalVisible}
        onCancel={() => {
          setModalVisible(false);
          form.resetFields();
        }}
        onOk={() => form.submit()}
        confirmLoading={submitting}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" onFinish={handleSubmit}>
          <Form.Item
            name="key"
            label="Parameter Key"
            rules={[
              { required: true, message: "Please enter a parameter key" },
              {
                pattern: /^[a-zA-Z0-9_-]+$/,
                message: "Key can only contain letters, numbers, hyphens, and underscores",
              },
            ]}
            tooltip="The name accessed in Rego via data.terrakube.inputs.<key>"
          >
            <Input placeholder="e.g. max_deletions, allowed_regions" />
          </Form.Item>

          <Form.Item
            name="value"
            label="Parameter Value"
            rules={[{ required: true, message: "Please enter a value" }]}
            extra={'Supports plain strings, numbers, booleans, arrays (e.g. ["us-east-1"]), and JSON objects. Automatically parsed for Rego evaluation.'}
          >
            <TextArea
              rows={4}
              autoSize={{ minRows: 3, maxRows: 8 }}
              placeholder='e.g. 5 or ["us-east-1", "us-west-2"]'
              style={{ fontFamily: "monospace" }}
            />
          </Form.Item>

          <Form.Item name="description" label="Description">
            <Input placeholder="Optional description of this parameter's purpose" />
          </Form.Item>
        </Form>
      </Modal>

      {/* Delete Confirmation Modal */}
      <DeleteConfirmationModal
        open={!!pendingDelete}
        title="Delete Policy Parameter"
        description={
          <>
            Are you sure you want to delete parameter <Text code>{pendingDelete?.key}</Text>? This parameter will no longer be provided during policy evaluations.
          </>
        }
        confirmLoading={deleting}
        onConfirm={handleDelete}
        onCancel={() => setPendingDelete(null)}
      />
    </Card>
  );
};

export default PolicySetParameters;
