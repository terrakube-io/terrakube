import { ReloadOutlined } from "@ant-design/icons";
import { Button, Flex, Popconfirm, Select, Table, Tabs, Tooltip, message } from "antd";
import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import CascadeStatusTag from "@/components/display/CascadeStatusTag/CascadeStatusTag";
import { AccessDeniedAlert } from "@/components/feedback/AccessDeniedAlert";
import { EmptyState } from "@/components/feedback/EmptyState";
import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";
import { ORGANIZATION_NAME } from "@/config/actionTypes";
import { RunCascadeRow, RunCascadeStatus } from "@/domain/types";
import {
  cancelCascade,
  getAdminErrorMessage,
  getCascadeCapabilities,
  listCascadesForOrganization,
} from "@/modules/cascades/cascadeService";
import { RunTriggerEventsTable } from "../Admin/RunTriggerEventsTable";
import "../Settings/Settings.css";

type StatusView = "attention" | "running" | "all";

const STATUS_VIEWS: Record<StatusView, { label: string; statuses?: RunCascadeStatus[] }> = {
  attention: { label: "Needs attention", statuses: [RunCascadeStatus.Blocked, RunCascadeStatus.Degraded] },
  running: { label: "Running", statuses: [RunCascadeStatus.Running] },
  all: { label: "All" },
};

// A cascade in one of these no longer dispatches anything on its own; only an operator can still change it.
const CANCELLABLE_STATUSES = [RunCascadeStatus.Running, RunCascadeStatus.Degraded, RunCascadeStatus.Blocked];

const REFRESH_MS = 10000;

type PanelProps = { organizationId: string; canOperate: boolean };

/** The organization's cascades, opening on the ones that need an operator. */
const CascadesPanel = ({ organizationId, canOperate }: PanelProps) => {
  const [view, setView] = useState<StatusView>("attention");
  const [cascades, setCascades] = useState<RunCascadeRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [forbidden, setForbidden] = useState(false);

  const load = useCallback(
    (background = false) => {
      if (!background) setLoading(true);
      listCascadesForOrganization(organizationId, STATUS_VIEWS[view].statuses)
        .then((rows) => {
          setCascades(rows);
          setForbidden(false);
        })
        .catch((err) => {
          if (err?.response?.status === 403) {
            setForbidden(true);
          } else if (!background) {
            // Keyed so a repeated load (React strict-mode remount, a refresh) updates one toast, not stack two.
            // Background refreshes stay quiet: a failed poll is not worth a toast every few seconds.
            message.error({
              key: "organization-cascades-load",
              content: `Could not load run cascades: ${getAdminErrorMessage(err)}`,
            });
          }
        })
        .finally(() => setLoading(false));
    },
    [organizationId, view]
  );

  useEffect(() => {
    load();
    const timer = window.setInterval(() => load(true), REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [load]);

  const onCancel = (cascadeId: string) => {
    cancelCascade(organizationId, cascadeId)
      .then(() => {
        message.success("Cascade cancelled");
        load();
      })
      .catch((err) => message.error(`Could not cancel the cascade: ${getAdminErrorMessage(err)}`));
  };

  if (forbidden) {
    return <AccessDeniedAlert />;
  }

  const columns = [
    {
      title: "Started from",
      key: "workspace",
      render: (_: unknown, record: RunCascadeRow) =>
        record.originWorkspaceId ? (
          <Link to={`/organizations/${organizationId}/workspaces/${record.originWorkspaceId}`}>
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
            <Link to={`/organizations/${organizationId}/workspaces/${record.originWorkspaceId}/cascades/${record.id}`}>
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

  return (
    <>
      <Flex gap="small" wrap justify="flex-end" style={{ marginBottom: 16 }}>
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
      <Table
        dataSource={cascades}
        columns={columns}
        rowKey="id"
        loading={loading}
        scroll={{ x: "max-content" }}
        pagination={{ pageSize: 10, hideOnSinglePage: true }}
        locale={{
          emptyText: (
            <EmptyState
              simple
              description={view === "attention" ? "No cascades need attention." : "No run cascades match this filter."}
            />
          ),
        }}
      />
    </>
  );
};

/**
 * Where an organization's run cascades are followed and operated: the cascades themselves, and the
 * trigger events that failed to start one. Operating needs manage job in the organization (or instance
 * admin); anyone who can see the organization's workspaces can look.
 */
export const OrganizationCascades = () => {
  const { id: organizationId } = useParams<{ id: string }>();
  const [canOperate, setCanOperate] = useState(false);
  const organizationName = sessionStorage.getItem(ORGANIZATION_NAME) ?? "Organization";

  useEffect(() => {
    let active = true;
    getCascadeCapabilities(organizationId)
      .then(({ operate }) => active && setCanOperate(operate))
      .catch(() => active && setCanOperate(false));
    return () => {
      active = false;
    };
  }, [organizationId]);

  if (!organizationId) {
    return null;
  }

  return (
    <PageWrapper
      title="Run cascades"
      subTitle="Changes that propagated through workspace dependencies. Open one to see which workspaces it reached and to retry or resume a stuck run."
      breadcrumbs={[
        { label: organizationName, path: `/organizations/${organizationId}/workspaces` },
        { label: "Run cascades" },
      ]}
    >
      <Tabs
        items={[
          {
            key: "cascades",
            label: "Cascades",
            children: <CascadesPanel organizationId={organizationId} canOperate={canOperate} />,
          },
          {
            key: "events",
            label: "Failed trigger events",
            children: <RunTriggerEventsTable organizationId={organizationId} canReplay={canOperate} />,
          },
        ]}
      />
    </PageWrapper>
  );
};

export default OrganizationCascades;
