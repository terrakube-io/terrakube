import { Alert, Select, Skeleton, Table, Tag, Typography } from "antd";
import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import workspaceService from "@/modules/workspaces/workspaceService";
import { WorkspaceListItem } from "@/modules/workspaces/types";
import projectService from "./projectService";
import { EffectiveVariable, VariableSource, describeSource, resolveEffectiveVariables } from "./effectiveVariables";

type Props = {
  orgid: string;
  projectId: string;
};

function SourceLink({ orgid, workspaceId, source }: { orgid: string; workspaceId: string; source: VariableSource }) {
  const text = describeSource(source);
  switch (source.type) {
    case "workspace":
      return <Link to={`/organizations/${orgid}/workspaces/${workspaceId}/variables`}>{text}</Link>;
    case "global":
      return <Link to={`/organizations/${orgid}/settings/variables`}>{text}</Link>;
    default:
      return <Link to={`/organizations/${orgid}/settings/collection/edit/${source.id}`}>{text}</Link>;
  }
}

/** The variables one workspace of the project will run with, and where each of them is defined. */
export default function ProjectEffectiveVariables({ orgid, projectId }: Props) {
  const [workspaces, setWorkspaces] = useState<WorkspaceListItem[]>([]);
  const [workspaceId, setWorkspaceId] = useState<string>();
  const workspaceIdRef = useRef<string | undefined>(undefined);
  workspaceIdRef.current = workspaceId;
  const [rows, setRows] = useState<EffectiveVariable[] | null>(null);
  const [failed, setFailed] = useState(false);
  // Only the newest search may fill the list, and the workspace being looked at stays in it while searching.
  const latestSearch = useRef(0);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  const searchWorkspaces = async (search: string) => {
    const current = ++latestSearch.current;
    const response = await workspaceService.listWorkspacePage(
      { organizationId: orgid, first: 20, after: 0, projectId, search, sort: "name_asc" },
      false
    );
    if (current !== latestSearch.current) return;
    if (!response.isError) {
      const found = response.data?.workspaces ?? [];
      setWorkspaces((shown) => {
        const keep = shown.find((workspace) => workspace.id === workspaceIdRef.current);
        return keep && !found.some((workspace) => workspace.id === keep.id) ? [keep, ...found] : found;
      });
      setWorkspaceId((chosen) => chosen ?? found[0]?.id);
    }
  };

  const searchSoon = (search: string) => {
    clearTimeout(timer.current);
    timer.current = setTimeout(() => searchWorkspaces(search), 250);
  };

  useEffect(() => () => clearTimeout(timer.current), []);

  useEffect(() => {
    searchWorkspaces("");
  }, [orgid, projectId]);

  useEffect(() => {
    if (!workspaceId) return;
    let cancelled = false;
    setRows(null);
    setFailed(false);
    projectService.getWorkspaceVariableData(orgid, workspaceId).then((response) => {
      if (cancelled) return;
      if (response.isError || !response.data) {
        setFailed(true);
        return;
      }
      setRows(resolveEffectiveVariables(response.data.variables, response.data.globals, response.data.collections));
    });
    return () => {
      cancelled = true;
    };
  }, [orgid, workspaceId]);

  const workspaceName = workspaces.find((workspace) => workspace.id === workspaceId)?.name;

  return (
    <div className="project-effective-variables">
      <h3>Effective variables{workspaceName ? ` for workspace ${workspaceName}` : ""}</h3>
      <Select
        showSearch
        filterOption={false}
        onSearch={searchSoon}
        aria-label="Workspace"
        placeholder="Choose a workspace"
        style={{ minWidth: 280, marginBottom: 12 }}
        value={workspaceId}
        onChange={setWorkspaceId}
        options={workspaces.map((workspace) => ({ value: workspace.id, label: workspace.name }))}
        notFoundContent="No workspaces in this project"
      />
      {failed && <Alert type="warning" showIcon message="Variables could not be loaded for this workspace." />}
      {!workspaceId ? null : rows === null && !failed ? (
        <Skeleton active paragraph={{ rows: 3 }} />
      ) : (
        rows && (
          <Table<EffectiveVariable>
            size="small"
            rowKey={(row) => `${row.category}:${row.key}`}
            dataSource={rows}
            pagination={false}
            locale={{ emptyText: "This workspace runs with no variables." }}
            columns={[
              {
                title: "Key",
                dataIndex: "key",
                render: (key: string, row) => (
                  <>
                    {key} {row.category === "ENV" && <Tag>env</Tag>}
                  </>
                ),
              },
              {
                title: "Value",
                dataIndex: "value",
                render: (value: string | null, row) =>
                  row.sensitive ? <Typography.Text type="secondary">(sensitive)</Typography.Text> : (value ?? ""),
              },
              {
                title: "Source",
                key: "source",
                render: (_: unknown, row) => <SourceLink orgid={orgid} workspaceId={workspaceId} source={row.source} />,
              },
            ]}
          />
        )
      )}
      <Typography.Paragraph type="secondary" className="project-overview-meta">
        Precedence: workspace, then organisation global variables, then collections (workspace, project, organisation
        tier, then priority). Each source links to where it is edited. A workflow template that sets its own inputsEnv
        or inputsTerraform does not load the default collections.
      </Typography.Paragraph>
    </div>
  );
}
