import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  SearchOutlined,
  AppstoreOutlined,
  UnorderedListOutlined,
} from "@ant-design/icons";
import { Button, Card, Input, List, Popconfirm, Space, Spin, Typography, Pagination, message } from "antd";
import { useEffect, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import axiosInstance from "../../config/axiosConfig";
import "./Settings.css";

// Type definitions for Variable Collections
type Collection = {
  id: string;
  attributes: CollectionAttributes;
  relationships?: {
    workspaces?: {
      data: any[];
    };
    variables?: {
      data: any[];
    };
  };
};

type CollectionAttributes = {
  name: string;
  description: string;
  priority: number;
};

export const VariableCollectionsSettings = () => {
  const { orgid } = useParams();
  const navigate = useNavigate();
  const [collections, setCollections] = useState<Collection[]>([]);
  const [loading, setLoading] = useState(false);
  const [deleteLoading, setDeleteLoading] = useState<string | null>(null);
  const [searchTerm, setSearchTerm] = useState("");
  const [currentPage, setCurrentPage] = useState(1);
  const pageSize = 10;

  const handleViewDetails = (id: string) => {
    navigate(`/organizations/${orgid}/settings/collection/edit/${id}`);
  };

  const handleEditCollection = (id: string) => {
    navigate(`/organizations/${orgid}/settings/collection/edit/${id}`);
  };

  const handleCreateCollection = () => {
    navigate(`/organizations/${orgid}/settings/collection/new`);
  };

  const onDelete = async (id: string) => {
    try {
      setDeleteLoading(id);

      // First get all variables and delete them
      const variablesResponse = await axiosInstance.get(`organization/${orgid}/collection/${id}/item`);
      const variables = variablesResponse.data.data || [];

      for (const variable of variables) {
        await axiosInstance.delete(`organization/${orgid}/collection/${id}/item/${variable.id}`);
      }

      // Then get all references and delete them
      const referencesResponse = await axiosInstance.get(`organization/${orgid}/collection/${id}/reference`);
      const references = referencesResponse.data.data || [];

      for (const reference of references) {
        await axiosInstance.delete(`organization/${orgid}/collection/${id}/reference/${reference.id}`);
      }

      // Finally delete the collection
      await axiosInstance.delete(`organization/${orgid}/collection/${id}`);

      // Reload collections
      message.success("Collection deleted successfully");
      loadCollections();
    } catch (error) {
      console.error("Error deleting collection:", error);
      message.error("Failed to delete collection");
    } finally {
      setDeleteLoading(null);
    }
  };

  const loadCollections = () => {
    axiosInstance.get(`organization/${orgid}/collection`).then((response) => {
      setCollections(response.data.data);
      setLoading(false);
    });
  };

  const getWorkspacesAndVariablesCounts = async (collections: Collection[]) => {
    const updatedCollections = [...collections];

    for (const collection of updatedCollections) {
      try {
        // Get workspaces count
        const workspacesResponse = await axiosInstance.get(
          `organization/${orgid}/collection/${collection.id}/reference`
        );
        collection.relationships = {
          ...collection.relationships,
          workspaces: {
              data: (workspacesResponse.data.data || []).filter(
                  (item: any) => item.relationships?.workspace?.data?.id != null
              ),
          },
        };

        // Get variables count
        const variablesResponse = await axiosInstance.get(`organization/${orgid}/collection/${collection.id}/item`);
        collection.relationships = {
          ...collection.relationships,
          variables: {
            data: variablesResponse.data.data || [],
          },
        };
      } catch (error) {
        console.error(`Error fetching details for collection ${collection.id}:`, error);
      }
    }

    setCollections(updatedCollections);
  };

  useEffect(() => {
    setLoading(true);
    loadCollections();
  }, [orgid]);

  useEffect(() => {
    if (collections.length > 0) {
      getWorkspacesAndVariablesCounts(collections);
    }
  }, [collections.length]);

  const filteredCollections = collections.filter(
    (collection) =>
      collection.attributes.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
      collection.attributes.description.toLowerCase().includes(searchTerm.toLowerCase())
  );

  const paginatedCollections = filteredCollections.slice((currentPage - 1) * pageSize, currentPage * pageSize);

  return (
    <div className="setting">
      <h1>Variable Collections</h1>
      <div>
        <Typography.Text type="secondary" className="App-text">
          Variable Collections allow you to define and apply variables one time across multiple workspaces within an
          organization.
        </Typography.Text>
      </div>
      <div style={{ display: "flex", justifyContent: "flex-start", marginBottom: "20px", marginTop: "20px" }}>
        <Button type="primary" onClick={handleCreateCollection} icon={<PlusOutlined />}>
          Create variable collection
        </Button>
      </div>
      <div style={{ marginBottom: "20px", width: "100%" }}>
        <Input
          prefix={<SearchOutlined />}
          placeholder="Search by variable collections name"
          style={{ width: "100%" }}
          value={searchTerm}
          onChange={(e) => setSearchTerm(e.target.value)}
        />
      </div>

      <Spin spinning={loading}>
        <List
          grid={{ gutter: 16, column: 1 }}
          dataSource={paginatedCollections}
          renderItem={(item) => (
            <List.Item>
              <Card hoverable style={{ width: "100%", cursor: "pointer" }} onClick={() => handleViewDetails(item.id)}>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
                  <div>
                    <Typography.Title level={4} style={{ margin: 0 }}>
                      {item.attributes.name}
                    </Typography.Title>
                    <Typography.Paragraph style={{ marginTop: "8px" }}>
                      {item.attributes.description}
                    </Typography.Paragraph>
                    <Space style={{ marginTop: "16px" }}>
                      <span style={{ display: "inline-flex", alignItems: "center" }}>
                        <AppstoreOutlined style={{ marginRight: "5px" }} />
                        {item.relationships?.workspaces?.data?.length || 0} workspaces
                      </span>
                      <span style={{ display: "inline-flex", alignItems: "center", marginLeft: "20px" }}>
                        <UnorderedListOutlined style={{ marginRight: "5px" }} />
                        {item.relationships?.variables?.data?.length || 0} variables
                      </span>
                    </Space>
                  </div>
                  <Space>
                    <Button
                      type="text"
                      icon={<EditOutlined />}
                      onClick={(e) => {
                        e.stopPropagation();
                        handleEditCollection(item.id);
                      }}
                    >
                      Edit
                    </Button>
                    <Popconfirm
                      title="Delete this variable collection?"
                      description="This will permanently delete this variable collection and all its variables. Are you sure?"
                      onConfirm={(e) => {
                        e?.stopPropagation();
                        onDelete(item.id);
                      }}
                      onCancel={(e) => e?.stopPropagation()}
                      okText="Yes"
                      cancelText="No"
                    >
                      <Button
                        danger
                        type="text"
                        icon={<DeleteOutlined />}
                        onClick={(e) => e.stopPropagation()}
                        loading={deleteLoading === item.id}
                      >
                        Delete
                      </Button>
                    </Popconfirm>
                  </Space>
                </div>
              </Card>
            </List.Item>
          )}
        />

        <div style={{ display: "flex", justifyContent: "center", marginTop: "20px" }}>
          {filteredCollections.length > 0 && (
            <Pagination
              current={currentPage}
              pageSize={pageSize}
              total={filteredCollections.length}
              onChange={setCurrentPage}
              showSizeChanger={false}
              simple={false}
            />
          )}
        </div>
      </Spin>
    </div>
  );
};
