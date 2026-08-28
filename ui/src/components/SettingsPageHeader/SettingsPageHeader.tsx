import { Typography } from "antd";

type Props = {
  title: React.ReactNode;
  description?: React.ReactNode;
};

export default function SettingsPageHeader({ title, description }: Props) {
  return (
    <>
      <Typography.Title level={1} style={{ margin: 0 }}>
        {title}
      </Typography.Title>
      {description && (
        <Typography.Text type="secondary" style={{ display: "block", margin: "8px 0 16px" }}>
          {description}
        </Typography.Text>
      )}
    </>
  );
}
