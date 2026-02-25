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
import { Row, Col, Select, Input, Button, Popover, Badge, Segmented } from "antd";
import { JobStatus } from "../../../domain/types";
import { useEffect, useMemo, useState } from "react";
import { WorkspaceListItem } from "@/modules/workspaces/types";
import organizationService from "@/modules/organizations/organizationService";
import useApiRequest from "@/modules/api/useApiRequest";
import { mapTag } from "@/modules/organizations/organizationMapper";
import { TagModel } from "@/modules/organizations/types";
import { WorkspaceSortOption, WORKSPACE_SORT_OPTIONS } from "../utils/workspaceSort";
import "./WorkspaceFilter.css";

type Props = {
  organizationId: string;
  workspaces: WorkspaceListItem[];
  onFiltered: (workspaces: WorkspaceListItem[]) => void;
  onTagsLoaded: (tags: TagModel[]) => void;
  sortOption: WorkspaceSortOption;
  onSortChange: (option: WorkspaceSortOption) => void;
};

enum Additional {
  All = "All",
  NeverExecuted = "NeverExecuted",
}

export default function WorkspaceFilter({
  workspaces,
  onFiltered,
  organizationId,
  onTagsLoaded,
  sortOption,
  onSortChange,
}: Props) {
  const [statusFilter, setStatusFilter] = useState<string>(sessionStorage.getItem("filterValue") || "All");
  const [searchFilter, setSearchFilter] = useState(sessionStorage.getItem("searchValue") || "");
  const [tagsFilter, setTagsFilter] = useState<string[]>((sessionStorage.getItem("selectedTags") as any) || []);
  const [tags, setTags] = useState<TagModel[]>([]);

  const { execute } = useApiRequest({
    action: () => organizationService.listOrganizationTags(organizationId),
    onReturn: (data) => {
      const mapped = data.map(mapTag);
      setTags(mapped);
      onTagsLoaded(mapped);
    },
  });

  const options = useMemo(() => {
    return tags.map((t) => ({ label: t.name, value: t.id }));
  }, [tags]);

  function filterItems(isClear?: boolean) {
    let internalSearchFilter = searchFilter;
    if (isClear) internalSearchFilter = "";

    let filteredWorkspaces =
      statusFilter === Additional.All
        ? workspaces
        : statusFilter === Additional.NeverExecuted
          ? workspaces.filter((x) => !x.lastStatus)
          : workspaces.filter((x) => x.lastStatus === statusFilter);

    filteredWorkspaces = filteredWorkspaces.filter((workspace) => {
      if (workspace.description) {
        return workspace.name.includes(internalSearchFilter) || workspace.description?.includes(internalSearchFilter);
      } else {
        return workspace.name.includes(internalSearchFilter);
      }
    });

    filteredWorkspaces = filteredWorkspaces.filter((workspace) => {
      if (tagsFilter && tagsFilter.length > 0) {
        return workspace.tags?.some((tag) => tagsFilter.includes(tag));
      } else {
        return true;
      }
    });

    onFiltered(filteredWorkspaces);
  }

  useEffect(() => {
    filterItems();
  }, [statusFilter, tagsFilter]);
  useEffect(() => {
    execute();
  }, []);

  const [isTagsPopoverOpen, setIsTagsPopoverOpen] = useState(false);
  const [tempTagRows, setTempTagRows] = useState<{ key: string; value: string }[]>([{ key: "", value: "" }]);

  const handleOpenChange = (newOpen: boolean) => {
    if (newOpen) {
      if (tagsFilter.length > 0) {
        setTempTagRows(tagsFilter.map((tagId) => ({ key: tagId, value: "" })));
      } else {
        setTempTagRows([{ key: "", value: "" }]);
      }
    }
    setIsTagsPopoverOpen(newOpen);
  };

  const handleApplyTags = () => {
    const validTags = tempTagRows.map((r) => r.key).filter((k) => k);
    setTagsFilter(validTags);
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

  return (
    <div className="workspace-filter-container">
      {/* Top row: Search */}
      <div className="workspace-filter-search-row">
        <Input.Search
          placeholder="Search by name..."
          value={searchFilter}
          onChange={(e) => setSearchFilter(e.target.value)}
          onSearch={() => filterItems()}
          allowClear
          className="workspace-search-input"
        />
      </div>

      {/* Bottom row: Status (left) | Tags + Sort (right) */}
      <div className="workspace-filter-bar">
        <div className="workspace-filter-left">
          <Segmented
            onChange={setStatusFilter}
            value={statusFilter}
            options={[
              {
                label: "All",
                value: Additional.All,
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
                value: Additional.NeverExecuted,
                icon: <InfoCircleOutlined />,
              },
            ]}
          />
        </div>

        <div className="workspace-filter-right">
          <Popover
            content={tagsContent}
            trigger="click"
            open={isTagsPopoverOpen}
            onOpenChange={handleOpenChange}
            placement="bottomRight"
            overlayClassName="workspace-filter-popover"
          >
            <Button className={`filter-button ${tagsFilter.length > 0 ? "active" : ""}`}>
              Tags
              {tagsFilter.length > 0 && <Badge count={tagsFilter.length} style={{ backgroundColor: "#52c41a" }} />}
              <DownOutlined />
            </Button>
          </Popover>
          <Select
            value={sortOption}
            onChange={onSortChange}
            options={WORKSPACE_SORT_OPTIONS}
            className="workspace-sort-select"
            placeholder="Sort by"
          />
        </div>
      </div>
    </div>
  );
}
