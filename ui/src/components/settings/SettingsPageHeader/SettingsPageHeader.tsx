import { ExportOutlined } from "@ant-design/icons";
import { Divider, Flex, Typography } from "antd";
import "./SettingsPageHeader.css";

type Props = {
  title: React.ReactNode;
  description?: React.ReactNode;
  actions?: React.ReactNode;
  action?: React.ReactNode;
  docUrl?: string;
  divider?: boolean;
};

export default function SettingsPageHeader({ title, description, actions, action, docUrl, divider = true }: Props) {
  const headerActions = actions ?? action;
  return (
    <>
      <Flex justify="space-between" align="center" wrap gap="middle">
        <div>
          <Typography.Title level={3} className="settings-page-header-title">
            {title}
          </Typography.Title>
          {(description || docUrl) && (
            <Typography.Text type="secondary" className="settings-page-header-description">
              {description}
              {description && docUrl && " "}
              {/* The docs link ends the intro instead of a separate icon button. */}
              {docUrl && (
                <Typography.Link
                  href={docUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="settings-page-header-doc-link"
                >
                  Documentation{" "}
                  <ExportOutlined aria-label="opens in a new tab" className="settings-page-header-doc-icon" />
                </Typography.Link>
              )}
            </Typography.Text>
          )}
        </div>
        {headerActions && (
          <Flex align="center" gap="small">
            {headerActions}
          </Flex>
        )}
      </Flex>
      {divider ? <Divider className="settings-page-header-divider" /> : <div className="settings-page-header-spacer" />}
    </>
  );
}
