import { DeleteOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, Form, Input, message, Select, Spin, Table, Typography } from "antd";
import { useEffect, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import SettingsSection from "@/components/SettingsSection/SettingsSection";
import "./Settings.css";
import DeleteConfirmationModal from "@/components/DeleteConfirmationModal/DeleteConfirmationModal";
import CollectionReferenceFormModal, { ReferenceFormValues } from "./components/CollectionReferenceFormModal";

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
  const [pendingDelete, setPendingDelete] = useState<CollectionReference | null>(null);

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
            <Button icon={<DeleteOutlined />} type="link" danger onClick={() => setPendingDelete(record)}>
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

  const onNew = () => {
    form.resetFields();
    setVisible(true);
  };

  const onDelete = (id: string) => {
    axiosInstance
      .delete(`organization/${orgid}/collection/${collectionId}/reference/${id}`)
      .then(() => {
        message.success("Workspace reference removed successfully");
        loadReferences();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
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
        message.success("Workspace reference added successfully");
        loadReferences();
        setVisible(false);
        form.resetFields();
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      });
  };

  const loadReferences = () => {
    axiosInstance
      .get(`organization/${orgid}/collection/${collectionId}/reference`)
      .then((response) => {
        setReferences(response.data.data);
        setLoading(false);
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
        setLoading(false);
      });
  };

  useEffect(() => {
    setLoading(true);
    // Parallel load: references and workspaces
    Promise.all([
      axiosInstance.get(`organization/${orgid}/collection/${collectionId}/reference`),
      axiosInstance.get(`organization/${orgid}/workspace`),
    ])
      .then(([refsRes, workspacesRes]) => {
        setReferences(refsRes.data.data);
        setWorkspaces(workspacesRes.data.data);

        // Create a map of workspace IDs to names for easier lookup
        const map: { [key: string]: string } = {};
        workspacesRes.data.data.forEach((workspace: Workspace) => {
          map[workspace.id] = workspace.attributes.name;
        });
        setWorkspacesMap(map);
        setLoading(false);
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
        setLoading(false);
      });
  }, [orgid, collectionId]);

  return (
    <div className="setting">
      <div>
        <Typography.Text type="secondary" className="App-text">
          Associate workspaces with this collection to apply its variables to them.
        </Typography.Text>
      </div>
      <SettingsSection maxWidth="100%">
        <Button type="primary" onClick={onNew} htmlType="button" icon={<PlusOutlined />}>
          Add workspace reference
        </Button>
        <br></br>

        <Typography.Title level={3} style={{ marginTop: "30px" }}>
          Associated Workspaces
        </Typography.Title>
        <Spin spinning={loading} description="Loading References...">
          <Table dataSource={references} columns={REFERENCE_COLUMNS} rowKey="id" />
        </Spin>
      </SettingsSection>

      <CollectionReferenceFormModal
        open={visible}
        workspaces={workspaces}
        form={form}
        onCancel={onCancel}
        onSubmit={(values) => {
          onCreate(values);
        }}
      />

      <DeleteConfirmationModal
        open={pendingDelete !== null}
        title="Delete workspace reference"
        message={
          <>
            Deleting the reference to the workspace{" "}
            <strong>
              {pendingDelete &&
                (workspacesMap[pendingDelete.relationships.workspace.data.id] ||
                  pendingDelete.relationships.workspace.data.id)}
            </strong>{" "}
            will remove the association between this collection and the workspace.
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
