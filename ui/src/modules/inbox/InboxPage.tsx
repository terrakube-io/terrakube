import { Alert, Button, Card, Modal, Skeleton, Typography, message } from "antd";
import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";
import { EmptyState } from "@/components/feedback/EmptyState";
import { usePolling } from "@/hooks";
import { listOrganizationNames } from "@/modules/navigation/navigationService";
import { approveRuns, findWaitingRuns } from "@/modules/projects/bulkWorkspaceActions";
import { shortAgo } from "@/modules/projects/projectHealth";
import { InboxData, InboxItem, InboxKind, KIND_ORDER, KIND_TITLES, totalCount } from "./inboxModel";
import { loadInbox } from "./inboxService";
import "./Inbox.css";

const POLL_MS = 30000;

function Row({ item, onApprove }: { item: InboxItem; onApprove: (item: InboxItem) => void }) {
  return (
    <li className="inbox-row">
      <span className="inbox-row-main">
        <Link to={`/organizations/${item.orgId}/workspaces/${item.workspaceId}`}>{item.name}</Link>
        <Typography.Text type="secondary" className="inbox-row-where">
          {item.orgName}
          {item.projectName ? ` / ${item.projectName}` : ""}
        </Typography.Text>
      </span>
      <span className="inbox-row-actions">
        <Typography.Text type="secondary">{shortAgo(item.at)}</Typography.Text>
        {item.kind === "waiting" && (
          <Button size="small" type="primary" onClick={() => onApprove(item)}>
            Approve
          </Button>
        )}
        <Link to={`/organizations/${item.orgId}/workspaces/${item.workspaceId}/runs`}>
          {item.kind === "policy" ? "View" : "Open run"}
        </Link>
      </span>
    </li>
  );
}

/** What needs a person, across every organization they can see: approvals first, then failures, then policy. */
export default function InboxPage() {
  const [data, setData] = useState<InboxData | null>(null);
  const [error, setError] = useState(false);
  const [orgCount, setOrgCount] = useState(0);
  const alive = useRef(true);

  const load = useCallback(async () => {
    try {
      const orgs = await listOrganizationNames();
      const next = await loadInbox(orgs);
      if (!alive.current) return;
      setOrgCount(orgs.length);
      setData(next);
      setError(false);
    } catch {
      if (alive.current) setError(true);
    }
  }, []);

  useEffect(() => {
    alive.current = true;
    load();
    return () => {
      alive.current = false;
    };
  }, [load]);

  usePolling(load, { interval: POLL_MS, immediate: false });

  const approve = (item: InboxItem) =>
    Modal.confirm({
      title: `Approve the run waiting on ${item.name}?`,
      content: `${item.orgName}${item.projectName ? ` / ${item.projectName}` : ""}. Approving lets the run go ahead.`,
      okText: "Approve",
      onOk: async () => {
        try {
          const runs = await findWaitingRuns(item.orgId, [item.workspaceId]);
          // A run with a policy violation is for a person to read on its page, never approved from a list.
          const clear = runs.filter((run) => !run.hardViolations && !run.softViolations);
          if (runs.length === 0) {
            message.info("That run is no longer waiting for approval.");
          } else if (clear.length === 0) {
            message.warning(`The run on ${item.name} has policy violations. Open it to review before approving.`);
          } else {
            const outcome = await approveRuns(
              item.orgId,
              clear.map((run) => run.jobId)
            );
            if (outcome.failed.length) message.error(outcome.failed[0].reason);
            else message.success(`Approved the run on ${item.name}`);
          }
        } catch (err: any) {
          message.error(err?.message ?? "The run could not be approved");
        }
        load();
      },
    });

  const total = data ? totalCount(data.counts) : 0;

  return (
    <PageWrapper
      title="Needs attention"
      subTitle="Across the organizations you can see"
      loadingText="Loading..."
      loading={false}
      breadcrumbs={[{ label: "Needs attention", path: "/inbox" }]}
    >
      {error && !data && (
        <Alert
          type="error"
          showIcon
          message="Could not load what needs attention."
          action={
            <Button size="small" onClick={load}>
              Retry
            </Button>
          }
        />
      )}
      {data && data.failedOrganizations.length > 0 && (
        <Alert
          type="warning"
          showIcon
          className="inbox-alert"
          message={`Could not read ${data.failedOrganizations.join(", ")}. What is shown may be incomplete.`}
          action={
            <Button size="small" onClick={load}>
              Retry
            </Button>
          }
        />
      )}
      {!data && !error && <Skeleton active paragraph={{ rows: 6 }} />}
      {data && total === 0 && data.failedOrganizations.length === 0 && (
        <EmptyState description={`Nothing needs you across ${orgCount} organization${orgCount === 1 ? "" : "s"}.`} />
      )}
      {data &&
        total > 0 &&
        KIND_ORDER.map((kind: InboxKind) => {
          const rows = data.items.filter((item) => item.kind === kind);
          if (data.counts[kind] === 0) return null;
          return (
            <Card
              key={kind}
              size="small"
              className="inbox-section"
              title={`${KIND_TITLES[kind]} (${data.counts[kind]})`}
            >
              <ul className="inbox-list">
                {rows.map((item) => (
                  <Row key={item.key} item={item} onApprove={approve} />
                ))}
              </ul>
              {data.counts[kind] > rows.length && (
                <Typography.Text type="secondary" className="inbox-more">
                  Showing the {rows.length} most {kind === "waiting" ? "overdue" : "recent"} of {data.counts[kind]}.
                  Open an organization to see the rest.
                </Typography.Text>
              )}
            </Card>
          );
        })}
    </PageWrapper>
  );
}
