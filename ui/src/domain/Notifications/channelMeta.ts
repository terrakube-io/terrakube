import { ApiOutlined, SlackOutlined, TeamOutlined } from "@ant-design/icons";
import { NotificationChannelType } from "../types";

type ChannelMeta = {
  value: NotificationChannelType;
  label: string;
  description: string;
  icon: typeof SlackOutlined;
  color: string;
  urlPlaceholder: string;
  urlHelp: string;
  docsLabel: string;
  docsUrl: string;
};

export const CHANNEL_META: Record<NotificationChannelType, ChannelMeta> = {
  SLACK: {
    value: "SLACK",
    label: "Slack",
    description: "Post to a channel via an Incoming Webhook",
    icon: SlackOutlined,
    color: "#611f69",
    urlPlaceholder: "https://hooks.slack.com/services/...",
    urlHelp: "Paste the Incoming Webhook URL for the Slack channel you want to notify.",
    docsLabel: "How to create a Slack Incoming Webhook",
    docsUrl: "https://api.slack.com/messaging/webhooks",
  },
  TEAMS: {
    value: "TEAMS",
    label: "Microsoft Teams",
    description: "Post to a channel via an Incoming Webhook",
    icon: TeamOutlined,
    color: "#6264a7",
    urlPlaceholder: "https://<org>.webhook.office.com/webhookb2/...",
    urlHelp: "Paste the Incoming Webhook URL for the Teams channel you want to notify.",
    docsLabel: "How to create a Teams Incoming Webhook",
    docsUrl:
      "https://learn.microsoft.com/en-us/microsoftteams/platform/webhooks-and-connectors/how-to/add-incoming-webhook",
  },
  WEBHOOK: {
    value: "WEBHOOK",
    label: "Generic Webhook",
    description: "POST a JSON payload to any HTTPS endpoint",
    icon: ApiOutlined,
    color: "#08979c",
    urlPlaceholder: "https://example.com/hooks/terrakube",
    urlHelp: "Any HTTPS endpoint that accepts a JSON POST. Add a signing secret to verify authenticity.",
    docsLabel: "About the webhook payload and signature",
    docsUrl: "",
  },
};

export const CHANNEL_ORDER: NotificationChannelType[] = ["SLACK", "TEAMS", "WEBHOOK"];
