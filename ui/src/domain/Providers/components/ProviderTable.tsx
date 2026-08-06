import { Table, Typography } from "antd";
import { useNavigate, useParams } from "react-router-dom";
import { FlatProvider } from "../types";

type Params = {
  orgid: string;
};

type Props = {
  providers: FlatProvider[];
  searchFilter: string;
};

export default function ProviderTable({ providers, searchFilter }: Props) {
  const { orgid } = useParams<Params>();
  const navigate = useNavigate();

  const filteredProviders = providers.filter(
    (provider) =>
      searchFilter === "" ||
      provider.name.toLowerCase().includes(searchFilter.toLowerCase()) ||
      provider.description?.toLowerCase().includes(searchFilter.toLowerCase())
  );

  const columns = [
    {
      title: "Name",
      dataIndex: "name",
      key: "name",
      sorter: (a: FlatProvider, b: FlatProvider) => a.name.localeCompare(b.name),
      render: (name: string, record: FlatProvider) => (
        <div>
          <Typography.Text strong>{name}</Typography.Text>
          <div>
            <Typography.Text type="secondary" ellipsis style={{ fontSize: 12 }}>
              {record.description || "No description provided for this provider"}
            </Typography.Text>
          </div>
        </div>
      ),
    },
    {
      title: "Latest version",
      dataIndex: "latestVersion",
      key: "latestVersion",
      width: 160,
      render: (version: string | undefined) => (version ? `v${version}` : "—"),
    },
  ];

  return (
    <Table
      rowKey="id"
      dataSource={filteredProviders}
      columns={columns}
      pagination={{ defaultPageSize: 10, showSizeChanger: true }}
      onRow={(record) => ({
        onClick: () => navigate(`/organizations/${orgid}/registry/providers/${record.id}`),
        style: { cursor: "pointer" },
      })}
      locale={{ emptyText: "No providers match your search." }}
    />
  );
}
