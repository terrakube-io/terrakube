import { Card, Flex, Typography } from "antd";
import clsx from "classnames";
import "./SettingsSection.css";

type Props = {
  title?: React.ReactNode;
  description?: React.ReactNode;
  children: React.ReactNode;
  danger?: boolean;
  maxWidth?: number | string;
  extra?: React.ReactNode;
};

export default function SettingsSection({ title, description, children, danger, maxWidth = 720, extra }: Props) {
  if (danger) {
    return (
      <Card
        className={clsx("settings-section", "settings-section-danger")}
        style={{ maxWidth: typeof maxWidth === "number" ? Math.max(maxWidth, 960) : maxWidth }}
        title={
          title ? (
            <Typography.Title level={4} style={{ margin: 0 }}>
              {title}
            </Typography.Title>
          ) : undefined
        }
        extra={extra}
      >
        <Flex justify="space-between" align="center" gap={24} wrap>
          {description && (
            <Typography.Text type="secondary" className="settings-section-description" style={{ marginBottom: 0 }}>
              {description}
            </Typography.Text>
          )}
          <div style={{ flexShrink: 0 }}>{children}</div>
        </Flex>
      </Card>
    );
  }

  return (
    <section className="settings-section">
      {(title || extra) && (
        <div className="settings-section-header">
          {title && (
            <Typography.Title level={4} style={{ margin: 0 }}>
              {title}
            </Typography.Title>
          )}
          {extra}
        </div>
      )}
      {description && (
        <Typography.Text type="secondary" className="settings-section-description">
          {description}
        </Typography.Text>
      )}
      <div className="settings-section-content" style={{ maxWidth }}>
        {children}
      </div>
    </section>
  );
}
