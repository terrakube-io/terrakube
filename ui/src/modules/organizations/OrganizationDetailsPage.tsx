import { Button, Flex, List, Space } from "antd";
import PageWrapper from "@/modules/layout/PageWrapper/PageWrapper";
import { ImportOutlined, PlusOutlined } from "@ant-design/icons";
import { useEffect, useMemo, useState } from "react";
import WorkspaceFilter from "@/modules/workspaces/components/WorkspaceFilter";
import { WorkspaceListItem } from "@/modules/workspaces/types";
import { JobStatus } from "@/domain/types";
import { Link, useNavigate, useParams } from "react-router-dom";
import workspaceService from "@/modules/workspaces/workspaceService";
import useApiRequest from "@/modules/api/useApiRequest";
import { useOrganizationJobStatusSubscription, usePolling } from "@/hooks";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../config/actionTypes";
import { TagModel } from "./types";
import WorkspaceCard from "@/modules/workspaces/components/WorkspaceCard";
import WorkspaceTable from "@/modules/workspaces/components/WorkspaceTable/WorkspaceTable";
import ListViewToggle from "@/modules/layout/ListViewToggle/ListViewToggle";
import { getStoredListViewMode, ListViewMode } from "@/modules/layout/ListViewToggle/listViewPreference";
import { useWorkspaceFilterState } from "@/modules/workspaces/hooks/useWorkspaceFilterState";
import { filterWorkspaces, WorkspaceStatusFilter } from "@/modules/workspaces/utils/workspaceFilter";
import {
  getStoredWorkspaceSortOption,
  setStoredWorkspaceSortOption,
  sortWorkspaces,
  WorkspaceSortOption,
} from "@/modules/workspaces/utils/workspaceSort";

type Props = {
  organizationName: string;
  setOrganizationName: React.Dispatch<React.SetStateAction<string>>;
};

export default function OrganizationsDetailPage({ organizationName, setOrganizationName }: Props) {
  const { id } = useParams();
  const navigate = useNavigate();
  const [workspaces, setWorkspaces] = useState<WorkspaceListItem[]>([]);
  const [sortOption, setSortOption] = useState<WorkspaceSortOption>(() => getStoredWorkspaceSortOption());
  const [tags, setTags] = useState<TagModel[]>([]);
  const [listViewMode, setListViewMode] = useState<ListViewMode>(() => getStoredListViewMode());
  const filterState = useWorkspaceFilterState();

  const filteredWorkspaces = useMemo(
    () =>
      filterWorkspaces(workspaces, {
        status: filterState.status,
        search: filterState.search,
        tagIds: filterState.tagIds,
        projectId: filterState.projectId,
      }),
    [workspaces, filterState.status, filterState.search, filterState.tagIds, filterState.projectId]
  );

  const sortedWorkspaces = useMemo(
    () => sortWorkspaces(filteredWorkspaces, sortOption),
    [filteredWorkspaces, sortOption]
  );

  const statusCounts = useMemo(() => {
    const counts: Record<string, number> = {
      [WorkspaceStatusFilter.All]: workspaces.length,
      [WorkspaceStatusFilter.NeverExecuted]: 0,
      [JobStatus.WaitingApproval]: 0,
      [JobStatus.Failed]: 0,
      [JobStatus.Queue]: 0,
      [JobStatus.Running]: 0,
      [JobStatus.Completed]: 0,
    };
    for (const ws of workspaces) {
      if (!ws.lastStatus) {
        counts[WorkspaceStatusFilter.NeverExecuted]++;
      } else if (ws.lastStatus in counts) {
        counts[ws.lastStatus]++;
      }
    }
    return counts;
  }, [workspaces]);

  const projects = useMemo(() => {
    const seen = new Set<string>();
    return workspaces
      .filter((ws) => ws.projectId && !seen.has(ws.projectId) && seen.add(ws.projectId!))
      .map((ws) => ({ id: ws.projectId!, name: ws.projectName! }));
  }, [workspaces]);

  const groups = useMemo(() => {
    const map = new Map<string, { key: string; label: string; items: WorkspaceListItem[] }>();
    for (const ws of sortedWorkspaces) {
      const key = ws.projectId ?? "__unassigned__";
      const label = ws.projectId ? (ws.projectName ?? "Unknown project") : "(unassigned)";
      if (!map.has(key)) {
        map.set(key, { key, label, items: [] });
      }
      map.get(key)!.items.push(ws);
    }
    return Array.from(map.values());
  }, [sortedWorkspaces]);

  const handleSortChange = (option: WorkspaceSortOption) => {
    setSortOption(option);
    setStoredWorkspaceSortOption(option);
  };

  const { loading, execute, error } = useApiRequest({
    action: () => workspaceService.listWorkspaces(id!),
    onReturn: (data) => {
      setWorkspaces(data.workspaces);
      sessionStorage.setItem(ORGANIZATION_NAME, data.organizationName);
      setOrganizationName(data.organizationName);
    },
  });

  useEffect(() => {
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, id!);
    execute();
  }, [id]);

  // Silently refresh workspace status in the background so job status/icon changes
  // (e.g. running -> completed) show up without a manual reload. Bypasses useApiRequest
  // so it doesn't toggle the page-level loading spinner on every poll.
  usePolling(
    () => {
      if (!id) return;
      workspaceService.listWorkspaces(id).then((response) => {
        if (!response.isError && response.data) {
          setWorkspaces(response.data.workspaces);
        }
      });
    },
    { interval: 10000, enabled: Boolean(id), immediate: false }
  );

  // Pushes an immediate refresh on real job status changes anywhere in this organization; the poll
  // above stays as a fallback for a dropped WebSocket connection.
  useOrganizationJobStatusSubscription({
    organizationId: id ?? "",
    enabled: Boolean(id),
    onEvent: () => {
      if (!id) return;
      workspaceService.listWorkspaces(id).then((response) => {
        if (!response.isError && response.data) {
          setWorkspaces(response.data.workspaces);
        }
      });
    },
  });

  const handleCreateWorkspace = () => {
    navigate("/workspaces/create");
  };

  const showGrouped = listViewMode === "compact" && filterState.groupByProject && filterState.projectId === null;

  return (
    <PageWrapper
      title="Workspaces"
      subTitle={`Workspaces in the ${organizationName} organization`}
      loadingText="Loading workspaces..."
      loading={loading}
      error={error}
      breadcrumbs={[
        { label: organizationName, path: "/" },
        { label: "Workspaces", path: `/organizations/${id}/workspaces` },
      ]}
      fluid
      actions={
        <Space>
          <ListViewToggle value={listViewMode} onChange={setListViewMode} />
          <Button icon={<ImportOutlined />}>
            <Link to="/workspaces/import">Import workspaces</Link>
          </Button>
          <Button icon={<PlusOutlined />} type="primary" onClick={handleCreateWorkspace}>
            New workspace
          </Button>
        </Space>
      }
    >
      <Flex vertical>
        {id && (
          <WorkspaceFilter
            organizationId={id}
            onTagsLoaded={(t) => setTags(t)}
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
            workspaces={sortedWorkspaces}
            groups={showGrouped ? groups : undefined}
            onSelectProject={filterState.setProjectId}
            sortOption={sortOption}
            onSortChange={handleSortChange}
          />
        )}
        {listViewMode === "cards" && (
          <List
            split={false}
            dataSource={sortedWorkspaces}
            pagination={{ showSizeChanger: true, defaultPageSize: 10 }}
            renderItem={(item) => (
              <List.Item style={{ position: "relative" }}>
                <Link
                  to={`/organizations/${id}/workspaces/${item.id}`}
                  aria-label={`Open workspace ${item.name}`}
                  style={{ position: "absolute", inset: 0, zIndex: 0 }}
                />
                <WorkspaceCard tags={tags} item={item} />
              </List.Item>
            )}
          />
        )}
      </Flex>
    </PageWrapper>
  );
}
