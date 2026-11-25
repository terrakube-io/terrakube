import { BellOutlined } from "@ant-design/icons";
import { Badge, Button, Popover, Typography } from "antd";
import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { ORGANIZATION_ARCHIVE } from "@/config/actionTypes";
import { getOrgIdFromPathname, isOrgId } from "@/config/orgId";
import { usePolling } from "@/hooks";
import { useOrganizationName } from "@/hooks/useOrganizationName";
import { confirmApprove } from "@/modules/inbox/confirmApprove";
import { InboxData, InboxItem, InboxKind, totalCount } from "@/modules/inbox/inboxModel";
import { loadInbox, loadOrganizationCounts } from "@/modules/inbox/inboxService";
import { listOrganizationNames } from "@/modules/navigation/navigationService";
import { shortAgo } from "@/modules/projects/projectHealth";
import { inboxOrigin, rememberWorkspaceOrigin } from "@/modules/workspaces/workspaceOrigin";
import "./InboxBell.css";

const POLL_MS = 60000;
const SHOWN = 6;
// Counting the other organizations is a request each, so a person in more of them than this is sent to the inbox
// without the numbers.
const MAX_OTHER_ORGANIZATIONS = 20;
const OTHERS_CACHE_MS = 60_000;

type Others = { organizations: number; total: number | null };

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
 * What needs a person, one click from any page: the count for the organization they are working in, and the first few
 * of them with the approval a click away. Only that organization is polled. The others are counted when the panel is
 * opened, so a person in many organizations does not cost the server a request each for every minute the tab is open.
 * The full page stays at /inbox.
 */
export const InboxBell = () => {
  const location = useLocation();
  const stored = sessionStorage.getItem(ORGANIZATION_ARCHIVE);
  const organizationId = getOrgIdFromPathname(location.pathname) ?? (isOrgId(stored) ? stored : null);
  const organizationName = useOrganizationName(organizationId ?? "");
  const [data, setData] = useState<InboxData | null>(null);
  const [others, setOthers] = useState<Others | null>(null);
  const [failed, setFailed] = useState(false);
  const [open, setOpen] = useState(false);
  const [shift, setShift] = useState(0);
  // Each request takes a number and only the newest may show its answer, so a slow one for the organization the
  // person has left cannot overwrite the one they are in.
  const latest = useRef(0);
  const latestOthers = useRef(0);
  const othersCache = useRef<{ organizationId: string | null; at: number; value: Others } | null>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);

  const load = useCallback(async () => {
    if (!organizationId || !organizationName) return;
    const mine = ++latest.current;
    try {
      const next = await loadInbox([{ id: organizationId, name: organizationName }]);
      if (mine !== latest.current) return;
      // loadInbox does not throw for an organization it could not read: it lists it. The bell reads one organization,
      // so that list is the failure of that one, and what is already shown is kept while the warning says so.
      if (next.failedOrganizations.length > 0) {
        setFailed(true);
        return;
      }
      setData(next);
      setFailed(false);
    } catch {
      if (mine === latest.current) setFailed(true);
    }
  }, [organizationId, organizationName]);

  // Another organization starts from nothing: the numbers of the last one are not this one's.
  useEffect(() => {
    setData(null);
    setOthers(null);
    setFailed(false);
    othersCache.current = null;
    return () => {
      latestOthers.current += 1;
    };
  }, [organizationId]);

  useEffect(() => {
    load();
    return () => {
      latest.current += 1;
    };
  }, [load]);

  usePolling(load, { interval: POLL_MS, immediate: false });

  const loadOthers = useCallback(async () => {
    // Opening the panel again within a minute shows what was counted: the numbers do not move that fast.
    const cached = othersCache.current;
    if (cached && cached.organizationId === organizationId && Date.now() - cached.at < OTHERS_CACHE_MS) {
      setOthers(cached.value);
      return;
    }
    const mine = ++latestOthers.current;
    const remember = (value: Others) => {
      othersCache.current = { organizationId, at: Date.now(), value };
      setOthers(value);
    };
    try {
      const everyone = await listOrganizationNames();
      const rest = everyone.filter((organization) => organization.id !== organizationId);
      if (mine !== latestOthers.current) return;
      if (rest.length > MAX_OTHER_ORGANIZATIONS) {
        remember({ organizations: rest.length, total: null });
        return;
      }
      const result = await loadOrganizationCounts(rest);
      if (mine !== latestOthers.current) return;
      const withWork = result.rows.filter((row) => totalCount(row.counts) > 0);
      remember({
        organizations: withWork.length,
        total: withWork.reduce((sum, row) => sum + totalCount(row.counts), 0),
      });
    } catch {
      // The other organizations are an extra: the panel stays useful without them.
    }
  }, [organizationId]);

  const total = data ? totalCount(data.counts) : 0;
  const shown = data?.items.slice(0, SHOWN) ?? [];
  const unreadable = failed;

  const content = (
    <div className="inbox-bell-panel">
      <div className="inbox-bell-head">
        <strong>{organizationName ? `Needs attention in ${organizationName}` : "Needs attention"}</strong>
        <Link to={organizationId ? `/inbox?org=${organizationId}` : "/inbox"} onClick={() => setOpen(false)}>
          View all{total > shown.length ? ` ${total}` : ""}
        </Link>
      </div>
      {unreadable && (
        <Typography.Text type="warning" className="inbox-bell-warning">
          Could not load what needs attention.
        </Typography.Text>
      )}
      {organizationId && data && shown.length === 0 && !unreadable && (
        <Typography.Text type="secondary">Nothing needs attention here.</Typography.Text>
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
      {others && (others.total === null || others.total > 0) && (
        <Link to="/inbox?org=all" className="inbox-bell-others" onClick={() => setOpen(false)}>
          {others.total === null
            ? "Other organizations"
            : `${others.total} more in ${others.organizations} other organization${others.organizations === 1 ? "" : "s"}`}
        </Link>
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
        if (next) {
          load();
          loadOthers();
        }
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
