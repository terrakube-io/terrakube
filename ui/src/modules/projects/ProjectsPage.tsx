import { Button, Flex, Form, Input, Modal, Pagination, Segmented, Select, Table, Tooltip, message } from "antd";
import type { InputRef, TableProps } from "antd";
import { AppstoreOutlined, PlusOutlined, SearchOutlined, UnorderedListOutlined } from "@ant-design/icons";
import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
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
import SavedViews from "./SavedViews";
import { viewQuery } from "./viewStore";
import { CountsUnavailable, NoMatch, NoProjects, ProjectsSkeleton } from "./ProjectStates";
import { ProjectChip, attentionTone, defaultView, policyRollup, shortAgo } from "./projectHealth";
import { attentionFilter, projectWorkspacesLink, readChoice, readPositiveInt, withViewState } from "./projectLinks";
import DisabledReason from "./DisabledReason";
import { useSlashToFocus } from "./useSlashToFocus";
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
const CHIPS = ["all", "attention", "mine", "empty", "stale"] as const;
const PAGE_SIZES = [24, 50, 100];
const SORT_FIELDS = new Set([
  "name",
  "workspaceCount",
  "attentionCount",
  "nonCompliantCount",
  "teamCount",
  "lastRunAt",
]);

// A sort comes from the address, so only "+field,-field" over known columns is accepted.
function readSort(value: string | null, fallback: string): string {
  if (!value) return fallback;
  const parts = value.split(",");
  const valid = parts.every((part) => /^[+-]/.test(part) && SORT_FIELDS.has(part.slice(1)));
  return valid ? value : fallback;
}
const SEARCH_DEBOUNCE_MS = 300;
const CARD_PAGE_SIZE = 24;
const TABLE_PAGE_SIZE = 50;

function storedView(): ViewMode | null {
  const stored = localStorage.getItem(VIEW_STORAGE_KEY);
  return stored === "cards" || stored === "table" ? stored : null;
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
  // The view lives in the address, so a filtered list can be shared and the back button keeps it.
  const [searchParams, setSearchParams] = useSearchParams();
  const initial = useRef(searchParams).current;
  const [search, setSearch] = useState(initial.get("q") ?? "");
  const [debouncedSearch, setDebouncedSearch] = useState(initial.get("q") ?? "");
  const [chip, setChip] = useState<ProjectChip>(readChoice<ProjectChip>(initial, "chip", CHIPS, "all"));
  const [sort, setSort] = useState(readSort(initial.get("sort"), PROJECT_SORT_PRESETS[0].value));
  const [view, setView] = useState<ViewMode | null>(
    readChoice<ViewMode | "">(initial, "view", ["cards", "table"], "") || storedView()
  );
  const [currentPage, setCurrentPage] = useState(readPositiveInt(initial, "page", 1));
  const [pageSize, setPageSize] = useState<number | null>(
    PAGE_SIZES.includes(readPositiveInt(initial, "size", 0)) ? readPositiveInt(initial, "size", 0) : null
  );
  const [modalOpen, setModalOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<ProjectForm>();
  const hasLoadedOnce = useRef(false);
  const searchRef = useRef<InputRef>(null);
  useSlashToFocus(searchRef);
  const groupsLoaded = useRef(false);

  // Until the first answer arrives the size of the catalogue is unknown, so the saved choice or the table is used.
  const effectiveView: ViewMode = view ?? (hasLoadedOnce.current && counts ? defaultView(counts.all) : "cards");
  const effectivePageSize = pageSize ?? (effectiveView === "cards" ? CARD_PAGE_SIZE : TABLE_PAGE_SIZE);

  const { loading, execute, error } = useApiRequest({
    action: () =>
      projectService.listProjectSummaries(id!, {
        search: debouncedSearch,
        chip,
        groups,
        sort,
        page: currentPage,
        pageSize: effectivePageSize,
      }),
    onReturn: (data) => {
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
    },
    [id]
  );

  useEffect(() => {
    projectService
      .getCurrentGroups()
      .catch(() => [] as string[])
      .then((current) => {
        setGroups(current);
        groupsLoaded.current = true;
        loadCounts(current);
      });
  }, [id, loadCounts]);

  useEffect(() => {
    const handle = window.setTimeout(() => {
      setDebouncedSearch(search);
      setCurrentPage(1);
    }, SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(handle);
  }, [search]);

  useEffect(() => {
    execute();
  }, [id, debouncedSearch, chip, groups, sort, currentPage, effectivePageSize]);

  useEffect(() => {
    const next = withViewState(
      searchParams,
      {
        q: debouncedSearch.trim(),
        chip,
        sort,
        view: view ?? undefined,
        page: currentPage,
        size: pageSize ?? undefined,
      },
      { chip: "all", sort: PROJECT_SORT_PRESETS[0].value, page: 1 }
    );
    if (next !== searchParams) setSearchParams(next, { replace: true });
  }, [debouncedSearch, chip, sort, view, currentPage, pageSize]);

  const applySavedView = (query: string) => {
    const params = new URLSearchParams(query);
    const q = params.get("q") ?? "";
    setSearch(q);
    setDebouncedSearch(q);
    setChip(readChoice<ProjectChip>(params, "chip", CHIPS, "all"));
    setSort(readSort(params.get("sort"), PROJECT_SORT_PRESETS[0].value));
    setCurrentPage(1);
  };

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
    localStorage.setItem(VIEW_STORAGE_KEY, next);
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
          <div className="project-name-cell">
            <Link to={`/organizations/${id}/projects/${record.id}`}>
              <strong>{name}</strong>
            </Link>
            {record.description && <div className="project-name-description">{record.description}</div>}
          </div>
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
      render: (count: number, record) => {
        const pill = <span className={`project-pill project-pill-${attentionTone(count)}`}>{count}</span>;
        if (count === 0 || record.id === "unassigned") return pill;
        // The number leads to the workspaces behind it.
        return (
          <Link
            to={projectWorkspacesLink(id!, record.id, attentionFilter(record))}
            aria-label={`${count} need attention in ${record.name}, show them`}
          >
            {pill}
          </Link>
        );
      },
    },
    {
      title: "Policy",
      key: "policy",
      sorter: true,
      sortOrder: sortFor("policy"),
      render: (_: unknown, record) => {
        const rollup = policyRollup(record);
        const pill = <span className={`project-pill project-pill-${rollup.tone}`}>{rollup.text}</span>;
        if (record.id === "unassigned" || record.nonCompliantCount === 0) return pill;
        return (
          <Link
            to={projectWorkspacesLink(id!, record.id, { policy: "NON_COMPLIANT" })}
            aria-label={`${rollup.text} in ${record.name}, show the workspaces`}
          >
            {pill}
          </Link>
        );
      },
    },
    {
      title: "Teams",
      dataIndex: "teamCount",
      ...sorter("teamCount"),
      render: (count: number, record) =>
        record.id === "unassigned" ? (
          "-"
        ) : permissions.manageWorkspace ? (
          count
        ) : (
          <Tooltip
            title={`Only users who manage project access can see team names. Total: ${count} team${count === 1 ? "" : "s"}.`}
          >
            <span aria-label={`${count} teams, names hidden`}>-</span>
          </Tooltip>
        ),
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
      width="full"
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
        <DisabledReason
          disabled={!permissions.manageWorkspace}
          reason="You need the Manage Workspaces permission to create projects."
        >
          <Button icon={<PlusOutlined />} type="primary" onClick={openCreate} disabled={!permissions.manageWorkspace}>
            New project
          </Button>
        </DisabledReason>
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
              ref={searchRef}
              suffix={search ? undefined : <kbd className="search-shortcut">/</kbd>}
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
            <SavedViews
              orgid={id!}
              currentQuery={viewQuery(
                withViewState(
                  new URLSearchParams(),
                  { q: debouncedSearch.trim(), chip, sort },
                  { chip: "all", sort: PROJECT_SORT_PRESETS[0].value }
                )
              )}
              onApply={applySavedView}
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
          {filtersActive && !firstLoad && (
            <div className="projects-results-summary" role="status" aria-live="polite">
              Showing {totalRecords} of {counts ? counts.all : totalRecords} project{totalRecords === 1 ? "" : "s"}
              {debouncedSearch.trim() ? ` matching "${debouncedSearch.trim()}"` : ""}.{" "}
              <Button type="link" size="small" onClick={clearFilters} className="projects-clear-filters">
                Clear filters
              </Button>
            </div>
          )}

          {firstLoad ? (
            <ProjectsSkeleton view={effectiveView} />
          ) : rows.length === 0 ? (
            <NoMatch search={debouncedSearch.trim()} onClear={clearFilters} />
          ) : effectiveView === "table" ? (
            <Table<ProjectSummaryModel>
              dataSource={rows}
              columns={columns}
              rowKey="id"
              loading={loading}
              rowClassName={(record) => (record.id === "unassigned" ? "project-unassigned-row" : "")}
              onChange={handleTableChange}
              pagination={{
                showSizeChanger: true,
                pageSizeOptions: [24, 50, 100],
                pageSize: effectivePageSize,
                current: currentPage,
                total: totalRecords,
              }}
            />
          ) : (
            <>
              <div className="projects-card-grid">
                {rows.map((project) => (
                  <ProjectCard
                    key={project.id}
                    orgid={id!}
                    project={project}
                    unassigned={project.id === "unassigned"}
                    canSeeTeams={permissions.manageWorkspace}
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
