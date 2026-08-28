import { Card, Space, Typography } from "antd";

type Props = {
  icon: React.ReactNode;
  title: React.ReactNode;
  description: React.ReactNode;
  footerLeft?: React.ReactNode;
  footerRight?: React.ReactNode;
};

export default function RegistryCard({ icon, title, description, footerLeft, footerRight }: Props) {
  return (
    <Card hoverable className="module-card" style={{ width: "100%" }} styles={{ body: { padding: 0 } }}>
      <div className="module-card-body">
        <div style={{ display: "flex", gap: 14, alignItems: "flex-start" }}>
          <div
            style={{
              flexShrink: 0,
              width: 36,
              height: 36,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
            }}
          >
            {icon}
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <Typography.Text strong className="module-card-name">
              {title}
            </Typography.Text>
            <div className="module-card-desc">{description}</div>
          </div>
        </div>
      </div>
      <div
        style={{
          borderTop: "1px solid var(--ant-color-border-secondary)",
          padding: "10px 24px",
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
        }}
      >
        <Space size={16}>{footerLeft}</Space>
        <Space size={6}>{footerRight}</Space>
      </div>
    </Card>
  );
}
