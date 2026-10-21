import { Alert, Tag } from "antd";
import { LoadingOutlined } from "@ant-design/icons";
import { OutputCompleteness, describeOutputCompleteness } from "./outputDelivery";

type Props = {
  completeness: OutputCompleteness;
};

/**
 * Says how far a step's structured result can be trusted. A confirmed final result is a small tag;
 * anything that could still change or be incomplete is an alert, so the two are not mistaken for
 * each other when rows are present either way.
 */
export const OutputCompletenessNotice = ({ completeness }: Props) => {
  const notice = describeOutputCompleteness(completeness);
  if (notice == null) {
    return null;
  }

  if (notice.tone === "success") {
    return (
      <div className="run-step-notice" data-testid="output-completeness" data-state={completeness}>
        <Tag color="green" title={notice.description}>
          {notice.label}
        </Tag>
      </div>
    );
  }

  return (
    <Alert
      type={notice.tone === "info" ? "info" : notice.tone === "error" ? "error" : "warning"}
      showIcon
      icon={notice.tone === "info" ? <LoadingOutlined /> : undefined}
      className="run-step-notice"
      data-testid="output-completeness"
      data-state={completeness}
      title={notice.label}
      description={notice.description}
    />
  );
};
