import { Button, Flex, Form, Input, Modal, Pagination, Segmented, Select, Table, message } from "antd";
import type { TableProps } from "antd";
import { AppstoreOutlined, PlusOutlined, SearchOutlined, UnorderedListOutlined } from "@ant-design/icons";
import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";
import projectService, { ProjectIndexCounts } from "./projectService";
import useApiRequest from "@/modules/api/useApiRequest";
import { ProjectSummaryModel } from "@/domain/types";
import { ORGANIZATION_NAME } from "../../config/actionTypes";
import { useOrgPermissions } from "@/modules/permissions/useOrgPermissions";
import { PermissionErrorMessage } from "@/components/feedback/PermissionErrorMessage";
import { HealthLegend, ProjectHealthBar } from "./ProjectHealthBar";
import ProjectCard from "./ProjectCard";
import ProjectFilterChips from "./ProjectFilterChips";
import { CountsUnavailable, NoMatch, NoProjects, ProjectsSkeleton } from "./ProjectStates";
import { ProjectChip, attentionTone, defaultView, policyRollup, shortAgo } from "./projectHealth";
import "./Projects.css";

type Props = {
  organizationName: string;
  setOrganizationName: React.Dispatch<React.SetStateAction<string>>;
};

type ProjectForm = {
  name: string;
  description?: string;
};

type ViewMode = "table" | "cards";

const PROJECT_SORT_PRESETS = [
  { value: "-attentionCount,+name", label: "Needs attention first" },
  { value: "+name", label: "Name A to Z" },
  { value: "-lastRunAt,+name", label: "Recently run" },
  { value: "-workspaceCount,+name", label: "Most workspaces" },
];

// Every column header sorts on the server, so each one maps to a column of the summary.
const COLUMN_SORT_FIELDS: Record<string, string> = {
  name: "name",
  workspaceCount: "workspaceCount",
  attentionCount: "attentionCount",
  policy: "nonCompliantCount",
  teamCount: "teamCount",
  lastRunAt: "lastRunAt",
};

const VIEW_STORAGE_KEY = "terrakube-projects-view";
const SEARCH_DEBOUNCE_MS = 300;
const CARD_PAGE_SIZE = 24;
const TABLE_PAGE_SIZE = 50;

function storedView(): ViewMode | null {
  try {
    const stored = localStorage.getItem(VIEW_STORAGE_KEY);
    return stored === "cards" || stored === "table" ? stored : null;
  } catch {
    // Storage blocked (private window, site data off): no saved choice.
    return null;
  }
}

export default function ProjectsPage({ organizationName, setOrganizationName }: Props) {
  const { id } = useParams();
  const { permissions } = useOrgPermissions(id);
  const [projects, setProjects] = useState<ProjectSummaryModel[]>([]);
  const [totalRecords, setTotalRecords] = useState(0);
  const [unassigned, setUnassigned] = useState<ProjectSummaryModel | null>(null);
  const [counts, setCounts] = useState<ProjectIndexCounts | null>(null);
  const [countsFailed, setCountsFailed] = useState(false);
  const [groups, setGroups] = useState<string[]>([]);
  const [search, setSearch] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [chip, setChip] = useState<ProjectChip>("all");
  const [sort, setSort] = useState(PROJECT_SORT_PRESETS[0].value);
  const [view, setView] = useState<ViewMode | null>(storedView);
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState<number | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<ProjectForm>();
  const hasLoadedOnce = useRef(false);
  // The first list waits for the caller's groups, because the chips filter on them: fetching earlier would load the
  // list once without them and again with them.
  const [groupsReady, setGroupsReady] = useState(false);
  // The page size depends on the view, and the view on how many projects there are, so unless the person chose a view
  // the first list waits for the counts as well.
  const [countsSettled, setCountsSettled] = useState(false);
  // Each request takes a number, and only the newest answer is shown, so a slow earlier page cannot replace a later one.
  const requestSeq = useRef(0);

  // Until the counts arrive the size of the catalogue is unknown, so the saved choice or the cards are used.
  const effectiveView: ViewMode = view ?? (counts ? defaultView(counts.all) : "cards");
  const effectivePageSize = pageSize ?? (effectiveView === "cards" ? CARD_PAGE_SIZE : TABLE_PAGE_SIZE);

  const { loading, execute, error } = useApiRequest({
    action: async () => {
      const seq = ++requestSeq.current;
      const response = await projectService.listProjectSummaries(id!, {
        search: debouncedSearch,
        chip,
        groups,
        sort,
        page: currentPage,
        pageSize: effectivePageSize,
      });
      return { ...response, data: response.data && { ...response.data, seq } };
    },
    onReturn: (data) => {
      if (data.seq !== requestSeq.current) return;
      hasLoadedOnce.current = true;
      setProjects(data.items);
      setTotalRecords(data.totalRecords);
      const stored = sessionStorage.getItem(ORGANIZATION_NAME);
      if (stored) setOrganizationName(stored);
    },
  });

  const loadCounts = useCallback(
    async (currentGroups: string[]) => {
      const [countsResponse, unassignedResponse] = await Promise.all([
        projectService.getProjectIndexCounts(id!, currentGroups),
        projectService.getUnassignedSummary(id!),
      ]);
      setCountsFailed(countsResponse.isError);
      setCounts(countsResponse.isError ? null : (countsResponse.data ?? null));
      const roll = unassignedResponse.isError ? undefined : unassignedResponse.data;
      setUnassigned(roll && roll.workspaceCount > 0 ? roll : null);
      setCountsSettled(true);
    },
    [id]
  );

  useEffect(() => {
    projectService
      .getCurrentGroups()
      .catch(() => [] as string[])
      .then((current) => {
        setGroups(current);
        setGroupsReady(true);
        loadCounts(current).catch(() => setCountsSettled(true));
      });
  }, [id, loadCounts]);

  useEffect(() => {
    // Only a search that differs from the applied one starts the timer, so opening the page, or a link to page 3, does
    // not fall back to page 1 a moment later.
    if (search === debouncedSearch) return;
    const handle = window.setTimeout(() => {
      setDebouncedSearch(search);
      setCurrentPage(1);
    }, SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(handle);
  }, [search, debouncedSearch]);

  useEffect(() => {
    if (groupsReady && (view !== null || countsSettled)) execute();
  }, [groupsReady, countsSettled, id, debouncedSearch, chip, groups, sort, currentPage, effectivePageSize]);

  const openCreate = () => {
    form.resetFields();
    setModalOpen(true);
  };

  const handleOk = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      await projectService.createProject(id!, values);
      setSearch("");
      setDebouncedSearch("");
      setChip("all");
      setCurrentPage(1);
      message.success("Project created");
      setModalOpen(false);
      execute();
      loadCounts(groups);
    } catch (err: any) {
      if (err?.errorFields) return;
      if (err?.response?.status === 403) {
        message.error(<PermissionErrorMessage action="create projects" permission="Manage Workspaces" />);
      } else if (err?.response?.status === 409) {
        message.error("A project with this name already exists");
      } else {
        message.error(err?.message ?? "An error occurred");
      }
    } finally {
      setSubmitting(false);
    }
  };

  const changeView = (next: ViewMode) => {
    setView(next);
    setPageSize(null);
    setCurrentPage(1);
    try {
      localStorage.setItem(VIEW_STORAGE_KEY, next);
    } catch {
      // The choice applies for this visit only.
    }
  };

  const clearFilters = () => {
    setSearch("");
    setDebouncedSearch("");
    setChip("all");
    setCurrentPage(1);
  };

  const sortFor = (columnKey: string) => {
    const field = COLUMN_SORT_FIELDS[columnKey];
    if (sort.startsWith(`+${field}`)) return "ascend" as const;
    if (sort.startsWith(`-${field}`)) return "descend" as const;
    return null;
  };

  const handleTableChange: TableProps<ProjectSummaryModel>["onChange"] = (pagination, _filters, sorter) => {
    const active = Array.isArray(sorter) ? sorter[0] : sorter;
    const field = active?.columnKey ? COLUMN_SORT_FIELDS[String(active.columnKey)] : undefined;
    if (field && active?.order) {
      setSort(`${active.order === "ascend" ? "+" : "-"}${field}${field === "name" ? "" : ",+name"}`);
      setCurrentPage(1);
    } else if (!active?.order && active?.columnKey) {
      setSort(PROJECT_SORT_PRESETS[0].value);
      setCurrentPage(1);
    }
    if (pagination.pageSize && pagination.pageSize !== effectivePageSize) {
      setPageSize(pagination.pageSize);
      setCurrentPage(1);
    } else if (pagination.current) {
      setCurrentPage(pagination.current);
    }
  };

  const sorter = (columnKey: string) => ({ sorter: true, sortOrder: sortFor(columnKey), key: columnKey });

  const columns: TableProps<ProjectSummaryModel>["columns"] = [
    {
      title: "Project",
      dataIndex: "name",
      ...sorter("name"),
      render: (name: string, record) =>
        record.id === "unassigned" ? (
          <em>Unassigned</em>
        ) : (
          <Link to={`/organizations/${id}/projects/${record.id}`}>
            <strong>{name}</strong>
          </Link>
        ),
    },
    {
      title: "Health",
      key: "health",
      render: (_: unknown, record) => <ProjectHealthBar summary={record} />,
    },
    { title: "Workspaces", dataIndex: "workspaceCount", ...sorter("workspaceCount") },
    {
      title: "Attention",
      dataIndex: "attentionCount",
      ...sorter("attentionCount"),
      render: (count: number) => <span className={`project-pill project-pill-${attentionTone(count)}`}>{count}</span>,
    },
    {
      title: "Policy",
      key: "policy",
      sorter: true,
      sortOrder: sortFor("policy"),
      render: (_: unknown, record) => {
        const rollup = policyRollup(record);
        return <span className={`project-pill project-pill-${rollup.tone}`}>{rollup.text}</span>;
      },
    },
    {
      title: "Teams",
      dataIndex: "teamCount",
      ...sorter("teamCount"),
      render: (count: number, record) => (record.id === "unassigned" ? "-" : count),
    },
    {
      title: "Last run",
      dataIndex: "lastRunAt",
      ...sorter("lastRunAt"),
      render: (value?: string) => shortAgo(value),
    },
  ];

  const filtersActive = debouncedSearch.trim() !== "" || chip !== "all";
  const showUnassigned = unassigned !== null && !filtersActive && currentPage === 1;
  const firstLoad = !hasLoadedOnce.current && loading;
  const noProjectsAtAll = hasLoadedOnce.current && !loading && totalRecords === 0 && !filtersActive && !unassigned;
  const rows = showUnassigned ? [...projects, unassigned as ProjectSummaryModel] : projects;

  return (
    <PageWrapper
      title={`Projects${counts ? ` (${counts.all})` : ""}`}
      subTitle={`Projects in the ${organizationName} organization`}
      loadingText="Loading projects..."
      loading={false}
      error={error}
      breadcrumbs={[
        { label: organizationName, path: "/" },
        { label: "Projects", path: `/organizations/${id}/projects` },
      ]}
      actions={
        <Button icon={<PlusOutlined />} type="primary" onClick={openCreate} disabled={!permissions.manageWorkspace}>
          New project
        </Button>
      }
    >
      {noProjectsAtAll ? (
        <Flex justify="center">
          <NoProjects canCreate={permissions.manageWorkspace} onCreate={openCreate} />
        </Flex>
      ) : (
        <>
          <Flex gap={12} wrap align="center" className="projects-toolbar">
            <Input
              aria-label="Search projects by name"
              placeholder="Search name or description"
              prefix={<SearchOutlined />}
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              allowClear
              className="projects-search-input"
            />
            <Select
              aria-label="Sort projects"
              value={PROJECT_SORT_PRESETS.some((preset) => preset.value === sort) ? sort : undefined}
              placeholder="Custom order"
              options={PROJECT_SORT_PRESETS}
              onChange={(value) => {
                setSort(value);
                setCurrentPage(1);
              }}
              style={{ minWidth: 200 }}
            />
            <Segmented
              value={effectiveView}
              onChange={(value) => changeView(value as ViewMode)}
              options={[
                { value: "cards", icon: <AppstoreOutlined />, label: "Cards" },
                { value: "table", icon: <UnorderedListOutlined />, label: "Table" },
              ]}
            />
          </Flex>
          <ProjectFilterChips
            value={chip}
            onChange={(next) => {
              setChip(next);
              setCurrentPage(1);
            }}
            counts={counts}
          />
          {countsFailed && <CountsUnavailable onRetry={() => loadCounts(groups)} />}

          {firstLoad ? (
            <ProjectsSkeleton view={effectiveView} />
          ) : rows.length === 0 ? (
            <NoMatch search={debouncedSearch.trim()} onClear={clearFilters} />
          ) : effectiveView === "table" ? (
            <>
              {/* The Unassigned entry is not part of the server page. Inside the paged table antd would cut it off
                  whenever the page is full, so it is a table of its own under the page. */}
              {(projects.length > 0 || !showUnassigned) && (
                <Table<ProjectSummaryModel>
                  dataSource={projects}
                  columns={columns}
                  rowKey="id"
                  loading={loading}
                  onChange={handleTableChange}
                  pagination={{
                    showSizeChanger: true,
                    pageSizeOptions: [24, 50, 100],
                    pageSize: effectivePageSize,
                    current: currentPage,
                    total: totalRecords,
                  }}
                />
              )}
              {showUnassigned && (
                <Table<ProjectSummaryModel>
                  className="project-unassigned-table"
                  dataSource={[unassigned as ProjectSummaryModel]}
                  columns={columns}
                  rowKey="id"
                  showHeader={false}
                  pagination={false}
                  rowClassName="project-unassigned-row"
                />
              )}
            </>
          ) : (
            <>
              <div className="projects-card-grid">
                {rows.map((project) => (
                  <ProjectCard
                    key={project.id}
                    orgid={id!}
                    project={project}
                    unassigned={project.id === "unassigned"}
                  />
                ))}
              </div>
              <Pagination
                current={currentPage}
                pageSize={effectivePageSize}
                total={totalRecords}
                showSizeChanger
                pageSizeOptions={[24, 50, 100]}
                onChange={(page, size) => {
                  setCurrentPage(page);
                  setPageSize(size);
                }}
              />
            </>
          )}
          {!firstLoad && rows.length > 0 && <HealthLegend />}
        </>
      )}
      <Modal
        title="New project"
        open={modalOpen}
        onOk={handleOk}
        onCancel={() => setModalOpen(false)}
        confirmLoading={submitting}
        okText="Create"
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="name"
            label="Name"
            rules={[
              { required: true, message: "Name is required" },
              { max: 256, message: "Project name must be 256 characters or fewer" },
            ]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="description"
            label="Description"
            rules={[{ max: 256, message: "Project description must be 256 characters or fewer" }]}
          >
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>
    </PageWrapper>
  );
}
