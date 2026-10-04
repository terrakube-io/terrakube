import React from "react";
import { Input, Select } from "antd";
import { SearchOutlined } from "@ant-design/icons";
import "../PolicySets.css";
import "./PolicyComponents.css";

export type ExemptionStatusFilter = "ALL" | "ACTIVE" | "EXPIRING_SOON" | "EXPIRED" | "INDEFINITE";

export type ExemptionScopeFilter = "ALL" | "ORGANIZATION" | "PROJECT" | "WORKSPACE";

export type PolicyExemptionFilterProps = {
  searchQuery: string;
  onSearchChange: (query: string) => void;
  statusFilter: ExemptionStatusFilter;
  onStatusFilterChange: (status: ExemptionStatusFilter) => void;
  scopeFilter: ExemptionScopeFilter;
  onScopeFilterChange: (scope: ExemptionScopeFilter) => void;
  policySetFilter: string;
  onPolicySetFilterChange: (policySetId: string) => void;
  policySets: Array<{ id: string; name: string }>;
  statusCounts?: {
    all: number;
    active: number;
    expiringSoon: number;
    expired: number;
    indefinite: number;
  };
};

export const PolicyExemptionFilter: React.FC<PolicyExemptionFilterProps> = ({
  searchQuery,
  onSearchChange,
  statusFilter,
  onStatusFilterChange,
  scopeFilter,
  onScopeFilterChange,
  policySetFilter,
  onPolicySetFilterChange,
  policySets,
  statusCounts,
}) => {
  const count = (n?: number) => (statusCounts ? ` (${n})` : "");

  return (
    <div className="policy-toolbar-filters policy-exemption-filters">
      <Input
        aria-label="Search exemptions by rule, ticket, or reason"
        placeholder="Search by rule, ticket or reason"
        prefix={<SearchOutlined />}
        value={searchQuery}
        onChange={(e) => onSearchChange(e.target.value)}
        allowClear
        className="policy-filter-search"
      />

      <Select
        aria-label="Filter by status"
        value={statusFilter}
        onChange={onStatusFilterChange}
        className="policy-filter-select"
        options={[
          { value: "ALL", label: `All statuses${count(statusCounts?.all)}` },
          { value: "ACTIVE", label: `Active${count(statusCounts?.active)}` },
          { value: "EXPIRING_SOON", label: `Expiring soon${count(statusCounts?.expiringSoon)}` },
          { value: "EXPIRED", label: `Expired${count(statusCounts?.expired)}` },
          { value: "INDEFINITE", label: `Permanent${count(statusCounts?.indefinite)}` },
        ]}
      />

      <Select
        aria-label="Filter by scope"
        value={scopeFilter}
        onChange={onScopeFilterChange}
        className="policy-filter-select"
        options={[
          { value: "ALL", label: "All scopes" },
          { value: "ORGANIZATION", label: "Organization-wide" },
          { value: "PROJECT", label: "Project" },
          { value: "WORKSPACE", label: "Workspace" },
        ]}
      />

      <Select
        aria-label="Filter by policy set"
        value={policySetFilter}
        onChange={onPolicySetFilterChange}
        className="policy-filter-select-wide"
        showSearch
        optionFilterProp="label"
        options={[
          { value: "ALL", label: "All policy sets" },
          ...policySets.map((ps) => ({ value: ps.id, label: ps.name })),
        ]}
      />
    </div>
  );
};
