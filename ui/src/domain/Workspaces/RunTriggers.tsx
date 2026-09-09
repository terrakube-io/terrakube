import { DeleteOutlined, PlusOutlined } from "@ant-design/icons";
import { Alert, Button, Form, message, Modal, Popconfirm, Select, Space, Switch, Table, Tag, Typography } from "antd";
import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import { RunTrigger, RunTriggerRow, Template, Workspace } from "../types";

type Props = {
  organizationId: string;
  workspaceId: string;
  workspaceName: string;
  manageWorkspace: boolean;
};

type TriggerForm = {
  sourceWorkspaceId: string;
  templateId?: string;
};

/** Every resource in an include block, keyed so an edge can resolve its own ends. */
type IncludedIndex = Record<string, { id: string; attributes: { name: string } }>;

const indexIncluded = (included: any[] | undefined): IncludedIndex => {
  const index: IncludedIndex = {};
  (included ?? []).forEach((item) => {
    index[`${item.type}:${item.id}`] = item;
  });
  return index;
};

const nameOf = (index: IncludedIndex, type: string, id: string | undefined) =>
  id ? (index[`${type}:${id}`]?.attributes?.name ?? id) : undefined;

export const RunTriggers = ({ organizationId, workspaceId, workspaceName, manageWorkspace }: Props) => {
  const [form] = Form.useForm<TriggerForm>();
  const [incoming, setIncoming] = useState<RunTriggerRow[]>([]);
  const [outgoing, setOutgoing] = useState<RunTriggerRow[]>([]);
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [templates, setTemplates] = useState<Template[]>([]);
  const [loading, setLoading] = useState(true);
  const [visible, setVisible] = useState(false);

  const loadTriggers = useCallback(() => {
    setLoading(true);
    axiosInstance
      .get("runTrigger", {
        params: {
          include: "sourceWorkspace,destinationWorkspace,template",
          // Both directions in one round trip: a comma is OR in Elide's filter syntax.
          "filter[runTrigger]": `sourceWorkspace.id==${workspaceId},destinationWorkspace.id==${workspaceId}`,
        },
      })
      .then((response) => {
        const index = indexIncluded(response.data.included);
        const edges: RunTrigger[] = response.data.data ?? [];

        const toRow = (trigger: RunTrigger, otherEnd: "sourceWorkspace" | "destinationWorkspace"): RunTriggerRow => {
          const otherId = trigger.relationships[otherEnd]?.data?.id;
          const templateId = trigger.relationships.template?.data?.id;
          return {
            id: trigger.id,
            enabled: trigger.attributes.enabled,
            workspaceId: otherId,
            workspaceName: nameOf(index, "workspace", otherId) ?? otherId,
            templateId,
            templateName: nameOf(index, "template", templateId),
          };
        };

        setIncoming(
          edges
            .filter((edge) => edge.relationships.destinationWorkspace?.data?.id === workspaceId)
            .map((edge) => toRow(edge, "sourceWorkspace"))
        );
        setOutgoing(
          edges
            .filter((edge) => edge.relationships.sourceWorkspace?.data?.id === workspaceId)
            .map((edge) => toRow(edge, "destinationWorkspace"))
        );
        setLoading(false);
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
        setLoading(false);
      });
  }, [workspaceId]);

  useEffect(() => {
    loadTriggers();
  }, [loadTriggers]);

  const loadPickerData = useCallback(() => {
    axiosInstance
      .get(`organization/${organizationId}/workspace`)
      .then((response) => {
        // A workspace cannot trigger itself, so it is not offered as a source.
        setWorkspaces((response.data.data ?? []).filter((item: Workspace) => item.id !== workspaceId));
      })
      .catch((err) => message.error(getErrorMessage(err)));

    axiosInstance
      .get(`organization/${organizationId}/template`)
      .then((response) => setTemplates(response.data.data ?? []))
      .catch((err) => message.error(getErrorMessage(err)));
  }, [organizationId, workspaceId]);

  const onAdd = () => {
    loadPickerData();
    form.resetFields();
    setVisible(true);
  };

  const onCreate = (values: TriggerForm) => {
    const relationships: Record<string, unknown> = {
      // This workspace is the destination: it is the one that will start running. That is
      // also the end the API checks manage rights on, so this is the direction that can be
      // configured from here.
      sourceWorkspace: { data: { type: "workspace", id: values.sourceWorkspaceId } },
      destinationWorkspace: { data: { type: "workspace", id: workspaceId } },
    };
    if (values.templateId) {
      relationships.template = { data: { type: "template", id: values.templateId } };
    }

    axiosInstance
      .post(
        "runTrigger",
        { data: { type: "runTrigger", attributes: { enabled: true }, relationships } },
        { headers: { "Content-Type": "application/vnd.api+json" } }
      )
      .then(() => {
        message.success("Run trigger created successfully");
        setVisible(false);
        form.resetFields();
        loadTriggers();
      })
      .catch((err) => message.error(getErrorMessage(err)));
  };

  const onToggle = (row: RunTriggerRow, enabled: boolean) => {
    axiosInstance
      .patch(
        `runTrigger/${row.id}`,
        { data: { type: "runTrigger", id: row.id, attributes: { enabled } } },
        { headers: { "Content-Type": "application/vnd.api+json" } }
      )
      .then(() => {
        message.success(enabled ? "Run trigger enabled" : "Run trigger disabled");
        loadTriggers();
      })
      .catch((err) => message.error(getErrorMessage(err)));
  };

  const onDelete = (id: string) => {
    // No content type on purpose: the request has no body, and Elide answers 400 when one is
    // declared anyway. Other pages set it here, which is safe only as long as axios drops the
    // header on a bodyless request.
    axiosInstance
      .delete(`runTrigger/${id}`)
      .then(() => {
        message.success("Run trigger deleted successfully");
        loadTriggers();
      })
      .catch((err) => message.error(getErrorMessage(err)));
  };

  const workspaceLink = (row: RunTriggerRow) => (
    <Link to={`/organizations/${organizationId}/workspaces/${row.workspaceId}`}>{row.workspaceName}</Link>
  );

  const incomingColumns = useMemo(
    () => [
      {
        title: "Source workspace",
        key: "workspace",
        render: (_: string, record: RunTriggerRow) => workspaceLink(record),
      },
      {
        title: "Template",
        key: "template",
        render: (_: string, record: RunTriggerRow) =>
          record.templateName ? (
            <Tag color="default">{record.templateName}</Tag>
          ) : (
            <Typography.Text type="secondary">Default template</Typography.Text>
          ),
      },
      {
        title: "Enabled",
        key: "enabled",
        render: (_: string, record: RunTriggerRow) => (
          <Switch
            checked={record.enabled}
            disabled={!manageWorkspace}
            onChange={(checked) => onToggle(record, checked)}
          />
        ),
      },
      {
        title: "Actions",
        key: "action",
        render: (_: string, record: RunTriggerRow) => (
          <Popconfirm
            okButtonProps={{ danger: true }}
            onConfirm={() => onDelete(record.id)}
            title={
              <p>
                This will stop <b>{workspaceName}</b> from running after <b>{record.workspaceName}</b>.
                <br />
                Are you sure?
              </p>
            }
            okText="Yes"
            cancelText="No"
          >
            <Button danger type="link" icon={<DeleteOutlined />} disabled={!manageWorkspace}>
              Delete
            </Button>
          </Popconfirm>
        ),
      },
    ],
    [manageWorkspace, organizationId, workspaceName]
  );

  const outgoingColumns = useMemo(
    () => [
      {
        title: "Destination workspace",
        key: "workspace",
        render: (_: string, record: RunTriggerRow) => workspaceLink(record),
      },
      {
        title: "Template",
        key: "template",
        render: (_: string, record: RunTriggerRow) =>
          record.templateName ? (
            <Tag color="default">{record.templateName}</Tag>
          ) : (
            <Typography.Text type="secondary">Default template</Typography.Text>
          ),
      },
      {
        title: "Enabled",
        key: "enabled",
        render: (_: string, record: RunTriggerRow) =>
          record.enabled ? <Tag color="green">Enabled</Tag> : <Tag>Disabled</Tag>,
      },
    ],
    [organizationId]
  );

  return (
    <div>
      <Typography.Title level={2} style={{ margin: 0 }}>
        Run Triggers
      </Typography.Title>
      <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
        A run trigger starts a run on one workspace after another one changes state. Only runs that apply, destroy or
        execute custom scripts fire them - a plan on its own does not.
      </Typography.Paragraph>

      <Typography.Title level={4}>Runs after</Typography.Title>
      <Typography.Paragraph type="secondary">
        <b>{workspaceName}</b> starts a run when any of these workspaces finishes changing state.
      </Typography.Paragraph>
      <Space orientation="vertical" style={{ width: "100%" }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={onAdd} disabled={!manageWorkspace}>
          Add source workspace
        </Button>
        <Table
          dataSource={incoming}
          columns={incomingColumns}
          rowKey="id"
          loading={loading}
          pagination={false}
          locale={{ emptyText: "This workspace does not run after any other workspace." }}
        />
      </Space>

      <Typography.Title level={4} style={{ marginTop: 32 }}>
        Triggers
      </Typography.Title>
      <Typography.Paragraph type="secondary">
        These workspaces start a run when <b>{workspaceName}</b> changes state. They are managed from their own page,
        because configuring a trigger requires manage rights on the workspace that will run.
      </Typography.Paragraph>
      <Table
        dataSource={outgoing}
        columns={outgoingColumns}
        rowKey="id"
        loading={loading}
        pagination={false}
        locale={{ emptyText: "No workspace runs after this one." }}
      />

      <Modal
        width="600px"
        open={visible}
        title="Add source workspace"
        okText="Create"
        onCancel={() => setVisible(false)}
        onOk={() => {
          form
            .validateFields()
            .then(onCreate)
            .catch(() => {});
        }}
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          description={
            <>
              <b>{workspaceName}</b> will start a run every time the workspace you pick finishes a run that changed
              state. A dependency that would close a loop is rejected.
            </>
          }
        />
        <Form form={form} layout="vertical" name="runTriggerForm">
          <Form.Item
            name="sourceWorkspaceId"
            label="Source workspace"
            rules={[{ required: true, message: "Source workspace is required!" }]}
            extra="The workspace whose runs will trigger this one."
          >
            <Select
              showSearch
              placeholder="Select a workspace"
              optionFilterProp="label"
              options={workspaces.map((item) => ({ label: item.attributes.name, value: item.id }))}
            />
          </Form.Item>
          <Form.Item name="templateId" label="Template" extra="Leave empty to use this workspace's default template.">
            <Select
              allowClear
              placeholder="Default template"
              optionFilterProp="label"
              options={templates.map((item) => ({ label: item.attributes.name, value: item.id }))}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};
