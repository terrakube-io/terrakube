import { ReloadOutlined } from "@ant-design/icons";
import { Button, Result, Space } from "antd";
import { Link } from "react-router-dom";

export type ErrorStateProps = {
  title?: React.ReactNode;
  message?: React.ReactNode;
  status?: number | string;
  onRetry?: () => void;
  showHomeLink?: boolean;
};

const resultStatus = (status?: number | string): "404" | "403" | "500" | "error" => {
  switch (String(status)) {
    case "404":
      return "404";
    case "403":
      return "403";
    case "500":
    case "502":
    case "503":
    case "504":
      return "500";
    default:
      return "error";
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
        status={resultStatus(status)}
        title={title ?? defaultTitle(status)}
        subTitle={message}
        extra={
          <Space>
            {onRetry && (
              <Button type="primary" icon={<ReloadOutlined />} onClick={onRetry}>
                Try again
              </Button>
            )}
            {showHomeLink && (
              <Link to="/">
                <Button>Back to home</Button>
              </Link>
            )}
          </Space>
        }
      />
    </div>
  );
}
