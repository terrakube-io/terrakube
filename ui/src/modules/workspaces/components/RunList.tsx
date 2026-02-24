import { List, Avatar, Tag, Pagination, Tooltip, Button } from "antd";
import {
  UserOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  ExclamationCircleOutlined,
  StopOutlined,
  SyncOutlined,
} from "@ant-design/icons";
import { FlatJob, JobStatus } from "../../../domain/types";
import { useState, useEffect, useCallback } from "react";
import axiosInstance from "../../../config/axiosConfig";
import { ORGANIZATION_ARCHIVE } from "../../../config/actionTypes";
import RunFilter from "./RunFilter";

// Storage key for persisting pagination state
const RUNS_PAGE_KEY = "runsCurrentPage";
const RUNS_FILTER_KEY = "runsFilterValue";
const RUNS_TEMPLATE_FILTER_KEY = "runsTemplateFilter";

// Helper function to format date
const formatDate = (dateString?: string) => {
  if (!dateString) return "";
  try {
    const date = new Date(dateString);
    return date.toLocaleString();
  } catch {
    return dateString;
  }
};

// Safely parse JSON with a fallback value
const safeJsonParse = (jsonString: string | null, fallback: any): any => {
  if (!jsonString) return fallback;

  try {
    return JSON.parse(jsonString);
  } catch {
    return fallback;
  }
};

type Props = {
  jobs: FlatJob[];
  onRunClick: (id: string) => void;
};

export default function RunList({ jobs, onRunClick }: Props) {
  const [currentPage, setCurrentPage] = useState<number>(parseInt(sessionStorage.getItem(RUNS_PAGE_KEY) || "1"));
  const pageSize = 10;
  const [templateNames, setTemplateNames] = useState<{ [key: string]: string }>({});
  const organizationId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);
  const [filteredJobs, setFilteredJobs] = useState<FlatJob[]>(jobs);

  // Save pagination state to session storage
  useEffect(() => {
    sessionStorage.setItem(RUNS_PAGE_KEY, currentPage.toString());
  }, [currentPage]);

  // Load all templates to map template IDs to names
  useEffect(() => {
    axiosInstance.get(`organization/${organizationId}/template`).then((response) => {
      const templates = response.data.data;
      const templateMap: { [key: string]: string } = {};

      templates.forEach((template: any) => {
        templateMap[template.id] = template.attributes.name;
      });

      setTemplateNames(templateMap);
    });
  }, [organizationId]);

  // Filter jobs based on the current filter
  const applyFilter = useCallback((jobsToFilter: FlatJob[], filterValue: string) => {
    if (filterValue !== "All") {
      return jobsToFilter.filter((job) => job.status === filterValue);
    }
    return jobsToFilter;
  }, []);

  // Apply all filters (status and templates)
  const applyAllFilters = useCallback(
    (jobsToFilter: FlatJob[]) => {
      const statusFilter = sessionStorage.getItem(RUNS_FILTER_KEY) || "All";
      const templateFiltersStr = sessionStorage.getItem(RUNS_TEMPLATE_FILTER_KEY) || "[]";
      const templateFilters = safeJsonParse(templateFiltersStr, []) as string[];

      // Apply status filter
      let filtered = applyFilter(jobsToFilter, statusFilter);

      // Apply template filters
      if (templateFilters.length > 0) {
        filtered = filtered.filter((job) => {
          const templateId = (job as any).templateReference;
          return templateId && templateFilters.includes(templateId);
        });
      }

      return filtered;
    },
    [applyFilter]
  );

  // Update filtered jobs when the jobs prop changes, applying all filters
  useEffect(() => {
    setFilteredJobs(applyAllFilters(jobs));
  }, [jobs, applyAllFilters]);

  const getTemplateName = (job: FlatJob) => {
    const templateId = (job as any).templateReference;
    if (templateId && templateNames[templateId]) {
      return templateNames[templateId];
    }
    return "Terraform";
  };

  const renderStatusTag = (status: JobStatus, statusColor: string) => (
    <Tag
      icon={
        status === JobStatus.Completed ? (
          <CheckCircleOutlined />
        ) : status === JobStatus.NoChanges ? (
          <CheckCircleOutlined />
        ) : status === JobStatus.Running ? (
          <SyncOutlined spin />
        ) : status === JobStatus.WaitingApproval ? (
          <ExclamationCircleOutlined />
        ) : status === JobStatus.Cancelled ? (
          <StopOutlined />
        ) : status === JobStatus.Failed ? (
          <StopOutlined />
        ) : (
          <ClockCircleOutlined />
        )
      }
      color={statusColor}
    >
      {status.toLowerCase()}
    </Tag>
  );

  const sortedJobs = [...filteredJobs].sort((a, b) => parseInt(b.id) - parseInt(a.id));
  const paginatedJobs = sortedJobs.slice((currentPage - 1) * pageSize, currentPage * pageSize);

  // Find the job with highest ID to mark as current
  const highestId = sortedJobs.length > 0 ? sortedJobs[0].id : "-1";

  // Reset to first page when filters change, but not when jobs update due to refresh
  useEffect(() => {
    const savedPage = parseInt(sessionStorage.getItem(RUNS_PAGE_KEY) || "1");
    if (savedPage > 1 && Math.ceil(filteredJobs.length / pageSize) < savedPage) {
      setCurrentPage(1);
    }
  }, [filteredJobs]);

  // Page change handler
  const handlePageChange = (page: number) => {
    setCurrentPage(page);
  };

  return (
    <div>
      <h3>Run List</h3>
      <RunFilter jobs={jobs} onFiltered={setFilteredJobs} applyFilter={applyFilter} templateNames={templateNames} />
      <List
        itemLayout="horizontal"
        dataSource={paginatedJobs}
        renderItem={(item) => (
          <List.Item
            actions={[
              <div key="status" style={{ textAlign: "right" }}>
                {renderStatusTag(item.status, item.statusColor)}
                <div>
                  <Tooltip title={formatDate((item as any).createdDate)}>
                    <span className="metadata">{item.latestChange}</span>
                  </Tooltip>
                </div>
              </div>,
            ]}
          >
            <List.Item.Meta
              avatar={<Avatar shape="square" icon={<UserOutlined />} />}
              title={
                <span>
                  <Button type="link" onClick={() => onRunClick(item.id)} style={{ color: "inherit", padding: 0 }}>
                    {item.title}
                  </Button>
                  {item.id === highestId && <Tag style={{ marginLeft: 8 }}>CURRENT</Tag>}
                </span>
              }
              description={
                <span>
                  #job-{item.id} &nbsp;&nbsp;|&nbsp;&nbsp; <b>{item.createdBy}</b> triggered via{" "}
                  <b>{item.via || "UI"}</b> using template <b>{getTemplateName(item)}</b>
                  {item.commitId && item.commitId !== "000000000" && (
                    <>
                      &nbsp;&nbsp;|&nbsp;&nbsp;{" "}
                      <Button type="link" style={{ padding: 0 }}>
                        #{item.commitId.substring(0, 6)}
                      </Button>
                    </>
                  )}
                </span>
              }
            />
          </List.Item>
        )}
        pagination={false}
      />
      {sortedJobs.length > 0 && (
        <div style={{ textAlign: "right", marginTop: "16px" }}>
          <Pagination
            current={currentPage}
            pageSize={pageSize}
            total={sortedJobs.length}
            onChange={handlePageChange}
            showSizeChanger={false}
          />
        </div>
      )}
    </div>
  );
}
