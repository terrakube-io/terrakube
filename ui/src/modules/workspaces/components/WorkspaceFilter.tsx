import {
  BarsOutlined,
  ExclamationCircleOutlined,
  StopOutlined,
  SyncOutlined,
  CheckCircleOutlined,
  InfoCircleOutlined,
  PlusOutlined,
  DeleteOutlined,
  DownOutlined,
} from "@ant-design/icons";
import { Row, Col, Select, Input, Button, Popover, Badge, Segmented, Switch, Flex, Typography, Tag } from "antd";
import clsx from "classnames";
import { JobStatus } from "../../../domain/types";
import { useEffect, useMemo, useState } from "react";
import organizationService from "@/modules/organizations/organizationService";
import useApiRequest from "@/modules/api/useApiRequest";
import { mapTag } from "@/modules/organizations/organizationMapper";
import { TagModel } from "@/modules/organizations/types";
import { WorkspaceSortOption, WORKSPACE_SORT_OPTIONS } from "../utils/workspaceSort";
import { WorkspaceStatusFilter } from "../utils/workspaceFilter";
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
}: Props) {
  const [searchInputValue, setSearchInputValue] = useState(search);
  const [tags, setTags] = useState<TagModel[]>([]);
  const [projectSearch, setProjectSearch] = useState("");

  const projectOptions = useMemo(() => {
    const term = projectSearch.trim().toLowerCase();
    const matchingProjects = term ? projects.filter((p) => p.name.toLowerCase().includes(term)) : projects;
    return [
      { label: "All projects", value: "__all__" },
      ...matchingProjects.map((p) => ({ label: p.name, value: p.id })),
      { label: "(unassigned)", value: "__unassigned__" },
    ];
  }, [projects, projectSearch]);

  const { execute } = useApiRequest({
    action: () => organizationService.listOrganizationTags(organizationId),
    onReturn: (data) => {
      const mapped = data.map(mapTag);
      setTags(mapped);
      onTagsLoaded(mapped);
    },
  });

  useEffect(() => {
    execute();
  }, []);

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

  return (
    <div className={clsx("workspace-filter-container", { "workspace-filter-container--compact": compact })}>
      {/* Top row: Search */}
      <div className="workspace-filter-search-row">
        <Input.Search
          size={controlSize}
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
      </div>

      {/* Bottom row: Status (left) | Tags + Sort (right) */}
      <div className="workspace-filter-bar">
        <div className="workspace-filter-left">
          <Segmented
            size={controlSize}
            onChange={onStatusChange}
            value={status}
            options={[
              {
                label: "All",
                value: WorkspaceStatusFilter.All,
                icon: <BarsOutlined />,
              },
              {
                label: "Awaiting approval",
                value: JobStatus.WaitingApproval,
                icon: <ExclamationCircleOutlined style={{ color: "#fa8f37" }} />,
              },
              {
                label: "Failed",
                value: JobStatus.Failed,
                icon: <StopOutlined style={{ color: "#FB0136" }} />,
              },
              {
                label: "Running",
                value: JobStatus.Running,
                icon: <SyncOutlined style={{ color: "#108ee9" }} />,
              },
              {
                label: "Completed",
                value: JobStatus.Completed,
                icon: <CheckCircleOutlined style={{ color: "#2eb039" }} />,
              },
              {
                label: "Never Executed",
                value: WorkspaceStatusFilter.NeverExecuted,
                icon: <InfoCircleOutlined />,
              },
            ]}
          />
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

      {compact && (
        <div className="workspace-filter-projects-row">
          {projects.length > 0 && (
            <Input
              size={controlSize}
              placeholder="Search projects..."
              allowClear
              value={projectSearch}
              onChange={(e) => setProjectSearch(e.target.value)}
              className="workspace-project-search"
            />
          )}
          <div className="workspace-project-scroll">
            <Segmented
              size={controlSize}
              value={projectId ?? "__all__"}
              onChange={(val) => onProjectIdChange(val === "__all__" ? null : (val as string))}
              options={projectOptions}
            />
          </div>
          <Flex align="center" gap={6} className="workspace-group-toggle">
            <Switch size="small" checked={groupByProject} onChange={(checked) => onGroupByProjectChange(checked)} />
            <Typography.Text style={{ fontSize: 12 }}>Group by project</Typography.Text>
          </Flex>
        </div>
      )}

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
