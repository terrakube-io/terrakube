import { PlusOutlined } from "@ant-design/icons";
import { Button, Modal, Popconfirm, Select, Table, Typography, message } from "antd";
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
  const [modalOpen, setModalOpen] = useState(false);
  const [selectedWorkspaceId, setSelectedWorkspaceId] = useState<string | undefined>();
  const [assigning, setAssigning] = useState(false);

  const assignedWorkspaces = allWorkspaces.filter((ws) => ws.projectId === projectId);
  const unassignedWorkspaces = allWorkspaces.filter((ws) => !ws.projectId);

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

  const handleAssign = async () => {
    if (!selectedWorkspaceId) return;
    setAssigning(true);
    try {
      await workspaceService.assignWorkspaceToProject(orgid, selectedWorkspaceId, projectId);
      message.success("Workspace added to project");
      setModalOpen(false);
      setSelectedWorkspaceId(undefined);
      await loadWorkspaces();
    } catch (err: any) {
      if (err?.response?.status === 403) {
        message.error(
          <span>
            You are not authorized to assign workspaces to projects. <br /> Please contact your administrator and
            request the <b>Manage Workspaces</b> permission. <br /> For more information, visit the{" "}
            <a
              target="_blank"
              href="https://docs.terrakube.io/user-guide/organizations/team-management"
              rel="noreferrer"
            >
              Terrakube documentation
            </a>
            .
          </span>
        );
      } else {
        message.error("Failed to add workspace to project");
      }
    } finally {
      setAssigning(false);
    }
  };

  const handleRemove = async (workspaceId: string) => {
    try {
      await workspaceService.removeWorkspaceFromProject(orgid, workspaceId);
      message.success("Workspace removed from project");
      await loadWorkspaces();
    } catch (err: any) {
      if (err?.response?.status === 403) {
        message.error(
          <span>
            You are not authorized to remove workspaces from projects. <br /> Please contact your administrator and
            request the <b>Manage Workspaces</b> permission. <br /> For more information, visit the{" "}
            <a
              target="_blank"
              href="https://docs.terrakube.io/user-guide/organizations/team-management"
              rel="noreferrer"
            >
              Terrakube documentation
            </a>
            .
          </span>
        );
      } else {
        message.error("Failed to remove workspace from project");
      }
    }
  };

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
    {
      title: "",
      key: "actions",
      render: (_: unknown, record: WorkspaceListItem) => (
        <Popconfirm
          title="Remove workspace from this project?"
          onConfirm={() => handleRemove(record.id)}
          okText="Yes"
          cancelText="No"
          placement="left"
        >
          <Button danger size="small">
            Remove
          </Button>
        </Popconfirm>
      ),
    },
  ];

  return (
    <div style={{ width: "100%" }}>
      <h1>Workspaces</h1>
      <p>Manage which workspaces belong to this project.</p>
      <Button
        type="primary"
        icon={<PlusOutlined />}
        style={{ marginBottom: 16 }}
        onClick={() => setModalOpen(true)}
        disabled={unassignedWorkspaces.length === 0}
      >
        Add Workspace
      </Button>
      <Table
        rowKey="id"
        loading={loading}
        dataSource={assignedWorkspaces}
        columns={columns}
        pagination={{ pageSize: 10, showSizeChanger: true }}
        locale={{ emptyText: "No workspaces assigned to this project yet." }}
      />
      <Modal
        title="Add workspace to project"
        open={modalOpen}
        onOk={handleAssign}
        onCancel={() => {
          setModalOpen(false);
          setSelectedWorkspaceId(undefined);
        }}
        confirmLoading={assigning}
        okText="Add"
        okButtonProps={{ disabled: !selectedWorkspaceId }}
      >
        <Select
          showSearch
          placeholder="Search workspaces..."
          optionFilterProp="label"
          style={{ width: "100%", marginTop: 8 }}
          value={selectedWorkspaceId}
          onChange={setSelectedWorkspaceId}
          options={unassignedWorkspaces.map((ws) => ({ label: ws.name, value: ws.id }))}
        />
      </Modal>
    </div>
  );
}
