import { Button, Table, Typography } from "antd";
import type { ColumnsType } from "antd/es/table";
import { InboxKind, KIND_ORDER, totalCount } from "./inboxModel";
import { OrganizationCounts } from "./inboxService";

type Props = {
  rows: OrganizationCounts[];
  /** Opens an organization, on one kind when a number was the thing clicked. */
  onOpen: (orgId: string, kind?: InboxKind) => void;
};

const HEADINGS: Record<InboxKind, string> = {
  waiting: "Waiting",
  failed: "Failed",
  drifted: "Drifted",
  policy: "Policy",
};

/**
 * The inbox of a person in many organizations, as one line each: how much needs them where, busiest first. The rows
 * of an organization are one click away, so nothing is listed that nobody will read.
 */
export default function InboxOrganizations({ rows, onOpen }: Props) {
  const withWork = rows
    .filter((row) => totalCount(row.counts) > 0)
    .sort((a, b) => totalCount(b.counts) - totalCount(a.counts) || a.org.name.localeCompare(b.org.name));
  const quiet = rows.length - withWork.length;

  const columns: ColumnsType<OrganizationCounts> = [
    {
      title: "Organization",
      key: "organization",
      render: (_, row) => (
        <Button type="link" className="inbox-org-link" onClick={() => onOpen(row.org.id)}>
          {row.org.name}
        </Button>
      ),
    },
    ...KIND_ORDER.map((kind) => ({
      title: HEADINGS[kind],
      key: kind,
      width: 110,
      align: "right" as const,
      render: (_: unknown, row: OrganizationCounts) =>
        row.counts[kind] > 0 ? (
          <Button
            type="link"
            size="small"
            aria-label={`${row.counts[kind]} ${HEADINGS[kind].toLowerCase()} in ${row.org.name}`}
            onClick={() => onOpen(row.org.id, kind)}
          >
            {row.counts[kind]}
          </Button>
        ) : (
          <Typography.Text type="secondary">-</Typography.Text>
        ),
    })),
    {
      title: "In all",
      key: "total",
      width: 90,
      align: "right",
      render: (_, row) => <strong>{totalCount(row.counts)}</strong>,
    },
    {
      title: <span className="inbox-visually-hidden">Open</span>,
      key: "open",
      width: 90,
      align: "right",
      render: (_, row) => (
        <Button size="small" onClick={() => onOpen(row.org.id)} aria-label={`Open ${row.org.name}`}>
          Open
        </Button>
      ),
    },
  ];

  return (
    <>
      <Table<OrganizationCounts>
        className="inbox-table"
        scroll={{ x: "max-content" }}
        size="middle"
        rowKey={(row) => row.org.id}
        columns={columns}
        dataSource={withWork}
        pagination={false}
      />
      {quiet > 0 && (
        <Typography.Text type="secondary" className="inbox-more">
          {quiet} other organization{quiet === 1 ? " has" : "s have"} nothing that needs you.
        </Typography.Text>
      )}
    </>
  );
}
