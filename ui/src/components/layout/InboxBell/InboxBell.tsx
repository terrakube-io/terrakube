import { BellOutlined } from "@ant-design/icons";
import { Badge, Button, Popover, Typography } from "antd";
import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { usePolling } from "@/hooks";
import { confirmApprove } from "@/modules/inbox/confirmApprove";
import { InboxData, InboxItem, InboxKind, totalCount } from "@/modules/inbox/inboxModel";
import { loadInbox } from "@/modules/inbox/inboxService";
import { listOrganizationNames } from "@/modules/navigation/navigationService";
import { shortAgo } from "@/modules/projects/projectHealth";
import { inboxOrigin, rememberWorkspaceOrigin } from "@/modules/workspaces/workspaceOrigin";
import "./InboxBell.css";

const POLL_MS = 60000;
const SHOWN = 6;

const KIND_LABEL: Record<InboxKind, string> = {
  waiting: "Waiting for approval",
  failed: "Failed",
  drifted: "Drifted",
  policy: "Policy violation",
};

function Item({ item, onApprove, onNavigate }: { item: InboxItem; onApprove: () => void; onNavigate: () => void }) {
  return (
    <li className="inbox-bell-item">
      <span className="inbox-bell-item-main">
        <Link
          to={`/organizations/${item.orgId}/workspaces/${item.workspaceId}`}
          onClick={() => {
            rememberWorkspaceOrigin(item.workspaceId, inboxOrigin());
            onNavigate();
          }}
        >
          {item.name}
        </Link>
        <Typography.Text type="secondary" className="inbox-bell-item-sub">
          {KIND_LABEL[item.kind]}
          {" · "}
          {item.orgName}
          {item.projectName ? ` / ${item.projectName}` : ""}
          {item.at ? ` · ${shortAgo(item.at)}` : ""}
        </Typography.Text>
      </span>
      {item.kind === "waiting" && (
        <Button size="small" type="primary" onClick={onApprove}>
          Approve
        </Button>
      )}
    </li>
  );
}

/**
 * What needs a person, one click from any page: the count of items across the organizations they can see, and the
 * first few of them with the approval a click away. The full page stays at /inbox.
 */
export const InboxBell = () => {
  const [data, setData] = useState<InboxData | null>(null);
  const [failed, setFailed] = useState(false);
  const [open, setOpen] = useState(false);
  const [shift, setShift] = useState(0);
  const alive = useRef(true);
  const buttonRef = useRef<HTMLButtonElement>(null);

  const load = useCallback(async () => {
    try {
      const orgs = await listOrganizationNames();
      const next = await loadInbox(orgs);
      if (!alive.current) return;
      setData(next);
      setFailed(false);
    } catch {
      if (alive.current) setFailed(true);
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

  const total = data ? totalCount(data.counts) : 0;
  const shown = data?.items.slice(0, SHOWN) ?? [];
  const unreadable = failed || (data?.failedOrganizations.length ?? 0) > 0;

  const content = (
    <div className="inbox-bell-panel">
      <div className="inbox-bell-head">
        <strong>Needs attention</strong>
        <Link to="/inbox" onClick={() => setOpen(false)}>
          View all{total > shown.length ? ` ${total}` : ""}
        </Link>
      </div>
      {unreadable && (
        <Typography.Text type="warning" className="inbox-bell-warning">
          {data ? "Some organizations could not be read." : "Could not load what needs attention."}
        </Typography.Text>
      )}
      {data && shown.length === 0 && !unreadable && (
        <Typography.Text type="secondary">Nothing needs attention.</Typography.Text>
      )}
      {shown.length > 0 && (
        <ul className="inbox-bell-list">
          {shown.map((item) => (
            <Item
              key={item.key}
              item={item}
              onNavigate={() => setOpen(false)}
              onApprove={() => {
                setOpen(false);
                confirmApprove(item, load);
              }}
            />
          ))}
        </ul>
      )}
    </div>
  );

  return (
    <Popover
      trigger="click"
      placement="bottomRight"
      arrow={false}
      open={open}
      // Opens to the right of the sidebar, not over its menu: the panel's left edge is moved to the sidebar's edge.
      align={{ points: ["tl", "bl"], offset: [shift, 6], overflow: { adjustX: true, adjustY: true } }}
      onOpenChange={(next) => {
        const button = buttonRef.current;
        const sidebar = button?.closest(".app-sidebar");
        if (next && button && sidebar) {
          setShift(Math.max(0, sidebar.getBoundingClientRect().right - button.getBoundingClientRect().left + 8));
        }
        setOpen(next);
        if (next) load();
      }}
      content={content}
    >
      <Badge
        count={total}
        overflowCount={99}
        size="small"
        offset={[2, 0]}
        dot={total === 0 && unreadable}
        className="inbox-bell-badge"
      >
        <Button
          ref={buttonRef}
          type="text"
          size="small"
          icon={<BellOutlined />}
          aria-label={total > 0 ? `Needs attention, ${total} ${total === 1 ? "item" : "items"}` : "Needs attention"}
          className="inbox-bell app-sidebar-search"
        />
      </Badge>
    </Popover>
  );
};
