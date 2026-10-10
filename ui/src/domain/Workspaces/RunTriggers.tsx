import { ClusterOutlined, DeleteOutlined, EditOutlined, PlusOutlined } from "@ant-design/icons";
import {
  Alert,
  Button,
  Flex,
  Form,
  message,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
} from "antd";
import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { LinkButton } from "@/components/navigation/LinkButton";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import {
  RunTrigger,
  RunTriggerOnDestroyPolicy,
  RunTriggerRow,
  RunTriggerSynchronizationMode,
  Template,
  Workspace,
} from "../types";
import "./Workspaces.css";

type Props = {
  organizationId: string;
  workspaceId: string;
  workspaceName: string;
  manageWorkspace: boolean;
};

type TriggerForm = {
  sourceWorkspaceId: string;
  templateId?: string;
  synchronizationMode: RunTriggerSynchronizationMode;
  onDestroy: RunTriggerOnDestroyPolicy;
  onDestroyPlanTemplateId?: string;
};

const SYNCHRONIZATION_MODE_OPTIONS = [
  { label: "EACH - run every time a parent succeeds", value: RunTriggerSynchronizationMode.Each },
  { label: "ANY - run once, on the first parent to succeed", value: RunTriggerSynchronizationMode.Any },
  { label: "ALL - run once, only after every parent has succeeded", value: RunTriggerSynchronizationMode.All },
];

const ON_DESTROY_OPTIONS = [
  { label: "TRIGGER - a destroy fires this edge like any other run", value: RunTriggerOnDestroyPolicy.Trigger },
  { label: "PLAN_ONLY - plan the drift on a destroy, never apply it", value: RunTriggerOnDestroyPolicy.PlanOnly },
  { label: "BLOCK - block this destination instead of running it", value: RunTriggerOnDestroyPolicy.Block },
  { label: "IGNORE - a destroy does not fire this edge at all", value: RunTriggerOnDestroyPolicy.Ignore },
];

const SYNC_MODE_TAG_COLOR: Record<RunTriggerSynchronizationMode, string> = {
  [RunTriggerSynchronizationMode.Each]: "default",
  [RunTriggerSynchronizationMode.Any]: "blue",
  [RunTriggerSynchronizationMode.All]: "purple",
};

// Reuses the same plain-English text the add/edit form already shows for these values, so the
// table doesn't make a user open Edit just to find out what an enum they're scanning means.
const descriptionOf = (options: { label: string; value: string }[], value: string) =>
  options.find((option) => option.value === value)?.label.split(" - ").slice(1).join(" - ");

const SYNC_MODE_COLUMN = {
  title: "Sync mode",
  key: "synchronizationMode",
  render: (_: string, record: RunTriggerRow) => (
    <Tooltip title={descriptionOf(SYNCHRONIZATION_MODE_OPTIONS, record.synchronizationMode)}>
      <Tag color={SYNC_MODE_TAG_COLOR[record.synchronizationMode]}>{record.synchronizationMode}</Tag>
    </Tooltip>
  ),
};

const ON_DESTROY_COLUMN = {
  title: "On destroy",
  key: "onDestroy",
  render: (_: string, record: RunTriggerRow) => (
    <Tooltip title={descriptionOf(ON_DESTROY_OPTIONS, record.onDestroy)}>
      <Tag>{record.onDestroy}</Tag>
    </Tooltip>
  ),
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
  const [editingRow, setEditingRow] = useState<RunTriggerRow>();
  const onDestroy = Form.useWatch("onDestroy", form);

  const loadTriggers = useCallback(() => {
    setLoading(true);
    axiosInstance
      .get("runTrigger", {
        params: {
          include: "sourceWorkspace,destinationWorkspace,template,onDestroyPlanTemplate",
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
          const onDestroyPlanTemplateId = trigger.relationships.onDestroyPlanTemplate?.data?.id;
          return {
            id: trigger.id,
            enabled: trigger.attributes.enabled,
            workspaceId: otherId,
            workspaceName: nameOf(index, "workspace", otherId) ?? otherId,
            templateId,
            templateName: nameOf(index, "template", templateId),
            synchronizationMode: trigger.attributes.synchronizationMode,
            onDestroy: trigger.attributes.onDestroy,
            onDestroyPlanTemplateId,
            onDestroyPlanTemplateName: nameOf(index, "template", onDestroyPlanTemplateId),
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
    setEditingRow(undefined);
    form.resetFields();
    form.setFieldsValue({
      synchronizationMode: RunTriggerSynchronizationMode.Each,
      onDestroy: RunTriggerOnDestroyPolicy.Trigger,
    });
    setVisible(true);
  };

  const onEdit = (row: RunTriggerRow) => {
    loadPickerData();
    setEditingRow(row);
    form.setFieldsValue({
      sourceWorkspaceId: row.workspaceId,
      templateId: row.templateId,
      synchronizationMode: row.synchronizationMode,
      onDestroy: row.onDestroy,
      onDestroyPlanTemplateId: row.onDestroyPlanTemplateId,
    });
    setVisible(true);
  };

  const onSubmit = (values: TriggerForm) => {
    const attributes: Record<string, unknown> = {
      synchronizationMode: values.synchronizationMode,
      onDestroy: values.onDestroy,
    };
    const relationships: Record<string, unknown> = {
      template: values.templateId ? { data: { type: "template", id: values.templateId } } : { data: null },
      onDestroyPlanTemplate:
        values.onDestroy === RunTriggerOnDestroyPolicy.PlanOnly && values.onDestroyPlanTemplateId
          ? { data: { type: "template", id: values.onDestroyPlanTemplateId } }
          : { data: null },
    };

    const onSuccess = (verb: string) => {
      message.success(`Run trigger ${verb} successfully`);
      setVisible(false);
      form.resetFields();
      loadTriggers();
    };
    const onFailure = (err: unknown) => message.error(getErrorMessage(err));

    if (editingRow) {
      axiosInstance
        .patch(
          `runTrigger/${editingRow.id}`,
          { data: { type: "runTrigger", id: editingRow.id, attributes, relationships } },
          { headers: { "Content-Type": "application/vnd.api+json" } }
        )
        .then(() => onSuccess("updated"))
        .catch(onFailure);
      return;
    }

    attributes.enabled = true;
    // This workspace is the destination: it is the one that will start running. That is
    // also the end the API checks manage rights on, so this is the direction that can be
    // configured from here. Immutable once created - only PATCH-able fields are sent above.
    relationships.sourceWorkspace = { data: { type: "workspace", id: values.sourceWorkspaceId } };
    relationships.destinationWorkspace = { data: { type: "workspace", id: workspaceId } };

    axiosInstance
      .post(
        "runTrigger",
        { data: { type: "runTrigger", attributes, relationships } },
        { headers: { "Content-Type": "application/vnd.api+json" } }
      )
      .then(() => onSuccess("created"))
      .catch(onFailure);
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

  /**
   * Sources already wired to this workspace are left out: the unique constraint would refuse
   * the duplicate, so offering it is an invitation to a 409. The workspace itself is excluded
   * when the list loads, since it cannot trigger itself either.
   */
  const selectableSources = useMemo(
    () => workspaces.filter((item) => !incoming.some((row) => row.workspaceId === item.id)),
    [workspaces, incoming]
  );

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
      SYNC_MODE_COLUMN,
      ON_DESTROY_COLUMN,
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
          <>
            <Button type="link" icon={<EditOutlined />} disabled={!manageWorkspace} onClick={() => onEdit(record)}>
              Edit
            </Button>
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
          </>
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
      SYNC_MODE_COLUMN,
      ON_DESTROY_COLUMN,
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
      <Flex justify="space-between" align="flex-start" wrap gap="small">
        <div>
          <Typography.Title level={2} className="workspace-flush-title">
            Run triggers
          </Typography.Title>
          <Typography.Paragraph type="secondary" className="run-triggers-intro">
            A run trigger starts a run on one workspace after another one changes state. Only runs that apply,
            destroy or execute custom scripts fire them - a plan on its own does not.
          </Typography.Paragraph>
        </div>
        <LinkButton to={`/organizations/${organizationId}/dependency-graph/${workspaceId}`} icon={<ClusterOutlined />}>
          View dependency graph
        </LinkButton>
      </Flex>
      <div style={{ marginTop: 24 }} />

      <Typography.Title level={4}>Runs after</Typography.Title>
      <Typography.Paragraph type="secondary">
        <b>{workspaceName}</b> starts a run when any of these workspaces finishes changing state.
      </Typography.Paragraph>
      <Space orientation="vertical" className="run-triggers-incoming">
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

      <Typography.Title level={4} className="run-triggers-outgoing-title">
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
        title={editingRow ? "Edit source workspace" : "Add source workspace"}
        okText={editingRow ? "Save" : "Create"}
        onCancel={() => setVisible(false)}
        onOk={() => {
          form
            .validateFields()
            .then(onSubmit)
            .catch(() => {});
        }}
      >
        {!editingRow && (
          <Alert
            type="info"
            showIcon
            className="run-triggers-modal-alert"
            description={
              <>
                <b>{workspaceName}</b> will start a run every time the workspace you pick finishes a run that changed
                state. A dependency that would close a loop is rejected.
              </>
            }
          />
        )}
        <Form form={form} layout="vertical" name="runTriggerForm">
          {editingRow ? (
            <Form.Item label="Source workspace" extra="Immutable once created - delete and re-add to change it.">
              <Typography.Text strong>{editingRow.workspaceName}</Typography.Text>
            </Form.Item>
          ) : (
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
                options={selectableSources.map((item) => ({ label: item.attributes.name, value: item.id }))}
              />
            </Form.Item>
          )}
          <Form.Item name="templateId" label="Template" extra="Leave empty to use this workspace's default template.">
            <Select
              allowClear
              placeholder="Default template"
              optionFilterProp="label"
              options={templates.map((item) => ({ label: item.attributes.name, value: item.id }))}
            />
          </Form.Item>
          <Form.Item
            name="synchronizationMode"
            label="Sync mode"
            extra="How this workspace waits when it has more than one enabled upstream edge."
          >
            <Select options={SYNCHRONIZATION_MODE_OPTIONS} />
          </Form.Item>
          <Form.Item name="onDestroy" label="On a destroy upstream" extra="What a destroy on the source does here.">
            <Select options={ON_DESTROY_OPTIONS} />
          </Form.Item>
          {onDestroy === RunTriggerOnDestroyPolicy.PlanOnly && (
            <Form.Item
              name="onDestroyPlanTemplateId"
              label="Plan-only template"
              rules={[{ required: true, message: "A plan-only template is required when a destroy only plans here!" }]}
              extra="Dispatched instead of the normal template on a destroy, so the drift is only ever planned."
            >
              <Select
                placeholder="Select a template"
                optionFilterProp="label"
                options={templates.map((item) => ({ label: item.attributes.name, value: item.id }))}
              />
            </Form.Item>
          )}
        </Form>
      </Modal>
    </div>
  );
};
