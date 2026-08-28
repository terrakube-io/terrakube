import { DeleteOutlined, EditOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, Form, message, Spin, Table, Tag, Typography } from "antd";
import { useEffect, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import SettingsSection from "@/components/SettingsSection/SettingsSection";
import "./Settings.css";
import { CollectionVariableModal, CollectionVariableFormValues } from "./components";
import DeleteConfirmationModal from "@/components/DeleteConfirmationModal/DeleteConfirmationModal";

// Type definitions for Collection Items
type CollectionItem = {
  id: string;
  attributes: CollectionItemAttributes;
};

type CollectionItemAttributes = {
  key: string;
  value?: string;
  hcl: boolean;
  category: string;
  description: string;
  sensitive: boolean;
};

type Props = {
  collectionId: string;
  collectionName: string;
};

export const CollectionItemsSettings = ({ collectionId, collectionName }: Props) => {
  const { orgid } = useParams();
  const navigate = useNavigate();
  const [items, setItems] = useState<CollectionItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [visible, setVisible] = useState(false);
  const [itemKey, setItemKey] = useState<string>("");
  const [mode, setMode] = useState<"create" | "edit">("create");
  const [itemId, setItemId] = useState<string>("");
  const [pendingDelete, setPendingDelete] = useState<CollectionItem | null>(null);
  const [form] = Form.useForm<CollectionVariableFormValues>();

  const ITEM_COLUMNS = (onEdit: (id: string) => void) => [
    {
      title: "Key",
      dataIndex: "key",
      width: "30%",
      key: "key",
      render: (_: any, record: CollectionItem) => {
        return (
          <div>
            {record.attributes.key} &nbsp;&nbsp;&nbsp;&nbsp;
            <Tag color="blue">{record.attributes.category === "ENV" ? "Environment" : "Terraform"}</Tag>
            {record.attributes.hcl && <Tag color="green">HCL</Tag>}
            {record.attributes.sensitive && <Tag color="red">Sensitive</Tag>}
          </div>
        );
      },
    },
    {
      title: "Value",
      dataIndex: "value",
      key: "value",
      width: "30%",
      render: (_: any, record: CollectionItem) => {
        return record.attributes.sensitive ? <i>Sensitive - write only</i> : <div>{record.attributes.value}</div>;
      },
    },
    {
      title: "Description",
      dataIndex: "description",
      key: "description",
      width: "20%",
      render: (_: any, record: CollectionItem) => {
        return record.attributes.description;
      },
    },
    {
      title: "Actions",
      key: "action",
      render: (_: any, record: CollectionItem) => {
        return (
          <div>
            <Button type="link" icon={<EditOutlined />} onClick={() => onEdit(record.id)}>
              Edit
            </Button>
            <Button danger type="link" icon={<DeleteOutlined />} onClick={() => setPendingDelete(record)}>
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
    setItemId(id);
    setVisible(true);
    axiosInstance
      .get(`organization/${orgid}/collection/${collectionId}/item/${id}`)
      .then((response) => {
        setItemKey(response.data.data.attributes.key);
        form.setFieldsValue({
          key: response.data.data.attributes.key,
          value: response.data.data.attributes.value,
          hcl: response.data.data.attributes.hcl,
          sensitive: response.data.data.attributes.sensitive,
          category: response.data.data.attributes.category,
          description: response.data.data.attributes.description,
        });
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      });
  };

  const onNew = () => {
    form.resetFields();
    setVisible(true);
    setItemKey("");
    setMode("create");
  };

  const onDelete = (id: string) => {
    axiosInstance
      .delete(`organization/${orgid}/collection/${collectionId}/item/${id}`)
      .then(() => {
        message.success("Variable deleted successfully");
        loadItems();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      });
  };

  const onCreate = (values: CollectionVariableFormValues) => {
    const body = {
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
    };

    axiosInstance
      .post(`organization/${orgid}/collection/${collectionId}/item`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then(() => {
        message.success("Variable added successfully");
        loadItems();
        setVisible(false);
        form.resetFields();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      });
  };

  const onUpdate = (values: CollectionVariableFormValues) => {
    const body = {
      data: {
        type: "item",
        id: itemId,
        attributes: {
          key: values.key,
          value: values.value,
          description: values.description,
          hcl: values.hcl,
          category: values.category,
        },
      },
    };

    axiosInstance
      .patch(`organization/${orgid}/collection/${collectionId}/item/${itemId}`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then(() => {
        message.success("Variable updated successfully");
        loadItems();
        setVisible(false);
        form.resetFields();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      });
  };

  const loadItems = () => {
    axiosInstance
      .get(`organization/${orgid}/collection/${collectionId}/item`)
      .then((response) => {
        setItems(response.data.data);
        setLoading(false);
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
        setLoading(false);
      });
  };

  useEffect(() => {
    setLoading(true);
    loadItems();
  }, [orgid, collectionId]);

  return (
    <div className="setting">
      <div>
        <Typography.Text type="secondary" className="App-text">
          Add and manage variables for this collection. These variables can be applied to workspaces.
        </Typography.Text>
      </div>
      <SettingsSection maxWidth="100%">
        <Button type="primary" onClick={onNew} htmlType="button" icon={<PlusOutlined />}>
          Add variable to collection
        </Button>
        <br></br>

        <Typography.Title level={3} style={{ marginTop: "30px" }}>
          Collection Variables
        </Typography.Title>
        <Spin spinning={loading} description="Loading Collection Variables...">
          <Table dataSource={items} columns={ITEM_COLUMNS(onEdit)} rowKey="id" />
        </Spin>
      </SettingsSection>

      <CollectionVariableModal
        open={visible}
        mode={mode}
        form={form}
        onCancel={onCancel}
        onSubmit={(values) => {
          if (mode === "create") onCreate(values);
          else onUpdate(values);
        }}
      />

      <DeleteConfirmationModal
        open={pendingDelete !== null}
        title="Delete variable"
        message={
          <>
            Deleting the variable <strong>{pendingDelete?.attributes.key}</strong> from the collection cannot be undone.
          </>
        }
        okText="Delete"
        onConfirm={() => {
          if (pendingDelete) onDelete(pendingDelete.id);
          setPendingDelete(null);
        }}
        onCancel={() => setPendingDelete(null)}
      />
    </div>
  );
};
