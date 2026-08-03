import { DownloadOutlined } from "@ant-design/icons";
import { Table, Typography } from "antd";
import { useNavigate, useParams } from "react-router-dom";
import { FlatModule } from "../../types";

type Params = {
  orgid: string;
};

type Props = {
  modules: FlatModule[];
  searchFilter: string;
};

export default function ModuleTable({ modules, searchFilter }: Props) {
  const { orgid } = useParams<Params>();
  const navigate = useNavigate();

  const filteredModules = modules.filter(
    (module) =>
      searchFilter === "" ||
      module.name.toLowerCase().includes(searchFilter.toLowerCase()) ||
      module.description?.toLowerCase().includes(searchFilter.toLowerCase())
  );

  const columns = [
    {
      title: "Name",
      dataIndex: "name",
      key: "name",
      sorter: (a: FlatModule, b: FlatModule) => a.name.localeCompare(b.name),
      render: (name: string, record: FlatModule) => (
        <div>
          <Typography.Text strong>{name}</Typography.Text>
          <div>
            <Typography.Text type="secondary" ellipsis style={{ fontSize: 12 }}>
              {record.description || "No description provided for this module"}
            </Typography.Text>
          </div>
        </div>
      ),
    },
    {
      title: "Provider",
      dataIndex: "provider",
      key: "provider",
      width: 160,
      sorter: (a: FlatModule, b: FlatModule) => a.provider.localeCompare(b.provider),
    },
    {
      title: "Latest version",
      dataIndex: "latestVersion",
      key: "latestVersion",
      width: 140,
      render: (version: string | undefined) => (version ? `v${version}` : "—"),
    },
    {
      title: "Downloads",
      dataIndex: "downloadQuantity",
      key: "downloadQuantity",
      width: 140,
      sorter: (a: FlatModule, b: FlatModule) => (a.downloadQuantity ?? 0) - (b.downloadQuantity ?? 0),
      render: (count: number) => (
        <span>
          <DownloadOutlined style={{ marginRight: 6, color: "#8c97a8" }} />
          {count ?? 0}
        </span>
      ),
    },
  ];

  return (
    <Table
      rowKey="id"
      dataSource={filteredModules}
      columns={columns}
      pagination={{ defaultPageSize: 10, showSizeChanger: true }}
      onRow={(record) => ({
        onClick: () => navigate(`/organizations/${orgid}/registry/${record.id}`),
        style: { cursor: "pointer" },
      })}
      locale={{ emptyText: "No modules match your search." }}
    />
  );
}
