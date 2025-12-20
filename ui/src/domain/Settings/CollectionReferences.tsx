import { DeleteOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, Form, Input, Modal, Popconfirm, Select, Space, Spin, Table, Typography } from "antd";
import { useEffect, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import axiosInstance from "../../config/axiosConfig";
import "./Settings.css";

// Type definitions for Collection References
type CollectionReference = {
  id: string;
  attributes: {
    description: string;
  };
  relationships: {
    workspace: {
      data: {
        id: string;
        type: string;
      };
    };
  };
};

type Workspace = {
  id: string;
  attributes: {
    name: string;
  };
};

type ReferenceFormValues = {
  workspaceId: string;
  description: string;
};

type Props = {
  collectionId: string;
  collectionName: string;
};

export const CollectionReferencesSettings = ({ collectionId, collectionName }: Props) => {
  const { orgid } = useParams();
  const navigate = useNavigate();
  const [references, setReferences] = useState<CollectionReference[]>([]);
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [loading, setLoading] = useState(false);
  const [visible, setVisible] = useState(false);
  const [form] = Form.useForm<ReferenceFormValues>();
  const [workspacesMap, setWorkspacesMap] = useState<{ [key: string]: string }>({});

  const REFERENCE_COLUMNS = [
    {
      title: "Workspace",
      dataIndex: "workspace",
      width: "40%",
      key: "workspace",
      render: (_: any, record: CollectionReference) => {
        return workspacesMap[record.relationships.workspace.data.id] || record.relationships.workspace.data.id;
      },
    },
    {
      title: "Description",
      dataIndex: "description",
      key: "description",
      width: "40%",
      render: (_: any, record: CollectionReference) => {
        return record.attributes.description;
      },
    },
    {
      title: "Actions",
      key: "action",
      render: (_: any, record: CollectionReference) => {
        return (
          <div>
            <Popconfirm
              onConfirm={() => {
                onDelete(record.id);
              }}
              title={
                <p>
                  This will remove the association between <br />
                  this collection and the workspace. <br />
                  Are you sure?
                </p>
              }
              okText="Yes"
              cancelText="No"
            >
              {" "}
              <Button icon={<DeleteOutlined />} type="link" danger>
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

  const onNew = () => {
    form.resetFields();
    setVisible(true);
  };

  const onDelete = (id: string) => {
    axiosInstance.delete(`organization/${orgid}/collection/${collectionId}/reference/${id}`).then(() => {
      loadReferences();
    });
  };

  const onCreate = (values: ReferenceFormValues) => {
    const body = {
      data: {
        type: "reference",
        attributes: {
          description: values.description,
        },
        relationships: {
          workspace: {
            data: {
              type: "workspace",
              id: values.workspaceId,
            },
          },
        },
      },
    };

    axiosInstance
      .post(`organization/${orgid}/collection/${collectionId}/reference`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then(() => {
        loadReferences();
        setVisible(false);
        form.resetFields();
      });
  };

  const loadReferences = () => {
    axiosInstance.get(`organization/${orgid}/collection/${collectionId}/reference`).then((response) => {
      setReferences(response.data.data);
      setLoading(false);
    });
  };

  const loadWorkspaces = () => {
    axiosInstance.get(`organization/${orgid}/workspace`).then((response) => {
      setWorkspaces(response.data.data);

      // Create a map of workspace IDs to names for easier lookup
      const map: { [key: string]: string } = {};
      response.data.data.forEach((workspace: Workspace) => {
        map[workspace.id] = workspace.attributes.name;
      });
      setWorkspacesMap(map);
    });
  };

  useEffect(() => {
    setLoading(true);
    loadReferences();
    loadWorkspaces();
  }, [orgid, collectionId]);

  return (
    <div className="setting">
      <div>
        <Typography.Text type="secondary" className="App-text">
          Associate workspaces with this collection to apply its variables to them.
        </Typography.Text>
      </div>
      <Button type="primary" onClick={onNew} htmlType="button" icon={<PlusOutlined />}>
        Add workspace reference
      </Button>
      <br></br>

      <h3 style={{ marginTop: "30px" }}>Associated Workspaces</h3>
      <Spin spinning={loading} tip="Loading References...">
        <Table dataSource={references} columns={REFERENCE_COLUMNS} rowKey="id" />
      </Spin>

      <Modal
        width="600px"
        open={visible}
        title="Add workspace reference"
        okText="Add reference"
        onCancel={onCancel}
        cancelText="Cancel"
        onOk={() => {
          form
            .validateFields()
            .then((values) => {
              onCreate(values);
            })
            .catch((info) => {
              console.log("Validate Failed:", info);
            });
        }}
      >
        <Space style={{ width: "100%" }} direction="vertical">
          <Form name="collectionReference" form={form} layout="vertical">
            <Form.Item name="workspaceId" label="Workspace" rules={[{ required: true }]}>
              <Select placeholder="Select a workspace">
                {workspaces.map((workspace) => (
                  <Select.Option key={workspace.id} value={workspace.id}>
                    {workspace.attributes.name}
                  </Select.Option>
                ))}
              </Select>
            </Form.Item>
            <Form.Item name="description" label="Description" rules={[{ required: true }]}>
              <Input.TextArea rows={3} />
            </Form.Item>
          </Form>
        </Space>
      </Modal>
    </div>
  );
};
