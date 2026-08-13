import { CheckCircleFilled } from "@ant-design/icons";
import { theme } from "antd";
import { NotificationChannelType } from "../types";
import { CHANNEL_META, CHANNEL_ORDER } from "./channelMeta";

type Props = {
  value?: NotificationChannelType;
  onChange?: (value: NotificationChannelType) => void;
};

// A Form.Item-compatible control: antd clones its single child with value/onChange
// props, same contract as a native input, so this drops straight into <Form.Item
// name="channelType"> in place of a <Select> without any extra wiring.
export const ChannelPicker = ({ value, onChange }: Props) => {
  const { token } = theme.useToken();

  return (
    <div style={{ display: "flex", gap: 12, flexWrap: "wrap" }}>
      {CHANNEL_ORDER.map((channelType) => {
        const meta = CHANNEL_META[channelType];
        const Icon = meta.icon;
        const selected = value === channelType;
        return (
          <div
            key={channelType}
            role="radio"
            aria-checked={selected}
            tabIndex={0}
            onClick={() => onChange?.(channelType)}
            onKeyDown={(e) => {
              if (e.key === "Enter" || e.key === " ") {
                e.preventDefault();
                onChange?.(channelType);
              }
            }}
            style={{
              position: "relative",
              width: 168,
              cursor: "pointer",
              padding: "14px 12px",
              borderRadius: token.borderRadius,
              border: `2px solid ${selected ? meta.color : token.colorBorderSecondary}`,
              background: selected ? `${meta.color}0d` : token.colorBgContainer,
              transition: "border-color 0.15s, background 0.15s",
            }}
          >
            {selected && (
              <CheckCircleFilled
                style={{ position: "absolute", top: 8, right: 8, color: meta.color, fontSize: 16 }}
              />
            )}
            <Icon style={{ fontSize: 26, color: meta.color, display: "block", marginBottom: 8 }} />
            <div style={{ fontWeight: 600 }}>{meta.label}</div>
            <div style={{ fontSize: 12, color: token.colorTextSecondary, marginTop: 2 }}>{meta.description}</div>
          </div>
        );
      })}
    </div>
  );
};
