import { CheckCircleFilled } from "@ant-design/icons";
import { NotificationChannelType } from "../types";
import { CHANNEL_META, CHANNEL_ORDER } from "./channelMeta";
import "./Notifications.css";

type Props = {
  value?: NotificationChannelType;
  onChange?: (value: NotificationChannelType) => void;
};

// A Form.Item-compatible control: antd clones its single child with value/onChange
// props, same contract as a native input, so this drops straight into <Form.Item
// name="channelType"> in place of a <Select> without any extra wiring.
export const ChannelPicker = ({ value, onChange }: Props) => {
  return (
    <div role="radiogroup" aria-label="Channel" className="channel-picker">
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
            className="channel-picker-option"
          >
            {selected && <CheckCircleFilled className="channel-picker-check" />}
            <Icon className="channel-picker-icon" />
            <div className="channel-picker-label">{meta.label}</div>
            <div className="channel-picker-description">{meta.description}</div>
          </div>
        );
      })}
    </div>
  );
};
