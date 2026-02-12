import { Button, Flex, List, Space } from "antd";
import "antd/dist/reset.css";
import PageWrapper from "@/modules/layout/PageWrapper/PageWrapper";
import { ImportOutlined, PlusOutlined } from "@ant-design/icons";
import { useEffect, useState } from "react";
import WorkspaceFilter from "@/modules/workspaces/components/WorkspaceFilter";
import { WorkspaceListItem } from "@/modules/workspaces/types";
import { Link, useNavigate, useParams } from "react-router-dom";
import workspaceService from "@/modules/workspaces/workspaceService";
import useApiRequest from "@/modules/api/useApiRequest";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../config/actionTypes";
import { TagModel } from "./types";
import WorkspaceCard from "@/modules/workspaces/components/WorkspaceCard";

type Props = {
  organizationName: string;
  setOrganizationName: React.Dispatch<React.SetStateAction<string>>;
};

export default function OrganizationsDetailPage({ organizationName, setOrganizationName }: Props) {
  const { id } = useParams();
  const navigate = useNavigate();
  const [workspaces, setWorkspaces] = useState<WorkspaceListItem[]>([]);
  const [filteredWorkspaces, setFilteredWorkspaces] = useState<WorkspaceListItem[]>([]);
  const [tags, setTags] = useState<TagModel[]>([]);

  const { loading, execute, error } = useApiRequest({
    action: () => workspaceService.listWorkspaces(id!),
    onReturn: (data) => {
      setWorkspaces(data.workspaces);
      setFilteredWorkspaces(data.workspaces);
      sessionStorage.setItem(ORGANIZATION_NAME, data.organizationName);
      setOrganizationName(data.organizationName);
    },
  });

  useEffect(() => {
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, id!);
    execute();
  }, []);

  const handleCreateWorkspace = () => {
    navigate("/workspaces/create");
  };

  return (
    <PageWrapper
      title="Workspaces"
      subTitle={`Workspaces in the ${organizationName} organization`}
      loadingText="Loading workspaces..."
      loading={loading}
      error={error}
      breadcrumbs={[
        { label: organizationName, path: "/" },
        { label: "Workspaces", path: `/organizations/${id}/workspaces` },
      ]}
      fluid
      actions={
        <Space>
          <Button icon={<ImportOutlined />}>
            <Link to="/workspaces/import">Import workspaces</Link>
          </Button>
          <Button icon={<PlusOutlined />} type="primary" onClick={handleCreateWorkspace}>
            New workspace
          </Button>
        </Space>
      }
    >
      <Flex vertical>
        {id && (
          <WorkspaceFilter
            workspaces={workspaces}
            onFiltered={(filtered) => setFilteredWorkspaces(filtered)}
            organizationId={id}
            onTagsLoaded={(t) => setTags(t)}
          />
        )}
        <List
          split={false}
          dataSource={filteredWorkspaces}
          pagination={{ showSizeChanger: true, defaultPageSize: 10 }}
          renderItem={(item) => (
            <List.Item style={{ cursor: "pointer" }} onClick={() => navigate(`/organizations/${id}/workspaces/${item.id}`)}>
              <WorkspaceCard tags={tags} item={item} />
            </List.Item>
          )}
        />
      </Flex>
    </PageWrapper>
  );
}
