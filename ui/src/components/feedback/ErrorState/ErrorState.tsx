import { DisconnectOutlined, FileSearchOutlined, LockOutlined, ReloadOutlined } from "@ant-design/icons";
import { Button, Result, Space } from "antd";
import { LinkButton } from "@/components/navigation/LinkButton";

export type ErrorStateProps = {
  title?: React.ReactNode;
  message?: React.ReactNode;
  status?: number | string;
  onRetry?: () => void;
  showHomeLink?: boolean;
};

const resultIcon = (status?: number | string): React.ReactNode | undefined => {
  switch (String(status)) {
    case "404":
      return <FileSearchOutlined style={{ color: "var(--tk-text-tertiary)" }} />;
    case "403":
      return <LockOutlined style={{ color: "var(--tk-text-tertiary)" }} />;
    case "502":
    case "503":
    case "504":
      return <DisconnectOutlined style={{ color: "var(--ant-color-error)" }} />;
    default:
      return undefined;
  }
};

const defaultTitle = (status?: number | string): string => {
  switch (String(status)) {
    case "404":
      return "Page not found";
    case "403":
      return "Access denied";
    case "502":
    case "503":
    case "504":
      return "Terrakube is unreachable";
    default:
      return "Something went wrong";
  }
};

export default function ErrorState({ title, message, status, onRetry, showHomeLink = true }: ErrorStateProps) {
  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        minHeight: "max(60vh, 100%)",
        width: "100%",
      }}
    >
      <Result
        status="error"
        icon={resultIcon(status)}
        title={title ?? defaultTitle(status)}
        subTitle={message}
        extra={
          <Space>
            {onRetry && (
              <Button type="primary" icon={<ReloadOutlined />} onClick={onRetry}>
                Try again
              </Button>
            )}
            {showHomeLink && <LinkButton to="/">Back to home</LinkButton>}
          </Space>
        }
      />
    </div>
  );
}
