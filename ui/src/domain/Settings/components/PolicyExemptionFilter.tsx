import React from "react";
import { Col, Input, Row, Select, Space } from "antd";
import {
  AppstoreOutlined,
  ClockCircleOutlined,
  FilterOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
} from "@ant-design/icons";

const { Option } = Select;

export type ExemptionStatusFilter =
  | "ALL"
  | "ACTIVE"
  | "EXPIRING_SOON"
  | "EXPIRED"
  | "INDEFINITE";

export type ExemptionScopeFilter =
  | "ALL"
  | "ORGANIZATION"
  | "PROJECT"
  | "WORKSPACE";

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
  return (
    <Row gutter={[16, 16]} style={{ marginBottom: 16 }} align="middle">
      <Col xs={24} sm={12} md={8}>
        <Input
          placeholder="Search exemptions by rule, ticket, reason..."
          prefix={<SearchOutlined style={{ color: "#bfbfbf" }} />}
          value={searchQuery}
          onChange={(e) => onSearchChange(e.target.value)}
          allowClear
        />
      </Col>

      <Col xs={24} sm={12} md={5}>
        <Select
          value={statusFilter}
          onChange={onStatusFilterChange}
          style={{ width: "100%" }}
          prefix={<ClockCircleOutlined />}
        >
          <Option value="ALL">
            Status: All {statusCounts ? `(${statusCounts.all})` : ""}
          </Option>
          <Option value="ACTIVE">
            Active {statusCounts ? `(${statusCounts.active})` : ""}
          </Option>
          <Option value="EXPIRING_SOON">
            Expiring Soon {statusCounts ? `(${statusCounts.expiringSoon})` : ""}
          </Option>
          <Option value="EXPIRED">
            Expired {statusCounts ? `(${statusCounts.expired})` : ""}
          </Option>
          <Option value="INDEFINITE">
            Permanent {statusCounts ? `(${statusCounts.indefinite})` : ""}
          </Option>
        </Select>
      </Col>

      <Col xs={24} sm={12} md={5}>
        <Select
          value={scopeFilter}
          onChange={onScopeFilterChange}
          style={{ width: "100%" }}
          prefix={<AppstoreOutlined />}
        >
          <Option value="ALL">Scope: All</Option>
          <Option value="ORGANIZATION">Organization-Wide</Option>
          <Option value="PROJECT">Project-Scoped</Option>
          <Option value="WORKSPACE">Workspace-Scoped</Option>
        </Select>
      </Col>

      <Col xs={24} sm={12} md={6}>
        <Select
          value={policySetFilter}
          onChange={onPolicySetFilterChange}
          style={{ width: "100%" }}
          prefix={<SafetyCertificateOutlined />}
          showSearch
          optionFilterProp="children"
        >
          <Option value="ALL">Policy Set: All</Option>
          {policySets.map((ps) => (
            <Option key={ps.id} value={ps.id}>
              {ps.name}
            </Option>
          ))}
        </Select>
      </Col>
    </Row>
  );
};
