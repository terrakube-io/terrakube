import { Flex, Spin, Typography } from "antd";

type Props = {
  loading: boolean;
  description?: string;
  overlay?: boolean;
  children?: React.ReactNode;
};

export default function Loading({ loading, description, overlay = false, children }: Props) {
  if (overlay) {
    return (
      <Spin spinning={loading} description={description}>
        {children}
      </Spin>
    );
  }

  if (loading) {
    return (
      <Flex vertical align="center" justify="center" gap="middle" style={{ minHeight: "40vh", width: "100%" }}>
        <Spin size="large" />
        {description && <Typography.Text type="secondary">{description}</Typography.Text>}
      </Flex>
    );
  }

  return <>{children}</>;
}
