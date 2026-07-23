import { DeleteOutlined, EditOutlined, InfoCircleOutlined, PlusOutlined, CloseCircleOutlined } from "@ant-design/icons";
import {
  Button,
  Form,
  Input,
  message,
  Modal,
  Popconfirm,
  Radio,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
  Checkbox,
} from "antd";
import { useEffect, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import "./Settings.css";

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

type CollectionItemFormValues = {
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
  const [mode, setMode] = useState("create");
  const [itemId, setItemId] = useState<string>("");
  const [form] = Form.useForm<CollectionItemFormValues>();

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
            <Popconfirm
              onConfirm={() => {
                onDelete(record.id);
              }}
              title={
                <p>
                  This will permanently delete this variable <br />
                  from the collection. <br />
                  Are you sure?
                </p>
              }
              okText="Yes"
              cancelText="No"
            >
              {" "}
              <Button danger type="link" icon={<DeleteOutlined />}>
                Delete
              </Button>
            </Popconfirm>
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

  const onCreate = (values: CollectionItemFormValues) => {
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

  const onUpdate = (values: CollectionItemFormValues) => {
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
      <Button type="primary" onClick={onNew} htmlType="button" icon={<PlusOutlined />}>
        Add variable to collection
      </Button>
      <br></br>

      <h3 style={{ marginTop: "30px" }}>Collection Variables</h3>
      <Spin spinning={loading} tip="Loading Collection Variables...">
        <Table dataSource={items} columns={ITEM_COLUMNS(onEdit)} rowKey="id" />
      </Spin>

      <Modal
        width="600px"
        open={visible}
        title={mode === "edit" ? "Edit variable" : "Add variable"}
        okText={mode === "edit" ? "Save changes" : "Add variable"}
        onCancel={onCancel}
        cancelText="Cancel"
        closeIcon={<CloseCircleOutlined />}
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
        <Form
          name="collectionItem"
          form={form}
          layout="vertical"
          initialValues={{ category: "TERRAFORM", hcl: false, sensitive: false }}
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

          <Form.Item name="key" label="Key" rules={[{ required: true }]}>
            <Input placeholder="Enter key name" />
          </Form.Item>

          <Form.Item name="value" label="Value" rules={[{ required: true }]}>
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

            {mode === "create" && (
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
            )}
          </div>

          <Form.Item name="description" label="Description (Optional)">
            <Input.TextArea placeholder="Enter description (optional)" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};
