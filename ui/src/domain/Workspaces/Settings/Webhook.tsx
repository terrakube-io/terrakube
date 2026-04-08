import { InfoCircleOutlined } from "@ant-design/icons";
import {
  Button,
  Col,
  Flex,
  Form,
  Grid,
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
import { Template, VcsType, WebhookEvent, WebhookEventPathType, Workspace } from "../../types";
import { atomicHeader, renderVCSLogo } from "../Workspaces";

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

const createEmptyWebhookEvent = (key: number) => {
  return {
    key,
    id: uuid(),
    prWorkflowEnabled: false,
    pathType: WebhookEventPathType.PATTERN,
  };
};

const isRegexPathType = (pathType: WebhookEventPathType | undefined) => {
  return pathType === WebhookEventPathType.REGEX;
};

type Props = {
  workspace: Workspace;
  manageWorkspace: boolean;
  orgTemplates: Template[];
  vcsProvider?: VcsType;
};

export const WorkspaceWebhook = ({ workspace, vcsProvider, orgTemplates, manageWorkspace }: Props) => {
  const screens = Grid.useBreakpoint();
  const isMobile = !screens.md;
  const [waiting, setWaiting] = useState(true);
  const [webhookEnabled, setWebhookEnabled] = useState(false);
  const [recordIndex, setRecordIndex] = useState(1);
  const organizationId = workspace.relationships.organization.data.id;
  const [webhookEvents, setWebhookEvents] = useState<any[]>([createEmptyWebhookEvent(1) as any]);
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
              pathType: event.attributes.pathType || WebhookEventPathType.REGEX,
              template: event.attributes.templateId,
              prWorkflowEnabled: event.attributes.prWorkflowEnabled || false,
              created: true,
            };
          });
        setRecordIndex(events.length + 1);
        setWebhookEvents(events.concat(createEmptyWebhookEvent(i)));
      })
      .catch(() => {
        message.error("Failed to load webhook");
      });
  };
  const handleEventChange = (index: number, _: any, name: string, value: string | boolean) => {
    webhookEvents[index][name] = value;
    if (name === "pathType" && !isRegexPathType(value as WebhookEventPathType)) {
      webhookEvents[index].fileStatus = "success";
    }

    if (index == webhookEvents.length - 1) {
      const index = recordIndex + 1;
      setWebhookEvents([...webhookEvents, createEmptyWebhookEvent(index)]);
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
      newWebhookEvents.push(createEmptyWebhookEvent(1));
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

        if (isRegexPathType(event.pathType) && !isValidRegexList(event.file)) {
          event.fileStatus = "error";
          regexError = true;
        }
      });
    if (regexError) {
      setWaiting(false);
      message.error("Branch and release matching use regex. File must be valid regex when Path Type is Regex.");
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
                .map(function (event) {
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
          .map(function (event) {
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
                  pathType: event.pathType || WebhookEventPathType.PATTERN,
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
      width: isMobile ? 80 : 90,
      render: (_: string, record: any, index: number) => (
        <Input
          placeholder="1"
          name="priority"
          value={record.priority}
          status={record.status}
          style={{ width: "100%" }}
          onChange={(e) => handleEventChange(index, record.key, e.target.name, e.target.value)}
        ></Input>
      ),
    },
    {
      title: "Event",
      dataIndex: "event",
      key: "event",
      width: isMobile ? 160 : 180,
      render: (_: string, record: any, index: number) => (
        <Select
          placeholder="Select an event"
          value={record.event}
          status={record.eventStatus}
          style={{ width: "100%" }}
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
      width: isMobile ? 110 : 120,
      render: (_: string, record: any, index: number) =>
        record.event === "pull_request" ? (
          <Switch
            size="small"
            checked={record.prWorkflowEnabled || false}
            onChange={(checked) => handleEventChange(index, record.key, "prWorkflowEnabled", checked)}
          />
        ) : null,
    },
    {
      title: "Branch/release",
      dataIndex: "branch",
      key: "branch",
      width: isMobile ? 220 : 240,
      render: (_: string, record: any, index: number) => (
        <Input
          placeholder="Regex list to match branch or release names"
          name="branch"
          status={record.branchStatus}
          value={record.branch}
          style={{ width: "100%" }}
          onChange={(e) => handleEventChange(index, record.key, e.target.name, e.target.value)}
        ></Input>
      ),
    },
    {
      title: "Path Type",
      dataIndex: "pathType",
      key: "pathType",
      width: isMobile ? 140 : 160,
      render: (_: string, record: any, index: number) => (
        <Select
          placeholder="Select a path type"
          value={record.pathType || WebhookEventPathType.PATTERN}
          style={{ width: "100%" }}
          onChange={(value) => handleEventChange(index, record.key, "pathType", value)}
        >
          <Select.Option value={WebhookEventPathType.PATTERN}>Pattern</Select.Option>
          <Select.Option value={WebhookEventPathType.REGEX}>Regex</Select.Option>
        </Select>
      ),
    },
    {
      title: "File",
      dataIndex: "file",
      key: "file",
      width: isMobile ? 320 : 420,
      render: (_: string, record: any, index: number) => (
        <Input
          placeholder={
            isRegexPathType(record.pathType)
              ? "List of regex to match against changed files"
              : "List of wildcard patterns like terraform/* or modules/**"
          }
          name="file"
          value={record.file}
          status={record.fileStatus}
          style={{ width: "100%", minWidth: isMobile ? 320 : 420 }}
          onChange={(e) => handleEventChange(index, record.key, e.target.name, e.target.value)}
        ></Input>
      ),
    },
    {
      title: "Template",
      dataIndex: "template",
      key: "template",
      width: isMobile ? 180 : 220,
      render: (_: string, record: any, index: number) => (
        <Select
          placeholder="Select a template"
          value={record.template}
          status={record.templateStatus}
          style={{ width: "100%" }}
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
      width: isMobile ? 90 : 110,
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
            <Button type="link" size={isMobile ? "middle" : "small"}>
              Delete
            </Button>
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
      <Typography.Text type="secondary" style={{ display: "block", marginBottom: 24 }}>
        Use <b>Pattern</b> for simple wildcards like <code>terraform/*</code> or <code>modules/**</code>. Use{" "}
        <b>Regex</b> when you need full regular expression matching. Branch and release matching always use regex.
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
            <Col xs={24} md={12}>
              <Form.Item label="ID" hidden={!webhookEnabled}>
                {webhookId}
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item hidden={!webhookEnabled} label={renderVCSLogo(vcsProvider!)}>
                {remoteHookId}
              </Form.Item>
            </Col>
          </Row>
          <Row hidden={!webhookEnabled}>
            <Col span={24}>
              <Table
                tableLayout="fixed"
                columns={columns}
                dataSource={webhookEvents}
                pagination={false}
                size={isMobile ? "small" : "middle"}
                scroll={{ x: "max-content" }}
                style={{ width: "100%" }}
              />
            </Col>
          </Row>
          <Form.Item>
            <Flex justify="flex-start" align="flex-start">
              <Button type="primary" htmlType="submit" disabled={!manageWorkspace} block={isMobile}>
                Save webhooks
              </Button>
            </Flex>
          </Form.Item>
        </Form>
      </Spin>
    </div>
  );
};
