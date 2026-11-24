import { Alert, Table, Tag, Typography } from "antd";
import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import projectService, { ProjectPolicySet } from "./projectService";

type Props = {
  orgid: string;
  projectId: string;
};

const LEVEL_LABELS: Record<string, string> = {
  HARD_MANDATORY: "Hard mandatory",
  SOFT_MANDATORY: "Soft mandatory",
  ADVISORY: "Advisory",
};

/** The policy sets that apply to every workspace in the project, and where each one comes from. */
export default function ProjectPoliciesTab({ orgid, projectId }: Props) {
  const [sets, setSets] = useState<ProjectPolicySet[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();

  useEffect(() => {
    setLoading(true);
    projectService.listProjectPolicySets(orgid, projectId).then((response) => {
      if (response.isError) {
        setError("Policy sets could not be loaded. You may not have permission to view them.");
        setSets([]);
      } else {
        setError(undefined);
        setSets(response.data ?? []);
      }
      setLoading(false);
    });
  }, [orgid, projectId]);

  return (
    <div className="project-settings-panel">
      <SettingsPageHeader
        title="Policies"
        description="Policy sets that run on every workspace in this project. Sets attached to a single workspace or to a tag are not listed."
      />
      {error && <Alert type="warning" showIcon message={error} className="project-overview-alert" />}
      <Table<ProjectPolicySet>
        rowKey="id"
        loading={loading}
        dataSource={sets}
        pagination={false}
        locale={{ emptyText: "No policy sets apply to this whole project." }}
        columns={[
          {
            title: "Policy set",
            dataIndex: "name",
            key: "name",
            render: (name: string, set: ProjectPolicySet) => (
              <Link to={`/organizations/${orgid}/settings/policies/edit/${set.id}`}>{name}</Link>
            ),
          },
          {
            title: "Enforcement",
            dataIndex: "enforcementLevel",
            key: "enforcementLevel",
            render: (level: string) => LEVEL_LABELS[level] ?? level,
          },
          {
            title: "Applies because",
            key: "source",
            render: (_: unknown, set: ProjectPolicySet) =>
              set.global ? <Tag>Global, every workspace</Tag> : <Tag color="blue">Attached to this project</Tag>,
          },
        ]}
      />
      <Typography.Paragraph type="secondary" className="project-overview-meta">
        Policy sets are managed in the organization settings.
      </Typography.Paragraph>
    </div>
  );
}
