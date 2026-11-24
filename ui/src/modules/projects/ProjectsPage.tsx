import {
  Badge,
  Button,
  Card,
  Checkbox,
  Flex,
  Form,
  Input,
  Modal,
  Pagination,
  Segmented,
  Select,
  Table,
  Typography,
  message,
} from "antd";
import { AppstoreOutlined, PlusOutlined, SearchOutlined, UnorderedListOutlined } from "@ant-design/icons";
import { useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";
import projectService from "./projectService";
import useApiRequest from "@/modules/api/useApiRequest";
import { ProjectSummaryModel } from "@/domain/types";
import { ORGANIZATION_NAME } from "../../config/actionTypes";
import { useOrgPermissions } from "@/modules/permissions/useOrgPermissions";
import { PermissionErrorMessage } from "@/components/feedback/PermissionErrorMessage";
import { EmptyState } from "@/components/feedback/EmptyState";
import { ProjectHealthBar } from "./ProjectHealthBar";
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

const VIEW_STORAGE_KEY = "terrakube-projects-view";
const SEARCH_DEBOUNCE_MS = 300;
// 24 is the default, so it has to be one of the choices the size changer offers.
const PAGE_SIZE_OPTIONS = [12, 24, 48, 96];

function formatLastRun(value?: string): string {
  return value ? new Date(value).toLocaleString() : "Never";
}

export default function ProjectsPage({ organizationName, setOrganizationName }: Props) {
  const { id } = useParams();
  const { permissions } = useOrgPermissions(id);
  const [projects, setProjects] = useState<ProjectSummaryModel[]>([]);
  const [totalRecords, setTotalRecords] = useState(0);
  const [unassigned, setUnassigned] = useState(0);
  const [search, setSearch] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [attentionOnly, setAttentionOnly] = useState(false);
  const [sort, setSort] = useState(PROJECT_SORT_PRESETS[0].value);
  const [view, setView] = useState<ViewMode>(() =>
    localStorage.getItem(VIEW_STORAGE_KEY) === "cards" ? "cards" : "table"
  );
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(24);
  const [modalOpen, setModalOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<ProjectForm>();
  const hasLoadedOnce = useRef(false);

  const { loading, execute, error } = useApiRequest({
    action: () =>
      projectService.listProjectSummaries(id!, {
        search: debouncedSearch,
        attentionOnly,
        sort,
        page: currentPage,
        pageSize,
      }),
    onReturn: (data) => {
      hasLoadedOnce.current = true;
      setProjects(data.items);
      setTotalRecords(data.totalRecords);
      const stored = sessionStorage.getItem(ORGANIZATION_NAME);
      if (stored) setOrganizationName(stored);
    },
  });

  useEffect(() => {
    const handle = window.setTimeout(() => {
      setDebouncedSearch(search);
      setCurrentPage(1);
    }, SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(handle);
  }, [search]);

  useEffect(() => {
    execute();
  }, [id, debouncedSearch, attentionOnly, sort, currentPage, pageSize]);

  useEffect(() => {
    projectService.countUnassignedWorkspaces(id!).then((response) => {
      if (!response.isError) setUnassigned(response.data ?? 0);
    });
  }, [id]);

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
      setCurrentPage(1);
      message.success("Project created");
      setModalOpen(false);
      execute();
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
    localStorage.setItem(VIEW_STORAGE_KEY, next);
  };

  const attentionBadge = (record: ProjectSummaryModel) =>
    record.attentionCount > 0 ? (
      <Badge status="error" text={`${record.attentionCount} need attention`} />
    ) : (
      <Typography.Text type="secondary">Healthy</Typography.Text>
    );

  const columns = [
    {
      title: "Name",
      dataIndex: "name",
      key: "name",
      render: (_: unknown, record: ProjectSummaryModel) => (
        <Button type="link" className="projects-name-link">
          <Link to={`/organizations/${id}/projects/${record.id}`}>{record.name}</Link>
        </Button>
      ),
    },
    { title: "Description", dataIndex: "description", key: "description" },
    { title: "Workspaces", dataIndex: "workspaceCount", key: "workspaceCount" },
    {
      title: "Health",
      key: "health",
      render: (_: unknown, record: ProjectSummaryModel) => <ProjectHealthBar summary={record} />,
    },
    { title: "Status", key: "status", render: (_: unknown, record: ProjectSummaryModel) => attentionBadge(record) },
    {
      title: "Last run",
      key: "lastRunAt",
      render: (_: unknown, record: ProjectSummaryModel) => formatLastRun(record.lastRunAt),
    },
  ];

  const filtersActive = debouncedSearch.trim() !== "" || attentionOnly;
  const showEmptyOrg = !loading && hasLoadedOnce.current && totalRecords === 0 && !filtersActive;

  return (
    <PageWrapper
      title="Projects"
      subTitle={`Projects in the ${organizationName} organization`}
      loadingText="Loading projects..."
      loading={loading && !hasLoadedOnce.current}
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
      {showEmptyOrg ? (
        <Flex justify="center">
          <EmptyState description="You have not created any projects yet. Projects let you group related workspaces together.">
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate} disabled={!permissions.manageWorkspace}>
              Create a new project
            </Button>
          </EmptyState>
        </Flex>
      ) : (
        <>
          <Flex gap={12} wrap align="center" className="projects-toolbar">
            <Input
              aria-label="Search projects by name"
              placeholder="Search projects"
              prefix={<SearchOutlined />}
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              allowClear
              className="projects-search-input"
            />
            <Select
              aria-label="Sort projects"
              value={sort}
              options={PROJECT_SORT_PRESETS}
              onChange={(value) => {
                setSort(value);
                setCurrentPage(1);
              }}
              style={{ minWidth: 200 }}
            />
            <Checkbox
              checked={attentionOnly}
              onChange={(event) => {
                setAttentionOnly(event.target.checked);
                setCurrentPage(1);
              }}
            >
              Needs attention only
            </Checkbox>
            <Segmented
              value={view}
              onChange={(value) => changeView(value as ViewMode)}
              options={[
                { value: "table", icon: <UnorderedListOutlined />, label: "Table" },
                { value: "cards", icon: <AppstoreOutlined />, label: "Cards" },
              ]}
            />
          </Flex>
          {view === "table" ? (
            <Table
              dataSource={projects}
              columns={columns}
              rowKey="id"
              loading={loading}
              locale={{ emptyText: <EmptyState description="No projects found" /> }}
              pagination={{
                showSizeChanger: true,
                pageSizeOptions: PAGE_SIZE_OPTIONS,
                pageSize,
                current: currentPage,
                total: totalRecords,
                onChange: (page, size) => {
                  setCurrentPage(page);
                  setPageSize(size);
                },
              }}
            />
          ) : (
            <>
              {projects.length === 0 && !loading ? (
                <EmptyState description="No projects found" />
              ) : (
                <div className="projects-card-grid">
                  {projects.map((project) => (
                    <Card
                      key={project.id}
                      size="small"
                      title={<Link to={`/organizations/${id}/projects/${project.id}`}>{project.name}</Link>}
                    >
                      <Typography.Paragraph type="secondary" ellipsis={{ rows: 2 }}>
                        {project.description || "No description"}
                      </Typography.Paragraph>
                      <ProjectHealthBar summary={project} />
                      <Flex justify="space-between" style={{ marginTop: 8 }}>
                        <span>{project.workspaceCount} workspaces</span>
                        {attentionBadge(project)}
                      </Flex>
                    </Card>
                  ))}
                </div>
              )}
              <Pagination
                current={currentPage}
                pageSize={pageSize}
                total={totalRecords}
                showSizeChanger
                pageSizeOptions={PAGE_SIZE_OPTIONS}
                onChange={(page, size) => {
                  setCurrentPage(page);
                  setPageSize(size);
                }}
              />
            </>
          )}
          {unassigned > 0 && (
            <Typography.Paragraph type="secondary" style={{ marginTop: 16 }}>
              {unassigned} workspace{unassigned === 1 ? " is" : "s are"} not in a project.{" "}
              <Link to={`/organizations/${id}/workspaces`}>View workspaces</Link>
            </Typography.Paragraph>
          )}
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
