import { Button, Popconfirm, Table, Tag, Typography, message } from "antd";
import { useCallback, useEffect, useState } from "react";
import { AccessDeniedAlert } from "@/components/feedback/AccessDeniedAlert";
import {
  getAdminErrorMessage,
  listRunTriggerEvents,
  replayRunTriggerEvent,
  RunTriggerEventSummary,
} from "@/modules/admin/runTriggerEventService";

/**
 * Operator visibility into the durable run trigger event queue's permanently FAILED rows -
 * RunTriggerEventOperationsController had a replay action with no UI to call it from until now.
 * Instance-admin only on the backend; a non-admin gets the controller's own 403, rendered the
 * same way any other permission error does, rather than a dedicated client-side admin check.
 */
export const FailedRunTriggerEvents = () => {
  const [events, setEvents] = useState<RunTriggerEventSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [forbidden, setForbidden] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    listRunTriggerEvents()
      .then((result) => {
        setEvents(result);
        setForbidden(false);
      })
      .catch((err) => {
        if (err?.response?.status === 403) {
          setForbidden(true);
        } else {
          message.error(getAdminErrorMessage(err));
        }
      })
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const onReplay = (eventId: string) => {
    replayRunTriggerEvent(eventId)
      .then((result) => {
        message.success(result.rearmed ? "Event replayed" : "Nothing to replay - it already retried on its own");
        load();
      })
      .catch((err) => message.error(getAdminErrorMessage(err)));
  };

  if (forbidden) {
    return <AccessDeniedAlert />;
  }

  const columns = [
    {
      title: "Job",
      key: "job",
      render: (_: string, record: RunTriggerEventSummary) => `#${record.jobId} (${record.workspaceName})`,
    },
    {
      title: "Status",
      key: "status",
      render: (_: string, record: RunTriggerEventSummary) => <Tag color="red">{record.status}</Tag>,
    },
    {
      title: "Attempts",
      key: "attemptCount",
      render: (_: string, record: RunTriggerEventSummary) => record.attemptCount,
    },
    {
      title: "Last error",
      key: "lastError",
      render: (_: string, record: RunTriggerEventSummary) => record.lastError ?? "-",
    },
    {
      title: "Created",
      key: "createdDate",
      render: (_: string, record: RunTriggerEventSummary) =>
        record.createdDate ? new Date(record.createdDate).toLocaleString() : "-",
    },
    {
      title: "Actions",
      key: "action",
      render: (_: string, record: RunTriggerEventSummary) => (
        <Popconfirm
          title="Rearm this event for another attempt. Are you sure?"
          okText="Yes"
          cancelText="No"
          onConfirm={() => onReplay(record.id)}
        >
          <Button type="link">Replay</Button>
        </Popconfirm>
      ),
    },
  ];

  return (
    <div>
      <Typography.Title level={2} style={{ margin: 0 }}>
        Failed Run Trigger Events
      </Typography.Title>
      <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
        Durable run trigger events that exhausted their retry attempts. Replaying one rearms it for another attempt
        immediately, rather than waiting for an operator to fix the underlying problem first.
      </Typography.Paragraph>
      <Table
        dataSource={events}
        columns={columns}
        rowKey="id"
        loading={loading}
        pagination={false}
        locale={{ emptyText: "No failed run trigger events." }}
      />
    </div>
  );
};

export default FailedRunTriggerEvents;
