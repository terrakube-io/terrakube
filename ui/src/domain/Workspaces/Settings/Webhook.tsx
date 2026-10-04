import { DeleteOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, Checkbox, Form, Input, InputNumber, Popconfirm, Select, Spin, Switch, message } from "antd";
import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { v7 as uuid } from "uuid";
import axiosInstance, { getErrorMessage } from "../../../config/axiosConfig";
import { Template, VcsType, WebhookEvent, WebhookEventPathType, Workspace } from "../../types";
import { atomicHeader } from "../Workspaces";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";
import SettingsSection from "@/components/settings/SettingsSection/SettingsSection";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { SettingsForm } from "@/components/settings/SettingsForm";
import { IdField } from "@/components/settings/IdField";
import { RadioChoices } from "@/components/settings/RadioChoices";
import { EmptyState } from "@/components/feedback/EmptyState";
import "./Webhook.css";

const isValidRegexList = (str: string | undefined) => {
  if (!str) {
    return true;
  }

  return str
    .split(",")
    .map((s) => s.trim())
    .every((s) => {
      try {
        new RegExp(s);
        return true;
      } catch {
        return false;
      }
    });
};

type Trigger = {
  id: string;
  priority?: number;
  event?: string;
  branch?: string;
  file?: string;
  pathType: WebhookEventPathType;
  template?: string;
  prWorkflowEnabled: boolean;
  prApplyEnabled: boolean;
  created?: boolean;
};

const EVENT_LABELS: Record<string, string> = { push: "Push", pull_request: "Pull request", release: "Release" };
const SHARED_WEBHOOK_VCS = [VcsType.GITHUB, VcsType.GITLAB, VcsType.AZURE_SP_MI];
// Explicitly drop Content-Type: Elide's Spring routing maps DELETE requests carrying
// "application/vnd.api+json" to its relationship-delete handler, which then rejects them.
const deleteHeaders = { headers: { "Content-Type": undefined } };

type Props = {
  workspace: Workspace;
  manageWorkspace: boolean;
  orgTemplates: Template[];
  vcsProvider?: VcsType;
  onWorkspaceUpdate?: () => void;
};

export const WorkspaceWebhook = ({
  workspace,
  vcsProvider,
  orgTemplates,
  manageWorkspace,
  onWorkspaceUpdate,
}: Props) => {
  const organizationId = workspace.relationships.organization.data.id;
  const workspaceId = workspace.id;
  const webhookId = workspace.relationships.webhook?.data?.id;
  const hasVcs = Boolean(workspace.relationships.vcs?.data?.id);
  const baseUrl = `organization/${organizationId}/workspace/${workspaceId}/webhook`;

  const [form] = Form.useForm<{ events: Trigger[] }>();
  const triggers: Trigger[] = Form.useWatch("events", { form, preserve: true }) ?? [];
  const [loading, setLoading] = useState(Boolean(webhookId && hasVcs));
  const [saving, setSaving] = useState(false);
  const [webhookEnabled, setWebhookEnabled] = useState(Boolean(webhookId));
  const [remoteHookId, setRemoteHookId] = useState("");
  const [migratedV2, setMigratedV2] = useState(false);
  const [expanded, setExpanded] = useState<string[]>([]);
  // Save model: every change waits for "Save webhook", then deleted, edited and new triggers go in one
  // atomic request, so a failed save changes nothing.
  const [deletedIds, setDeletedIds] = useState<string[]>([]);
  const [pendingDelete, setPendingDelete] = useState<Trigger | null>(null);
  const [confirmDisable, setConfirmDisable] = useState(false);

  useEffect(() => {
    if (!webhookId || !hasVcs) {
      return;
    }
    setLoading(true);
    Promise.all([axiosInstance.get(`${baseUrl}/${webhookId}`), axiosInstance.get(`${baseUrl}/${webhookId}/events`)])
      .then(([webhookRes, eventsRes]) => {
        setRemoteHookId(webhookRes.data.data.attributes.remoteHookId);
        setMigratedV2(webhookRes.data.data.attributes.migratedV2 || false);
        // Ascending, the order the API checks triggers in.
        const events: Trigger[] = eventsRes.data.data
          .sort((a: WebhookEvent, b: WebhookEvent) => a.attributes.priority - b.attributes.priority)
          .map((event: WebhookEvent) => ({
            id: event.id,
            priority: event.attributes.priority,
            event: (event.attributes.event || "").toString().toLowerCase(),
            branch: event.attributes.branch,
            file: event.attributes.path,
            pathType: event.attributes.pathType || WebhookEventPathType.REGEX,
            template: event.attributes.templateId,
            prWorkflowEnabled: event.attributes.prWorkflowEnabled || false,
            prApplyEnabled: event.attributes.prApplyEnabled || false,
            created: true,
          }));
        form.setFieldsValue({ events });
      })
      .catch((error) => message.error(`Couldn't load the webhook: ${getErrorMessage(error)}`))
      .finally(() => setLoading(false));
  }, [baseUrl, webhookId, hasVcs, form]);

  const addTrigger = () => {
    const trigger: Trigger = {
      id: uuid(),
      pathType: WebhookEventPathType.PATTERN,
      prWorkflowEnabled: false,
      prApplyEnabled: false,
    };
    form.setFieldValue("events", [...(form.getFieldValue("events") ?? []), trigger]);
    setExpanded((ids) => [...ids, trigger.id]);
  };

  const removeTrigger = (trigger: Trigger) => {
    form.setFieldValue(
      "events",
      (form.getFieldValue("events") as Trigger[]).filter((t) => t.id !== trigger.id)
    );
    if (trigger.created) {
      setDeletedIds((ids) => [...ids, trigger.id]);
    }
  };

  const toggleExpanded = (id: string) =>
    setExpanded((ids) => (ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id]));

  const onEnabledChange = (checked: boolean) => {
    setWebhookEnabled(checked);
    if (checked && triggers.length === 0) {
      addTrigger();
    }
  };

  const disableWebhook = async () => {
    setConfirmDisable(false);
    setSaving(true);
    try {
      await axiosInstance.delete(`${baseUrl}/${webhookId}`, deleteHeaders);
      form.setFieldValue("events", []);
      setDeletedIds([]);
      message.success("Webhook deleted");
      onWorkspaceUpdate?.();
    } catch (error) {
      message.error(`Couldn't delete the webhook: ${getErrorMessage(error)}`);
    } finally {
      setSaving(false);
    }
  };

  const save = async () => {
    if (!webhookEnabled) {
      setConfirmDisable(true);
      return;
    }
    const events: Trigger[] = form.getFieldValue("events") ?? [];
    const newWebhookId = webhookId ?? uuid();
    const body = {
      "atomic:operations": [
        ...deletedIds.map((id) => ({ op: "remove", href: `/${baseUrl}/${newWebhookId}/events/${id}` })),
        {
          op: webhookId ? "update" : "add",
          href: `/${baseUrl}`,
          data: { type: "webhook", id: newWebhookId },
          relationships: {
            events: { data: events.map((event) => ({ type: "webhook_event", id: event.id })) },
          },
        },
        ...events.map((event) => ({
          op: event.created ? "update" : "add",
          href: event.created ? `/${baseUrl}/${newWebhookId}/events/${event.id}` : `/${baseUrl}/${newWebhookId}/events`,
          data: {
            type: "webhook_event",
            id: event.id,
            attributes: {
              priority: event.priority ? event.priority : 1,
              event: event.event!.toUpperCase(),
              branch: event.branch,
              path: event.file,
              pathType: event.pathType || WebhookEventPathType.PATTERN,
              templateId: event.template,
              prWorkflowEnabled: event.prWorkflowEnabled || false,
              prApplyEnabled: event.prApplyEnabled || false,
            },
          },
        })),
      ],
    };

    setSaving(true);
    try {
      const response = await axiosInstance.post("/operations", body, atomicHeader);
      if (response.status !== 200) {
        throw new Error(`Unexpected response ${response.status}`);
      }
      form.setFieldValue(
        "events",
        events.map((event) => ({ ...event, created: true }))
      );
      setDeletedIds([]);
      setExpanded([]);
      message.success("Webhook saved");
      onWorkspaceUpdate?.();
    } catch (error: any) {
      message.error(
        error?.response?.status === 424
          ? "The VCS connection can't manage webhooks on this repository. It needs Webhooks: write, and Pull requests: write to post plans."
          : `Couldn't save the webhook: ${getErrorMessage(error)}`
      );
    } finally {
      setSaving(false);
    }
  };

  const handleMigrateV2 = async () => {
    setSaving(true);
    try {
      const response = await axiosInstance.patch(
        `${baseUrl}/${webhookId}`,
        { data: { type: "webhook", id: webhookId, attributes: { migratedV2: true } } },
        { headers: { "Content-Type": "application/vnd.api+json" } }
      );
      if (response.status !== 200 && response.status !== 204) {
        throw new Error(`Unexpected response ${response.status}`);
      }
      setMigratedV2(true);
      message.success("Migrated to a shared webhook");
    } catch (error) {
      message.error(`Couldn't migrate the webhook: ${getErrorMessage(error)}`);
    } finally {
      setSaving(false);
    }
  };

  const templateName = (id?: string) => orgTemplates.find((t) => t.id === id)?.attributes?.name;

  const summaryText = (trigger: Trigger) =>
    [EVENT_LABELS[trigger.event ?? ""] ?? "New trigger", trigger.branch, trigger.file].filter(Boolean).join(" · ") +
    (trigger.template ? ` → ${templateName(trigger.template) ?? trigger.template}` : "");

  const header = (
    <SettingsPageHeader
      docUrl="https://docs.terrakube.io/user-guide/workspaces/webhooks"
      title="Webhook"
      description="Start runs automatically when the repository receives a push, pull request or release."
    />
  );

  if (!hasVcs) {
    return (
      <div>
        {header}
        <EmptyState description="Webhooks need a VCS connection. Connect this workspace to a VCS provider to start runs from repository events.">
          <Link to={`/organizations/${organizationId}/workspaces/${workspaceId}/settings/general`}>
            Choose a VCS provider in General settings
          </Link>
        </EmptyState>
      </div>
    );
  }

  const enabledHelp = webhookEnabled
    ? webhookId
      ? undefined
      : "A webhook is created on the repository when you save."
    : webhookId
      ? "Saving deletes the webhook from the repository."
      : undefined;

  return (
    <div>
      {header}
      <Spin spinning={loading}>
        <SettingsForm
          form={form}
          name="webhook"
          onFinish={save}
          onFinishFailed={({ errorFields }) => {
            const ids = errorFields
              .map((field) => triggers[field.name[1] as number]?.id)
              .filter((id): id is string => Boolean(id));
            setExpanded((current) => [...new Set([...current, ...ids])]);
          }}
          saveLabel="Save webhook"
          saving={saving}
          saveDisabled={!manageWorkspace || (!webhookEnabled && !webhookId)}
        >
          <SettingsSection title="Repository webhook">
            <Form.Item label="Enable webhook" htmlFor="webhook-enabled" extra={enabledHelp}>
              <Switch
                id="webhook-enabled"
                checked={webhookEnabled}
                onChange={onEnabledChange}
                disabled={!manageWorkspace}
              />
            </Form.Item>
            {webhookEnabled && webhookId && (
              <>
                <IdField id="webhook-id" label="Webhook ID" value={webhookId} copiedMessage="Webhook ID copied" />
                {migratedV2 ? (
                  <Form.Item label="Repository webhook">Shared with every workspace on this repository.</Form.Item>
                ) : (
                  remoteHookId && (
                    <IdField
                      id="webhook-remote-id"
                      label="Repository webhook ID"
                      value={remoteHookId}
                      copiedMessage="Repository webhook ID copied"
                    />
                  )
                )}
                {!migratedV2 && vcsProvider && SHARED_WEBHOOK_VCS.includes(vcsProvider) && (
                  <Form.Item
                    label="Shared webhook"
                    extra="Experimental. One webhook serves every workspace that uses this repository."
                  >
                    <Popconfirm
                      title="Migrate to a shared webhook?"
                      description="This workspace's webhook is replaced by one webhook for the whole repository."
                      okText="Migrate"
                      cancelText="Cancel"
                      onConfirm={handleMigrateV2}
                      disabled={!manageWorkspace}
                    >
                      <Button disabled={!manageWorkspace}>Migrate to shared webhook</Button>
                    </Popconfirm>
                  </Form.Item>
                )}
              </>
            )}
          </SettingsSection>

          {webhookEnabled && (
            <SettingsSection
              title={`Triggers (${triggers.length})`}
              description="A trigger starts a run with its template when a matching repository event arrives."
            >
              <Form.List
                name="events"
                rules={[
                  {
                    validator: async (_, list?: Trigger[]) => {
                      if (!list?.length) {
                        throw new Error("Add at least one trigger.");
                      }
                    },
                  },
                ]}
              >
                {(fields, _, { errors }) => (
                  <>
                    {fields.length > 0 && (
                      <ul className="webhook-triggers">
                        {fields.map(({ key, name }) => {
                          const trigger: Trigger | undefined = triggers[name] ?? form.getFieldValue(["events", name]);
                          if (!trigger) {
                            return null;
                          }
                          const open = expanded.includes(trigger.id);
                          const panelId = `webhook-trigger-${trigger.id}`;
                          const isRegex = trigger.pathType === WebhookEventPathType.REGEX;
                          const meta = [
                            `Priority ${trigger.priority ?? 1}`,
                            isRegex ? "Regex paths" : "Pattern paths",
                            trigger.event === "pull_request" && trigger.prWorkflowEnabled && "Plan posted on PRs",
                            trigger.event === "pull_request" && trigger.prApplyEnabled && "Apply by comment",
                            !trigger.created && "Not saved",
                          ].filter(Boolean);
                          return (
                            <li key={key} className="webhook-trigger">
                              <div className="webhook-trigger-summary">
                                <div className="webhook-trigger-text">
                                  <span className="webhook-trigger-title">
                                    {EVENT_LABELS[trigger.event ?? ""] ?? "New trigger"}
                                    {trigger.branch && (
                                      <>
                                        {" · "}
                                        <span className="webhook-mono">{trigger.branch}</span>
                                      </>
                                    )}
                                    {trigger.file && (
                                      <>
                                        {" · "}
                                        <span className="webhook-mono">{trigger.file}</span>
                                      </>
                                    )}
                                    {trigger.template && <> → {templateName(trigger.template) ?? trigger.template}</>}
                                  </span>
                                  <span className="webhook-trigger-meta">{meta.join(" · ")}</span>
                                </div>
                                <Button
                                  aria-expanded={open}
                                  aria-controls={panelId}
                                  aria-label={`${open ? "Collapse" : "Edit"} trigger ${name + 1}`}
                                  onClick={() => toggleExpanded(trigger.id)}
                                >
                                  {open ? "Collapse" : "Edit"}
                                </Button>
                                <Button
                                  icon={<DeleteOutlined />}
                                  aria-label={`Delete trigger ${name + 1}`}
                                  disabled={!manageWorkspace}
                                  onClick={() => (trigger.created ? setPendingDelete(trigger) : removeTrigger(trigger))}
                                />
                              </div>
                              <div id={panelId} className="webhook-trigger-fields" hidden={!open}>
                                <Form.Item
                                  name={[name, "event"]}
                                  label="Event"
                                  rules={[{ required: true, message: "Choose an event." }]}
                                >
                                  <Select
                                    placeholder="Select an event"
                                    disabled={!manageWorkspace}
                                    options={Object.entries(EVENT_LABELS).map(([value, label]) => ({ value, label }))}
                                  />
                                </Form.Item>
                                <Form.Item
                                  name={[name, "branch"]}
                                  label="Branches or releases"
                                  extra="Comma-separated regexes matched against branch names, or release tags for release events."
                                  rules={[
                                    { required: true, message: "Enter at least one branch or release regex." },
                                    {
                                      validator: async (_, value?: string) => {
                                        if (!isValidRegexList(value)) {
                                          throw new Error("Use valid regexes, separated by commas.");
                                        }
                                      },
                                    },
                                  ]}
                                >
                                  <Input className="webhook-mono" placeholder="main" disabled={!manageWorkspace} />
                                </Form.Item>
                                <Form.Item name={[name, "pathType"]} label="Path matching">
                                  <RadioChoices
                                    disabled={!manageWorkspace}
                                    options={[
                                      {
                                        value: WebhookEventPathType.PATTERN,
                                        label: "Pattern",
                                        help: "Simple wildcards such as terraform/* or modules/**.",
                                      },
                                      {
                                        value: WebhookEventPathType.REGEX,
                                        label: "Regex",
                                        help: "Full regular expressions.",
                                      },
                                    ]}
                                  />
                                </Form.Item>
                                <Form.Item
                                  name={[name, "file"]}
                                  label="Paths"
                                  dependencies={[["events", name, "pathType"]]}
                                  extra={
                                    isRegex
                                      ? "Comma-separated regexes. A run starts when a changed file matches one."
                                      : "Comma-separated wildcards. A run starts when a changed file matches one."
                                  }
                                  rules={[
                                    { required: true, message: "Enter at least one path." },
                                    {
                                      validator: async (_, value?: string) => {
                                        if (
                                          form.getFieldValue(["events", name, "pathType"]) ===
                                            WebhookEventPathType.REGEX &&
                                          !isValidRegexList(value)
                                        ) {
                                          throw new Error("Use valid regexes, separated by commas.");
                                        }
                                      },
                                    },
                                  ]}
                                >
                                  <Input
                                    className="webhook-mono"
                                    placeholder={isRegex ? "terraform/.*\\.tf" : "terraform/*"}
                                    disabled={!manageWorkspace}
                                  />
                                </Form.Item>
                                <Form.Item
                                  name={[name, "template"]}
                                  label="Template"
                                  rules={[{ required: true, message: "Choose a template." }]}
                                >
                                  <Select
                                    placeholder="Select a template"
                                    disabled={!manageWorkspace}
                                    options={orgTemplates.map((t) => ({ value: t.id, label: t.attributes?.name }))}
                                  />
                                </Form.Item>
                                <Form.Item
                                  name={[name, "priority"]}
                                  label="Priority"
                                  extra="Triggers for the same event are checked from the lowest number up. The first match starts the run."
                                >
                                  <InputNumber min={1} placeholder="1" disabled={!manageWorkspace} />
                                </Form.Item>
                                {trigger.event === "pull_request" && (
                                  <>
                                    <Form.Item
                                      name={[name, "prWorkflowEnabled"]}
                                      valuePropName="checked"
                                      extra={
                                        <>
                                          Comments the plan on every pull request. Comment <code>terrakube plan</code>{" "}
                                          to run it again.
                                        </>
                                      }
                                    >
                                      <Checkbox
                                        disabled={!manageWorkspace}
                                        onChange={(e) => {
                                          if (!e.target.checked) {
                                            form.setFieldValue(["events", name, "prApplyEnabled"], false);
                                          }
                                        }}
                                      >
                                        Post the plan on pull requests
                                      </Checkbox>
                                    </Form.Item>
                                    <Form.Item
                                      name={[name, "prApplyEnabled"]}
                                      valuePropName="checked"
                                      extra={
                                        <>
                                          Comment <code>terrakube apply</code> on the pull request to apply this
                                          workspace. Needs the plan on pull requests.
                                        </>
                                      }
                                    >
                                      <Checkbox disabled={!manageWorkspace || !trigger.prWorkflowEnabled}>
                                        Allow apply from a pull request comment
                                      </Checkbox>
                                    </Form.Item>
                                  </>
                                )}
                              </div>
                            </li>
                          );
                        })}
                      </ul>
                    )}
                    <Form.ErrorList errors={errors} />
                    <Button icon={<PlusOutlined />} onClick={addTrigger} disabled={!manageWorkspace}>
                      Add trigger
                    </Button>
                  </>
                )}
              </Form.List>
            </SettingsSection>
          )}
        </SettingsForm>
      </Spin>

      <DeleteConfirmationModal
        open={pendingDelete !== null}
        title="Delete trigger"
        message={pendingDelete && `"${summaryText(pendingDelete)}" stops starting runs once you save the webhook.`}
        okText="Delete"
        onConfirm={() => {
          if (pendingDelete) {
            removeTrigger(pendingDelete);
          }
          setPendingDelete(null);
        }}
        onCancel={() => setPendingDelete(null)}
      />
      <DeleteConfirmationModal
        open={confirmDisable}
        title="Delete webhook"
        message={`The webhook and its ${triggers.length} trigger${triggers.length === 1 ? "" : "s"} are deleted from the repository. Repository events stop starting runs for this workspace.`}
        okText="Delete webhook"
        onConfirm={disableWebhook}
        onCancel={() => setConfirmDisable(false)}
      />
    </div>
  );
};
