import { Button, Empty, Flex, Form, Input, Modal, Table, message } from "antd";
import { PlusOutlined } from "@ant-design/icons";
import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import PageWrapper from "@/modules/layout/PageWrapper/PageWrapper";
import projectService from "./projectService";
import useApiRequest from "@/modules/api/useApiRequest";
import { ProjectModel } from "@/domain/types";
import { ORGANIZATION_NAME } from "../../config/actionTypes";
import { useOrgPermissions } from "@/modules/permissions/useOrgPermissions";

type Props = {
  organizationName: string;
  setOrganizationName: React.Dispatch<React.SetStateAction<string>>;
};

type ProjectForm = {
  name: string;
  description?: string;
};

export default function ProjectsPage({ organizationName, setOrganizationName }: Props) {
  const { id } = useParams();
  const { permissions } = useOrgPermissions(id);
  const [projects, setProjects] = useState<ProjectModel[]>([]);
  const [modalOpen, setModalOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<ProjectForm>();

  const { loading, execute, error } = useApiRequest({
    action: () => projectService.listProjects(id!),
    onReturn: (data) => {
      setProjects(data);
      const stored = sessionStorage.getItem(ORGANIZATION_NAME);
      if (stored) setOrganizationName(stored);
    },
  });

  useEffect(() => {
    execute();
  }, [id]);

  const openCreate = () => {
    form.resetFields();
    setModalOpen(true);
  };

  const handleOk = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      await projectService.createProject(id!, values);
      message.success("Project created");
      setModalOpen(false);
      execute();
    } catch (err: any) {
      if (err?.errorFields) return;
      if (err?.response?.status === 403) {
        message.error(
          <span>
            You are not authorized to create projects. <br /> Please contact your administrator and request the{" "}
            <b>Manage Workspaces</b> permission. <br /> For more information, visit the{" "}
            <a
              target="_blank"
              href="https://docs.terrakube.io/user-guide/organizations/team-management"
              rel="noreferrer"
            >
              Terrakube documentation
            </a>
            .
          </span>
        );
      } else {
        message.error(err?.message ?? "An error occurred");
      }
    } finally {
      setSubmitting(false);
    }
  };

  const columns = [
    {
      title: "Name",
      dataIndex: "name",
      key: "name",
      render: (_: any, record: ProjectModel) => (
        <Button type="link" style={{ padding: 0 }}>
          <Link to={`/organizations/${id}/projects/${record.id}`}>{record.name}</Link>
        </Button>
      ),
    },
    {
      title: "Description",
      dataIndex: "description",
      key: "description",
    },
  ];

  return (
    <PageWrapper
      title="Projects"
      subTitle={`Projects in the ${organizationName} organization`}
      loadingText="Loading projects..."
      loading={loading}
      error={error}
      breadcrumbs={[
        { label: organizationName, path: "/" },
        { label: "Projects", path: `/organizations/${id}/projects` },
      ]}
      fluid
      actions={
        <Button icon={<PlusOutlined />} type="primary" onClick={openCreate} disabled={!permissions.manageWorkspace}>
          New project
        </Button>
      }
    >
      {!loading && projects.length === 0 ? (
        <Flex justify="center">
          <Empty
            className="page-wrapper-no-content"
            style={{ textAlign: "center" }}
            description="You have not created any projects yet. Projects let you group related workspaces together."
          >
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate} disabled={!permissions.manageWorkspace}>
              Create a new project
            </Button>
          </Empty>
        </Flex>
      ) : (
        <Table
          dataSource={projects}
          columns={columns}
          rowKey="id"
          pagination={{ showSizeChanger: true, defaultPageSize: 10 }}
        />
      )}
      <Modal
        title="New project"
        open={modalOpen}
        onOk={handleOk}
        onCancel={() => setModalOpen(false)}
        confirmLoading={submitting}
        okText="Create"
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="Name" rules={[{ required: true, message: "Name is required" }]}>
            <Input />
          </Form.Item>
          <Form.Item name="description" label="Description">
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>
    </PageWrapper>
  );
}
