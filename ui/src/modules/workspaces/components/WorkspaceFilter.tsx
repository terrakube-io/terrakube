import { CloseOutlined, DeleteOutlined, DownOutlined, PlusOutlined, SearchOutlined } from "@ant-design/icons";
import { Button, Input, Popover, Select, Switch, Tag } from "antd";
import clsx from "classnames";
import { useEffect, useMemo, useState } from "react";
import organizationService from "@/modules/organizations/organizationService";
import { TagModel } from "@/modules/organizations/types";
import { WorkspaceTagFilter } from "@/modules/workspaces/types";
import { formatWorkspaceTag } from "../utils/workspaceTags";
import WorkspaceTagLabel from "./WorkspaceTagLabel";
import { TAG_VALUE_MAX_LENGTH } from "../utils/tagLimits";
import { WorkspaceSortOption, WORKSPACE_SORT_OPTIONS } from "../utils/workspaceSort";
import { WorkspaceStatusFilter, PolicyComplianceFilter } from "../utils/workspaceFilter";
import { WORKSPACE_STATUS_PALETTE } from "../utils/workspaceStatusPalette";
import "./WorkspaceFilter.css";

type Props = {
  organizationId: string;
  status: string;
  onStatusChange: (status: string) => void;
  policyStatus?: string;
  onPolicyStatusChange?: (status: string) => void;
  policyCounts?: Record<string, number>;
  search: string;
  onSearchChange: (search: string) => void;
  tagFilters: WorkspaceTagFilter[];
  onTagFiltersChange: (tagFilters: WorkspaceTagFilter[]) => void;
  projectId: string | null;
  onProjectIdChange: (projectId: string | null) => void;
  groupByProject: boolean;
  onGroupByProjectChange: (value: boolean) => void;
  onTagsLoaded: (tags: TagModel[]) => void;
  sortOption: WorkspaceSortOption;
  onSortChange: (option: WorkspaceSortOption) => void;
  projects?: { id: string; name: string }[];
  compact?: boolean;
  statusCounts?: Record<string, number>;
};

export default function WorkspaceFilter({
  organizationId,
  status,
  onStatusChange,
  policyStatus,
  onPolicyStatusChange,
  policyCounts,
  search,
  onSearchChange,
  tagFilters,
  onTagFiltersChange,
  projectId,
  onProjectIdChange,
  groupByProject,
  onGroupByProjectChange,
  onTagsLoaded,
  sortOption,
  onSortChange,
  projects = [],
  compact = false,
  statusCounts,
}: Props) {
  const [searchInputValue, setSearchInputValue] = useState(search);
  const [tags, setTags] = useState<TagModel[]>([]);

  useEffect(() => {
    let cancelled = false;

    organizationService
      .listOrganizationTags(organizationId)
      .then((loadedTags) => {
        if (cancelled) return;
        setTags(loadedTags);
        onTagsLoaded(loadedTags);
      })
      .catch((err: unknown) => {
        // eslint-disable-next-line no-console
        console.error(err);
      });

    return () => {
      cancelled = true;
    };
    // onTagsLoaded is intentionally excluded: the parent passes a new
    // inline function on every render, which would otherwise refetch in a loop.
  }, [organizationId]);

  const tagOptions = useMemo(() => tags.map((t) => ({ label: t.name, value: t.id })), [tags]);

  const [isTagsPopoverOpen, setIsTagsPopoverOpen] = useState(false);
  const [tempTagRows, setTempTagRows] = useState<{ key: string; value: string }[]>([{ key: "", value: "" }]);

  const handleOpenChange = (newOpen: boolean) => {
    if (newOpen) {
      if (tagFilters.length > 0) {
        setTempTagRows(tagFilters.map((filter) => ({ key: filter.tagId, value: filter.value ?? "" })));
      } else {
        setTempTagRows([{ key: "", value: "" }]);
      }
    }
    setIsTagsPopoverOpen(newOpen);
  };

  const handleApplyTags = () => {
    // A row without a value matches the key whatever value it carries.
    const validTags = tempTagRows
      .filter((row) => row.key)
      .map((row) => ({ tagId: row.key, value: row.value.trim() || undefined }));
    onTagFiltersChange(validTags);
    setIsTagsPopoverOpen(false);
  };

  const handleClearTags = () => {
    onTagFiltersChange([]);
    setIsTagsPopoverOpen(false);
  };

  const addFilterRow = () => {
    setTempTagRows([...tempTagRows, { key: "", value: "" }]);
  };

  // Removing the last row leaves an empty one, so the popover always has a row to fill in
  const removeFilterRow = (index: number) => {
    const newRows = tempTagRows.filter((_, current) => current !== index);
    setTempTagRows(newRows.length > 0 ? newRows : [{ key: "", value: "" }]);
  };

  const updateFilterRow = (index: number, field: "key" | "value", val: string) => {
    const newRows = [...tempTagRows];
    newRows[index] = { ...newRows[index], [field]: val };
    setTempTagRows(newRows);
  };

  const tagsContent = (
    <div className="workspace-tag-filter">
      <div className="workspace-tag-filter-header" aria-hidden="true">
        <span>Tag key</span>
        <span>Value (optional)</span>
      </div>
      {tempTagRows.map((row, index) => (
        <div key={index} className="workspace-tag-filter-row">
          <Select
            showSearch
            aria-label="Tag key"
            placeholder="Select a key"
            options={tagOptions}
            optionFilterProp="label"
            value={row.key || undefined}
            onChange={(val) => updateFilterRow(index, "key", val)}
            notFoundContent="No tags in this organization"
          />
          <Input
            aria-label="Tag value"
            placeholder="Any value"
            value={row.value}
            maxLength={TAG_VALUE_MAX_LENGTH}
            onChange={(e) => updateFilterRow(index, "value", e.target.value)}
          />
          <Button
            type="text"
            icon={<DeleteOutlined />}
            aria-label="Remove this tag filter"
            onClick={() => removeFilterRow(index)}
          />
        </div>
      ))}
      <Button type="link" icon={<PlusOutlined />} className="workspace-tag-filter-add" onClick={addFilterRow}>
        Filter by another tag
      </Button>
      <div className="workspace-tag-filter-footer">
        {tagFilters.length > 0 && (
          <Button type="link" className="workspace-tag-filter-clear" onClick={handleClearTags}>
            Clear
          </Button>
        )}
        <Button onClick={() => setIsTagsPopoverOpen(false)}>Cancel</Button>
        <Button type="primary" onClick={handleApplyTags}>
          Apply filter
        </Button>
      </div>
    </div>
  );

  const hasActiveFilters =
    status !== WorkspaceStatusFilter.All ||
    (policyStatus && policyStatus !== PolicyComplianceFilter.All) ||
    tagFilters.length > 0 ||
    !!projectId;
  const handleClearFilters = () => {
    onStatusChange(WorkspaceStatusFilter.All);
    if (onPolicyStatusChange) {
      onPolicyStatusChange(PolicyComplianceFilter.All);
    }
    onTagFiltersChange([]);
    onProjectIdChange(null);
  };

  const policyOptions = [
    { value: PolicyComplianceFilter.All, label: `All policies (${policyCounts?.All ?? 0})` },
    { value: PolicyComplianceFilter.Compliant, label: `Compliant (${policyCounts?.COMPLIANT ?? 0})` },
    { value: PolicyComplianceFilter.NonCompliant, label: `Non-compliant (${policyCounts?.NON_COMPLIANT ?? 0})` },
    { value: PolicyComplianceFilter.Exempted, label: `Exempted (${policyCounts?.EXEMPTED ?? 0})` },
    { value: PolicyComplianceFilter.Unknown, label: `Unknown (${policyCounts?.UNKNOWN ?? 0})` },
  ];

  // Statuses nobody is in stay out of the way; "All" and the selected one always show.
  const visibleStatuses = WORKSPACE_STATUS_PALETTE.filter(
    (opt) =>
      opt.value === WorkspaceStatusFilter.All ||
      opt.value === status ||
      statusCounts === undefined ||
      (statusCounts[opt.value] ?? 0) > 0
  );

  return (
    <div className={clsx("workspace-filter-container", { "workspace-filter-container--compact": compact })}>
      <div className="workspace-filter-controls">
        <Input
          aria-label="Search workspaces by name"
          placeholder="Search by name"
          prefix={<SearchOutlined />}
          value={searchInputValue}
          onChange={(e) => {
            setSearchInputValue(e.target.value);
            onSearchChange(e.target.value);
          }}
          allowClear
          className="workspace-search-input"
        />
        {projects.length > 0 && (
          <Select
            showSearch
            allowClear
            aria-label="Project"
            placeholder="All projects"
            value={projectId ?? undefined}
            onChange={(val) => onProjectIdChange(val ?? null)}
            optionFilterProp="label"
            options={[
              { label: "(Unassigned)", value: "__unassigned__" },
              ...projects.map((p) => ({ label: p.name, value: p.id })),
            ]}
            className="workspace-project-select"
          />
        )}
        {onPolicyStatusChange && (
          <Select
            aria-label="Policy status"
            value={policyStatus || PolicyComplianceFilter.All}
            onChange={(val) => onPolicyStatusChange(val)}
            options={policyOptions}
            className="workspace-policy-select"
            data-testid="workspace-policy-filter-select"
          />
        )}
        <Popover
          content={tagsContent}
          trigger="click"
          open={isTagsPopoverOpen}
          onOpenChange={handleOpenChange}
          placement="bottomRight"
          arrow={false}
        >
          <Button aria-haspopup="dialog" aria-expanded={isTagsPopoverOpen} className="workspace-tags-button">
            Tags
            {tagFilters.length > 0 && <span className="workspace-tags-count">{tagFilters.length}</span>}
            <DownOutlined />
          </Button>
        </Popover>
        <Select
          aria-label="Sort by"
          value={sortOption}
          onChange={onSortChange}
          options={WORKSPACE_SORT_OPTIONS}
          className="workspace-sort-select"
        />
      </div>

      <div className="workspace-filter-status-row">
        <div className="workspace-status-pills" role="group" aria-label="Filter by status">
          {visibleStatuses.map((opt) => (
            <button
              key={opt.value}
              type="button"
              aria-pressed={status === opt.value}
              data-status={opt.value}
              className={clsx("workspace-status-pill", { "workspace-status-pill--active": status === opt.value })}
              onClick={() => onStatusChange(opt.value)}
            >
              {opt.icon}
              {opt.label}
              {statusCounts?.[opt.value] !== undefined && (
                <span className="workspace-status-count">{statusCounts[opt.value]}</span>
              )}
            </button>
          ))}
          {hasActiveFilters && (
            <button type="button" className="workspace-clear-filters" onClick={handleClearFilters}>
              Clear all
            </button>
          )}
        </div>
        {compact && (
          <span className="workspace-group-toggle">
            <Switch
              id="workspace-group-by-project"
              size="small"
              checked={groupByProject}
              onChange={(checked) => onGroupByProjectChange(checked)}
            />
            <label htmlFor="workspace-group-by-project">Group by project</label>
          </span>
        )}
      </div>
      {tagFilters.length > 0 && (
        <div className="workspace-active-tags" role="group" aria-label="Active tag filters">
          <span className="workspace-active-tags-label">Filtering by tag</span>
          {tagFilters.map((filter) => (
            <Tag
              key={`${filter.tagId}:${filter.value ?? ""}`}
              title={formatWorkspaceTag(filter, tags)}
              closable
              closeIcon={<CloseOutlined aria-label={`Remove the tag filter ${formatWorkspaceTag(filter, tags)}`} />}
              onClose={(e) => {
                e.preventDefault();
                onTagFiltersChange(tagFilters.filter((current) => current !== filter));
              }}
            >
              <WorkspaceTagLabel binding={filter} tags={tags} />
            </Tag>
          ))}
        </div>
      )}
    </div>
  );
}
