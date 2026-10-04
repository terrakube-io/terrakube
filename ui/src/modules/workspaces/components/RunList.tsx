import { Avatar, Button, Pagination, Tag, Tooltip, Typography } from "antd";
import { FieldTimeOutlined, UserOutlined, WarningOutlined } from "@ant-design/icons";
import { FiGitCommit } from "react-icons/fi";
import { Link } from "react-router-dom";
import { FlatJob, formatJobVia } from "../../../domain/types";
import { useState, useEffect, useMemo } from "react";
import axiosInstance from "../../../config/axiosConfig";
import { ORGANIZATION_ARCHIVE } from "../../../config/actionTypes";
import RunFilter, { ALL_RUNS } from "./RunFilter";
import WorkspaceStatusTag from "@/components/display/WorkspaceStatusTag";
import { EmptyState } from "@/components/feedback/EmptyState";
import { formatDateTime, formatDuration } from "@/modules/utils/dates";
import { isTerminalStatus } from "@/domain/Jobs/stepStatus";
import "@/domain/Jobs/runTokens.css";
import "./RunList.css";

// Storage keys for persisting pagination and filter state
const RUNS_PAGE_KEY = "runsCurrentPage";
const RUNS_FILTER_KEY = "runsFilterValue";
const RUNS_TEMPLATE_FILTER_KEY = "runsTemplateFilter";
const PAGE_SIZE = 10;

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
  runLink: (id: string) => string;
};

export default function RunList({ jobs, onRunClick, runLink }: Props) {
  const [currentPage, setCurrentPage] = useState<number>(parseInt(sessionStorage.getItem(RUNS_PAGE_KEY) || "1"));
  const [templateNames, setTemplateNames] = useState<{ [key: string]: string }>({});
  const organizationId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);
  const [status, setStatus] = useState<string>(sessionStorage.getItem(RUNS_FILTER_KEY) || ALL_RUNS);
  const [templateIds, setTemplateIds] = useState<string[]>(
    safeJsonParse(sessionStorage.getItem(RUNS_TEMPLATE_FILTER_KEY), [])
  );
  const [search, setSearch] = useState("");

  useEffect(() => {
    sessionStorage.setItem(RUNS_PAGE_KEY, currentPage.toString());
  }, [currentPage]);

  useEffect(() => {
    sessionStorage.setItem(RUNS_FILTER_KEY, status);
    sessionStorage.setItem(RUNS_TEMPLATE_FILTER_KEY, JSON.stringify(templateIds));
  }, [status, templateIds]);

  // Load all templates to map template IDs to names
  useEffect(() => {
    axiosInstance.get(`organization/${organizationId}/template`).then((response) => {
      const templateMap: { [key: string]: string } = {};
      response.data.data.forEach((template: any) => {
        templateMap[template.id] = template.attributes.name;
      });
      setTemplateNames(templateMap);
    });
  }, [organizationId]);

  const statusCounts = useMemo(() => {
    const counts: Record<string, number> = { [ALL_RUNS]: jobs.length };
    jobs.forEach((job) => {
      counts[job.status] = (counts[job.status] ?? 0) + 1;
    });
    return counts;
  }, [jobs]);

  const templateOptions = useMemo(
    () =>
      [...new Set(jobs.map((job) => job.templateReference).filter((id): id is string => Boolean(id)))].map((id) => ({
        label: templateNames[id] || `Template ${id}`,
        value: id,
      })),
    [jobs, templateNames]
  );

  const sortedJobs = useMemo(() => {
    const query = search.trim().toLowerCase();
    return jobs
      .filter((job) => status === ALL_RUNS || job.status === status)
      .filter(
        (job) => templateIds.length === 0 || (job.templateReference && templateIds.includes(job.templateReference))
      )
      .filter(
        (job) =>
          !query || [job.title, job.createdBy, job.commitId, `#${job.id}`].some((v) => v?.toLowerCase().includes(query))
      )
      .sort((a, b) => parseInt(b.id) - parseInt(a.id));
  }, [jobs, status, templateIds, search]);

  const paginatedJobs = sortedJobs.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE);

  // The newest run of the workspace, whatever the filters show.
  const currentId = jobs.reduce((max, job) => Math.max(max, parseInt(job.id)), -1).toString();

  // Back to the first page when the filters leave fewer pages than the saved one.
  useEffect(() => {
    if (currentPage > 1 && Math.ceil(sortedJobs.length / PAGE_SIZE) < currentPage) {
      setCurrentPage(1);
    }
  }, [sortedJobs.length, currentPage]);

  const clearFilters = () => {
    setStatus(ALL_RUNS);
    setTemplateIds([]);
    setSearch("");
  };

  return (
    <section className="run-list-section" aria-labelledby="run-list-title">
      <Typography.Title level={3} id="run-list-title">
        Runs ({jobs.length})
      </Typography.Title>
      {jobs.length === 0 ? (
        <EmptyState description="No runs yet. Use Run now to start the first plan for this workspace." />
      ) : (
        <>
          <RunFilter
            status={status}
            onStatusChange={setStatus}
            statusCounts={statusCounts}
            templateIds={templateIds}
            onTemplateIdsChange={setTemplateIds}
            templateOptions={templateOptions}
            search={search}
            onSearchChange={setSearch}
          />
          {sortedJobs.length === 0 ? (
            <EmptyState description="No runs match these filters.">
              <Button onClick={clearFilters}>Clear filters</Button>
            </EmptyState>
          ) : (
            <ul className="run-list">
              {paginatedJobs.map((item) => {
                const commitId = item.commitId && item.commitId !== "000000000" ? item.commitId : undefined;
                const templateName = item.templateReference ? templateNames[item.templateReference] : undefined;
                const duration = isTerminalStatus(item.status)
                  ? formatDuration(item.createdDate, item.updatedDate)
                  : null;
                return (
                  <li key={item.id} className="run-row">
                    <Avatar size={20} shape="square" icon={<UserOutlined />} />
                    <div className="run-row-body">
                      <div className="run-row-title">
                        <Link to={runLink(item.id)} onClick={() => onRunClick(item.id)} className="run-row-link">
                          {item.title}
                        </Link>
                        {item.id === currentId && <Tag>Current</Tag>}
                      </div>
                      <p className="run-row-meta">
                        <span>
                          <code>#{item.id}</code>
                        </span>
                        <span>
                          <strong>{item.createdBy}</strong>{" "}
                          <Tooltip title={formatDateTime(item.createdDate)}>
                            <span className="run-row-time">{item.latestChange}</span>
                          </Tooltip>
                        </span>
                        <span>via {formatJobVia(item.via)}</span>
                        {templateName && <span>{templateName}</span>}
                        {commitId && (
                          <span>
                            <FiGitCommit aria-label="commit" /> <code>{commitId.substring(0, 7)}</code>
                          </span>
                        )}
                        {duration && (
                          <span>
                            <FieldTimeOutlined aria-label="duration" /> {duration}
                          </span>
                        )}
                      </p>
                    </div>
                    <div className="run-row-status">
                      {item.prCommentError && (
                        <Tooltip title={item.prCommentError}>
                          <WarningOutlined className="run-row-warning" aria-label="Pull request comment failed" />
                        </Tooltip>
                      )}
                      <WorkspaceStatusTag status={item.status} />
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
          {sortedJobs.length > PAGE_SIZE && (
            <Pagination
              className="run-list-pagination"
              current={currentPage}
              pageSize={PAGE_SIZE}
              total={sortedJobs.length}
              onChange={setCurrentPage}
              showSizeChanger={false}
            />
          )}
        </>
      )}
    </section>
  );
}
