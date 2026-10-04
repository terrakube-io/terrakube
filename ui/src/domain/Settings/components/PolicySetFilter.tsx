import React from "react";
import { Button, Input, Segmented, Select } from "antd";
import { AppstoreOutlined, BarsOutlined, SearchOutlined } from "@ant-design/icons";
import { PolicySetsViewMode, setStoredPolicySetsViewMode } from "./policySetsViewPreference";
import "../PolicySets.css";
import "./PolicyComponents.css";

type Props = {
  searchQuery: string;
  onSearchChange: (val: string) => void;
  categoryFilter: string;
  onCategoryChange: (val: string) => void;
  scopeFilter: string;
  onScopeChange: (val: string) => void;
  viewMode: PolicySetsViewMode;
  onViewModeChange: (mode: PolicySetsViewMode) => void;
  onResetFilters: () => void;
  hasActiveFilters: boolean;
};

export const PolicySetFilter: React.FC<Props> = ({
  searchQuery,
  onSearchChange,
  categoryFilter,
  onCategoryChange,
  scopeFilter,
  onScopeChange,
  viewMode,
  onViewModeChange,
  onResetFilters,
  hasActiveFilters,
}) => {
  const handleViewModeChange = (val: string | number) => {
    const mode = val as PolicySetsViewMode;
    setStoredPolicySetsViewMode(mode);
    onViewModeChange(mode);
  };

  return (
    <div className="policy-toolbar">
      <div className="policy-toolbar-filters">
        <Input
          aria-label="Search policy sets by name or description"
          placeholder="Search by name or description"
          prefix={<SearchOutlined />}
          allowClear
          value={searchQuery}
          onChange={(e) => onSearchChange(e.target.value)}
          className="policy-filter-search"
          data-testid="policy-set-search-input"
        />

        <Select
          value={categoryFilter}
          onChange={onCategoryChange}
          className="policy-filter-select-wide"
          data-testid="policy-set-category-select"
          aria-label="Filter by enforcement level"
          options={[
            { label: "All enforcement levels", value: "ALL" },
            { label: "Hard mandatory", value: "HARD_MANDATORY" },
            { label: "Soft mandatory", value: "SOFT_MANDATORY" },
            { label: "Advisory", value: "ADVISORY" },
          ]}
        />

        <Select
          value={scopeFilter}
          onChange={onScopeChange}
          className="policy-filter-select-narrow"
          data-testid="policy-set-scope-select"
          aria-label="Filter by scope"
          options={[
            { label: "All scopes", value: "ALL" },
            { label: "Global", value: "GLOBAL" },
            { label: "Attached", value: "ATTACHED" },
          ]}
        />

        {hasActiveFilters && (
          <Button type="link" onClick={onResetFilters} data-testid="clear-filters-btn">
            Clear filters
          </Button>
        )}
      </div>

      <Segmented
        value={viewMode}
        onChange={handleViewModeChange}
        data-testid="policy-set-view-toggle"
        options={[
          { label: "Cards", value: "cards", icon: <AppstoreOutlined /> },
          { label: "Compact", value: "compact", icon: <BarsOutlined /> },
        ]}
      />
    </div>
  );
};
