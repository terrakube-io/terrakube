import { Input, Typography, Pagination } from "antd";
import { CaretUpOutlined, CaretDownOutlined } from "@ant-design/icons";
import { useEffect, useMemo, useState, type KeyboardEvent } from "react";
import { Link } from "react-router-dom";
import { OrganizationModel } from "../../types";
import { parseIconField, getOrgIcon } from "../../utils/orgIcon";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "@/config/actionTypes";
import { WORKSPACE_STATUS_PALETTE } from "@/modules/workspaces/utils/workspaceStatusPalette";
import { WorkspaceStatusFilter } from "@/modules/workspaces/utils/workspaceFilter";
import OrgSettingsButton from "./OrgSettingsButton";
import "./OrganizationTable.css";

// "All" and "NeverExecuted" are meta-filters, not real job statuses - excluded explicitly
// here rather than inferred from the palette entry having no `color`, so a future purely
// cosmetic change to the palette (e.g. giving "All" a color) can't silently add it as a badge.
const BREAKDOWN_STATUSES = WORKSPACE_STATUS_PALETTE.filter(
  (entry) => entry.value !== WorkspaceStatusFilter.All && entry.value !== WorkspaceStatusFilter.NeverExecuted
);

type Props = {
  organizations: OrganizationModel[];
};

type OrgSortOption =
  | "name_asc"
  | "name_desc"
  | "workspaceCount_asc"
  | "workspaceCount_desc"
  | "description_asc"
  | "description_desc"
  | "executionMode_asc"
  | "executionMode_desc";

function activateOnKey(e: KeyboardEvent, callback: () => void) {
  if (e.key === "Enter" || e.key === " ") {
    e.preventDefault();
    callback();
  }
}

function compareByName(a: OrganizationModel, b: OrganizationModel): number {
  return a.name.toLowerCase().localeCompare(b.name.toLowerCase());
}

function compareByWorkspaceCount(a: OrganizationModel, b: OrganizationModel): number {
  return (a.workspaceCount ?? 0) - (b.workspaceCount ?? 0);
}

function compareByDescription(a: OrganizationModel, b: OrganizationModel): number {
  return (a.description ?? "").toLowerCase().localeCompare((b.description ?? "").toLowerCase());
}

function compareByExecutionMode(a: OrganizationModel, b: OrganizationModel): number {
  return (a.executionMode ?? "").toLowerCase().localeCompare((b.executionMode ?? "").toLowerCase());
}

function sortOrganizations(organizations: OrganizationModel[], option: OrgSortOption): OrganizationModel[] {
  const list = [...organizations];
  switch (option) {
    case "name_asc":
      return list.sort(compareByName);
    case "name_desc":
      return list.sort((a, b) => -compareByName(a, b));
    case "workspaceCount_asc":
      return list.sort(compareByWorkspaceCount);
    case "workspaceCount_desc":
      return list.sort((a, b) => -compareByWorkspaceCount(a, b));
    case "description_asc":
      return list.sort(compareByDescription);
    case "description_desc":
      return list.sort((a, b) => -compareByDescription(a, b));
    case "executionMode_asc":
      return list.sort(compareByExecutionMode);
    case "executionMode_desc":
      return list.sort((a, b) => -compareByExecutionMode(a, b));
    default:
      return list;
  }
}

function SortableHeader({
  label,
  asc,
  desc,
  sortOption,
  onSortChange,
}: {
  label: string;
  asc: OrgSortOption;
  desc: OrgSortOption;
  sortOption: OrgSortOption;
  onSortChange: (option: OrgSortOption) => void;
}) {
  const isAsc = sortOption === asc;
  const isDesc = sortOption === desc;
  const activate = () => onSortChange(isAsc ? desc : asc);
  return (
    <span
      className="organization-sortable-header"
      role="button"
      tabIndex={0}
      onClick={activate}
      onKeyDown={(e) => activateOnKey(e, activate)}
    >
      {label}
      <span className="organization-sort-carets">
        <CaretUpOutlined style={{ color: isAsc ? "#1890ff" : undefined }} />
        <CaretDownOutlined style={{ color: isDesc ? "#1890ff" : undefined }} />
      </span>
    </span>
  );
}

function OrganizationRow({ organization }: { organization: OrganizationModel }) {
  const { iconName, color } = parseIconField(organization.icon, organization.id);
  const statusCounts = organization.workspaceStatusCounts ?? {};
  const statusBreakdown = BREAKDOWN_STATUSES.filter((entry) => (statusCounts[entry.value] ?? 0) > 0);

  const rememberOrganization = () => {
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, organization.id);
    sessionStorage.setItem(ORGANIZATION_NAME, organization.name);
  };

  return (
    <div className="organization-row">
      <Link
        to={`/organizations/${organization.id}/workspaces`}
        onClick={rememberOrganization}
        className="organization-row-link"
        aria-label={`Open organization ${organization.name}`}
      />
      <div className="organization-col-name">
        <div className="organization-col-name-icon">{getOrgIcon(iconName, color, 20)}</div>
        <Typography.Text strong ellipsis title={organization.name}>
          {organization.name}
        </Typography.Text>
      </div>
      <div className="organization-col-workspaces">
        {typeof organization.workspaceCount === "number" && (
          <span className="organization-workspace-count">
            {organization.workspaceCount} workspace{organization.workspaceCount === 1 ? "" : "s"}
          </span>
        )}
        {statusBreakdown.length > 0 && (
          <span className="organization-status-badges">
            {statusBreakdown.map((entry) => (
              <span
                key={entry.value}
                className="organization-status-badge"
                style={{ color: entry.color }}
                title={`${entry.label}: ${statusCounts[entry.value]}`}
              >
                {entry.icon}
                {statusCounts[entry.value]}
              </span>
            ))}
          </span>
        )}
      </div>
      <div className="organization-col-description">
        <Typography.Text
          type="secondary"
          ellipsis
          title={organization.description || "No description set for this organization"}
        >
          {organization.description || "No description set for this organization"}
        </Typography.Text>
      </div>
      <div className="organization-col-mode">{organization.executionMode || "—"}</div>
      <div className="organization-col-actions">
        <OrgSettingsButton orgId={organization.id} />
      </div>
    </div>
  );
}

export default function OrganizationTable({ organizations }: Props) {
  const [searchTerm, setSearchTerm] = useState("");
  const [sortOption, setSortOption] = useState<OrgSortOption>("name_asc");
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);

  const filteredOrganizations = useMemo(() => {
    const term = searchTerm.trim().toLowerCase();
    if (!term) return organizations;
    return organizations.filter(
      (org) => org.name.toLowerCase().includes(term) || (org.description ?? "").toLowerCase().includes(term)
    );
  }, [organizations, searchTerm]);

  const sortedOrganizations = useMemo(
    () => sortOrganizations(filteredOrganizations, sortOption),
    [filteredOrganizations, sortOption]
  );

  useEffect(() => {
    setPage(1);
  }, [searchTerm]);

  const pagedOrganizations = useMemo(() => {
    const start = (page - 1) * pageSize;
    return sortedOrganizations.slice(start, start + pageSize);
  }, [sortedOrganizations, page, pageSize]);

  return (
    <div>
      <Input.Search
        placeholder="Search organizations..."
        allowClear
        onChange={(e) => setSearchTerm(e.target.value)}
        style={{ maxWidth: 320, marginBottom: 16 }}
      />
      <div className="organization-list">
        <div className="organization-list-header">
          <div className="organization-col-name">
            <SortableHeader
              label="Name"
              asc="name_asc"
              desc="name_desc"
              sortOption={sortOption}
              onSortChange={setSortOption}
            />
          </div>
          <div className="organization-col-workspaces">
            <SortableHeader
              label="Workspaces"
              asc="workspaceCount_asc"
              desc="workspaceCount_desc"
              sortOption={sortOption}
              onSortChange={setSortOption}
            />
          </div>
          <div className="organization-col-description">
            <SortableHeader
              label="Description"
              asc="description_asc"
              desc="description_desc"
              sortOption={sortOption}
              onSortChange={setSortOption}
            />
          </div>
          <div className="organization-col-mode">
            <SortableHeader
              label="Execution mode"
              asc="executionMode_asc"
              desc="executionMode_desc"
              sortOption={sortOption}
              onSortChange={setSortOption}
            />
          </div>
          <div className="organization-col-actions" />
        </div>
        {pagedOrganizations.length === 0 ? (
          <div className="organization-empty">No organizations match your search.</div>
        ) : (
          pagedOrganizations.map((organization) => (
            <OrganizationRow key={organization.id} organization={organization} />
          ))
        )}
      </div>
      <div className="organization-list-pagination">
        <Pagination
          current={page}
          pageSize={pageSize}
          total={sortedOrganizations.length}
          showSizeChanger
          onChange={(newPage, newPageSize) => {
            setPage(newPage);
            setPageSize(newPageSize);
          }}
        />
      </div>
    </div>
  );
}
