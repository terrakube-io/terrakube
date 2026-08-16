import { Tag, Typography, Pagination } from "antd";
import {
  FolderOutlined,
  ClockCircleOutlined,
  LockOutlined,
  CaretUpOutlined,
  CaretDownOutlined,
} from "@ant-design/icons";
import { DateTime } from "luxon";
import { useEffect, useMemo, useState, type KeyboardEvent } from "react";
import { Link } from "react-router-dom";
import { WorkspaceListItem } from "@/modules/workspaces/types";
import WorkspaceStatusTag from "@/modules/workspaces/components/WorkspaceStatusTag";
import { statusColors } from "@/modules/workspaces/utils/workspaceStatusColors";
import { getWorkspaceStatusIcon } from "@/modules/workspaces/utils/workspaceStatusIcon";
import IacTypeLogo from "@/modules/workspaces/components/IacTypeLogo";
import VcsLogo from "@/modules/workspaces/components/VcsLogo";
import getVcsNameFromUrl from "@/modules/workspaces/utils/getVcsNameFromUrl";
import getVcsTypeFromUrl from "@/modules/workspaces/utils/getVcsTypeFromUrl";
import { WorkspaceSortOption } from "@/modules/workspaces/utils/workspaceSort";
import "./WorkspaceTable.css";

const GROUP_PREVIEW_SIZE = 10;

export type WorkspaceGroup = {
  key: string;
  label: string;
  items: WorkspaceListItem[];
};

type Props = {
  organizationId: string;
  workspaces: WorkspaceListItem[];
  groups?: WorkspaceGroup[];
  onSelectProject: (projectId: string | null) => void;
  sortOption: WorkspaceSortOption;
  onSortChange: (option: WorkspaceSortOption) => void;
};

type SortSpec = { asc: WorkspaceSortOption; desc: WorkspaceSortOption } | { single: WorkspaceSortOption };

function activateOnKey(e: KeyboardEvent, callback: () => void) {
  if (e.key === "Enter" || e.key === " ") {
    e.preventDefault();
    callback();
  }
}

function SortableHeader({
  label,
  spec,
  sortOption,
  onSortChange,
}: {
  label: string;
  spec: SortSpec;
  sortOption: WorkspaceSortOption;
  onSortChange: (option: WorkspaceSortOption) => void;
}) {
  if ("single" in spec) {
    const active = sortOption === spec.single;
    const activate = () => onSortChange(spec.single);
    return (
      <span
        className="workspace-sortable-header"
        style={{ fontWeight: active ? 700 : undefined }}
        role="button"
        tabIndex={0}
        onClick={activate}
        onKeyDown={(e) => activateOnKey(e, activate)}
      >
        {label}
      </span>
    );
  }
  const isAsc = sortOption === spec.asc;
  const isDesc = sortOption === spec.desc;
  const activate = () => onSortChange(isAsc ? spec.desc : spec.asc);
  return (
    <span
      className="workspace-sortable-header"
      role="button"
      tabIndex={0}
      onClick={activate}
      onKeyDown={(e) => activateOnKey(e, activate)}
    >
      {label}
      <span className="workspace-sort-carets">
        <CaretUpOutlined style={{ color: isAsc ? "#1890ff" : undefined }} />
        <CaretDownOutlined style={{ color: isDesc ? "#1890ff" : undefined }} />
      </span>
    </span>
  );
}

function WorkspaceRow({
  item,
  organizationId,
  onSelectProject,
}: {
  item: WorkspaceListItem;
  organizationId: string;
  onSelectProject: (projectId: string | null) => void;
}) {
  return (
    <div className="workspace-row">
      <Link
        to={`/organizations/${organizationId}/workspaces/${item.id}`}
        className="workspace-row-link"
        aria-label={`Open workspace ${item.name}`}
      />
      <div className="workspace-col-name">
        <div className="workspace-name-line1">
          <span
            className="workspace-status-icon"
            style={{ color: (item.lastStatus && statusColors[item.lastStatus]) || "#8b949e" }}
          >
            {getWorkspaceStatusIcon(item.lastStatus)}
          </span>
          {item.locked && <LockOutlined aria-label="lock" className="workspace-lock-icon" />}
          <Typography.Text className="workspace-name" ellipsis title={item.name}>
            {item.name}
          </Typography.Text>
        </div>
        {item.projectName && (
          <div className="workspace-name-line2">
            <Tag
              icon={<FolderOutlined />}
              color="blue"
              className="workspace-clickable-tag"
              onClick={(e) => {
                e.stopPropagation();
                onSelectProject(item.projectId ?? null);
              }}
            >
              {item.projectName}
            </Tag>
          </div>
        )}
      </div>
      <div className="workspace-col-status">
        <WorkspaceStatusTag status={item.lastStatus} />
      </div>
      <div className="workspace-col-run">
        <ClockCircleOutlined />
        <span>{item.lastRun ? DateTime.fromISO(item.lastRun).toRelative() : "Never Executed"}</span>
      </div>
      <div className="workspace-col-version">
        <IacTypeLogo type={item.iacType} />
        <span>{item.terraformVersion}</span>
      </div>
      <div className="workspace-col-source">
        {item.branch !== "remote-content" && item.normalizedSource ? (
          <>
            <VcsLogo type={getVcsTypeFromUrl(item.normalizedSource)} />
            <Typography.Link
              href={item.normalizedSource}
              target="_blank"
              rel="noreferrer"
              title={getVcsNameFromUrl(item.normalizedSource)}
              className="workspace-source-link"
              onClick={(e) => e.stopPropagation()}
            >
              {getVcsNameFromUrl(item.normalizedSource)}
            </Typography.Link>
          </>
        ) : (
          <Typography.Text type="secondary" className="workspace-source-cli">
            cli/api driven workflow
          </Typography.Text>
        )}
      </div>
    </div>
  );
}

export default function WorkspaceTable({
  organizationId,
  workspaces,
  groups,
  onSelectProject,
  sortOption,
  onSortChange,
}: Props) {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());
  const isGrouped = !!groups;

  useEffect(() => {
    setPage(1);
  }, [workspaces]);

  const pagedWorkspaces = useMemo(() => {
    const start = (page - 1) * pageSize;
    return workspaces.slice(start, start + pageSize);
  }, [workspaces, page, pageSize]);

  const toggleGroupExpanded = (key: string) => {
    setExpandedGroups((prev) => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  };

  return (
    <div className="workspace-list">
      <div className="workspace-list-header">
        <div className="workspace-col-name">
          <SortableHeader
            label="Name"
            spec={{ asc: "name_asc", desc: "name_desc" }}
            sortOption={sortOption}
            onSortChange={onSortChange}
          />
        </div>
        <div className="workspace-col-status">
          <SortableHeader
            label="Status"
            spec={{ single: "status" }}
            sortOption={sortOption}
            onSortChange={onSortChange}
          />
        </div>
        <div className="workspace-col-run">
          <SortableHeader
            label="Last run"
            spec={{ asc: "lastRun_asc", desc: "lastRun_desc" }}
            sortOption={sortOption}
            onSortChange={onSortChange}
          />
        </div>
        <div className="workspace-col-version">
          <SortableHeader
            label="Version"
            spec={{ asc: "terraformVersion_asc", desc: "terraformVersion_desc" }}
            sortOption={sortOption}
            onSortChange={onSortChange}
          />
        </div>
        <div className="workspace-col-source">
          <SortableHeader
            label="Source"
            spec={{ asc: "source_asc", desc: "source_desc" }}
            sortOption={sortOption}
            onSortChange={onSortChange}
          />
        </div>
      </div>

      {isGrouped
        ? groups!.map((group) => {
            const isExpanded = expandedGroups.has(group.key);
            const visibleItems = isExpanded ? group.items : group.items.slice(0, GROUP_PREVIEW_SIZE);
            const hiddenCount = group.items.length - visibleItems.length;
            return (
              <div key={group.key}>
                <div className="workspace-group-divider">
                  {group.label}{" "}
                  <span className="workspace-group-count">
                    {group.items.length} workspace{group.items.length === 1 ? "" : "s"}
                  </span>
                </div>
                {visibleItems.map((item) => (
                  <WorkspaceRow
                    key={item.id}
                    item={item}
                    organizationId={organizationId}
                    onSelectProject={onSelectProject}
                  />
                ))}
                {group.items.length > GROUP_PREVIEW_SIZE && (
                  <div
                    className="workspace-group-show-more"
                    role="button"
                    tabIndex={0}
                    onClick={() => toggleGroupExpanded(group.key)}
                    onKeyDown={(e) => activateOnKey(e, () => toggleGroupExpanded(group.key))}
                  >
                    {isExpanded ? "Show less" : `Show ${hiddenCount} more workspace${hiddenCount === 1 ? "" : "s"}`}
                  </div>
                )}
              </div>
            );
          })
        : pagedWorkspaces.map((item) => (
            <WorkspaceRow key={item.id} item={item} organizationId={organizationId} onSelectProject={onSelectProject} />
          ))}

      {!isGrouped && (
        <div className="workspace-list-pagination">
          <Pagination
            current={page}
            pageSize={pageSize}
            total={workspaces.length}
            showSizeChanger
            onChange={(newPage, newPageSize) => {
              setPage(newPage);
              setPageSize(newPageSize);
            }}
          />
        </div>
      )}
    </div>
  );
}
