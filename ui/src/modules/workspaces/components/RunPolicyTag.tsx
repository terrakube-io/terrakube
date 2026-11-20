import { Tag } from "antd";
import { SafetyOutlined } from "@ant-design/icons";
import { PolicyResult } from "../runPolicyService";

const COPY: Record<PolicyResult, { text: string; color: string }> = {
  PASSED: { text: "Policy passed", color: "success" },
  WARNING: { text: "Policy warning", color: "warning" },
  FAILED: { text: "Policy failed", color: "error" },
  OVERRIDDEN: { text: "Policy overridden", color: "warning" },
  EXEMPTED: { text: "Policy exempted", color: "default" },
};

/** What the policy checks said about one run, as a tag next to its status. */
export default function RunPolicyTag({ result }: { result: PolicyResult }) {
  const { text, color } = COPY[result];
  return (
    <Tag icon={<SafetyOutlined />} color={color}>
      {text}
    </Tag>
  );
}
