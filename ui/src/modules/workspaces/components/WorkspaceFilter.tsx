import { PlusOutlined, DeleteOutlined, DownOutlined } from "@ant-design/icons";
import { Row, Col, Select, Input, Button, Popover, Badge, Switch, Flex, Typography, Tag } from "antd";
import clsx from "classnames";
import { useEffect, useMemo, useState } from "react";
import organizationService from "@/modules/organizations/organizationService";
import { TagModel } from "@/modules/organizations/types";
import { WorkspaceSortOption, WORKSPACE_SORT_OPTIONS } from "../utils/workspaceSort";
import { WorkspaceStatusFilter } from "../utils/workspaceFilter";
import { WORKSPACE_STATUS_PALETTE } from "../utils/workspaceStatusPalette";
import "./WorkspaceFilter.css";

type Props = {
  organizationId: string;
  status: string;
  onStatusChange: (status: string) => void;
  search: string;
  onSearchChange: (search: string) => void;
  tagIds: string[];
  onTagIdsChange: (tagIds: string[]) => void;
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
  search,
  onSearchChange,
  tagIds,
  onTagIdsChange,
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

  const options = useMemo(() => {
    return tags.map((t) => ({ label: t.name, value: t.id }));
  }, [tags]);

  const [isTagsPopoverOpen, setIsTagsPopoverOpen] = useState(false);
  const [tempTagRows, setTempTagRows] = useState<{ key: string; value: string }[]>([{ key: "", value: "" }]);

  const handleOpenChange = (newOpen: boolean) => {
    if (newOpen) {
      if (tagIds.length > 0) {
        setTempTagRows(tagIds.map((tagId) => ({ key: tagId, value: "" })));
      } else {
        setTempTagRows([{ key: "", value: "" }]);
      }
    }
    setIsTagsPopoverOpen(newOpen);
  };

  const handleApplyTags = () => {
    const validTags = tempTagRows.map((r) => r.key).filter((k) => k);
    onTagIdsChange(validTags);
    setIsTagsPopoverOpen(false);
  };

  const handleCancelTags = () => {
    setIsTagsPopoverOpen(false);
  };

  const addFilterRow = () => {
    setTempTagRows([...tempTagRows, { key: "", value: "" }]);
  };

  const removeFilterRow = (index: number) => {
    const newRows = [...tempTagRows];
    newRows.splice(index, 1);
    setTempTagRows(newRows);
  };

  const updateFilterRow = (index: number, field: "key" | "value", val: string) => {
    const newRows = [...tempTagRows];
    newRows[index] = { ...newRows[index], [field]: val };
    setTempTagRows(newRows);
  };

  const tagsContent = (
    <div className="filter-popover-content">
      <div className="filter-popover-header">
        <Row gutter={12}>
          <Col span={11}>Tag key</Col>
          <Col span={11}>Tag value (Optional)</Col>
          <Col span={2}></Col>
        </Row>
      </div>
      {tempTagRows.map((row, index) => (
        <div key={index} className="filter-row">
          <Select
            showSearch
            placeholder="Select tag"
            optionFilterProp="children"
            options={options}
            value={row.key || undefined}
            onChange={(val) => updateFilterRow(index, "key", val)}
            filterOption={(input, option) => (option?.label ?? "").toLowerCase().includes(input.toLowerCase())}
            style={{ width: "45%" }}
          />
          <Input
            placeholder="Value"
            value={row.value}
            onChange={(e) => updateFilterRow(index, "value", e.target.value)}
            style={{ width: "45%" }}
          />
          {tempTagRows.length > 1 && (
            <DeleteOutlined className="filter-row-remove" onClick={() => removeFilterRow(index)} />
          )}
        </div>
      ))}
      <button type="button" className="add-filter-btn" onClick={addFilterRow}>
        <PlusOutlined /> Filter by another tag
      </button>
      <div className="filter-footer">
        <Button onClick={handleCancelTags}>Cancel</Button>
        <Button type="primary" onClick={handleApplyTags}>
          Apply Filter
        </Button>
      </div>
    </div>
  );

  const controlSize = compact ? "small" : "middle";

  const hasActiveFilters = status !== WorkspaceStatusFilter.All || tagIds.length > 0 || !!projectId;
  const handleClearFilters = () => {
    onStatusChange(WorkspaceStatusFilter.All);
    onTagIdsChange([]);
    onProjectIdChange(null);
  };

  return (
    <div className={clsx("workspace-filter-container", { "workspace-filter-container--compact": compact })}>
      {/* Top row: Search (+ project picker and group-by-project in compact mode) */}
      <div className="workspace-filter-search-row">
        <Input.Search
          size="large"
          placeholder="Search by name..."
          value={searchInputValue}
          onChange={(e) => {
            const value = e.target.value;
            setSearchInputValue(value);
            // Compact ("New") view filters live as you type. Legacy view keeps its
            // original behavior of only committing the search on Enter/search-click.
            if (compact) {
              onSearchChange(value);
            }
          }}
          onSearch={() => onSearchChange(searchInputValue)}
          allowClear
          className="workspace-search-input"
        />
        {compact && projects.length > 0 && (
          <Select
            size="large"
            showSearch
            allowClear
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
        {compact && (
          <Flex align="center" gap={8} className="workspace-group-toggle">
            <Switch checked={groupByProject} onChange={(checked) => onGroupByProjectChange(checked)} />
            <Typography.Text style={{ fontSize: 14 }}>Group by project</Typography.Text>
          </Flex>
        )}
      </div>

      {/* Bottom row: Status (left) | Tags + Sort (right) */}
      <div className="workspace-filter-bar">
        <div className="workspace-filter-left">
          <div className="workspace-status-pills">
            {WORKSPACE_STATUS_PALETTE.map((opt) => {
              const active = status === opt.value;
              return (
                <button
                  key={opt.value}
                  type="button"
                  aria-pressed={active}
                  className={clsx("workspace-status-pill", { "workspace-status-pill--active": active })}
                  style={opt.color ? { color: opt.color, borderColor: active ? opt.color : undefined } : undefined}
                  onClick={() => onStatusChange(opt.value)}
                >
                  {opt.icon}
                  {opt.label}
                  {statusCounts?.[opt.value] !== undefined && (
                    <span className="workspace-status-count">{statusCounts[opt.value]}</span>
                  )}
                </button>
              );
            })}
            {hasActiveFilters && (
              <button type="button" className="workspace-clear-filters" onClick={handleClearFilters}>
                Clear all
              </button>
            )}
          </div>
        </div>

        <div className="workspace-filter-right">
          {!compact && projects.length > 0 && (
            <Select
              size={controlSize}
              allowClear
              placeholder="Project"
              value={projectId || undefined}
              onChange={(val) => onProjectIdChange(val ?? null)}
              options={[
                { label: "(Unassigned)", value: "__unassigned__" },
                ...projects.map((p) => ({ label: p.name, value: p.id })),
              ]}
              style={{ minWidth: 140 }}
            />
          )}
          <Popover
            content={tagsContent}
            trigger="click"
            open={isTagsPopoverOpen}
            onOpenChange={handleOpenChange}
            placement="bottomRight"
            overlayClassName="workspace-filter-popover"
          >
            <Button size={controlSize} className={`filter-button ${tagIds.length > 0 ? "active" : ""}`}>
              Tags
              {tagIds.length > 0 && <Badge count={tagIds.length} style={{ backgroundColor: "#52c41a" }} />}
              <DownOutlined />
            </Button>
          </Popover>
          <Select
            size={controlSize}
            value={sortOption}
            onChange={onSortChange}
            options={WORKSPACE_SORT_OPTIONS}
            className="workspace-sort-select"
            placeholder="Sort by"
          />
        </div>
      </div>

      {compact && tagIds.length > 0 && (
        <Flex align="center" gap={6} wrap className="workspace-active-tags-row">
          <Typography.Text style={{ fontSize: 12 }} type="secondary">
            Filtering by tag:
          </Typography.Text>
          {tagIds.map((tagId) => {
            const name = tags.find((t) => t.id === tagId)?.name ?? tagId;
            return (
              <Tag
                key={tagId}
                closable
                onClose={(e) => {
                  e.preventDefault();
                  onTagIdsChange(tagIds.filter((t) => t !== tagId));
                }}
              >
                {name}
              </Tag>
            );
          })}
        </Flex>
      )}
    </div>
  );
}
