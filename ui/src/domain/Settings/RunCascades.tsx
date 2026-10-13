import { ReloadOutlined } from "@ant-design/icons";
import { Button, Flex, Popconfirm, Select, Table, Tooltip, Typography, message } from "antd";
import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import CascadeStatusTag from "@/components/display/CascadeStatusTag/CascadeStatusTag";
import { AccessDeniedAlert } from "@/components/feedback/AccessDeniedAlert";
import { EmptyState } from "@/components/feedback/EmptyState";
import { Loading } from "@/components/feedback/Loading";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { RunCascadeRow, RunCascadeStatus } from "@/domain/types";
import {
  cancelCascade,
  getAdminErrorMessage,
  getCascadeCapabilities,
  listCascadesForOrganization,
} from "@/modules/cascades/cascadeService";
import "./Settings.css";
import "./TeamsTagsVariables.css";

type StatusView = "attention" | "running" | "all";

const STATUS_VIEWS: Record<StatusView, { label: string; statuses?: RunCascadeStatus[] }> = {
  attention: { label: "Needs attention", statuses: [RunCascadeStatus.Blocked, RunCascadeStatus.Degraded] },
  running: { label: "Running", statuses: [RunCascadeStatus.Running] },
  all: { label: "All" },
};

// A cascade in one of these no longer dispatches anything on its own; only an operator can still change it.
const CANCELLABLE_STATUSES = [RunCascadeStatus.Running, RunCascadeStatus.Degraded, RunCascadeStatus.Blocked];

const REFRESH_MS = 10000;

/** Organization-wide view of run cascades, led by the ones that need an operator. */
export const RunCascadesSettings = () => {
  const { orgid } = useParams();
  const [view, setView] = useState<StatusView>("attention");
  const [cascades, setCascades] = useState<RunCascadeRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [forbidden, setForbidden] = useState(false);
  const [canOperate, setCanOperate] = useState(false);

  const load = useCallback(
    (showLoading = true) => {
      if (!orgid) return;
      if (showLoading) setLoading(true);
      listCascadesForOrganization(orgid, STATUS_VIEWS[view].statuses)
        .then((rows) => {
          setCascades(rows);
          setForbidden(false);
        })
        .catch((err) => {
          if (err?.response?.status === 403) {
            setForbidden(true);
          } else {
            message.error(`Could not load run cascades: ${getAdminErrorMessage(err)}`);
          }
        })
        .finally(() => setLoading(false));
    },
    [orgid, view]
  );

  useEffect(() => {
    load();
    const timer = window.setInterval(() => load(false), REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [load]);

  useEffect(() => {
    let active = true;
    getCascadeCapabilities(orgid)
      .then(({ operate }) => active && setCanOperate(operate))
      .catch(() => active && setCanOperate(false));
    return () => {
      active = false;
    };
  }, [orgid]);

  const onCancel = (cascadeId: string) => {
    cancelCascade(cascadeId)
      .then(() => {
        message.success("Cascade cancelled");
        load();
      })
      .catch((err) => message.error(`Could not cancel the cascade: ${getAdminErrorMessage(err)}`));
  };

  const columns = [
    {
      title: "Started from",
      key: "workspace",
      render: (_: unknown, record: RunCascadeRow) =>
        record.originWorkspaceId ? (
          <Link to={`/organizations/${orgid}/workspaces/${record.originWorkspaceId}`}>
            {record.originWorkspaceName ?? record.originWorkspaceId}
          </Link>
        ) : (
          `Job #${record.originJobId}`
        ),
    },
    {
      title: "Status",
      key: "status",
      render: (_: unknown, record: RunCascadeRow) => <CascadeStatusTag status={record.status} />,
    },
    {
      title: "Started",
      key: "createdDate",
      render: (_: unknown, record: RunCascadeRow) =>
        record.createdDate ? new Date(record.createdDate).toLocaleString() : "-",
    },
    {
      title: <span className="settings-list-sr-only">Actions</span>,
      key: "actions",
      align: "right" as const,
      render: (_: unknown, record: RunCascadeRow) => (
        <Flex gap="small" justify="flex-end">
          {record.originWorkspaceId && (
            <Link to={`/organizations/${orgid}/workspaces/${record.originWorkspaceId}/cascades/${record.id}`}>
              <Button>View</Button>
            </Link>
          )}
          {CANCELLABLE_STATUSES.includes(record.status) && (
            <Popconfirm
              okButtonProps={{ danger: true }}
              title="Stop this cascade?"
              description="No further workspace is started by it. Runs already in progress are not cancelled."
              okText="Stop"
              cancelText="Keep running"
              disabled={!canOperate}
              onConfirm={() => onCancel(record.id)}
            >
              <Tooltip title={canOperate ? undefined : "Requires the manage job permission"}>
                <Button danger disabled={!canOperate}>
                  Stop future runs
                </Button>
              </Tooltip>
            </Popconfirm>
          )}
        </Flex>
      ),
    },
  ];

  if (forbidden) {
    return <AccessDeniedAlert />;
  }

  return (
    <div className="setting">
      <SettingsPageHeader
        title="Run cascades"
        description="Changes that propagated through workspace dependencies. Open one to see which workspaces it reached and to retry or resume a stuck run."
        divider={false}
        actions={
          <Flex gap="small" wrap>
            <Select
              aria-label="Cascade status"
              value={view}
              onChange={setView}
              options={(Object.keys(STATUS_VIEWS) as StatusView[]).map((key) => ({
                value: key,
                label: STATUS_VIEWS[key].label,
              }))}
            />
            <Button icon={<ReloadOutlined />} onClick={() => load()}>
              Refresh
            </Button>
          </Flex>
        }
      />
      <Loading loading={loading} description="Loading run cascades...">
        {cascades.length === 0 ? (
          <EmptyState
            simple
            description={
              view === "attention" ? "No cascades need attention." : "No run cascades match this filter yet."
            }
          />
        ) : (
          <section>
            <Typography.Title level={4} className="settings-list-count">
              Run cascades ({cascades.length})
            </Typography.Title>
            <Table
              dataSource={cascades}
              columns={columns}
              rowKey="id"
              scroll={{ x: "max-content" }}
              pagination={{ pageSize: 10, hideOnSinglePage: true }}
            />
          </section>
        )}
      </Loading>
    </div>
  );
};
