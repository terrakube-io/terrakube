import { NodeIndexOutlined } from "@ant-design/icons";
import { Button, Flex, Popconfirm, Table, Typography, message } from "antd";
import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { LinkButton } from "@/components/navigation/LinkButton";
import CascadeStatusTag from "@/components/display/CascadeStatusTag/CascadeStatusTag";
import { RunCascadeRow, RunCascadeStatus } from "@/domain/types";
import {
  cancelCascade,
  getCascadeCapabilities,
  getAdminErrorMessage,
  listCascadesForWorkspace,
} from "@/modules/cascades/cascadeService";

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
  const [canOperate, setCanOperate] = useState(false);

  const loadCascades = useCallback(
    (showLoading = true) => {
      if (showLoading) setLoading(true);
      listCascadesForWorkspace(workspaceId)
        .then(setCascades)
        .catch((err) => {
          // Keyed, and quiet for background refreshes, so a failing poll cannot stack toasts.
          if (showLoading) message.error({ key: "workspace-cascades-load", content: getAdminErrorMessage(err) });
        })
        .finally(() => setLoading(false));
    },
    [workspaceId]
  );

  useEffect(() => {
    loadCascades();
    const timer = window.setInterval(() => loadCascades(false), 5000);
    return () => window.clearInterval(timer);
  }, [loadCascades]);

  useEffect(() => {
    let active = true;
    getCascadeCapabilities(organizationId)
      .then(({ operate }) => {
        if (active) setCanOperate(operate);
      })
      .catch(() => {
        if (active) setCanOperate(false);
      });
    return () => {
      active = false;
    };
  }, [organizationId]);

  const onCancel = (cascadeId: string) => {
    cancelCascade(organizationId, cascadeId)
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
          {canOperate && CANCELLABLE_STATUSES.includes(record.status) && (
            <Popconfirm
              okButtonProps={{ danger: true }}
              title="This stops the cascade from dispatching any further workspace. Are you sure?"
              okText="Yes"
              cancelText="No"
              onConfirm={() => onCancel(record.id)}
            >
              <Button type="link" danger>
                Stop future runs
              </Button>
            </Popconfirm>
          )}
        </>
      ),
    },
  ];

  return (
    <div>
      <Flex justify="space-between" align="flex-start" wrap gap="small">
        <div>
          <Typography.Title level={2} className="workspace-flush-title">
            Run cascades
          </Typography.Title>
          <Typography.Paragraph type="secondary">
            Executions in which <b>{workspaceName}</b> participates, including changes started by an upstream workspace.
          </Typography.Paragraph>
        </div>
        <LinkButton to={`/organizations/${organizationId}/cascades`} icon={<NodeIndexOutlined />}>
          All run cascades
        </LinkButton>
      </Flex>
      <Table
        dataSource={cascades}
        columns={columns}
        rowKey="id"
        loading={loading}
        pagination={false}
        locale={{ emptyText: "This workspace has not participated in a run cascade yet." }}
      />
    </div>
  );
};
