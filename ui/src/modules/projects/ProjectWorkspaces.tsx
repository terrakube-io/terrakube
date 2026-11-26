import { Alert, Button, Flex, Pagination, Segmented, Spin, message } from "antd";
import { projectOrigin, rememberWorkspaceOrigin } from "@/modules/workspaces/workspaceOrigin";
import { AppstoreOutlined, UnorderedListOutlined } from "@ant-design/icons";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { EmptyState } from "@/components/feedback/EmptyState";
import WorkspaceTable from "@/modules/workspaces/components/WorkspaceTable/WorkspaceTable";
import WorkspaceCard from "@/modules/workspaces/components/WorkspaceCard";
import WorkspaceFilter from "@/modules/workspaces/components/WorkspaceFilter";
import EstimatedSpendRollupCard from "@/modules/workspaces/components/EstimatedSpendRollupCard";
import { WorkspaceListItem, WorkspacePageRequest, WorkspaceTagFilter } from "@/modules/workspaces/types";
import workspaceService from "@/modules/workspaces/workspaceService";
import { WorkspaceSortOption } from "@/modules/workspaces/utils/workspaceSort";
import { PolicyComplianceFilter, WorkspaceStatusFilter } from "@/modules/workspaces/utils/workspaceFilter";
import { TagModel } from "@/modules/organizations/types";
import { ErrorInformation } from "@/modules/api/types";
import { usePolling } from "@/hooks";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import projectService from "./projectService";
import BulkWorkspaceBar from "./BulkWorkspaceBar";
import DisabledReason from "./DisabledReason";
import MoveExistingModal from "./MoveExistingModal";
import { readChoice, readPositiveInt, withViewState } from "./projectLinks";
import { WORKSPACE_STATUS_PALETTE } from "@/modules/workspaces/utils/workspaceStatusPalette";
import "./Projects.css";

type Props = {
  orgid: string;
  projectId: string;
  projectName?: string;
  canManage?: boolean;
  /** Removing workspaces from the project needs the organization level right to manage workspaces. */
  canRemove?: boolean;
};

const DEFAULT_PAGE_SIZE = 10;
const DEFAULT_SORT: WorkspaceSortOption = "name_asc";
const POLL_INTERVAL_MS = 10000;
const SEARCH_DEBOUNCE_MS = 300;
const STATUS_VALUES = WORKSPACE_STATUS_PALETTE.map((entry) => entry.value);
const POLICY_VALUES = Object.values(PolicyComplianceFilter) as string[];
const SORT_VALUES: WorkspaceSortOption[] = [
  "name_asc",
  "name_desc",
  "lastRun_desc",
  "lastRun_asc",
  "cost_desc",
  "cost_asc",
  "status",
  "source_asc",
  "source_desc",
  "terraformVersion_asc",
  "terraformVersion_desc",
];
const PAGE_SIZES = [10, 20, 50, 100];

// Tags travel in the address as "tagId" or "tagId~value", one parameter each.
function readTagFilters(params: URLSearchParams): WorkspaceTagFilter[] {
  return params
    .getAll("tag")
    .map((raw) => {
      const [tagId, ...rest] = raw.split("~");
      return { tagId, value: rest.length ? rest.join("~") : undefined };
    })
    .filter((filter) => filter.tagId);
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
  // The filters live in the address, so a filtered list can be shared, bookmarked, and linked to from a number.
  const [searchParams, setSearchParams] = useSearchParams();
  const initial = useRef(searchParams).current;
  const [selected, setSelected] = useState<WorkspaceListItem[]>([]);
  const [workspaces, setWorkspaces] = useState<WorkspaceListItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(readPositiveInt(initial, "page", 1));
  const [pageSize, setPageSize] = useState(
    PAGE_SIZES.includes(readPositiveInt(initial, "size", 0)) ? readPositiveInt(initial, "size", 0) : DEFAULT_PAGE_SIZE
  );
  const [sort, setSort] = useState<WorkspaceSortOption>(readChoice(initial, "sort", SORT_VALUES, DEFAULT_SORT));
  const [search, setSearch] = useState(initial.get("q") ?? "");
  const [debouncedSearch, setDebouncedSearch] = useState(initial.get("q") ?? "");
  const [status, setStatus] = useState<string>(readChoice(initial, "status", STATUS_VALUES, WorkspaceStatusFilter.All));
  const [policyStatus, setPolicyStatus] = useState<string>(
    readChoice(initial, "policy", POLICY_VALUES, PolicyComplianceFilter.All)
  );
  const [tagFilters, setTagFilters] = useState<WorkspaceTagFilter[]>(readTagFilters(initial));
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
    const next = new URLSearchParams(
      withViewState(
        searchParams,
        {
          q: debouncedSearch.trim(),
          status,
          policy: policyStatus,
          sort,
          page,
          size: pageSize,
        },
        {
          status: WorkspaceStatusFilter.All,
          policy: PolicyComplianceFilter.All,
          sort: DEFAULT_SORT,
          page: 1,
          size: DEFAULT_PAGE_SIZE,
        }
      )
    );
    // Tags are repeated parameters, so they are rewritten as a set.
    next.delete("tag");
    tagFilters.forEach((filter) => next.append("tag", filter.value ? `${filter.tagId}~${filter.value}` : filter.tagId));
    if (next.toString() !== searchParams.toString()) setSearchParams(next, { replace: true });
  }, [debouncedSearch, status, policyStatus, sort, page, pageSize, tagFilters]);

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

  const changeSort = (next: WorkspaceSortOption) => {
    setSort(next);
    setPage(1);
    setSelected([]);
  };

  const changePage = (nextPage: number, nextSize: number) => {
    if (nextSize !== pageSize) {
      setPageSize(nextSize);
      setPage(1);
    } else {
      setPage(nextPage);
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

  const costSortActive = sort.startsWith("cost_");
  const filtersActive =
    debouncedSearch !== "" ||
    status !== WorkspaceStatusFilter.All ||
    policyStatus !== PolicyComplianceFilter.All ||
    tagFilters.length > 0 ||
    costSortActive;
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
            <DisabledReason
              disabled={!canManage}
              reason="Moving workspaces into this project needs the Admin role on it, or Manage Workspaces in the organization."
            >
              <Button disabled={!canManage} onClick={() => setMoveExisting(true)}>
                Move existing
              </Button>
            </DisabledReason>
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
                { value: "rows", icon: <UnorderedListOutlined />, label: "Compact" },
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
          <EstimatedSpendRollupCard
            workspaces={workspaces}
            title="Estimated Project Monthly Spend (Current Page)"
            costSortActive={costSortActive}
          />
          {view === "rows" ? (
            workspaces.length === 0 ? (
              <EmptyState
                description={
                  !loaded
                    ? "Loading workspaces..."
                    : costSortActive &&
                        debouncedSearch === "" &&
                        status === WorkspaceStatusFilter.All &&
                        policyStatus === PolicyComplianceFilter.All &&
                        tagFilters.length === 0
                      ? "No workspaces have a cost estimate yet."
                      : filtersActive
                        ? "No workspaces match these filters."
                        : "No workspaces assigned to this project yet."
                }
              />
            ) : (
              <Spin spinning={loading}>
                <WorkspaceTable
                  organizationId={orgid}
                  workspaces={workspaces}
                  tags={tags}
                  onSelectProject={() => undefined}
                  origin={projectOrigin(orgid, projectId)}
                  hideProject
                  defaultTags={defaultTags}
                  selection={canManage ? { selected, onChange: setSelected } : undefined}
                  sortOption={sort}
                  onSortChange={changeSort}
                  page={page}
                  pageSize={pageSize}
                  total={total}
                  onPageChange={changePage}
                />
              </Spin>
            )
          ) : (
            <>
              <div className="projects-card-grid">
                {workspaces.map((item) => (
                  <Link
                    key={item.id}
                    to={`/organizations/${orgid}/workspaces/${item.id}`}
                    onClick={() => rememberWorkspaceOrigin(item.id, projectOrigin(orgid, projectId))}
                  >
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
                onChange={changePage}
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
