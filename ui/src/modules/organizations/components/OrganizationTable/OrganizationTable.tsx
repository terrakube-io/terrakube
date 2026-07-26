import { Table, Input, Typography, Flex, Tag } from "antd";
import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { OrganizationModel } from "../../types";
import { parseIconField, getOrgIcon } from "../../utils/orgIcon";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "@/config/actionTypes";
import OrgSettingsButton from "./OrgSettingsButton";

type Props = {
  organizations: OrganizationModel[];
};

export default function OrganizationTable({ organizations }: Props) {
  const navigate = useNavigate();
  const [searchTerm, setSearchTerm] = useState("");

  const filteredOrganizations = useMemo(() => {
    const term = searchTerm.trim().toLowerCase();
    if (!term) return organizations;
    return organizations.filter(
      (org) => org.name.toLowerCase().includes(term) || (org.description ?? "").toLowerCase().includes(term)
    );
  }, [organizations, searchTerm]);

  const handleRowClick = (organization: OrganizationModel) => {
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, organization.id);
    sessionStorage.setItem(ORGANIZATION_NAME, organization.name);
    navigate(`/organizations/${organization.id}/workspaces`);
  };

  const columns = [
    {
      title: "Name",
      dataIndex: "name",
      key: "name",
      sorter: (a: OrganizationModel, b: OrganizationModel) => a.name.localeCompare(b.name),
      render: (_: string, record: OrganizationModel) => {
        const { iconName, color } = parseIconField(record.icon, record.id);
        return (
          <Flex align="center" gap={10} style={{ minWidth: 0 }}>
            <Flex align="center" style={{ width: 24, height: 24, flexShrink: 0 }}>
              {getOrgIcon(iconName, color, 20)}
            </Flex>
            <Typography.Text strong ellipsis>
              {record.name}
            </Typography.Text>
            {typeof record.workspaceCount === "number" && (
              <Tag color="blue" style={{ fontSize: 12, fontWeight: 600, margin: 0, flexShrink: 0 }}>
                {record.workspaceCount} workspace{record.workspaceCount === 1 ? "" : "s"}
              </Tag>
            )}
          </Flex>
        );
      },
    },
    {
      title: "Description",
      dataIndex: "description",
      key: "description",
      sorter: (a: OrganizationModel, b: OrganizationModel) =>
        (a.description ?? "").localeCompare(b.description ?? ""),
      render: (description: string | undefined) => (
        <Typography.Text type="secondary" ellipsis style={{ maxWidth: 320, fontSize: 12 }}>
          {description || "No description set for this organization"}
        </Typography.Text>
      ),
    },
    {
      title: "Execution mode",
      dataIndex: "executionMode",
      key: "executionMode",
      sorter: (a: OrganizationModel, b: OrganizationModel) =>
        (a.executionMode ?? "").localeCompare(b.executionMode ?? ""),
      render: (mode: string | undefined) => mode || "—",
    },
    {
      title: "",
      key: "actions",
      width: 56,
      render: (_: unknown, record: OrganizationModel) => <OrgSettingsButton orgId={record.id} />,
    },
  ];

  return (
    <div>
      <Input.Search
        placeholder="Search organizations..."
        allowClear
        onChange={(e) => setSearchTerm(e.target.value)}
        style={{ maxWidth: 320, marginBottom: 16 }}
      />
      <Table
        rowKey="id"
        dataSource={filteredOrganizations}
        columns={columns}
        pagination={{ pageSize: 10, showSizeChanger: true }}
        onRow={(record) => ({
          onClick: () => handleRowClick(record),
          style: { cursor: "pointer" },
        })}
        locale={{ emptyText: "No organizations match your search." }}
      />
    </div>
  );
}
