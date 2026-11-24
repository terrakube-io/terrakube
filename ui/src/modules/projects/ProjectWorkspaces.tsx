import { Alert, Button, Flex, Pagination, Segmented, Table, Typography, message } from "antd";
import type { TableProps } from "antd";
import { AppstoreOutlined, LockOutlined, UnorderedListOutlined } from "@ant-design/icons";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import WorkspaceStatusTag from "@/components/display/WorkspaceStatusTag";
import PolicyStatusTag from "@/components/display/PolicyStatusTag";
import { EmptyState } from "@/components/feedback/EmptyState";
import LastRun from "@/modules/workspaces/components/LastRun";
import WorkspaceCard from "@/modules/workspaces/components/WorkspaceCard";
import WorkspaceFilter from "@/modules/workspaces/components/WorkspaceFilter";
import WorkspaceTagChips from "@/modules/workspaces/components/WorkspaceTagChips";
import { WorkspaceListItem, WorkspacePageRequest, WorkspaceTagFilter } from "@/modules/workspaces/types";
import workspaceService from "@/modules/workspaces/workspaceService";
import { WorkspaceSortOption } from "@/modules/workspaces/utils/workspaceSort";
import { PolicyComplianceFilter, WorkspaceStatusFilter } from "@/modules/workspaces/utils/workspaceFilter";
import getVcsNameFromUrl from "@/modules/workspaces/utils/getVcsNameFromUrl";
import { TagModel } from "@/modules/organizations/types";
import { ErrorInformation } from "@/modules/api/types";
import { usePolling } from "@/hooks";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import projectService from "./projectService";
import BulkWorkspaceBar from "./BulkWorkspaceBar";
import MoveExistingModal from "./MoveExistingModal";
import "./Projects.css";

type Props = {
  orgid: string;
  projectId: string;
  projectName?: string;
  canManage?: boolean;
  /** Removing workspaces from the project needs the organization level right to manage workspaces. */
  canRemove?: boolean;
};

type Sorter = Parameters<NonNullable<TableProps<WorkspaceListItem>["onChange"]>>[2];

const DEFAULT_PAGE_SIZE = 10;
const DEFAULT_SORT: WorkspaceSortOption = "name_asc";
const POLL_INTERVAL_MS = 10000;
const SEARCH_DEBOUNCE_MS = 300;

/** Clearing the Last run sort falls back to the default name order. */
function toSortOption(sorter: Sorter): WorkspaceSortOption {
  const active = Array.isArray(sorter) ? sorter[0] : sorter;
  if (active?.columnKey === "lastRun" && active.order) {
    return active.order === "ascend" ? "lastRun_asc" : "lastRun_desc";
  }
  if (active?.columnKey === "name" && active.order) {
    return active.order === "ascend" ? "name_asc" : "name_desc";
  }
  return DEFAULT_SORT;
}

/**
 * The workspaces of one project, a page at a time, with the same filters as the organization list. The project
 * filter, order and paging all run on the server, so the tab never loads the rest of the organization. The
 * parent keys it by project, so a different project starts from the first page.
 */
export default function ProjectWorkspaces({
  orgid,
  projectId,
  projectName = "this project",
  canManage = false,
  canRemove = false,
}: Props) {
  const navigate = useNavigate();
  const [selected, setSelected] = useState<WorkspaceListItem[]>([]);
  const [workspaces, setWorkspaces] = useState<WorkspaceListItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [sort, setSort] = useState<WorkspaceSortOption>(DEFAULT_SORT);
  const [search, setSearch] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [status, setStatus] = useState<string>(WorkspaceStatusFilter.All);
  const [policyStatus, setPolicyStatus] = useState<string>(PolicyComplianceFilter.All);
  const [tagFilters, setTagFilters] = useState<WorkspaceTagFilter[]>([]);
  const [statusCounts, setStatusCounts] = useState<Record<string, number>>();
  const [policyCounts, setPolicyCounts] = useState<Record<string, number>>();
  const [tags, setTags] = useState<TagModel[]>([]);
  // The value each default tag has in the project. A workspace's own value for it is not "from project".
  const [defaultTags, setDefaultTags] = useState<Map<string, string>>(new Map());
  const [view, setView] = useState<"rows" | "cards">("rows");
  const [loading, setLoading] = useState(true);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<ErrorInformation>();
  const [moveExisting, setMoveExisting] = useState(false);
  const requestSequence = useRef(0);

  useEffect(() => {
    // Only a search that differs from the applied one starts the timer, so opening the page, or a link to page 3, does
    // not fall back to page 1 a moment later.
    if (search === debouncedSearch) return;
    const handle = window.setTimeout(() => {
      setDebouncedSearch(search);
      setPage(1);
    }, SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(handle);
  }, [search, debouncedSearch]);

  useEffect(() => {
    projectService
      .listProjectDefaultTags(orgid, projectId)
      .then((defaults) => setDefaultTags(new Map(defaults.map((item) => [item.tagId, item.value ?? ""]))))
      .catch(() => setDefaultTags(new Map()));
  }, [orgid, projectId]);

  const request = useMemo<WorkspacePageRequest>(
    () => ({
      organizationId: orgid,
      first: pageSize,
      after: (page - 1) * pageSize,
      projectId,
      sort,
      search: debouncedSearch || undefined,
      status,
      policyStatus,
      tagFilters,
    }),
    [orgid, projectId, page, pageSize, sort, debouncedSearch, status, policyStatus, tagFilters]
  );

  const load = useCallback(
    async (silent = false) => {
      const sequence = ++requestSequence.current;
      const response = await workspaceService.listWorkspacePage(request, true);
      if (sequence !== requestSequence.current) return;

      if (response.isError || !response.data) {
        if (!loaded) {
          setError({
            title: response.error?.status || "Failed to load workspaces",
            message: response.error?.message,
          });
        } else if (!silent) {
          // Polling failures stay quiet; a failed page or sort change must not look like success.
          message.error(response.error?.message || "Failed to load workspaces");
        }
        setLoading(false);
        return;
      }

      const { workspaces: rows, pageInfo, statusCounts: statuses, policyCounts: policies } = response.data;
      // The last page can disappear while it is open (workspaces moved to another project).
      if (rows.length === 0 && pageInfo.totalRecords > 0 && request.after >= pageInfo.totalRecords) {
        setPage(Math.ceil(pageInfo.totalRecords / request.first));
        return;
      }
      setWorkspaces(rows);
      setTotal(pageInfo.totalRecords);
      setStatusCounts(statuses);
      setPolicyCounts(policies);
      // Only rows that are still on the page stay selected.
      setSelected((current) => current.filter((item) => rows.some((row) => row.id === item.id)));
      setError(undefined);
      setLoaded(true);
      setLoading(false);
    },
    [request, loaded]
  );

  useEffect(() => {
    setLoading(true);
    load();
    return () => {
      requestSequence.current += 1;
    };
    // `load` also changes once the first page arrives, which must not fetch it again.
  }, [request]);

  usePolling(() => load(true), { interval: POLL_INTERVAL_MS, immediate: false });

  const handleTableChange: TableProps<WorkspaceListItem>["onChange"] = (pagination, _filters, sorter) => {
    const nextSort = toSortOption(sorter);
    if (nextSort !== sort) {
      setSort(nextSort);
      setPage(1);
    } else if (pagination.pageSize && pagination.pageSize !== pageSize) {
      setPageSize(pagination.pageSize);
      setPage(1);
    } else if (pagination.current) {
      setPage(pagination.current);
    }
    // Ticked rows belong to the page they were ticked on, so a bulk action never reaches rows that are out of sight.
    setSelected([]);
  };

  const firstPage =
    <T,>(set: (value: T) => void) =>
    (value: T) => {
      set(value);
      setPage(1);
      setSelected([]);
    };

  const columns: TableProps<WorkspaceListItem>["columns"] = [
    {
      title: "Workspace",
      dataIndex: "name",
      key: "name",
      ellipsis: true,
      sorter: true,
      sortDirections: ["ascend", "descend"],
      sortOrder: sort === "name_asc" ? "ascend" : sort === "name_desc" ? "descend" : null,
      render: (name: string, record: WorkspaceListItem) => (
        <div className="project-workspace-name">
          <Link to={`/organizations/${orgid}/workspaces/${record.id}`} title={name}>
            {name}
          </Link>
          {record.locked && <LockOutlined aria-label="Locked" className="project-workspace-lock" />}
          <div className="project-workspace-source">
            {record.branch !== "remote-content" && record.normalizedSource ? (
              <Typography.Link
                href={record.normalizedSource}
                target="_blank"
                rel="noreferrer"
                title={record.normalizedSource}
              >
                {getVcsNameFromUrl(record.normalizedSource)}
              </Typography.Link>
            ) : (
              <Typography.Text type="secondary">cli/api driven workflow</Typography.Text>
            )}
          </div>
        </div>
      ),
    },
    {
      title: "Status",
      dataIndex: "lastStatus",
      key: "lastStatus",
      width: 170,
      render: (value: WorkspaceListItem["lastStatus"]) => <WorkspaceStatusTag status={value} />,
    },
    {
      title: "Policy",
      dataIndex: "policyComplianceStatus",
      key: "policy",
      width: 150,
      render: (value: string | undefined) => <PolicyStatusTag status={value} />,
    },
    {
      title: "Tags",
      key: "tags",
      render: (_: unknown, record: WorkspaceListItem) => (
        <Flex wrap gap={4} align="center">
          {(record.tags ?? []).map((binding) => (
            <span key={binding.tagId} className="project-tag-with-source">
              <WorkspaceTagChips bindings={[binding]} tags={tags} />
              {defaultTags.has(binding.tagId) && (defaultTags.get(binding.tagId) ?? "") === (binding.value ?? "") && (
                <span className="project-tag-source">from project</span>
              )}
            </span>
          ))}
        </Flex>
      ),
    },
    {
      title: "Last run",
      dataIndex: "lastRun",
      key: "lastRun",
      width: 180,
      sorter: true,
      // The first click shows the newest runs first.
      sortDirections: ["descend", "ascend"],
      sortOrder: sort === "lastRun_desc" ? "descend" : sort === "lastRun_asc" ? "ascend" : null,
      render: (lastRun: string | undefined) => <LastRun lastRun={lastRun} />,
    },
  ];

  const filtersActive =
    debouncedSearch !== "" ||
    status !== WorkspaceStatusFilter.All ||
    policyStatus !== PolicyComplianceFilter.All ||
    tagFilters.length > 0;
  const projectIsEmpty = loaded && total === 0 && !filtersActive;

  return (
    <div className="project-settings-panel project-workspaces-panel">
      <SettingsPageHeader
        docUrl="https://docs.terrakube.io/user-guide/projects/workspace-assignment"
        title="Workspaces"
        description="Workspaces assigned to this project."
      />
      {error && !loaded ? (
        <Alert
          type="error"
          showIcon
          title={error.title}
          description={error.message}
          action={
            <Button
              size="small"
              onClick={() => {
                setLoading(true);
                load();
              }}
            >
              Try again
            </Button>
          }
        />
      ) : projectIsEmpty ? (
        <EmptyState description="No workspaces in this project.">
          <Flex gap={8} justify="center">
            <Button
              type="primary"
              onClick={() => navigate(`/organizations/${orgid}/workspaces/create?projectId=${projectId}`)}
            >
              New workspace
            </Button>
            <Button disabled={!canManage} onClick={() => setMoveExisting(true)}>
              Move existing
            </Button>
          </Flex>
        </EmptyState>
      ) : (
        <>
          <WorkspaceFilter
            organizationId={orgid}
            status={status}
            onStatusChange={firstPage(setStatus)}
            policyStatus={policyStatus}
            onPolicyStatusChange={firstPage(setPolicyStatus)}
            policyCounts={policyCounts}
            statusCounts={statusCounts}
            search={search}
            onSearchChange={setSearch}
            tagFilters={tagFilters}
            onTagFiltersChange={firstPage(setTagFilters)}
            projectId={null}
            onProjectIdChange={() => undefined}
            groupByProject={false}
            onGroupByProjectChange={() => undefined}
            onTagsLoaded={setTags}
            sortOption={sort}
            onSortChange={firstPage(setSort)}
          />
          <Flex justify="space-between" align="center" className="project-workspaces-toolbar">
            <Segmented
              value={view}
              onChange={(value) => setView(value as "rows" | "cards")}
              options={[
                { value: "rows", icon: <UnorderedListOutlined />, label: "Rows" },
                { value: "cards", icon: <AppstoreOutlined />, label: "Cards" },
              ]}
            />
            {canManage && <Button onClick={() => setMoveExisting(true)}>Move existing</Button>}
          </Flex>
          {canManage && (
            <BulkWorkspaceBar
              orgid={orgid}
              projectId={projectId}
              selected={selected}
              total={total}
              tags={tags}
              canRemove={canRemove}
              onClear={() => setSelected([])}
              onDone={() => load(true)}
            />
          )}
          {view === "rows" ? (
            <Table<WorkspaceListItem>
              className="project-workspaces-table"
              rowKey="id"
              loading={loading}
              dataSource={workspaces}
              columns={columns}
              onChange={handleTableChange}
              rowSelection={
                canManage
                  ? {
                      selectedRowKeys: selected.map((item) => item.id),
                      onChange: (_keys, rows) => setSelected(rows),
                    }
                  : undefined
              }
              pagination={{ current: page, pageSize, total, showSizeChanger: true }}
              locale={{
                emptyText: loaded
                  ? filtersActive
                    ? "No workspaces match these filters."
                    : "No workspaces assigned to this project yet."
                  : "Loading workspaces...",
              }}
            />
          ) : (
            <>
              <div className="projects-card-grid">
                {workspaces.map((item) => (
                  <Link key={item.id} to={`/organizations/${orgid}/workspaces/${item.id}`}>
                    <WorkspaceCard item={item} tags={tags} organizationId={orgid} />
                  </Link>
                ))}
              </div>
              <Pagination
                className="projects-card-pagination"
                current={page}
                pageSize={pageSize}
                total={total}
                showSizeChanger
                hideOnSinglePage={total <= DEFAULT_PAGE_SIZE}
                onChange={(nextPage, nextPageSize) => {
                  if (nextPageSize !== pageSize) {
                    setPageSize(nextPageSize);
                    setPage(1);
                  } else {
                    setPage(nextPage);
                  }
                  setSelected([]);
                }}
              />
            </>
          )}
        </>
      )}
      <MoveExistingModal
        orgid={orgid}
        projectId={projectId}
        projectName={projectName}
        open={moveExisting}
        onClose={() => setMoveExisting(false)}
        onMoved={() => load(true)}
      />
    </div>
  );
}
