import { Table, Typography } from "antd";
import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import WorkspaceStatusTag from "@/modules/workspaces/components/WorkspaceStatusTag";
import { WorkspaceListItem } from "@/modules/workspaces/types";
import workspaceService from "@/modules/workspaces/workspaceService";

type Props = {
  orgid: string;
  projectId: string;
};

export default function ProjectWorkspaces({ orgid, projectId }: Props) {
  const [allWorkspaces, setAllWorkspaces] = useState<WorkspaceListItem[]>([]);
  const [loading, setLoading] = useState(false);

  const assignedWorkspaces = allWorkspaces.filter((ws) => ws.projectId === projectId);

  async function loadWorkspaces() {
    setLoading(true);
    try {
      const result = await workspaceService.listWorkspaces(orgid);
      if (!result.isError) {
        setAllWorkspaces(result.data.workspaces);
      }
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    loadWorkspaces();
  }, [orgid, projectId]);

  const columns = [
    {
      title: "Name",
      dataIndex: "name",
      key: "name",
      render: (name: string, record: WorkspaceListItem) => (
        <Link to={`/organizations/${orgid}/workspaces/${record.id}`}>{name}</Link>
      ),
    },
    {
      title: "Status",
      dataIndex: "lastStatus",
      key: "lastStatus",
      render: (status: WorkspaceListItem["lastStatus"]) => <WorkspaceStatusTag status={status} />,
    },
    {
      title: "Repository",
      dataIndex: "normalizedSource",
      key: "normalizedSource",
      render: (src: string | undefined) => <Typography.Text type="secondary">{src || "—"}</Typography.Text>,
    },
  ];

  return (
    <div style={{ width: "100%" }}>
      <h1>Workspaces</h1>
      <p>Workspaces assigned to this project. To change a workspace&apos;s project, go to the workspace settings.</p>
      <Table
        rowKey="id"
        loading={loading}
        dataSource={assignedWorkspaces}
        columns={columns}
        pagination={{ pageSize: 10, showSizeChanger: true }}
        locale={{ emptyText: "No workspaces assigned to this project yet." }}
      />
    </div>
  );
}
