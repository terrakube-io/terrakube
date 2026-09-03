import { Flex, List, Pagination, Space } from "antd";
import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";
import { ImportOutlined, PlusOutlined } from "@ant-design/icons";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import WorkspaceFilter from "@/modules/workspaces/components/WorkspaceFilter";
import { WorkspaceListItem, WorkspacePageInfo, WorkspacePageRequest } from "@/modules/workspaces/types";
import { JobStatus } from "@/domain/types";
import { Link, useParams } from "react-router-dom";
import { LinkButton } from "@/components/navigation/LinkButton";
import workspaceService from "@/modules/workspaces/workspaceService";
import { useOrganizationJobStatusSubscription, usePolling } from "@/hooks";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../config/actionTypes";
import { TagModel } from "./types";
import WorkspaceCard from "@/modules/workspaces/components/WorkspaceCard";
import WorkspaceTable from "@/modules/workspaces/components/WorkspaceTable/WorkspaceTable";
import ListViewToggle from "@/components/display/ListViewToggle/ListViewToggle";
import { getStoredListViewMode, ListViewMode } from "@/components/display/ListViewToggle/listViewPreference";
import { useWorkspaceFilterState } from "@/modules/workspaces/hooks/useWorkspaceFilterState";
import {
  getStoredWorkspaceSortOption,
  setStoredWorkspaceSortOption,
  WorkspaceSortOption,
} from "@/modules/workspaces/utils/workspaceSort";
import organizationService from "@/modules/organizations/organizationService";
import projectService from "@/modules/projects/projectService";
import { ErrorInformation } from "@/modules/api/types";

type Props = {
  organizationName: string;
  setOrganizationName: React.Dispatch<React.SetStateAction<string>>;
};

const DEFAULT_PAGE_SIZE = 20;

export default function OrganizationsDetailPage({ organizationName, setOrganizationName }: Props) {
  const { id } = useParams();
  const filterState = useWorkspaceFilterState();
  const [workspaces, setWorkspaces] = useState<WorkspaceListItem[]>([]);
  const [sortOption, setSortOption] = useState<WorkspaceSortOption>(() => getStoredWorkspaceSortOption());
  const [tags, setTags] = useState<TagModel[]>([]);
  const [projects, setProjects] = useState<{ id: string; name: string }[]>([]);
  const [listViewMode, setListViewMode] = useState<ListViewMode>(() => getStoredListViewMode());
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [pageCursors, setPageCursors] = useState<Record<number, string | undefined>>({ 1: undefined });
  const [pageInfo, setPageInfo] = useState<WorkspacePageInfo>({ hasNextPage: false, totalRecords: 0 });
  const [statusCounts, setStatusCounts] = useState<Record<string, number>>({});
  const [debouncedSearch, setDebouncedSearch] = useState(filterState.search);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ErrorInformation>();
  const requestSequence = useRef(0);
  const loadedOnce = useRef(false);

  useEffect(() => {
    const timeout = window.setTimeout(() => setDebouncedSearch(filterState.search), 300);
    return () => window.clearTimeout(timeout);
  }, [filterState.search]);

  useEffect(() => {
    setPage(1);
    setPageCursors({ 1: undefined });
  }, [id, debouncedSearch, filterState.status, filterState.tagIds, filterState.projectId, sortOption, pageSize]);

  const currentCursor = pageCursors[page];
  const request = useMemo<WorkspacePageRequest | null>(() => {
    if (!id) return null;
    return {
      organizationId: id,
      first: pageSize,
      after: page === 1 ? undefined : currentCursor,
      search: debouncedSearch,
      status: filterState.status,
      tagIds: filterState.tagIds,
      projectId: filterState.projectId,
      sort: sortOption,
    };
  }, [
    id,
    page,
    pageSize,
    currentCursor,
    debouncedSearch,
    filterState.status,
    filterState.tagIds,
    filterState.projectId,
    sortOption,
  ]);

  const fetchPage = useCallback(async () => {
    if (!request || (page > 1 && !request.after)) return;
    const sequence = ++requestSequence.current;
    const response = await workspaceService.listWorkspacePage(request);
    if (sequence !== requestSequence.current) return;

    if (response.isError || !response.data) {
      if (!loadedOnce.current) {
        setError({
          title: response.error?.status || "Failed to load workspaces",
          message: response.error?.message,
        });
      }
      setLoading(false);
      return;
    }

    setWorkspaces(response.data.workspaces);
    loadedOnce.current = true;
    setPageInfo(response.data.pageInfo);
    setStatusCounts(response.data.statusCounts);
    setError(undefined);
    setLoading(false);
    if (response.data.pageInfo.hasNextPage && response.data.pageInfo.endCursor) {
      setPageCursors((current) =>
        current[page + 1] === response.data!.pageInfo.endCursor
          ? current
          : { ...current, [page + 1]: response.data!.pageInfo.endCursor }
      );
    }
  }, [request, page]);

  useEffect(() => {
    fetchPage();
    return () => {
      requestSequence.current += 1;
    };
  }, [fetchPage]);

  useEffect(() => {
    if (!id) return;
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, id);

    organizationService
      .getOrganizationNameGraphQL(id)
      .then((name) => {
        if (!name) return;
        sessionStorage.setItem(ORGANIZATION_NAME, name);
        setOrganizationName(name);
      })
      .catch((requestError: unknown) => {
        // The workspace query still provides the useful page when only the breadcrumb lookup fails.
        // eslint-disable-next-line no-console
        console.error(requestError);
      });

    projectService.listProjects(id).then((response) => {
      if (!response.isError && response.data) {
        setProjects(response.data.map((project) => ({ id: project.id, name: project.name })));
      }
    });
  }, [id, setOrganizationName]);

  usePolling(fetchPage, { interval: 10000, enabled: Boolean(request), immediate: false });

  useOrganizationJobStatusSubscription({
    organizationId: id ?? "",
    enabled: Boolean(id),
    onEvent: (event) => {
      setWorkspaces((current) =>
        current.map((workspace) =>
          workspace.id === event.workspaceId ? { ...workspace, lastStatus: event.status as JobStatus } : workspace
        )
      );
    },
  });

  const groups = useMemo(() => {
    const map = new Map<string, { key: string; label: string; items: WorkspaceListItem[] }>();
    for (const workspace of workspaces) {
      const key = workspace.projectId ?? "__unassigned__";
      const label = workspace.projectId ? (workspace.projectName ?? "Unknown project") : "(unassigned)";
      if (!map.has(key)) {
        map.set(key, { key, label, items: [] });
      }
      map.get(key)!.items.push(workspace);
    }
    return Array.from(map.values());
  }, [workspaces]);

  const handleSortChange = (option: WorkspaceSortOption) => {
    setSortOption(option);
    setStoredWorkspaceSortOption(option);
  };

  const handlePageChange = (nextPage: number, nextPageSize: number) => {
    if (nextPageSize !== pageSize) {
      setPageSize(nextPageSize);
      return;
    }
    if (nextPage === 1 || pageCursors[nextPage] !== undefined) {
      setPage(nextPage);
    }
  };

  const showGrouped = listViewMode === "compact" && filterState.groupByProject && filterState.projectId === null;

  return (
    <PageWrapper
      title="Workspaces"
      subTitle={`Workspaces in the ${organizationName} organization`}
      loadingText="Loading workspaces..."
      loading={loading && workspaces.length === 0}
      error={error}
      breadcrumbs={[
        { label: organizationName, path: "/" },
        { label: "Workspaces", path: `/organizations/${id}/workspaces` },
      ]}
      actions={
        <Space>
          <ListViewToggle value={listViewMode} onChange={setListViewMode} />
          <LinkButton to={`/organizations/${id}/workspaces/import`} icon={<ImportOutlined />}>
            Import workspaces
          </LinkButton>
          <LinkButton to={`/organizations/${id}/workspaces/create`} icon={<PlusOutlined />} type="primary">
            New workspace
          </LinkButton>
        </Space>
      }
    >
      <Flex vertical>
        {id && (
          <WorkspaceFilter
            organizationId={id}
            onTagsLoaded={setTags}
            sortOption={sortOption}
            onSortChange={handleSortChange}
            projects={projects}
            compact={listViewMode === "compact"}
            statusCounts={statusCounts}
            status={filterState.status}
            onStatusChange={filterState.setStatus}
            search={filterState.search}
            onSearchChange={filterState.setSearch}
            tagIds={filterState.tagIds}
            onTagIdsChange={filterState.setTagIds}
            projectId={filterState.projectId}
            onProjectIdChange={filterState.setProjectId}
            groupByProject={filterState.groupByProject}
            onGroupByProjectChange={filterState.setGroupByProject}
          />
        )}
        {listViewMode === "compact" && id && (
          <WorkspaceTable
            organizationId={id}
            workspaces={workspaces}
            groups={showGrouped ? groups : undefined}
            onSelectProject={filterState.setProjectId}
            sortOption={sortOption}
            onSortChange={handleSortChange}
            page={page}
            pageSize={pageSize}
            total={pageInfo.totalRecords}
            onPageChange={handlePageChange}
          />
        )}
        {listViewMode === "cards" && (
          <>
            <List
              split={false}
              dataSource={workspaces}
              renderItem={(item) => (
                <List.Item style={{ position: "relative" }}>
                  <Link
                    to={`/organizations/${id}/workspaces/${item.id}`}
                    aria-label={`Open workspace ${item.name}`}
                    style={{ position: "absolute", inset: 0, zIndex: 1 }}
                  />
                  <WorkspaceCard tags={tags} item={item} />
                </List.Item>
              )}
            />
            <Pagination
              current={page}
              pageSize={pageSize}
              total={pageInfo.totalRecords}
              showSizeChanger
              onChange={handlePageChange}
            />
          </>
        )}
      </Flex>
    </PageWrapper>
  );
}
