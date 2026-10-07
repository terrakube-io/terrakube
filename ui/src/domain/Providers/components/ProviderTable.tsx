import { Table } from "antd";
import { Link, useNavigate, useParams } from "react-router-dom";
import { parseProviderDescription } from "../../Modules/registryHelpers";
import { FlatProvider } from "../types";

type Params = {
  orgid: string;
};

type Props = {
  providers: FlatProvider[];
};

export default function ProviderTable({ providers }: Props) {
  const { orgid } = useParams<Params>();
  const navigate = useNavigate();

  const columns = [
    {
      title: "Name",
      dataIndex: "name",
      key: "name",
      sorter: (a: FlatProvider, b: FlatProvider) => a.name.localeCompare(b.name),
      render: (name: string, record: FlatProvider) => {
        const { text } = parseProviderDescription(record.description);
        return (
          <>
            <Link to={`/organizations/${orgid}/registry/providers/${record.id}`} className="registry-table-name">
              {name}
            </Link>
            {text && <span className="registry-table-description">{text}</span>}
          </>
        );
      },
    },
    {
      title: "Namespace",
      dataIndex: "registryNamespace",
      key: "registryNamespace",
      width: 180,
      render: (namespace: string | undefined) => namespace || "—",
    },
    {
      title: "Latest version",
      dataIndex: "latestVersion",
      key: "latestVersion",
      width: 160,
      render: (version: string | undefined) => (version ? <code className="registry-mono">{version}</code> : "—"),
    },
  ];

  return (
    <Table
      rowKey="id"
      dataSource={providers}
      columns={columns}
      tableLayout="fixed"
      pagination={{ defaultPageSize: 10, showSizeChanger: true, hideOnSinglePage: true }}
      rowClassName="registry-table-row"
      onRow={(record) => ({
        onClick: (event) => {
          if ((event.target as HTMLElement).closest("a")) return;
          navigate(`/organizations/${orgid}/registry/providers/${record.id}`);
        },
      })}
    />
  );
}
