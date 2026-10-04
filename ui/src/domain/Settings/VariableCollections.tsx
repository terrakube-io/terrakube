import { DeleteOutlined, FolderOutlined, PlusOutlined, SearchOutlined } from "@ant-design/icons";
import { Alert, Button, Input, Typography, Pagination, message } from "antd";
import { Loading } from "@/components/feedback/Loading";
import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { LinkButton } from "@/components/navigation/LinkButton";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import { EmptyState } from "@/components/feedback/EmptyState";
import "./Settings.css";
import "./VariableCollections.css";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";
import { deleteCollection } from "./deleteCollection";

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

type Props = {
  managePermission?: boolean;
};

export const VariableCollectionsSettings = ({ managePermission = true }: Props) => {
  const { orgid } = useParams();
  const [collections, setCollections] = useState<Collection[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [deleteLoading, setDeleteLoading] = useState<string | null>(null);
  const [pendingDelete, setPendingDelete] = useState<Collection | null>(null);
  const [searchTerm, setSearchTerm] = useState("");
  const [currentPage, setCurrentPage] = useState(1);
  const pageSize = 10;

  const editCollectionLink = (id: string) => `/organizations/${orgid}/settings/collection/edit/${id}`;

  const onDelete = async (id: string) => {
    try {
      setDeleteLoading(id);

      await deleteCollection(orgid, id);

      // Reload collections
      message.success("Variable collection deleted");
      loadCollections();
    } catch (error) {
      message.error(`Could not delete the variable collection: ${getErrorMessage(error)}`);
    } finally {
      setDeleteLoading(null);
    }
  };

  const loadCollections = () => {
    axiosInstance
      .get(`organization/${orgid}/collection`)
      .then((response) => {
        setCollections(response.data.data);
        setError(null);
      })
      .catch((err) => {
        setError(getErrorMessage(err));
      })
      .finally(() => {
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

  const plural = (count: number, noun: string) => `${count} ${noun}${count === 1 ? "" : "s"}`;

  const createButton = (
    <LinkButton
      to={`/organizations/${orgid}/settings/collection/new`}
      type="primary"
      icon={<PlusOutlined />}
      disabled={!managePermission}
    >
      Create variable collection
    </LinkButton>
  );

  const renderCollection = (item: Collection) => {
    const { name, description, priority } = item.attributes;
    const workspaces = item.relationships?.workspaces?.data;
    const variables = item.relationships?.variables?.data;
    return (
      <li key={item.id} className="collection-card">
        <span className="collection-card-icon" aria-hidden="true">
          <FolderOutlined />
        </span>
        <div className="collection-card-body">
          <Link to={editCollectionLink(item.id)} className="collection-card-name">
            {name}
          </Link>
          {description && <span className="collection-card-description">{description}</span>}
          <span className="collection-card-meta">
            Priority {priority}
            {workspaces && variables && (
              <>
                {" · "}
                {plural(workspaces.length, "workspace")} · {plural(variables.length, "variable")}
              </>
            )}
          </span>
        </div>
        <Button
          icon={<DeleteOutlined />}
          aria-label={`Delete variable collection ${name}`}
          onClick={() => setPendingDelete(item)}
          loading={deleteLoading === item.id}
          disabled={!managePermission}
        />
      </li>
    );
  };

  const renderList = () => {
    if (collections.length === 0) {
      return (
        <EmptyState description="There are no variable collections in this organization yet.">
          {createButton}
        </EmptyState>
      );
    }
    return (
      <>
        <Typography.Title level={4} className="collections-heading">
          Collections ({collections.length})
        </Typography.Title>
        <Input
          prefix={<SearchOutlined />}
          aria-label="Search variable collections"
          placeholder="Search by name or description"
          className="collections-search"
          value={searchTerm}
          onChange={(e) => {
            setSearchTerm(e.target.value);
            setCurrentPage(1);
          }}
          allowClear
        />
        {filteredCollections.length === 0 ? (
          <EmptyState simple description={`No variable collections match "${searchTerm}".`} />
        ) : (
          <ul className="collections-list">{paginatedCollections.map(renderCollection)}</ul>
        )}
        {filteredCollections.length > pageSize && (
          <Pagination
            className="collections-pagination"
            current={currentPage}
            pageSize={pageSize}
            total={filteredCollections.length}
            onChange={setCurrentPage}
            showSizeChanger={false}
          />
        )}
      </>
    );
  };

  return (
    <div className="setting collections-page">
      <SettingsPageHeader
        title="Variable collections"
        description="Define variables once and apply them to several workspaces in this organization."
        divider={false}
        actions={collections.length > 0 && createButton}
      />
      {error ? (
        <Alert title="Could not load variable collections" description={error} type="error" showIcon />
      ) : (
        <Loading loading={loading} description="Loading variable collections...">
          {renderList()}
        </Loading>
      )}

      <DeleteConfirmationModal
        open={pendingDelete !== null}
        title="Delete variable collection"
        message={
          <>
            The collection <strong>{pendingDelete?.attributes.name}</strong>, its variables and its workspace references
            will be deleted, and its workspaces stop receiving these variables. This cannot be undone.
          </>
        }
        okText="Delete variable collection"
        onConfirm={() => {
          if (pendingDelete) onDelete(pendingDelete.id);
          setPendingDelete(null);
        }}
        onCancel={() => setPendingDelete(null)}
      />
    </div>
  );
};
