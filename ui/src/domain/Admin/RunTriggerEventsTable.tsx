import { ReloadOutlined, RedoOutlined } from "@ant-design/icons";
import { Button, Flex, Popconfirm, Table, Tag, Tooltip, Typography, message } from "antd";
import { useCallback, useEffect, useState } from "react";
import { AccessDeniedAlert } from "@/components/feedback/AccessDeniedAlert";
import { EmptyState } from "@/components/feedback/EmptyState";
import { Loading } from "@/components/feedback/Loading";
import {
  getAdminErrorMessage,
  listRunTriggerEvents,
  replayRunTriggerEvent,
  RunTriggerEventSummary,
} from "@/modules/admin/runTriggerEventService";

type Props = {
  /** Limit to one organization; leave out for the instance-wide list (instance admins only). */
  organizationId?: string;
  /** Whether the caller may replay events; the API enforces the same rule. */
  canReplay: boolean;
};

/** Durable run trigger events that exhausted their retries, with a replay action. */
export const RunTriggerEventsTable = ({ organizationId, canReplay }: Props) => {
  const [events, setEvents] = useState<RunTriggerEventSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [forbidden, setForbidden] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    listRunTriggerEvents(undefined, organizationId)
      .then((result) => {
        setEvents(result);
        setForbidden(false);
      })
      .catch((err) => {
        if (err?.response?.status === 403) {
          setForbidden(true);
        } else {
          // Keyed so a repeated load (React strict-mode remount, a refresh) updates one toast, not stack two.
          message.error({
            key: "run-trigger-events-load",
            content: `Could not load run trigger events: ${getAdminErrorMessage(err)}`,
          });
        }
      })
      .finally(() => setLoading(false));
  }, [organizationId]);

  useEffect(() => {
    load();
  }, [load]);

  const onReplay = (event: RunTriggerEventSummary) => {
    const eventOrganizationId = event.organizationId ?? organizationId;
    if (!eventOrganizationId) return;
    replayRunTriggerEvent(eventOrganizationId, event.id)
      .then((result) => {
        message.success(result.rearmed ? "Event replayed" : "Nothing to replay - it already retried on its own");
        load();
      })
      .catch((err) => message.error(`Could not replay the event: ${getAdminErrorMessage(err)}`));
  };

  if (forbidden) {
    return <AccessDeniedAlert />;
  }

  const columns = [
    {
      title: "Job",
      key: "job",
      render: (_: unknown, record: RunTriggerEventSummary) => `#${record.jobId} (${record.workspaceName})`,
    },
    ...(organizationId
      ? []
      : [
          {
            title: "Organization",
            key: "organization",
            render: (_: unknown, record: RunTriggerEventSummary) => record.organizationName ?? "-",
          },
        ]),
    {
      title: "Status",
      key: "status",
      render: (_: unknown, record: RunTriggerEventSummary) => <Tag color="red">{record.status}</Tag>,
    },
    {
      title: "Attempts",
      key: "attemptCount",
      render: (_: unknown, record: RunTriggerEventSummary) => record.attemptCount,
    },
    {
      title: "Last error",
      key: "lastError",
      render: (_: unknown, record: RunTriggerEventSummary) => record.lastError ?? "-",
    },
    {
      title: "Created",
      key: "createdDate",
      render: (_: unknown, record: RunTriggerEventSummary) =>
        record.createdDate ? new Date(record.createdDate).toLocaleString() : "-",
    },
    {
      title: <span className="settings-list-sr-only">Actions</span>,
      key: "action",
      align: "right" as const,
      render: (_: unknown, record: RunTriggerEventSummary) => (
        <Popconfirm
          title="Rearm this event for another attempt?"
          description="It is retried straight away, without waiting for the underlying problem to be fixed."
          okText="Replay"
          cancelText="Cancel"
          disabled={!canReplay}
          onConfirm={() => onReplay(record)}
        >
          <Tooltip title={canReplay ? "Replay event" : "Requires the manage job permission"}>
            <Button icon={<RedoOutlined />} disabled={!canReplay} aria-label={`Replay event for job ${record.jobId}`}>
              Replay
            </Button>
          </Tooltip>
        </Popconfirm>
      ),
    },
  ];

  return (
    <Loading loading={loading} description="Loading run trigger events...">
      {events.length === 0 ? (
        <EmptyState simple description="No failed run trigger events.">
          <Button icon={<ReloadOutlined />} onClick={load}>
            Refresh
          </Button>
        </EmptyState>
      ) : (
        <section>
          <Flex justify="space-between" align="center" wrap gap="small">
            <Typography.Title level={4} className="settings-list-count">
              Failed events ({events.length})
            </Typography.Title>
            <Button icon={<ReloadOutlined />} onClick={load}>
              Refresh
            </Button>
          </Flex>
          <Table
            dataSource={events}
            columns={columns}
            rowKey="id"
            scroll={{ x: "max-content" }}
            pagination={{ pageSize: 10, hideOnSinglePage: true }}
          />
        </section>
      )}
    </Loading>
  );
};

export default RunTriggerEventsTable;
