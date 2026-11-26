import { Button, Checkbox, Flex, Tag, Typography, Pagination } from "antd";
import { WorkspaceOrigin, listOrigin, rememberWorkspaceOrigin } from "@/modules/workspaces/workspaceOrigin";
import {
  FolderOutlined,
  LockOutlined,
  CaretUpOutlined,
  CaretDownOutlined,
  DownOutlined,
  RightOutlined,
} from "@ant-design/icons";
import { useEffect, useId, useMemo, useState, type KeyboardEvent } from "react";
import { Link } from "react-router-dom";
import { WorkspaceListItem } from "@/modules/workspaces/types";
import { TagModel } from "@/modules/organizations/types";
import WorkspaceTagChips from "@/modules/workspaces/components/WorkspaceTagChips";
import WorkspaceStatusTag from "@/components/display/WorkspaceStatusTag";
import PolicyStatusTag from "@/components/display/PolicyStatusTag";
import { statusColors } from "@/modules/workspaces/utils/workspaceStatusColors";
import { getWorkspaceStatusIcon } from "@/modules/workspaces/utils/workspaceStatusIcon";
import LastRun from "@/modules/workspaces/components/LastRun";
import IacTypeLogo from "@/modules/workspaces/components/IacTypeLogo";
import VcsLogo from "@/components/display/VcsLogo";
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
  /** Organization tags, used to turn the tag ids on each row into names. */
  tags?: TagModel[];
  onSelectProject: (projectId: string | null) => void;
  /** Where "back" leads from a workspace opened here. The organization list unless told otherwise. */
  origin?: WorkspaceOrigin;
  /** On a project's own list the project is the page, so its chip is left out and the tags take its place. */
  hideProject?: boolean;
  /** The tags a project hands to its workspaces. Their rows say "from project" next to the tag. */
  defaultTags?: ReadonlyMap<string, string>;
  /** Adds a checkbox to every row and to the header, for actions on several workspaces at once. */
  selection?: { selected: WorkspaceListItem[]; onChange: (rows: WorkspaceListItem[]) => void };
  sortOption: WorkspaceSortOption;
  onSortChange: (option: WorkspaceSortOption) => void;
  page?: number;
  pageSize?: number;
  total?: number;
  onPageChange?: (page: number, pageSize: number) => void;
  /** Project groups the user has collapsed, by group key. Without `onToggleGroup` the headings stay static. */
  collapsedGroups?: ReadonlySet<string>;
  onToggleGroup?: (key: string) => void;
  /** Collapses or expands the given groups; backs "Expand all" and "Collapse all". */
  onSetGroupsCollapsed?: (keys: string[], collapsed: boolean) => void;
  /** Shows every group open, e.g. while a search is active, without touching the stored choices. */
  groupsForcedOpen?: boolean;
};

type SortSpec =
  | {
      asc: WorkspaceSortOption;
      desc: WorkspaceSortOption;
      /** Which way the first click on an unsorted column goes. Ascending unless a column reads better the other way. */
      first?: "asc" | "desc";
    }
  | { single: WorkspaceSortOption };

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
        className={active ? "workspace-sortable-header workspace-sortable-header--active" : "workspace-sortable-header"}
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
  const startsDescending = spec.first === "desc";
  // An active column flips; an unsorted one starts the way the column reads best.
  const activate = () => onSortChange(isAsc ? spec.desc : isDesc ? spec.asc : startsDescending ? spec.desc : spec.asc);
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
        <CaretUpOutlined className={isAsc ? "workspace-sort-caret--active" : undefined} />
        <CaretDownOutlined className={isDesc ? "workspace-sort-caret--active" : undefined} />
      </span>
    </span>
  );
}

function WorkspaceRow({
  item,
  organizationId,
  onSelectProject,
  tags,
  origin,
  hideProject,
  defaultTags,
  selection,
}: {
  item: WorkspaceListItem;
  organizationId: string;
  onSelectProject: (projectId: string | null) => void;
  tags: TagModel[];
  origin: WorkspaceOrigin;
  hideProject: boolean;
  defaultTags?: ReadonlyMap<string, string>;
  selection?: Props["selection"];
}) {
  const checked = !!selection?.selected.some((row) => row.id === item.id);
  return (
    <div className="workspace-row">
      <Link
        to={`/organizations/${organizationId}/workspaces/${item.id}`}
        className="workspace-row-link"
        aria-label={`Open workspace ${item.name}`}
        onClick={() => rememberWorkspaceOrigin(item.id, origin)}
      />
      {selection && (
        <div className="workspace-col-select">
          <Checkbox
            checked={checked}
            aria-label={`Select ${item.name}`}
            onChange={(event) =>
              selection.onChange(
                event.target.checked
                  ? [...selection.selected, item]
                  : selection.selected.filter((row) => row.id !== item.id)
              )
            }
          />
        </div>
      )}
      <div className="workspace-col-name">
        <div className="workspace-name-line1">
          <span
            className="workspace-status-icon"
            style={
              { "--workspace-status-color": item.lastStatus && statusColors[item.lastStatus] } as React.CSSProperties
            }
          >
            {getWorkspaceStatusIcon(item.lastStatus)}
          </span>
          {item.locked && <LockOutlined aria-label="lock" className="workspace-lock-icon" />}
          <Typography.Text className="workspace-name" ellipsis title={item.name}>
            {item.name}
          </Typography.Text>
          {/* The other columns have fixed widths, so the tags open from an icon and leave the room to the name */}
          {!defaultTags && <WorkspaceTagChips bindings={item.tags} tags={tags} collapsed />}
        </div>
        {defaultTags && (item.tags ?? []).length > 0 && (
          <Flex wrap gap={4} align="center" className="workspace-name-line2 workspace-name-tags">
            {(item.tags ?? []).map((binding) => (
              <span key={binding.tagId} className="workspace-tag-with-source">
                <WorkspaceTagChips bindings={[binding]} tags={tags} />
                {defaultTags.has(binding.tagId) && (defaultTags.get(binding.tagId) ?? "") === (binding.value ?? "") && (
                  <span className="workspace-tag-source">from project</span>
                )}
              </span>
            ))}
          </Flex>
        )}
        {!hideProject && item.projectName && (
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
      <div className="workspace-col-policy">
        <PolicyStatusTag
          status={item.policyComplianceStatus}
          organizationId={organizationId}
          workspaceId={item.id}
          clickable
        />
      </div>
      <div className="workspace-col-run">
        <LastRun lastRun={item.lastRun} />
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
  tags = [],
  onSelectProject,
  origin,
  hideProject = false,
  defaultTags,
  selection,
  sortOption,
  onSortChange,
  page: controlledPage,
  pageSize: controlledPageSize,
  total,
  onPageChange,
  collapsedGroups,
  onToggleGroup,
  onSetGroupsCollapsed,
  groupsForcedOpen = false,
}: Props) {
  const listId = useId();
  const rowOrigin = origin ?? listOrigin(organizationId);
  const [internalPage, setInternalPage] = useState(1);
  const [internalPageSize, setInternalPageSize] = useState(20);
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());
  const isGrouped = !!groups;
  const collapsible = isGrouped && !!onToggleGroup;
  const isControlled = controlledPage !== undefined && controlledPageSize !== undefined && !!onPageChange;
  const page = controlledPage ?? internalPage;
  const pageSize = controlledPageSize ?? internalPageSize;

  useEffect(() => {
    if (!isControlled) {
      setInternalPage((prev) => Math.min(prev, Math.max(1, Math.ceil(workspaces.length / pageSize))));
    }
  }, [workspaces, pageSize, isControlled]);

  const pagedWorkspaces = useMemo(() => {
    if (isControlled) return workspaces;
    const start = (page - 1) * pageSize;
    return workspaces.slice(start, start + pageSize);
  }, [workspaces, page, pageSize, isControlled]);

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
      {collapsible && onSetGroupsCollapsed && (
        <div className="workspace-group-controls">
          <Button
            type="link"
            size="small"
            disabled={groupsForcedOpen}
            onClick={() =>
              onSetGroupsCollapsed(
                groups!.map((group) => group.key),
                false
              )
            }
          >
            Expand all
          </Button>
          <Button
            type="link"
            size="small"
            disabled={groupsForcedOpen}
            onClick={() =>
              onSetGroupsCollapsed(
                groups!.map((group) => group.key),
                true
              )
            }
          >
            Collapse all
          </Button>
        </div>
      )}
      <div className="workspace-list-header">
        {selection && (
          <div className="workspace-col-select">
            <Checkbox
              aria-label="Select all workspaces on this page"
              checked={
                workspaces.length > 0 &&
                workspaces.every((item) => selection.selected.some((row) => row.id === item.id))
              }
              indeterminate={
                selection.selected.length > 0 &&
                !workspaces.every((item) => selection.selected.some((row) => row.id === item.id))
              }
              onChange={(event) => selection.onChange(event.target.checked ? [...workspaces] : [])}
            />
          </div>
        )}
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
            label={isGrouped ? "Status (grouped)" : "Status"}
            spec={{ single: "status" }}
            sortOption={sortOption}
            onSortChange={onSortChange}
          />
        </div>
        <div className="workspace-col-policy">
          <span>Policy</span>
        </div>
        <div className="workspace-col-run">
          <SortableHeader
            label="Last run"
            spec={{ asc: "lastRun_asc", desc: "lastRun_desc", first: "desc" }}
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
            const isCollapsed = collapsible && !groupsForcedOpen && !!collapsedGroups?.has(group.key);
            const visibleItems = isControlled || isExpanded ? group.items : group.items.slice(0, GROUP_PREVIEW_SIZE);
            const hiddenCount = group.items.length - visibleItems.length;
            const panelId = `${listId}-group-${group.key}`;
            const heading = (
              <>
                {collapsible && (isCollapsed ? <RightOutlined aria-hidden /> : <DownOutlined aria-hidden />)}
                <span className="workspace-group-label">{group.label}</span>{" "}
                <span className="workspace-group-count">
                  {group.items.length} workspace{group.items.length === 1 ? "" : "s"}
                  {isControlled ? " on this page" : ""}
                </span>
              </>
            );
            return (
              <div key={group.key}>
                <div className="workspace-group-divider">
                  {collapsible ? (
                    <button
                      type="button"
                      className="workspace-group-toggle"
                      aria-expanded={!isCollapsed}
                      aria-controls={panelId}
                      aria-disabled={groupsForcedOpen || undefined}
                      title={groupsForcedOpen ? "Groups stay open while searching" : undefined}
                      onClick={() => {
                        if (!groupsForcedOpen) onToggleGroup!(group.key);
                      }}
                    >
                      {heading}
                    </button>
                  ) : (
                    heading
                  )}
                </div>
                <div id={panelId}>
                  {!isCollapsed &&
                    visibleItems.map((item) => (
                      <WorkspaceRow
                        key={item.id}
                        item={item}
                        organizationId={organizationId}
                        onSelectProject={onSelectProject}
                        tags={tags}
                        origin={rowOrigin}
                        hideProject={hideProject}
                        defaultTags={defaultTags}
                        selection={selection}
                      />
                    ))}
                  {!isCollapsed && !isControlled && group.items.length > GROUP_PREVIEW_SIZE && (
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
              </div>
            );
          })
        : pagedWorkspaces.map((item) => (
            <WorkspaceRow
              key={item.id}
              item={item}
              organizationId={organizationId}
              onSelectProject={onSelectProject}
              tags={tags}
              origin={rowOrigin}
              hideProject={hideProject}
              defaultTags={defaultTags}
              selection={selection}
            />
          ))}

      {(!isGrouped || isControlled) && (
        <div className="workspace-list-pagination">
          <Pagination
            current={page}
            pageSize={pageSize}
            total={total ?? workspaces.length}
            showSizeChanger
            onChange={(newPage, newPageSize) => {
              if (isControlled) {
                onPageChange(newPage, newPageSize);
              } else {
                setInternalPage(newPage);
                setInternalPageSize(newPageSize);
              }
            }}
          />
        </div>
      )}
    </div>
  );
}
