import { QuestionCircleOutlined } from "@ant-design/icons";
import { Button, Divider, Flex, Tooltip, Typography } from "antd";

type Props = {
  title: React.ReactNode;
  description?: React.ReactNode;
  actions?: React.ReactNode;
  docUrl?: string;
  divider?: boolean;
};

export default function SettingsPageHeader({ title, description, actions, docUrl, divider = true }: Props) {
  return (
    <>
      <Flex justify="space-between" align="center" wrap gap="middle">
        <div>
          <Typography.Title level={3} style={{ margin: 0 }}>
            {title}
          </Typography.Title>
          {description && (
            <Typography.Text type="secondary" style={{ display: "block", margin: "8px 0 0", maxWidth: 720 }}>
              {description}
            </Typography.Text>
          )}
        </div>
        {(actions || docUrl) && (
          <Flex align="center" gap="small">
            {actions}
            {docUrl && (
              <Tooltip title="Open documentation">
                <Button
                  type="default"
                  icon={<QuestionCircleOutlined />}
                  href={docUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  aria-label="Open documentation"
                />
              </Tooltip>
            )}
          </Flex>
        )}
      </Flex>
      {divider ? <Divider style={{ margin: "16px 0 24px" }} /> : <div style={{ marginBottom: 24 }} />}
    </>
  );
}
