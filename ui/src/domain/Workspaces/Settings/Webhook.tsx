import { InfoCircleOutlined } from "@ant-design/icons";
import {
  Button,
  Col,
  Flex,
  Form,
  Input,
  Popconfirm,
  Row,
  Select,
  Space,
  Spin,
  Switch,
  Table,
  Tooltip,
  Typography,
  message,
} from "antd";
import { useEffect, useState } from "react";
import { v7 as uuid } from "uuid";
import axiosInstance from "../../../config/axiosConfig";
import { Template, VcsType, WebhookEvent, Workspace } from "../../types";
import { atomicHeader, renderVCSLogo } from "../Workspaces";

const isValidRegexList = (str: string | undefined) => {
  if (!str) return true;
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

type Props = {
  workspace: Workspace;
  manageWorkspace: boolean;
  orgTemplates: Template[];
  vcsProvider?: VcsType;
};

export const WorkspaceWebhook = ({ workspace, vcsProvider, orgTemplates, manageWorkspace }: Props) => {
  const [waiting, setWaiting] = useState(true);
  const [webhookEnabled, setWebhookEnabled] = useState(false);
  const [recordIndex, setRecordIndex] = useState(1);
  const organizationId = workspace.relationships.organization.data.id;
  const [webhookEvents, setWebhookEvents] = useState<any[]>([
    {
      key: 1,
      id: uuid(),
      prWorkflowEnabled: false,
    } as any,
  ]);
  const workspaceId = workspace.id;
  const [remoteHookId, setRemoteHookId] = useState("");
  const webhookId = workspace.relationships.webhook?.data?.id;

  useEffect(() => {
    setWaiting(true);
    loadWebhook();
    setWaiting(false);
  }, []);
  const loadWebhook = () => {
    if (!webhookId) {
      setWebhookEnabled(false);
      return;
    }
    setWebhookEnabled(true);

    // Parallel load: webhook details and webhook events
    Promise.all([
      axiosInstance.get(`organization/${organizationId}/workspace/${workspaceId}/webhook/${webhookId}`),
      axiosInstance.get(`organization/${organizationId}/workspace/${workspaceId}/webhook/${webhookId}/events`),
    ])
      .then(([webhookRes, eventsRes]) => {
        setRemoteHookId(webhookRes.data.data.attributes.remoteHookId);

        let i = 1;
        const events = eventsRes.data.data
          .sort((a: WebhookEvent, b: WebhookEvent) => b.attributes.priority - a.attributes.priority)
          .map((event: WebhookEvent) => {
            return {
              key: i++,
              id: event.id,
              priority: event.attributes.priority,
              event: event.attributes.event,
              branch: event.attributes.branch,
              file: event.attributes.path,
              template: event.attributes.templateId,
              prWorkflowEnabled: event.attributes.prWorkflowEnabled || false,
              created: true,
            };
          });
        setRecordIndex(events.length + 1);
        setWebhookEvents(
          events.concat({
            key: i,
            id: uuid(),
          })
        );
      })
      .catch(() => {
        message.error("Failed to load webhook");
      });
  };
  const handleEventChange = (index: number, _: any, name: string, value: string | boolean) => {
    webhookEvents[index][name] = value;
    if (index == webhookEvents.length - 1) {
      const index = recordIndex + 1;
      setWebhookEvents([
        ...webhookEvents,
        {
          key: index,
          id: uuid(),
          prWorkflowEnabled: false,
        },
      ]);
      setRecordIndex(index);
    } else {
      setWebhookEvents([...webhookEvents]);
    }
  };
  const handleWebhookClick = () => {
    setWebhookEnabled(!webhookEnabled);
  };
  const onDelete = (record: any) => {
    const newWebhookEvents = webhookEvents.filter((item) => item.key !== record.key);
    if (record.created) {
      axiosInstance
        .delete(`organization/${organizationId}/workspace/${workspaceId}/webhook/${webhookId}/events/${record.id}`)
        .then((response) => {
          if (response.status != 204) {
            message.error("Failed to delete webhook event");
            return;
          }
          message.success("Webhook event deleted successfully");
          setRecordIndex(recordIndex - 1);
        });
    }
    if (newWebhookEvents.length == 0) {
      newWebhookEvents.push({
        key: 1,
        id: uuid(),
        prWorkflowEnabled: false,
      });
    }
    setWebhookEvents(newWebhookEvents);
  };
  const onFinish = () => {
    setWaiting(true);
    if (!webhookEnabled) {
      axiosInstance
        .delete(`organization/${organizationId}/workspace/${workspaceId}/webhook/${webhookId}`)
        .then((response) => {
          if (response.status != 204) {
            message.error("Failed to disable webhook");
            setWaiting(false);
            return;
          }
        });
      message.success("Webhook disabled successfully");
      setWebhookEvents([]);
      setWaiting(false);
      return;
    }
    if (webhookEnabled && webhookEvents.length == 1) {
      message.error("At least one event configuration is required");
      setWaiting(false);
      return;
    }
    // Verify required fields
    let inputError = false;
    webhookEvents
      .filter((_, index) => index < recordIndex - 1)
      .forEach((event) => {
        event.eventStatus = event.event ? "success" : "error";
        event.branchStatus = event.branch ? "success" : "error";
        event.fileStatus = event.file ? "success" : "error";
        event.templateStatus = event.template ? "success" : "error";

        if (!event.event || !event.branch || !event.file || !event.template) {
          inputError = true;
        }
      });
    if (inputError) {
      setWaiting(false);
      message.error("Event, Branch, File and Template are required fields");
      setWebhookEvents([...webhookEvents]);
      return;
    }
    // Verify regex patterns
    let regexError = false;
    webhookEvents
      .filter((_, index) => index < recordIndex - 1)
      .forEach((event) => {
        if (!isValidRegexList(event.branch)) {
          event.branchStatus = "error";
          regexError = true;
        }
        if (!isValidRegexList(event.file)) {
          event.fileStatus = "error";
          regexError = true;
        }
      });
    if (regexError) {
      setWaiting(false);
      message.error("Branch and File must be valid regex patterns");
      setWebhookEvents([...webhookEvents]);
      return;
    }
    const baseRequestURL = `/organization/${organizationId}/workspace/${workspaceId}/webhook`;
    const newWebhookId = webhookId ? webhookId : uuid();
    const body = {
      "atomic:operations": [
        {
          op: webhookId ? "update" : "add",
          href: baseRequestURL,
          data: {
            type: "webhook",
            id: newWebhookId,
          },
          relationships: {
            events: {
              data: webhookEvents
                .filter((_, index) => index < recordIndex - 1)
                .map(function (event, _) {
                  return {
                    type: "webhook_event",
                    id: event.id,
                  };
                }),
            },
          },
        },
        ...webhookEvents
          .filter((_, index) => index < recordIndex - 1)
          .map(function (event, _) {
            return {
              op: event.created ? "update" : "add",
              href: event.created
                ? `${baseRequestURL}/${newWebhookId}/events/${event.id}`
                : `${baseRequestURL}/${newWebhookId}/events`,
              data: {
                type: "webhook_event",
                id: event.id,
                attributes: {
                  priority: event.priority ? event.priority : 1,
                  event: event.event.toUpperCase(),
                  branch: event.branch,
                  path: event.file,
                  templateId: event.template,
                  prWorkflowEnabled: event.prWorkflowEnabled || false,
                },
              },
            };
          }),
      ],
    };

    axiosInstance.post("/operations", body, atomicHeader).then((response) => {
      if (response.status != 200) {
        message.error("Failed to save webhook");
        setWaiting(false);
        return;
      }
      // Mark all events as created
      webhookEvents
        .filter((_, index) => index < recordIndex - 1)
        .forEach((event) => {
          event.created = true;
          event.eventStatus = "success";
          event.branchStatus = "success";
          event.fileStatus = "success";
          event.templateStatus = "success";
        });
      setWebhookEvents([...webhookEvents]);
      setWaiting(false);
      message.success("Webhook saved successfully");
    });
  };
  const columns = [
    {
      title: "Priority",
      dataIndex: "priority",
      key: "priority",
      width: "5%",
      render: (_: string, record: any, index: number) => (
        <Input
          placeholder="1"
          name="priority"
          value={record.priority}
          status={record.status}
          onChange={(e) => handleEventChange(index, record.key, e.target.name, e.target.value)}
        ></Input>
      ),
    },
    {
      title: "Event",
      dataIndex: "event",
      key: "event",
      width: "8%",
      render: (_: string, record: any, index: number) => (
        <Select
          placeholder="Select an event"
          value={record.event}
          status={record.eventStatus}
          onChange={(e) => handleEventChange(index, record.key, "event", e)}
        >
          <Select.Option value="push">Push</Select.Option>
          <Select.Option value="pull_request">Pull Request</Select.Option>
          <Select.Option value="release">Release</Select.Option>
        </Select>
      ),
    },
    {
      title: (
        <Tooltip title="Enable Atlantis-style workflow: post plan/apply results as PR comments and accept 'terrakube plan' / 'terrakube apply' commands from PR comments">
          PR Workflow <InfoCircleOutlined />
        </Tooltip>
      ),
      dataIndex: "prWorkflowEnabled",
      key: "prWorkflowEnabled",
      width: "8%",
      render: (_: string, record: any, index: number) => (
        record.event === "pull_request" ? (
          <Switch
            size="small"
            checked={record.prWorkflowEnabled || false}
            onChange={(checked) => handleEventChange(index, record.key, "prWorkflowEnabled", checked)}
          />
        ) : null
      ),
    },
    {
      title: "Branch/release",
      dataIndex: "branch",
      key: "branch",
      render: (_: string, record: any, index: number) => (
        <Input
          placeholder="List of regex to match aginst branch names or release names"
          name="branch"
          status={record.branchStatus}
          value={record.branch}
          onChange={(e) => handleEventChange(index, record.key, e.target.name, e.target.value)}
        ></Input>
      ),
    },
    {
      title: "File",
      dataIndex: "file",
      key: "file",
      width: "45%",
      render: (_: string, record: any, index: number) => (
        <Input
          placeholder="List of regex to match aginst changed files"
          name="file"
          value={record.file}
          status={record.fileStatus}
          onChange={(e) => handleEventChange(index, record.key, e.target.name, e.target.value)}
        ></Input>
      ),
    },
    {
      title: "Template",
      dataIndex: "template",
      key: "template",
      width: "12%",
      render: (_: string, record: any, index: number) => (
        <Select
          placeholder="Select a template"
          value={record.template}
          status={record.templateStatus}
          onChange={(e) => handleEventChange(index, record.key, "template", e)}
        >
          {orgTemplates.map(function (template) {
            return <Select.Option key={template?.id}>{template?.attributes?.name}</Select.Option>;
          })}
        </Select>
      ),
    },
    {
      title: "Action",
      key: "action",
      width: "8%",
      render: (_: string, record: any) => (
        <Space size="middle">
          <Popconfirm
            onConfirm={() => {
              onDelete(record);
            }}
            style={{ width: "20px" }}
            title={
              <p>
                This will permanently delete this trigger from the webhook
                <br />
                Are you sure?
              </p>
            }
            okText="Yes"
            cancelText="No"
            disabled={!manageWorkspace}
          >
            <a>Delete</a>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <h1>Webhook</h1>
      <Typography.Text type="secondary" style={{ display: "block", marginBottom: 24 }}>
        Webhooks allow you to trigger a workspace run when a specific event occurs in the repository. This only works
        with VCS flow workspace.
      </Typography.Text>
      <h2>VCS Webhook Configuration</h2>
      <Spin spinning={waiting}>
        <Form onFinish={onFinish}>
          <Form.Item
            label="Enable VCS Webhook?"
            hidden={vcsProvider === undefined}
            tooltip={{
              title: "Whether to enable webhook on the VCS provider",
              icon: <InfoCircleOutlined />,
            }}
          >
            <Switch onChange={handleWebhookClick} checked={webhookEnabled} disabled={!manageWorkspace} />
          </Form.Item>
          <Row hidden={!webhookEnabled}>
            <Col span={12}>
              <Form.Item label="ID" hidden={!webhookEnabled}>
                {webhookId}
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item hidden={!webhookEnabled} label={renderVCSLogo(vcsProvider!)}>
                {remoteHookId}
              </Form.Item>
            </Col>
          </Row>
          <Row hidden={!webhookEnabled}>
            <Col span={24}>
              <Table tableLayout="auto" columns={columns} dataSource={webhookEvents} />
            </Col>
          </Row>
          <Form.Item>
            <Flex justify="flex-start" align="flex-start">
              <Button type="primary" htmlType="submit" disabled={!manageWorkspace}>
                Save webhooks
              </Button>
            </Flex>
          </Form.Item>
        </Form>
      </Spin>
    </div>
  );
};
