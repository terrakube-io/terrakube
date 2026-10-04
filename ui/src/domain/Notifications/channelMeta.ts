import { ApiOutlined, SlackOutlined, TeamOutlined } from "@ant-design/icons";
import { NotificationChannelType } from "../types";

type ChannelMeta = {
  value: NotificationChannelType;
  label: string;
  description: string;
  icon: typeof SlackOutlined;
  urlPlaceholder: string;
  urlHelp: string;
  docsLabel: string;
  docsUrl: string;
};

export const CHANNEL_META: Record<NotificationChannelType, ChannelMeta> = {
  SLACK: {
    value: "SLACK",
    label: "Slack",
    description: "Post to a channel via an incoming webhook",
    icon: SlackOutlined,
    urlPlaceholder: "https://hooks.slack.com/services/...",
    urlHelp: "Paste the incoming webhook URL for the Slack channel you want to notify.",
    docsLabel: "How to create a Slack incoming webhook",
    docsUrl: "https://api.slack.com/messaging/webhooks",
  },
  TEAMS: {
    value: "TEAMS",
    label: "Microsoft Teams",
    description: "Post to a channel via an incoming webhook",
    icon: TeamOutlined,
    urlPlaceholder: "https://<org>.webhook.office.com/webhookb2/...",
    urlHelp: "Paste the incoming webhook URL for the Teams channel you want to notify.",
    docsLabel: "How to create a Teams incoming webhook",
    docsUrl:
      "https://learn.microsoft.com/en-us/microsoftteams/platform/webhooks-and-connectors/how-to/add-incoming-webhook",
  },
  WEBHOOK: {
    value: "WEBHOOK",
    label: "Webhook",
    description: "POST a JSON payload to any HTTPS endpoint",
    icon: ApiOutlined,
    urlPlaceholder: "https://example.com/hooks/terrakube",
    urlHelp: "Any HTTPS endpoint that accepts a JSON POST. Add a signing secret to verify authenticity.",
    docsLabel: "About the webhook payload and signature",
    docsUrl: "",
  },
};

export const CHANNEL_ORDER: NotificationChannelType[] = ["SLACK", "TEAMS", "WEBHOOK"];
