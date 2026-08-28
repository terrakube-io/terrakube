import { Alert } from "antd";

type Props = {
  description?: React.ReactNode;
};

export default function AccessDeniedAlert({ description }: Props) {
  return <Alert message="Access Denied" description={description} type="error" showIcon />;
}
