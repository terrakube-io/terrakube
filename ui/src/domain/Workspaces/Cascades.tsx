import { Button, Popconfirm, Table, Typography, message } from "antd";
import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import CascadeStatusTag from "@/components/display/CascadeStatusTag/CascadeStatusTag";
import { RunCascadeRow, RunCascadeStatus } from "@/domain/types";
import { cancelCascade, getAdminErrorMessage, listCascadesForWorkspace } from "@/modules/cascades/cascadeService";

type Props = {
  organizationId: string;
  workspaceId: string;
  workspaceName: string;
};

// A cascade past this point no longer dispatches anything further on its own - only an operator
// cancelling it, or retrying/resuming an individual node, can still change it.
const CANCELLABLE_STATUSES = [RunCascadeStatus.Running, RunCascadeStatus.Degraded, RunCascadeStatus.Blocked];

export const Cascades = ({ organizationId, workspaceId, workspaceName }: Props) => {
  const [cascades, setCascades] = useState<RunCascadeRow[]>([]);
  const [loading, setLoading] = useState(true);

  const loadCascades = useCallback(() => {
    setLoading(true);
    listCascadesForWorkspace(workspaceId)
      .then(setCascades)
      .catch((err) => message.error(getAdminErrorMessage(err)))
      .finally(() => setLoading(false));
  }, [workspaceId]);

  useEffect(() => {
    loadCascades();
  }, [loadCascades]);

  const onCancel = (cascadeId: string) => {
    cancelCascade(cascadeId)
      .then(() => {
        message.success("Cascade cancelled");
        loadCascades();
      })
      .catch((err) => message.error(getAdminErrorMessage(err)));
  };

  const columns = [
    {
      title: "Status",
      key: "status",
      render: (_: string, record: RunCascadeRow) => <CascadeStatusTag status={record.status} />,
    },
    {
      title: "Started by job",
      key: "originJob",
      render: (_: string, record: RunCascadeRow) => `#${record.originJobId}`,
    },
    {
      title: "Started",
      key: "createdDate",
      render: (_: string, record: RunCascadeRow) =>
        record.createdDate ? new Date(record.createdDate).toLocaleString() : "-",
    },
    {
      title: "Actions",
      key: "action",
      render: (_: string, record: RunCascadeRow) => (
        <>
          <Link to={`/organizations/${organizationId}/workspaces/${workspaceId}/cascades/${record.id}`}>
            <Button type="link">View</Button>
          </Link>
          {CANCELLABLE_STATUSES.includes(record.status) && (
            <Popconfirm
              okButtonProps={{ danger: true }}
              title="This stops the cascade from dispatching any further workspace. Are you sure?"
              okText="Yes"
              cancelText="No"
              onConfirm={() => onCancel(record.id)}
            >
              <Button type="link" danger>
                Cancel
              </Button>
            </Popconfirm>
          )}
        </>
      ),
    },
  ];

  return (
    <div>
      <Typography.Title level={2} style={{ margin: 0 }}>
        Cascades
      </Typography.Title>
      <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
        Every run that started from a job on <b>{workspaceName}</b> and propagated to dependent workspaces through a run
        trigger.
      </Typography.Paragraph>
      <Table
        dataSource={cascades}
        columns={columns}
        rowKey="id"
        loading={loading}
        pagination={false}
        locale={{ emptyText: "No cascade has started from this workspace yet." }}
      />
    </div>
  );
};
