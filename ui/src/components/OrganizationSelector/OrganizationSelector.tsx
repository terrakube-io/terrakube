import React from "react";
import { BankOutlined, DownOutlined, CheckOutlined, SearchOutlined, UnorderedListOutlined } from "@ant-design/icons";
import { Button, Dropdown, Input } from "antd";
import { FlatOrganization } from "@/domain/types";
import "./OrganizationSelector.css";

// Below this count, a search box just adds clutter for no benefit.
const SEARCH_THRESHOLD = 8;

let initialOnOrgChange: ((orgId: string) => void) | null = null;

/**
 * Props for the OrganizationSelector component
 */
export interface OrganizationSelectorProps {
  /** Current organization name to display */
  organizationName: string;
  /** List of available organizations */
  organizations: FlatOrganization[];
  /** Callback when user selects a different organization */
  onOrgChange: (orgId: string) => void;
  /** Callback when user clicks "Manage Organizations" */
  onManageOrgs: () => void;
  /** Which side the dropdown opens toward. Defaults to "bottom". Use "top" when the button sits near the bottom of the viewport (e.g. pinned to a sidebar footer). */
  placement?: "top" | "bottom";
}

/**
 * OrganizationSelector Component
 *
 * Displays a dropdown selector for switching between organizations.
 * Features:
 * - Shows current organization name
 * - Dropdown list of available organizations
 * - "Manage Organizations" link
 *
 * @component
 * @example
 * ```tsx
 * <OrganizationSelector
 *   organizationName="My Org"
 *   organizations={orgs}
 *   onOrgChange={(orgId) => handleOrgChange(orgId)}
 *   onManageOrgs={() => navigate('/')}
 * />
 * ```
 */
export const OrganizationSelector: React.FC<OrganizationSelectorProps> = ({
  organizationName,
  organizations,
  onOrgChange,
  onManageOrgs,
  placement = "bottom",
}) => {
  const [open, setOpen] = React.useState(false);
  const [searchTerm, setSearchTerm] = React.useState("");
  const containerRef = React.useRef<HTMLDivElement>(null);

  const selectedOrgId = React.useMemo(
    () => organizations.find((org) => org.name === organizationName)?.id,
    [organizationName, organizations]
  );

  const displayName = organizationName?.trim() ? organizationName : "Choose an organization";

  if (initialOnOrgChange === null) {
    initialOnOrgChange = onOrgChange;
  }

  const shouldShowCurrentOrgOption = onOrgChange !== initialOnOrgChange;

  React.useEffect(() => {
    if (!open) {
      return;
    }

    const onDocumentClick = (event: MouseEvent) => {
      const target = event.target as Node;
      if (!containerRef.current?.contains(target)) {
        setOpen(false);
        setSearchTerm("");
      }
    };

    document.addEventListener("mousedown", onDocumentClick);
    return () => {
      document.removeEventListener("mousedown", onDocumentClick);
    };
  }, [open]);

  const handleOrganizationClick = (orgId: string) => {
    if (orgId === selectedOrgId) {
      return;
    }
    onOrgChange(orgId);
    setOpen(false);
    setSearchTerm("");
  };

  const handleManageOrganizationsClick = () => {
    onManageOrgs();
    setOpen(false);
    setSearchTerm("");
  };

  const visibleOrganizations = organizations
    .filter((org) => shouldShowCurrentOrgOption || org.id !== selectedOrgId)
    .filter((org) => org.name.toLowerCase().includes(searchTerm.toLowerCase()));

  return (
    <div className="org-selector-container" ref={containerRef}>
      <Dropdown trigger={["click"]} open={false} menu={{ items: [] }}>
        <Button
          className="org-selector-button"
          aria-expanded={open}
          onClick={() => setOpen((currentOpen) => !currentOpen)}
        >
          <BankOutlined className="org-selector-icon" />
          <span>{displayName}</span>
          <DownOutlined className="org-selector-arrow" />
        </Button>
      </Dropdown>
      {open && (
        <div className={`org-selector-dropdown${placement === "top" ? " org-selector-dropdown--top" : ""}`}>
          {organizations.length > SEARCH_THRESHOLD && (
            <Input
              className="org-selector-search"
              placeholder="Search organizations..."
              prefix={<SearchOutlined />}
              value={searchTerm}
              onChange={(event) => setSearchTerm(event.target.value)}
              onClick={(event) => event.stopPropagation()}
              allowClear
              autoFocus
            />
          )}
          <div className="org-selector-list">
            {visibleOrganizations.length === 0 ? (
              <div className="org-selector-empty">No organizations match your search.</div>
            ) : (
              visibleOrganizations.map((org) => (
                <div
                  key={org.id}
                  className={org.id === selectedOrgId ? "org-selector-item selected" : "org-selector-item"}
                  onClick={() => handleOrganizationClick(org.id)}
                  role="button"
                  tabIndex={0}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault();
                      handleOrganizationClick(org.id);
                    }
                  }}
                >
                  <span className="org-selector-item-name">{org.name}</span>
                  {org.id === selectedOrgId && <CheckOutlined className="org-selector-check" />}
                </div>
              ))
            )}
          </div>
          <div
            className="org-selector-manage-link"
            onClick={handleManageOrganizationsClick}
            role="button"
            tabIndex={0}
            onKeyDown={(event) => {
              if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                handleManageOrganizationsClick();
              }
            }}
          >
            <UnorderedListOutlined className="org-selector-manage-icon" />
            <span>Manage Organizations</span>
          </div>
        </div>
      )}
    </div>
  );
};

export default OrganizationSelector;
