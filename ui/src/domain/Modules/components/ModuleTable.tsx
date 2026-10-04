import { Table } from "antd";
import { Link, useNavigate, useParams } from "react-router-dom";
import { formatCount } from "@/modules/utils/formatCount";
import { FlatModule } from "../../types";

type Params = {
  orgid: string;
};

type Props = {
  modules: FlatModule[];
};

export default function ModuleTable({ modules }: Props) {
  const { orgid } = useParams<Params>();
  const navigate = useNavigate();

  const columns = [
    {
      title: "Name",
      dataIndex: "name",
      key: "name",
      sorter: (a: FlatModule, b: FlatModule) => a.name.localeCompare(b.name),
      render: (name: string, record: FlatModule) => (
        <>
          <Link to={`/organizations/${orgid}/registry/${record.id}`} className="registry-table-name">
            {name}
          </Link>
          {record.description && <span className="registry-table-description">{record.description}</span>}
        </>
      ),
    },
    {
      title: "Provider",
      dataIndex: "provider",
      key: "provider",
      width: 160,
      sorter: (a: FlatModule, b: FlatModule) => a.provider.localeCompare(b.provider),
      render: (provider: string) => <code className="registry-mono">{provider}</code>,
    },
    {
      title: "Latest version",
      dataIndex: "latestVersion",
      key: "latestVersion",
      width: 140,
      render: (version: string | undefined) => (version ? <code className="registry-mono">{version}</code> : "—"),
    },
    {
      title: "Downloads",
      dataIndex: "downloadQuantity",
      key: "downloadQuantity",
      width: 150,
      align: "right" as const,
      sorter: (a: FlatModule, b: FlatModule) => (a.downloadQuantity ?? 0) - (b.downloadQuantity ?? 0),
      render: (count: number) => formatCount(count ?? 0),
    },
  ];

  return (
    <Table
      rowKey="id"
      dataSource={modules}
      columns={columns}
      tableLayout="fixed"
      pagination={{ defaultPageSize: 10, showSizeChanger: true, hideOnSinglePage: true }}
      rowClassName="registry-table-row"
      onRow={(record) => ({
        onClick: (event) => {
          if ((event.target as HTMLElement).closest("a")) return;
          navigate(`/organizations/${orgid}/registry/${record.id}`);
        },
      })}
    />
  );
}
