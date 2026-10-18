import { Alert, Button, message, Table, Typography } from "antd";
import type { TableProps } from "antd";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import WorkspaceStatusTag from "@/components/display/WorkspaceStatusTag";
import VcsLogo from "@/components/display/VcsLogo";
import LastRun from "@/modules/workspaces/components/LastRun";
import { WorkspaceListItem, WorkspacePageRequest } from "@/modules/workspaces/types";
import workspaceService from "@/modules/workspaces/workspaceService";
import { WorkspaceSortOption } from "@/modules/workspaces/utils/workspaceSort";
import getVcsNameFromUrl from "@/modules/workspaces/utils/getVcsNameFromUrl";
import getVcsTypeFromUrl from "@/modules/workspaces/utils/getVcsTypeFromUrl";
import { ErrorInformation } from "@/modules/api/types";
import { usePolling } from "@/hooks";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import "./Projects.css";

type Props = {
  orgid: string;
  projectId: string;
};

type Sorter = Parameters<NonNullable<TableProps<WorkspaceListItem>["onChange"]>>[2];

const DEFAULT_PAGE_SIZE = 10;
const DEFAULT_SORT: WorkspaceSortOption = "name_asc";
const POLL_INTERVAL_MS = 10000;

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
 * The workspaces of one project, a page at a time. The project filter, order and paging all run on the server,
 * so the tab never loads the rest of the organization. The parent keys it by project, so a different project
 * starts from the first page.
 */
export default function ProjectWorkspaces({ orgid, projectId }: Props) {
  const [workspaces, setWorkspaces] = useState<WorkspaceListItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [sort, setSort] = useState<WorkspaceSortOption>(DEFAULT_SORT);
  const [loading, setLoading] = useState(true);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<ErrorInformation>();
  const requestSequence = useRef(0);

  const request = useMemo<WorkspacePageRequest>(
    () => ({ organizationId: orgid, first: pageSize, after: (page - 1) * pageSize, projectId, sort }),
    [orgid, projectId, page, pageSize, sort]
  );

  const load = useCallback(
    async (silent = false) => {
      const sequence = ++requestSequence.current;
      const response = await workspaceService.listWorkspacePage(request, false);
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

      const { workspaces: rows, pageInfo } = response.data;
      // The last page can disappear while it is open (workspaces moved to another project).
      if (rows.length === 0 && pageInfo.totalRecords > 0 && request.after >= pageInfo.totalRecords) {
        setPage(Math.ceil(pageInfo.totalRecords / request.first));
        return;
      }
      setWorkspaces(rows);
      setTotal(pageInfo.totalRecords);
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
  };

  const columns: TableProps<WorkspaceListItem>["columns"] = [
    {
      title: "Name",
      dataIndex: "name",
      key: "name",
      width: "24%",
      ellipsis: true,
      sorter: true,
      sortDirections: ["ascend", "descend"],
      sortOrder: sort === "name_asc" ? "ascend" : sort === "name_desc" ? "descend" : null,
      render: (name: string, record: WorkspaceListItem) => (
        <Link to={`/organizations/${orgid}/workspaces/${record.id}`} title={name}>
          {name}
        </Link>
      ),
    },
    {
      title: "Status",
      dataIndex: "lastStatus",
      key: "lastStatus",
      width: 170,
      render: (status: WorkspaceListItem["lastStatus"]) => <WorkspaceStatusTag status={status} />,
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
    {
      title: "Repository",
      dataIndex: "normalizedSource",
      key: "normalizedSource",
      ellipsis: true,
      // Shown like the organization list: the repository as owner/name, or the workflow when there is none.
      render: (src: string | undefined, record: WorkspaceListItem) =>
        record.branch !== "remote-content" && src ? (
          <span className="project-workspace-source">
            <VcsLogo type={getVcsTypeFromUrl(src)} />
            <Typography.Link href={src} target="_blank" rel="noreferrer" title={src}>
              {getVcsNameFromUrl(src)}
            </Typography.Link>
          </span>
        ) : (
          <Typography.Text type="secondary">cli/api driven workflow</Typography.Text>
        ),
    },
  ];

  return (
    <div className="project-settings-panel">
      <SettingsPageHeader
        docUrl="https://docs.terrakube.io/user-guide/projects/workspace-assignment"
        title="Workspaces"
        description="Workspaces assigned to this project. To change a workspace's project, go to the workspace settings."
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
      ) : (
        <Table<WorkspaceListItem>
          className="project-workspaces-table"
          rowKey="id"
          loading={loading}
          dataSource={workspaces}
          columns={columns}
          onChange={handleTableChange}
          pagination={{ current: page, pageSize, total, showSizeChanger: true }}
          locale={{ emptyText: loaded ? "No workspaces assigned to this project yet." : "Loading workspaces..." }}
        />
      )}
    </div>
  );
}
