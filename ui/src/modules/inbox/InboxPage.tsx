import {
  ClockCircleOutlined,
  CloseCircleOutlined,
  SafetyOutlined,
  SearchOutlined,
  SwapOutlined,
} from "@ant-design/icons";
import { Alert, Button, Checkbox, Input, Segmented, Select, Skeleton, Table, Tag, Typography } from "antd";
import type { ColumnsType } from "antd/es/table";
import { ReactNode, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useLocation, useSearchParams } from "react-router-dom";
import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";
import { EmptyState } from "@/components/feedback/EmptyState";
import { ORGANIZATION_ARCHIVE } from "@/config/actionTypes";
import { usePolling } from "@/hooks";
import { listOrganizationNames } from "@/modules/navigation/navigationService";
import projectService from "@/modules/projects/projectService";
import { shortAgo, shortDuration } from "@/modules/projects/projectHealth";
import { inboxOrigin, rememberWorkspaceOrigin } from "@/modules/workspaces/workspaceOrigin";
import { InboxCounts, InboxItem, InboxKind, KIND_ORDER, KIND_TITLES, totalCount } from "./inboxModel";
import { confirmApprove, confirmApproveMany } from "./confirmApprove";
import {
  InboxPage as PageOfItems,
  InboxScope,
  OrganizationCounts,
  loadInboxCounts,
  loadInboxPage,
  loadOrganizationCounts,
} from "./inboxService";
import InboxOrganizations from "./InboxOrganizations";
import "./Inbox.css";

const ALL = "all";
const PAGE_SIZE = 25;
const POLL_MS = 30000;
// The summary of all organizations is one request each. Past this many it is read when asked for, not every 30 s.
const MAX_POLLED_ORGANIZATIONS = 20;
const SEARCH_DELAY_MS = 300;

const KIND_ICON: Record<InboxKind, ReactNode> = {
  waiting: <ClockCircleOutlined />,
  failed: <CloseCircleOutlined />,
  drifted: <SwapOutlined />,
  policy: <SafetyOutlined />,
};

type Organization = { id: string; name: string };

const positive = (value: string | null) => {
  const parsed = parseInt(value ?? "", 10);
  return parsed > 0 ? parsed : 1;
};

/** What happened and for how long, in the words of each kind. */
function Reason({ item }: { item: InboxItem }) {
  const since = shortDuration(item.at);
  switch (item.kind) {
    case "waiting":
      return (
        <Tag color="warning" icon={KIND_ICON.waiting}>
          Waiting {since && since !== "now" ? `${since}` : "just now"}
        </Tag>
      );
    case "failed":
      return (
        <Tag color="error" icon={KIND_ICON.failed}>
          Failed {shortAgo(item.at)}
        </Tag>
      );
    case "drifted":
      return (
        <Tag icon={KIND_ICON.drifted} color="processing">
          Drifted {shortAgo(item.at)}
        </Tag>
      );
    default:
      return (
        <Tag color="purple" icon={KIND_ICON.policy}>
          Non-compliant
        </Tag>
      );
  }
}

/**
 * What needs a person. A rail of the four kinds with their counts, and one kind at a time as a table, read a page of
 * 25 from the server: the same shape as the notifications of a code host, which is what people already know how to use.
 * One organization at a time, because the rows of every organization at once cannot be paged and nobody reads them;
 * with none picked, one line per organization says where the work is.
 */
export default function InboxPage() {
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  const [organizations, setOrganizations] = useState<Organization[] | null>(null);
  const [organizationsFailed, setOrganizationsFailed] = useState(false);
  const [counts, setCounts] = useState<InboxCounts | null>(null);
  const [listed, setListed] = useState<PageOfItems | null>(null);
  const [activeKind, setActiveKind] = useState<InboxKind | null>(null);
  const [loading, setLoading] = useState(false);
  const [summary, setSummary] = useState<{ rows: OrganizationCounts[]; failedOrganizations: string[] } | null>(null);
  const [error, setError] = useState(false);
  const [selected, setSelected] = useState<Map<string, InboxItem>>(new Map());
  const requestId = useRef(0);

  const mine = params.get("scope") === "mine";
  const search = params.get("q") ?? "";
  const page = positive(params.get("page"));
  const kindParam = params.get("kind");
  const askedKind: InboxKind | null = (KIND_ORDER as string[]).includes(kindParam ?? "")
    ? (kindParam as InboxKind)
    : null;
  const [searchText, setSearchText] = useState(search);

  // The teams the person is in, read once and only when they ask for their own.
  const [groups, setGroups] = useState<string[] | null>(null);
  // A lookup that failed is not the same as being in no team: it is shown as an error with a retry, and "My teams"
  // never reads it as an empty list.
  const [groupsFailed, setGroupsFailed] = useState(false);
  useEffect(() => {
    if (!mine || groups !== null || groupsFailed) return;
    let current = true;
    projectService
      .getCurrentGroups()
      .then((found) => current && setGroups(found))
      .catch(() => current && setGroupsFailed(true));
    return () => {
      current = false;
    };
  }, [mine, groups, groupsFailed]);
  const scope: InboxScope = mine ? { ownerTeams: groups ?? [] } : null;
  const waitingForGroups = mine && groups === null;
  const scopeKey = mine ? (groups ?? []).join("\n") : "";

  const loadOrganizations = useCallback(() => {
    setOrganizationsFailed(false);
    listOrganizationNames()
      .then(setOrganizations)
      .catch(() => setOrganizationsFailed(true));
  }, []);
  useEffect(loadOrganizations, [loadOrganizations]);

  const update = useCallback(
    (patch: Record<string, string | number | null>, replace = false) =>
      setParams(
        (previous) => {
          const next = new URLSearchParams(previous);
          Object.entries(patch).forEach(([key, value]) => {
            if (value === null || value === "" || (key === "page" && value === 1)) next.delete(key);
            else next.set(key, String(value));
          });
          return next;
        },
        { replace }
      ),
    [setParams]
  );

  // The organization asked for in the address; otherwise the one the person was last in, or the only one they have.
  const selectedOrganizationId = useMemo(() => {
    if (!organizations) return null;
    const asked = params.get("org");
    if (asked === ALL) return ALL;
    if (asked && organizations.some((organization) => organization.id === asked)) return asked;
    if (!asked) {
      const stored = sessionStorage.getItem(ORGANIZATION_ARCHIVE);
      if (stored && organizations.some((organization) => organization.id === stored)) return stored;
      if (organizations.length === 1) return organizations[0].id;
    }
    return ALL;
  }, [organizations, params]);
  const organization = organizations?.find((candidate) => candidate.id === selectedOrganizationId);

  // A new organization, kind, page, search or scope starts from nothing, so a list is never shown under the wrong tab.
  useEffect(() => {
    setCounts(null);
    setListed(null);
    setSummary(null);
    setSelected(new Map());
    setError(false);
    setActiveKind(null);
  }, [selectedOrganizationId, askedKind, page, search, mine]);

  const activeKindRef = useRef<InboxKind | null>(null);
  activeKindRef.current = activeKind;

  const load = useCallback(async () => {
    if (waitingForGroups) {
      // A load for another scope may still be on its way. Its answer must not fill the page under My teams.
      requestId.current += 1;
      setLoading(false);
      return;
    }
    if (!organizations || !selectedOrganizationId) return;
    const current = ++requestId.current;
    setLoading(true);
    try {
      if (!organization) {
        const result = await loadOrganizationCounts(organizations, scope);
        if (current !== requestId.current) return;
        setSummary(result);
        setError(false);
        return;
      }
      const nextCounts = await loadInboxCounts(organization, scope, search);
      // With no tab asked for, open the first one that has something: approvals, then failures, drift, policy.
      const kind = askedKind ?? KIND_ORDER.find((each) => nextCounts[each] > 0) ?? null;
      // With no tab asked for, a poll can move the first non-empty tab (the waiting runs were approved elsewhere). The
      // page number and the ticked rows belong to the old tab, so the new one starts from its first page.
      if (!askedKind && activeKindRef.current && kind !== activeKindRef.current) {
        setSelected(new Map());
        if (page > 1) {
          update({ page: null }, true);
          return;
        }
      }
      let result: PageOfItems | null = null;
      if (kind && nextCounts[kind] > 0) {
        result = await loadInboxPage(organization, { kind, page, pageSize: PAGE_SIZE, search, scope });
        // The last page can empty while it is open (the runs were approved elsewhere): go to the new last one.
        if (current === requestId.current && result.items.length === 0 && result.total > 0 && page > 1) {
          update({ page: Math.ceil(result.total / PAGE_SIZE) }, true);
          return;
        }
      }
      if (current !== requestId.current) return;
      setActiveKind(kind);
      setListed(result);
      setCounts(nextCounts);
      setError(false);
    } catch {
      if (current === requestId.current) setError(true);
    } finally {
      if (current === requestId.current) setLoading(false);
    }
    // scope is rebuilt on every render; scopeKey and mine say when it really changed.
  }, [
    organizations,
    selectedOrganizationId,
    organization,
    waitingForGroups,
    mine,
    scopeKey,
    search,
    askedKind,
    page,
    update,
  ]);

  useEffect(() => {
    load();
  }, [load]);
  // A refresh skips its turn while the last one is still running, and does not sweep a long list of organizations.
  const loadingRef = useRef(false);
  loadingRef.current = loading;
  const refresh = useCallback(async () => {
    if (loadingRef.current) return;
    if (!organization && organizations && organizations.length > MAX_POLLED_ORGANIZATIONS) return;
    await load();
  }, [load, organization, organizations]);
  usePolling(refresh, { interval: POLL_MS, immediate: false });

  // Typing in the search box waits a moment, so each key press is not a request.
  useEffect(() => {
    if (searchText === search) return undefined;
    const timer = setTimeout(() => update({ q: searchText.trim() || null, page: null }, true), SEARCH_DELAY_MS);
    return () => clearTimeout(timer);
  }, [searchText, search, update]);
  useEffect(() => setSearchText(search), [search]);

  const approveSelected = () =>
    confirmApproveMany([...selected.values()], () => {
      setSelected(new Map());
      load();
    });

  const total = counts ? totalCount(counts) : 0;
  const pageRows = listed?.items ?? [];
  const addressSearch = location.search;
  const remember = (item: InboxItem) => rememberWorkspaceOrigin(item.workspaceId, inboxOrigin(addressSearch));

  const columns: ColumnsType<InboxItem> = [
    {
      title: "Workspace",
      key: "workspace",
      render: (_, item) => (
        <div className="inbox-cell">
          <Link
            className="inbox-name"
            to={`/organizations/${item.orgId}/workspaces/${item.workspaceId}`}
            onClick={() => remember(item)}
          >
            {item.name}
          </Link>
          <Typography.Text type="secondary" className="inbox-where">
            {[item.projectName, item.ownerTeam].filter(Boolean).join(" · ") || "No project"}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: "What happened",
      key: "reason",
      width: 220,
      render: (_, item) => <Reason item={item} />,
    },
    {
      title: <span className="inbox-visually-hidden">Actions</span>,
      key: "actions",
      align: "right",
      width: 220,
      render: (_, item) => (
        <span className="inbox-actions">
          {item.kind === "waiting" && (
            <Button size="small" type="primary" onClick={() => confirmApprove(item, load)}>
              Approve
            </Button>
          )}
          <Link
            to={`/organizations/${item.orgId}/workspaces/${item.workspaceId}/runs${item.runId ? `/${item.runId}` : ""}`}
            onClick={() => remember(item)}
          >
            {item.kind === "policy" ? "View" : item.kind === "drifted" ? "View plan" : "Open run"}
          </Link>
        </span>
      ),
    },
  ];

  return (
    <PageWrapper
      title="Needs attention"
      subTitle={
        organization
          ? counts
            ? `${total} item${total === 1 ? "" : "s"} in ${organization.name}`
            : organization.name
          : "Across the organizations you can see"
      }
      loadingText="Loading..."
      loading={false}
      breadcrumbs={[{ label: "Needs attention", path: "/inbox" }]}
    >
      <div className="inbox-toolbar">
        {organizations && (
          <Select
            aria-label="Organization"
            showSearch
            optionFilterProp="label"
            className="inbox-organization"
            value={selectedOrganizationId ?? ALL}
            options={[
              { value: ALL, label: "All organizations" },
              ...organizations.map((each) => ({ value: each.id, label: each.name })),
            ]}
            onChange={(value) => update({ org: value, kind: null, page: null, q: null })}
          />
        )}
        {organization && (
          <Input
            allowClear
            className="inbox-search"
            aria-label="Search workspaces by name"
            placeholder="Search workspaces"
            prefix={<SearchOutlined />}
            value={searchText}
            onChange={(event) => setSearchText(event.target.value)}
          />
        )}
        <Segmented
          className="inbox-scope"
          value={mine ? "mine" : "all"}
          options={[
            { label: "Everything I can see", value: "all" },
            { label: "My teams", value: "mine" },
          ]}
          onChange={(value) => update({ scope: value === "mine" ? "mine" : null, page: null })}
        />
      </div>

      {organizationsFailed && (
        <Alert
          type="error"
          showIcon
          message="Could not load your organizations."
          action={
            <Button size="small" onClick={loadOrganizations}>
              Retry
            </Button>
          }
        />
      )}
      {mine && groupsFailed && (
        <Alert
          type="error"
          showIcon
          className="inbox-alert"
          message="Your teams could not be read, so My teams cannot be shown."
          action={
            <Button size="small" onClick={() => setGroupsFailed(false)}>
              Retry
            </Button>
          }
        />
      )}
      {error && (
        <Alert
          type="error"
          showIcon
          className="inbox-alert"
          message="Could not load what needs attention."
          action={
            <Button size="small" onClick={load}>
              Retry
            </Button>
          }
        />
      )}
      {!organizations && !organizationsFailed && <Skeleton active paragraph={{ rows: 6 }} />}

      {organizations && !organization && (
        <>
          {summary && summary.failedOrganizations.length > 0 && (
            <Alert
              type="warning"
              showIcon
              className="inbox-alert"
              message={`Could not read ${summary.failedOrganizations.join(", ")}. What is shown may be incomplete.`}
              action={
                <Button size="small" onClick={load}>
                  Retry
                </Button>
              }
            />
          )}
          {!summary && !error && <Skeleton active paragraph={{ rows: 4 }} />}
          {summary &&
            summary.rows.every((row) => totalCount(row.counts) === 0) &&
            summary.failedOrganizations.length === 0 && (
              <EmptyState
                description={
                  mine
                    ? "Nothing needs your teams. Projects without an owner only show under Everything I can see."
                    : `Nothing needs you across ${organizations.length} organization${organizations.length === 1 ? "" : "s"}.`
                }
              />
            )}
          {summary && summary.rows.some((row) => totalCount(row.counts) > 0) && (
            <InboxOrganizations
              rows={summary.rows}
              onOpen={(id, kind) => update({ org: id, kind: kind ?? null, page: null, q: null })}
            />
          )}
        </>
      )}

      {organization && (
        <>
          {!counts && !error && <Skeleton active paragraph={{ rows: 6 }} />}
          {counts && total === 0 && (
            <EmptyState
              description={
                search
                  ? `Nothing matches "${search}" in ${organization.name}.`
                  : mine
                    ? "Nothing needs your teams. Projects without an owner only show under Everything I can see."
                    : `Nothing needs you in ${organization.name}.`
              }
            />
          )}
          {counts && total > 0 && (
            <div className="inbox-layout">
              <nav className="inbox-rail" aria-label="What needs attention">
                {KIND_ORDER.map((each) => (
                  <button
                    key={each}
                    type="button"
                    className={`inbox-rail-item inbox-rail-${each}${activeKind === each ? " inbox-rail-active" : ""}`}
                    aria-current={activeKind === each ? "page" : undefined}
                    onClick={() => update({ kind: each, page: null })}
                  >
                    <span className="inbox-rail-icon" aria-hidden="true">
                      {KIND_ICON[each]}
                    </span>
                    <span className="inbox-rail-label">{KIND_TITLES[each]}</span>
                    <span className="inbox-rail-count">{counts[each]}</span>
                  </button>
                ))}
              </nav>
              <section className="inbox-main" aria-label={activeKind ? KIND_TITLES[activeKind] : "Needs attention"}>
                {selected.size > 0 && (
                  <div className="inbox-bulk" role="region" aria-label="Selected runs">
                    <strong>{selected.size} selected</strong>
                    <Button type="primary" size="small" onClick={approveSelected}>
                      Approve {selected.size}
                    </Button>
                    <Button size="small" onClick={() => setSelected(new Map())}>
                      Clear
                    </Button>
                  </div>
                )}
                {activeKind && counts[activeKind] === 0 && (
                  <EmptyState description={`No ${KIND_TITLES[activeKind].toLowerCase()} here.`} />
                )}
                {activeKind && counts[activeKind] > 0 && (
                  <Table<InboxItem>
                    className="inbox-table"
                    scroll={{ x: "max-content" }}
                    size="middle"
                    rowKey="key"
                    loading={loading && !listed}
                    columns={columns}
                    dataSource={listed?.items ?? []}
                    rowSelection={
                      activeKind === "waiting"
                        ? {
                            selectedRowKeys: [...selected.keys()],
                            // The box in the header, with a name, because the default one has none.
                            columnTitle: (
                              <Checkbox
                                aria-label="Select all on this page"
                                checked={pageRows.length > 0 && pageRows.every((row) => selected.has(row.key))}
                                indeterminate={
                                  pageRows.some((row) => selected.has(row.key)) &&
                                  !pageRows.every((row) => selected.has(row.key))
                                }
                                onChange={(event) =>
                                  setSelected(
                                    new Map(event.target.checked ? pageRows.map((row) => [row.key, row]) : [])
                                  )
                                }
                              />
                            ),
                            getCheckboxProps: (item) => ({ "aria-label": `Select ${item.name}` }),
                            onChange: (_keys, rows) => setSelected(new Map(rows.map((row) => [row.key, row]))),
                          }
                        : undefined
                    }
                    pagination={{
                      current: page,
                      pageSize: PAGE_SIZE,
                      total: listed?.total ?? 0,
                      showSizeChanger: false,
                      hideOnSinglePage: true,
                      showTotal: (all, range) => `${range[0]}-${range[1]} of ${all}`,
                      onChange: (next) => update({ page: next }),
                    }}
                  />
                )}
              </section>
            </div>
          )}
        </>
      )}
    </PageWrapper>
  );
}
