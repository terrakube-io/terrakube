import { Alert, Button, Flex, Popconfirm, Select, Table, Typography, message } from "antd";
import { useCallback, useEffect, useState } from "react";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import projectService, { ProjectCollection } from "./projectService";

type Props = {
  orgid: string;
  projectId: string;
  canManage: boolean;
};

/**
 * Variable collections shared by every workspace in the project. A workspace's own variables and collections
 * attached to the workspace still win over these, and these win over organization wide collections.
 */
export default function ProjectVariablesTab({ orgid, projectId, canManage }: Props) {
  const [collections, setCollections] = useState<ProjectCollection[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [toAttach, setToAttach] = useState<string>();

  const load = useCallback(async () => {
    setLoading(true);
    const response = await projectService.listCollectionsForProject(orgid, projectId);
    if (response.isError) setError(response.error?.message ?? "Failed to load variable collections");
    else {
      setError(undefined);
      setCollections(response.data ?? []);
    }
    setLoading(false);
  }, [orgid, projectId]);

  useEffect(() => {
    load();
  }, [load]);

  const attached = collections.filter((collection) => collection.referenceId);
  const available = collections.filter((collection) => !collection.referenceId);

  const attach = async () => {
    if (!toAttach) return;
    try {
      await projectService.attachCollectionToProject(orgid, projectId, toAttach);
      message.success("Collection attached to the project");
      setToAttach(undefined);
      load();
    } catch (err: any) {
      message.error(
        err?.response?.status === 403 ? "You need Manage Collections to attach a collection" : err?.message
      );
    }
  };

  const detach = async (collection: ProjectCollection) => {
    try {
      await projectService.detachCollectionFromProject(orgid, collection.id, collection.referenceId!);
      message.success("Collection detached");
      load();
    } catch (err: any) {
      message.error(
        err?.response?.status === 403 ? "You need Manage Collections to detach a collection" : err?.message
      );
    }
  };

  return (
    <div className="project-settings-panel">
      <SettingsPageHeader
        title="Variables"
        description="Variable collections attached here apply to every workspace in this project."
      />
      {error && <Alert type="error" showIcon message={error} className="project-overview-alert" />}
      {canManage && (
        <Flex gap={8} className="project-bulk-bar">
          <Select
            showSearch
            optionFilterProp="label"
            placeholder="Attach a variable collection"
            aria-label="Collection to attach"
            style={{ minWidth: 280 }}
            value={toAttach}
            onChange={setToAttach}
            options={available.map((collection) => ({ value: collection.id, label: collection.name }))}
          />
          <Button type="primary" disabled={!toAttach} onClick={attach}>
            Attach
          </Button>
        </Flex>
      )}
      <Table<ProjectCollection>
        rowKey="id"
        loading={loading}
        dataSource={attached}
        pagination={false}
        locale={{ emptyText: "No variable collections are attached to this project." }}
        columns={[
          { title: "Collection", dataIndex: "name", key: "name" },
          { title: "Variables", dataIndex: "variableCount", key: "variableCount", width: 110 },
          { title: "Priority", dataIndex: "priority", key: "priority", width: 100 },
          {
            title: "",
            key: "actions",
            width: 110,
            render: (_: unknown, collection: ProjectCollection) =>
              canManage ? (
                <Popconfirm
                  title="Detach this collection from the project?"
                  okText="Detach"
                  onConfirm={() => detach(collection)}
                >
                  <Button type="link" danger>
                    Detach
                  </Button>
                </Popconfirm>
              ) : null,
          },
        ]}
      />
      <Typography.Paragraph type="secondary" className="project-overview-meta">
        Precedence: workspace variables, then the organization global variables, then collections attached to the
        workspace, then collections attached to this project, then organization wide collections. Within one level the
        collection with the higher priority wins.
      </Typography.Paragraph>
    </div>
  );
}
