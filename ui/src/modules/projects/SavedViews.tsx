import { CloseOutlined, StarOutlined } from "@ant-design/icons";
import { Button, Dropdown, Flex, Input, type InputRef, Popover, Typography } from "antd";
import { useRef, useState } from "react";
import { SavedView, addView, loadViews, removeView, validateViewName } from "./viewStore";

type Props = {
  orgid: string;
  /** What the list shows now, as a query string. Empty when nothing is filtered or sorted away from the default. */
  currentQuery: string;
  onApply: (query: string) => void;
};

/** Named filters kept in this browser, for the lists a person opens every day. */
export default function SavedViews({ orgid, currentQuery, onApply }: Props) {
  const [views, setViews] = useState<SavedView[]>(() => loadViews(orgid));
  const [saveOpen, setSaveOpen] = useState(false);
  const [name, setName] = useState("");
  const [touched, setTouched] = useState(false);
  const nameRef = useRef<InputRef>(null);

  const problem = validateViewName(name, views);
  const save = () => {
    setTouched(true);
    if (problem) return;
    setViews(addView(orgid, name, currentQuery));
    setName("");
    setTouched(false);
    setSaveOpen(false);
  };

  const items = [
    ...views.map((view) => ({
      key: view.name,
      label: (
        <Flex justify="space-between" align="center" gap={12}>
          <span>{view.name}</span>
          <Button
            type="text"
            size="small"
            aria-label={`Delete view ${view.name}`}
            icon={<CloseOutlined />}
            onClick={(event) => {
              event.stopPropagation();
              setViews(removeView(orgid, view.name));
            }}
          />
        </Flex>
      ),
    })),
    ...(views.length === 0 ? [{ key: "none", disabled: true, label: "No saved views yet" }] : []),
  ];

  return (
    <Flex gap={4} align="center">
      <Dropdown
        trigger={["click"]}
        menu={{
          items,
          onClick: ({ key }) => {
            const view = views.find((item) => item.name === key);
            if (view) onApply(view.query);
          },
        }}
      >
        <Button icon={<StarOutlined />} aria-label="Saved views">
          Views{views.length > 0 ? ` (${views.length})` : ""}
        </Button>
      </Dropdown>
      <Popover
        trigger="click"
        open={saveOpen}
        afterOpenChange={(open) => open && nameRef.current?.focus()}
        onOpenChange={(open) => {
          setSaveOpen(open);
          if (!open) setTouched(false);
        }}
        title="Save this view"
        content={
          <Flex vertical gap={8} style={{ width: 240 }}>
            <Input
              ref={nameRef}
              aria-label="View name"
              placeholder="For example Failing runs"
              value={name}
              maxLength={60}
              onChange={(event) => setName(event.target.value)}
              onPressEnter={save}
              status={touched && problem ? "error" : undefined}
            />
            {touched && problem && <Typography.Text type="danger">{problem}</Typography.Text>}
            <Button type="primary" onClick={save}>
              Save view
            </Button>
          </Flex>
        }
      >
        <Button
          disabled={currentQuery === ""}
          title={currentQuery === "" ? "Filter or sort the list first" : undefined}
        >
          Save view
        </Button>
      </Popover>
    </Flex>
  );
}
