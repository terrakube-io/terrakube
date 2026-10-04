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

// The width is a prop, so it reaches the stylesheet as a custom property. It is set on every section, never
// left to the CSS default: custom properties inherit, so a nested section would otherwise take its parent's width.
const maxWidthStyle = (maxWidth: number | string, min = 0) =>
  ({
    "--settings-section-max-width": typeof maxWidth === "number" ? `${Math.max(maxWidth, min)}px` : maxWidth,
  }) as React.CSSProperties;

export default function SettingsSection({ title, description, children, danger, maxWidth = 720, extra }: Props) {
  if (danger) {
    return (
      <Card
        className={clsx("settings-section", "settings-section-danger")}
        style={maxWidthStyle(maxWidth, 960)}
        title={
          title ? (
            <Typography.Title level={4} className="settings-section-title">
              {title}
            </Typography.Title>
          ) : undefined
        }
        extra={extra}
      >
        <Flex justify="space-between" align="center" gap={24} wrap>
          {description && (
            <Typography.Text type="secondary" className="settings-section-description">
              {description}
            </Typography.Text>
          )}
          <div className="settings-section-danger-action">{children}</div>
        </Flex>
      </Card>
    );
  }

  return (
    <section className="settings-section" style={maxWidthStyle(maxWidth)}>
      {(title || extra) && (
        <div className="settings-section-header">
          {title && (
            <Typography.Title level={4} className="settings-section-title">
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
      <div className="settings-section-content">{children}</div>
    </section>
  );
}
