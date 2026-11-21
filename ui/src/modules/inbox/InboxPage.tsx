import { Alert, Button, Card, Segmented, Skeleton, Typography } from "antd";
import { inboxOrigin, rememberWorkspaceOrigin } from "@/modules/workspaces/workspaceOrigin";
import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";
import { EmptyState } from "@/components/feedback/EmptyState";
import { usePolling } from "@/hooks";
import { listOrganizationNames } from "@/modules/navigation/navigationService";
import projectService from "@/modules/projects/projectService";
import { shortAgo } from "@/modules/projects/projectHealth";
import { InboxData, InboxItem, InboxKind, KIND_ORDER, KIND_TITLES, totalCount } from "./inboxModel";
import { confirmApprove } from "./confirmApprove";
import { InboxScope, loadInbox } from "./inboxService";
import "./Inbox.css";

const POLL_MS = 30000;

function Row({ item, onApprove }: { item: InboxItem; onApprove: (item: InboxItem) => void }) {
  return (
    <li className="inbox-row">
      <span className="inbox-row-main">
        <Link
          to={`/organizations/${item.orgId}/workspaces/${item.workspaceId}`}
          onClick={() => rememberWorkspaceOrigin(item.workspaceId, inboxOrigin())}
        >
          {item.name}
        </Link>
        <Typography.Text type="secondary" className="inbox-row-where">
          {item.orgName}
          {item.projectName ? ` / ${item.projectName}` : ""}
          {item.ownerTeam ? ` · ${item.ownerTeam}` : ""}
        </Typography.Text>
      </span>
      <span className="inbox-row-actions">
        <Typography.Text type="secondary">{shortAgo(item.at)}</Typography.Text>
        {item.kind === "waiting" && (
          <Button size="small" type="primary" onClick={() => onApprove(item)}>
            Approve
          </Button>
        )}
        <Link
          to={`/organizations/${item.orgId}/workspaces/${item.workspaceId}/runs${item.runId ? `/${item.runId}` : ""}`}
          onClick={() => rememberWorkspaceOrigin(item.workspaceId, inboxOrigin())}
        >
          {item.kind === "policy" ? "View" : item.kind === "drifted" ? "View plan" : "Open run"}
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
  const [params, setParams] = useSearchParams();
  const mine = params.get("scope") === "mine";
  // The teams the person is in, read once and only when they ask for their own.
  const [groups, setGroups] = useState<string[] | null>(null);

  useEffect(() => {
    if (!mine || groups !== null) return;
    projectService
      .getCurrentGroups()
      .then(setGroups)
      .catch(() => setGroups([]));
  }, [mine, groups]);

  const scope: InboxScope = mine ? { ownerTeams: groups ?? [] } : null;
  const waitingForGroups = mine && groups === null;
  const scopeKey = mine ? (groups ?? []).join("\n") : "";

  const load = useCallback(async () => {
    if (waitingForGroups) return;
    try {
      const orgs = await listOrganizationNames();
      const next = await loadInbox(orgs, scope);
      if (!alive.current) return;
      setOrgCount(orgs.length);
      setData(next);
      setError(false);
    } catch {
      if (alive.current) setError(true);
    }
    // scope is rebuilt on every render; scopeKey says when it really changed.
  }, [waitingForGroups, mine, scopeKey]);

  useEffect(() => {
    alive.current = true;
    load();
    return () => {
      alive.current = false;
    };
  }, [load]);

  usePolling(load, { interval: POLL_MS, immediate: false });

  const approve = (item: InboxItem) => confirmApprove(item, load);

  const total = data ? totalCount(data.counts) : 0;

  return (
    <PageWrapper
      title="Needs attention"
      subTitle="Across the organizations you can see"
      loadingText="Loading..."
      loading={false}
      breadcrumbs={[{ label: "Needs attention", path: "/inbox" }]}
    >
      <Segmented
        className="inbox-scope"
        value={mine ? "mine" : "all"}
        options={[
          { label: "Everything I can see", value: "all" },
          { label: "My teams", value: "mine" },
        ]}
        onChange={(value) => {
          setData(null);
          setParams(value === "mine" ? { scope: "mine" } : {});
        }}
      />
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
        <EmptyState
          description={
            mine
              ? "Nothing needs your teams. Projects without an owner only show under Everything I can see."
              : `Nothing needs you across ${orgCount} organization${orgCount === 1 ? "" : "s"}.`
          }
        />
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
                  Showing the {rows.length} most{" "}
                  {kind === "waiting" ? "overdue" : kind === "drifted" ? "long-standing" : "recent"} of{" "}
                  {data.counts[kind]}. Open an organization to see the rest.
                </Typography.Text>
              )}
            </Card>
          );
        })}
    </PageWrapper>
  );
}
