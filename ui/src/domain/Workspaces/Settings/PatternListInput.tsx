import { Collapse, Input, Select, Tag, Typography } from "antd";
import { useState } from "react";
import { isValidRegex, matchesWhole } from "./patterns";

type Props = {
  id?: string;
  value?: string[];
  onChange?: (value: string[]) => void;
  isValid?: (pattern: string) => boolean;
  placeholder?: string;
  disabled?: boolean;
};

export function PatternListInput({ id, value = [], onChange, isValid = () => true, placeholder, disabled }: Props) {
  return (
    <Select
      id={id}
      mode="tags"
      className="webhook-mono webhook-patterns"
      value={value}
      open={false}
      suffixIcon={null}
      tokenSeparators={[","]}
      placeholder={placeholder}
      disabled={disabled}
      onChange={(next: string[]) => {
        const cleaned = [...new Set(next.map((item) => item.trim()).filter(Boolean))];
        onChange?.(cleaned);
      }}
      tagRender={({ label, value: tag, closable, onClose }) => (
        <Tag
          color={isValid(String(tag)) ? undefined : "error"}
          closable={closable && !disabled}
          onClose={onClose}
          onMouseDown={(event) => event.preventDefault()}
          className="webhook-pattern-tag"
        >
          {label}
        </Tag>
      )}
    />
  );
}

type SampleProps = {
  label: string;
  patterns: string[];
};

function SampleCheck({ label, patterns }: SampleProps) {
  const [sample, setSample] = useState("");
  const usable = patterns.filter((pattern) => pattern && isValidRegex(pattern));
  const matched = usable.find((pattern) => matchesWhole(pattern, sample));

  return (
    <div className="webhook-sample">
      <Typography.Text strong>{label}</Typography.Text>
      <Input
        aria-label={label}
        className="webhook-mono"
        value={sample}
        onChange={(event) => setSample(event.target.value)}
        allowClear
      />
      <Typography.Text type="secondary" className="webhook-sample-result" aria-live="polite">
        {sample === "" || usable.length === 0 ? "\u00a0" : matched ? `Matches ${matched}` : "No pattern matches."}
      </Typography.Text>
    </div>
  );
}

type PlaygroundProps = {
  branchLabel: string;
  branches?: string[];
  // Only regexes: wildcard paths are matched by the API with its own rules, so a browser-side answer could be wrong.
  paths?: string[];
};

// One collapsed row, so the editor stays about the patterns and not the checking of them.
export function PatternPlayground({ branchLabel, branches, paths }: PlaygroundProps) {
  if (!branches && !paths) {
    return null;
  }
  return (
    <Collapse
      ghost
      size="small"
      className="webhook-playground"
      items={[
        {
          key: "test",
          label: "Test patterns",
          children: (
            <>
              <Typography.Paragraph type="secondary">
                Type a sample to see whether a pattern matches it. A pattern must match the whole value.
              </Typography.Paragraph>
              <div className="webhook-samples">
                {branches && <SampleCheck label={branchLabel} patterns={branches} />}
                {paths && <SampleCheck label="Try a file path" patterns={paths} />}
              </div>
            </>
          ),
        },
      ]}
    />
  );
}
