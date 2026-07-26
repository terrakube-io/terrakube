import { Segmented } from "antd";
import { ListViewMode, setStoredListViewMode } from "./listViewPreference";

type Props = {
  value: ListViewMode;
  onChange: (mode: ListViewMode) => void;
};

export default function ListViewToggle({ value, onChange }: Props) {
  return (
    <Segmented
      value={value}
      onChange={(val) => {
        const mode = val as ListViewMode;
        setStoredListViewMode(mode);
        onChange(mode);
      }}
      options={[
        { label: "Cards", value: "cards" },
        { label: "Compact", value: "compact" },
      ]}
    />
  );
}
