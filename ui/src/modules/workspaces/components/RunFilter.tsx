import { BarsOutlined, SearchOutlined } from "@ant-design/icons";
import { Input, Select } from "antd";
import clsx from "classnames";
import { cloneElement } from "react";
import { JobStatus } from "../../../domain/types";
import { getWorkspaceStatusIcon } from "../utils/workspaceStatusIcon";
import { getWorkspaceStatusText } from "../utils/workspaceStatusText";
import "./WorkspaceFilter.css";

export const ALL_RUNS = "All";

// Chip order; a status nobody is in is hidden unless it is the selected one.
const RUN_STATUSES: string[] = [
  JobStatus.WaitingApproval,
  JobStatus.Failed,
  JobStatus.Running,
  JobStatus.Pending,
  JobStatus.Queue,
  JobStatus.Completed,
  JobStatus.NoChanges,
  JobStatus.NotExecuted,
  JobStatus.Approved,
  JobStatus.Rejected,
  JobStatus.Cancelled,
];

type Props = {
  status: string;
  onStatusChange: (status: string) => void;
  statusCounts: Record<string, number>;
  templateIds: string[];
  onTemplateIdsChange: (ids: string[]) => void;
  templateOptions: { label: string; value: string }[];
  search: string;
  onSearchChange: (search: string) => void;
};

export default function RunFilter({
  status,
  onStatusChange,
  statusCounts,
  templateIds,
  onTemplateIdsChange,
  templateOptions,
  search,
  onSearchChange,
}: Props) {
  const statuses = [...new Set([ALL_RUNS, ...RUN_STATUSES, ...Object.keys(statusCounts)])].filter(
    (s) => s === ALL_RUNS || s === status || (statusCounts[s] ?? 0) > 0
  );
  const hasActiveFilters = status !== ALL_RUNS || templateIds.length > 0 || search !== "";

  return (
    <div className="workspace-filter-container">
      <div className="workspace-filter-controls">
        <Input
          aria-label="Search runs"
          placeholder="Search by title, author or commit"
          prefix={<SearchOutlined />}
          value={search}
          onChange={(e) => onSearchChange(e.target.value)}
          allowClear
          className="workspace-search-input"
        />
        <Select
          mode="multiple"
          allowClear
          aria-label="Templates"
          placeholder="All templates"
          maxTagCount="responsive"
          value={templateIds}
          onChange={onTemplateIdsChange}
          options={templateOptions}
          optionFilterProp="label"
          notFoundContent="No templates in these runs"
          className="run-template-select"
        />
      </div>
      <div className="workspace-status-pills" role="group" aria-label="Filter by status">
        {statuses.map((value) => (
          <button
            key={value}
            type="button"
            aria-pressed={status === value}
            data-status={value}
            className={clsx("workspace-status-pill", { "workspace-status-pill--active": status === value })}
            onClick={() => onStatusChange(value)}
          >
            {value === ALL_RUNS ? (
              <BarsOutlined aria-hidden />
            ) : (
              cloneElement(getWorkspaceStatusIcon(value), { spin: false, "aria-hidden": true })
            )}
            {value === ALL_RUNS ? "All" : getWorkspaceStatusText(value)}
            <span className="workspace-status-count">{statusCounts[value] ?? 0}</span>
          </button>
        ))}
        {hasActiveFilters && (
          <button
            type="button"
            className="workspace-clear-filters"
            onClick={() => {
              onStatusChange(ALL_RUNS);
              onTemplateIdsChange([]);
              onSearchChange("");
            }}
          >
            Clear all
          </button>
        )}
      </div>
    </div>
  );
}
