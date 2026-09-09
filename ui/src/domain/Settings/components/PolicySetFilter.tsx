import React from "react";
import { Input, Select, Segmented, Row, Col, Space, Button } from "antd";
import {
  SearchOutlined,
  AppstoreOutlined,
  BarsOutlined,
  FilterOutlined,
  CloseCircleOutlined,
} from "@ant-design/icons";
import {
  PolicySetsViewMode,
  setStoredPolicySetsViewMode,
} from "./policySetsViewPreference";

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
    <Row
      gutter={[12, 12]}
      justify="space-between"
      align="middle"
      style={{ marginBottom: 16 }}
    >
      <Col xs={24} lg={18}>
        <Space wrap size={10} style={{ width: "100%" }}>
          <Input
            placeholder="Search by name or description..."
            prefix={<SearchOutlined style={{ color: "rgba(0,0,0,0.45)" }} />}
            allowClear
            value={searchQuery}
            onChange={(e) => onSearchChange(e.target.value)}
            style={{ width: 260 }}
            data-testid="policy-set-search-input"
          />

          <Select
            value={categoryFilter}
            onChange={onCategoryChange}
            style={{ minWidth: 190 }}
            data-testid="policy-set-category-select"
            aria-label="Filter by policy category"
            options={[
              { label: "All Categories", value: "ALL" },
              { label: "Hard Mandatory", value: "HARD_MANDATORY" },
              { label: "Soft Mandatory", value: "SOFT_MANDATORY" },
              { label: "Advisory", value: "ADVISORY" },
            ]}
          />

          <Select
            value={scopeFilter}
            onChange={onScopeChange}
            style={{ minWidth: 140 }}
            data-testid="policy-set-scope-select"
            aria-label="Filter by scope"
            options={[
              { label: "All Scopes", value: "ALL" },
              { label: "Global", value: "GLOBAL" },
              { label: "Attached", value: "ATTACHED" },
            ]}
          />

          {hasActiveFilters && (
            <Button
              type="link"
              icon={<CloseCircleOutlined />}
              onClick={onResetFilters}
              style={{ padding: 0 }}
              data-testid="clear-filters-btn"
            >
              Clear filters
            </Button>
          )}
        </Space>
      </Col>

      <Col xs={24} lg={6} style={{ display: "flex", justifyContent: "flex-end" }}>
        <Segmented
          value={viewMode}
          onChange={handleViewModeChange}
          data-testid="policy-set-view-toggle"
          options={[
            {
              label: "Cards",
              value: "cards",
              icon: <AppstoreOutlined />,
            },
            {
              label: "Compact",
              value: "compact",
              icon: <BarsOutlined />,
            },
          ]}
        />
      </Col>
    </Row>
  );
};
