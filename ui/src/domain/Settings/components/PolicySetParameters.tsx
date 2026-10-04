import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Button, Flex, Form, Input, Table, Typography, message } from "antd";
import { DeleteOutlined, EditOutlined, PlusOutlined, SearchOutlined } from "@ant-design/icons";
import axiosInstance, { getErrorMessage } from "../../../config/axiosConfig";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";
import { CrudFormModal } from "@/components/modals/CrudFormModal";
import { EmptyState } from "@/components/feedback/EmptyState";
import "../PolicySets.css";
import "./PolicyComponents.css";

const { Text } = Typography;
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

export const PolicySetParameters: React.FC<Props> = ({ policySetId, managePermission = true }) => {
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
    if (!pendingDelete || deleting) return;
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
      (p) => p.key.toLowerCase().includes(q) || (p.description && p.description.toLowerCase().includes(q))
    );
  }, [parameters, searchQuery]);

  const columns = [
    {
      title: "Key",
      dataIndex: "key",
      key: "key",
      width: "28%",
      render: (text: string) => (
        <Text strong className="policy-mono" ellipsis={{ tooltip: text }}>
          {text}
        </Text>
      ),
    },
    {
      title: "Value",
      dataIndex: "value",
      key: "value",
      render: (text: string) => (
        <Text className="policy-mono" ellipsis={{ tooltip: text }}>
          {text}
        </Text>
      ),
    },
    {
      title: "Description",
      dataIndex: "description",
      key: "description",
      width: "28%",
      render: (text?: string) =>
        text ? (
          <Text type="secondary" ellipsis={{ tooltip: text }}>
            {text}
          </Text>
        ) : (
          <Text type="secondary">—</Text>
        ),
    },
    {
      title: "Actions",
      key: "actions",
      width: 96,
      align: "right" as const,
      render: (_: any, record: PolicyParameterItem) => (
        <Flex gap={8} justify="flex-end">
          <Button
            icon={<EditOutlined />}
            onClick={() => handleOpenEdit(record)}
            disabled={!managePermission}
            aria-label={`Edit parameter ${record.key}`}
          />
          <Button
            icon={<DeleteOutlined />}
            onClick={() => setPendingDelete(record)}
            disabled={!managePermission}
            aria-label={`Delete parameter ${record.key}`}
          />
        </Flex>
      ),
    },
  ];

  return (
    <section>
      <div className="policy-list-header">
        <div>
          <Typography.Title level={4}>Parameters ({parameters.length})</Typography.Title>
          <Typography.Text type="secondary">
            Values passed to this policy set at evaluation, read in Rego as{" "}
            <Text code>data.terrakube.inputs.&lt;key&gt;</Text>.
          </Typography.Text>
        </div>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={handleOpenCreate}
          disabled={!managePermission}
          data-testid="add-parameter-btn"
        >
          Add parameter
        </Button>
      </div>

      {!loading && parameters.length === 0 ? (
        <EmptyState simple description="No parameters. Add one to pass a value to the rules in this policy set.">
          {managePermission && (
            <Button icon={<PlusOutlined />} onClick={handleOpenCreate}>
              Add parameter
            </Button>
          )}
        </EmptyState>
      ) : (
        <>
          <Input
            aria-label="Search parameters by key or description"
            placeholder="Search by key or description"
            prefix={<SearchOutlined />}
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            allowClear
            className="policy-parameter-search"
          />
          <Table
            dataSource={filteredParameters}
            columns={columns}
            rowKey="id"
            loading={loading}
            tableLayout="fixed"
            pagination={filteredParameters.length > 10 ? { pageSize: 10 } : false}
          />
        </>
      )}

      <CrudFormModal
        open={modalVisible}
        title={modalMode === "create" ? "Add parameter" : `Edit parameter ${editingParam?.key}`}
        okText={modalMode === "create" ? "Add parameter" : "Save parameter"}
        form={form}
        formName="policy-set-parameter"
        confirmLoading={submitting}
        onCancel={() => {
          setModalVisible(false);
          form.resetFields();
        }}
        onSubmit={handleSubmit}
      >
        <Form.Item
          name="key"
          label="Key"
          rules={[
            { required: true, message: "Enter a key" },
            {
              pattern: /^[a-zA-Z0-9_-]+$/,
              message: "Use only letters, numbers, hyphens and underscores",
            },
          ]}
          extra={
            <>
              Rules read it as <Text code>data.terrakube.inputs.&lt;key&gt;</Text>.
            </>
          }
        >
          <Input className="policy-mono" placeholder="max_deletions" />
        </Form.Item>

        <Form.Item
          name="value"
          label="Value"
          rules={[{ required: true, message: "Enter a value" }]}
          extra="A string, number, boolean, JSON array or JSON object. JSON is parsed before evaluation."
        >
          <TextArea
            autoSize={{ minRows: 3, maxRows: 8 }}
            placeholder='["us-east-1", "us-west-2"]'
            className="policy-mono"
          />
        </Form.Item>

        <Form.Item name="description" label="Description">
          <Input placeholder="What this value controls" />
        </Form.Item>
      </CrudFormModal>

      <DeleteConfirmationModal
        open={!!pendingDelete}
        title="Delete parameter"
        message={`Rules will no longer receive ${pendingDelete?.key} when this policy set is evaluated.`}
        okText="Delete parameter"
        confirmLoading={deleting}
        onConfirm={handleDelete}
        onCancel={() => setPendingDelete(null)}
      />
    </section>
  );
};

export default PolicySetParameters;
