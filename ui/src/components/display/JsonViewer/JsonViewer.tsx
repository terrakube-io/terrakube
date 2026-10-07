import {
  CaretRightOutlined,
  CopyOutlined,
  FullscreenExitOutlined,
  FullscreenOutlined,
  SearchOutlined,
} from "@ant-design/icons";
import { Button, Empty, Input, Modal, Space, message } from "antd";
import { memo, useDeferredValue, useEffect, useMemo, useRef, useState } from "react";
import { copyValue } from "@/components/settings/IdField/IdField";
import "./JsonViewer.css";

type Container = Record<string, unknown> | unknown[];
type Key = string | number;

const isContainer = (v: unknown): v is Container => v !== null && typeof v === "object";
const entriesOf = (v: Container): [Key, unknown][] => (Array.isArray(v) ? v.map((c, i) => [i, c]) : Object.entries(v));
const childPath = (path: string, key: Key) =>
  typeof key === "number" ? `${path}[${key}]` : path ? `${path}.${key}` : key;

export function JsonScalar({ value }: { value: unknown }) {
  if (typeof value === "string") return <span className="jv-string">{JSON.stringify(value)}</span>;
  if (typeof value === "number") return <span className="jv-number">{value}</span>;
  if (typeof value === "boolean") return <span className="jv-boolean">{String(value)}</span>;
  return <span className="jv-null">null</span>;
}

/** `"key": ` for object members, `0: ` for array items, nothing for the root. */
export function JsonKey({ name }: { name?: Key }) {
  if (name === undefined) return null;
  return typeof name === "number" ? (
    <span className="jv-index">{name}: </span>
  ) : (
    <>
      <span className="jv-key">{JSON.stringify(name)}</span>
      <span className="jv-punct">: </span>
    </>
  );
}

export const Caret = () => <CaretRightOutlined className="jv-caret" aria-hidden />;

const countLabel = (value: Container) => {
  const n = Array.isArray(value) ? value.length : Object.keys(value).length;
  return Array.isArray(value) ? `${n} ${n === 1 ? "item" : "items"}` : `${n} ${n === 1 ? "key" : "keys"}`;
};

type TreeOptions = { openDepth: number; visible: Set<string> | null };

type NodeProps = { name?: Key; value: unknown; path: string; depth: number; last: boolean; options: TreeOptions };

// Children render only while their parent is open, so a collapsed tree of any size stays cheap.
const JsonNode = memo(function JsonNode({ name, value, path, depth, last, options }: NodeProps) {
  const [open, setOpen] = useState(depth < options.openDepth);
  const comma = last ? null : <span className="jv-punct">,</span>;

  if (!isContainer(value)) {
    return (
      <div className="jv-row">
        <JsonKey name={name} />
        <JsonScalar value={value} />
        {comma}
      </div>
    );
  }

  const [opener, closer] = Array.isArray(value) ? ["[", "]"] : ["{", "}"];
  const entries = entriesOf(value);
  if (entries.length === 0) {
    return (
      <div className="jv-row">
        <JsonKey name={name} />
        <span className="jv-punct">
          {opener}
          {closer}
        </span>
        {comma}
      </div>
    );
  }

  const { visible } = options;
  const shown = visible ? entries.filter(([key]) => visible.has(childPath(path, key))) : entries;
  return (
    <div>
      <button type="button" className="jv-row jv-toggle" aria-expanded={open} onClick={() => setOpen(!open)}>
        <Caret />
        <JsonKey name={name} />
        {open ? (
          <span className="jv-punct">{opener}</span>
        ) : (
          <>
            <span className="jv-punct">{`${opener}…${closer}`}</span>
            <span className="jv-count"> {countLabel(value)}</span>
            {comma}
          </>
        )}
      </button>
      {open && (
        <>
          {/* ponytail: renders every child of an open node; window long arrays if states with 10k+ items appear */}
          <div className="jv-children">
            {shown.map(([key, child], i) => (
              <JsonNode
                key={key}
                name={key}
                value={child}
                path={childPath(path, key)}
                depth={depth + 1}
                last={i === shown.length - 1}
                options={options}
              />
            ))}
          </div>
          <div className="jv-row">
            <span className="jv-punct">{closer}</span>
            {comma}
          </div>
        </>
      )}
    </div>
  );
});

/** A bare collapsible tree, without the toolbar or surface. */
export function JsonTree({
  value,
  name,
  openDepth = 2,
  visible = null,
}: {
  value: unknown;
  name?: Key;
  openDepth?: number;
  visible?: Set<string> | null;
}) {
  const options = useMemo(() => ({ openDepth, visible }), [openDepth, visible]);
  return <JsonNode name={name} value={value} path="" depth={0} last options={options} />;
}

const PATH = /^(?:[\w$-]+|\[\d+\])(?:\.[\w$-]+|\[\d+\])*$/;

/** Resolves `resources[0].instances` against the value; undefined when the text is not a path that exists. */
export function resolvePath(value: unknown, text: string): { value: unknown } | undefined {
  if (!PATH.test(text)) return undefined;
  let current = value;
  for (const segment of text.match(/[^.[\]]+/g) ?? []) {
    if (!isContainer(current) || !Object.prototype.hasOwnProperty.call(current, segment)) return undefined;
    current = (current as Record<string, unknown>)[segment];
  }
  return { value: current };
}

/** Paths of every node whose key or value contains the text, plus their ancestors and, for key hits, descendants. */
export function matchingPaths(value: unknown, text: string): Set<string> {
  const query = text.toLowerCase();
  const paths = new Set<string>();
  const addAll = (v: unknown, path: string) => {
    paths.add(path);
    if (isContainer(v)) for (const [key, child] of entriesOf(v)) addAll(child, childPath(path, key));
  };
  const walk = (v: unknown, path: string, key?: Key): boolean => {
    if (typeof key === "string" && key.toLowerCase().includes(query)) {
      addAll(v, path);
      return true;
    }
    let hit = false;
    if (isContainer(v)) {
      for (const [k, child] of entriesOf(v)) if (walk(child, childPath(path, k), k)) hit = true;
    } else {
      hit = String(v).toLowerCase().includes(query);
    }
    if (hit) paths.add(path);
    return hit;
  };
  walk(value, "");
  return paths;
}

type Props = {
  /** Parsed JSON to show. */
  value?: unknown;
  /** JSON text; shown as plain text when it does not parse. */
  text?: string;
  /** Accessible name of the scrollable viewer region. */
  label: string;
};

export default function JsonViewer({ value, text, label }: Props) {
  const parsed = useMemo(() => {
    if (text === undefined) return { ok: true, value };
    try {
      return { ok: true, value: JSON.parse(text) as unknown };
    } catch {
      return { ok: false, value: undefined };
    }
  }, [text, value]);

  const [filter, setFilter] = useState("");
  const query = useDeferredValue(filter.trim());
  // null = default depth (two levels, or everything while searching); set by Expand all / Collapse all.
  const [openDepth, setOpenDepth] = useState<number | null>(null);
  const [generation, setGeneration] = useState(0);
  const [fullScreen, setFullScreen] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  // Inline copy feedback for full screen, where antd's toasts render outside the visible element.
  const [copyStatus, setCopyStatus] = useState("");
  const shellRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!copyStatus) return;
    const timer = setTimeout(() => setCopyStatus(""), 2000);
    return () => clearTimeout(timer);
  }, [copyStatus]);

  useEffect(() => {
    const onChange = () => setFullScreen(document.fullscreenElement === shellRef.current);
    document.addEventListener("fullscreenchange", onChange);
    return () => document.removeEventListener("fullscreenchange", onChange);
  }, []);

  const view = useMemo(() => {
    if (!query) return { mode: "all" as const };
    const atPath = resolvePath(parsed.value, query);
    if (atPath) return { mode: "path" as const, value: atPath.value };
    return { mode: "search" as const, visible: matchingPaths(parsed.value, query) };
  }, [parsed.value, query]);

  if (!parsed.ok) {
    return (
      <div className="json-viewer">
        {/* Scrollable and without focusable content, so it takes focus itself for keyboard scrolling. */}
        {/* eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex */}
        <pre className="jv-surface jv-pre" tabIndex={0} aria-label={label}>
          {text}
        </pre>
      </div>
    );
  }

  const depth = openDepth ?? (view.mode === "search" ? Infinity : 2);
  const setDepth = (d: number) => {
    setOpenDepth(d);
    setGeneration((g) => g + 1);
  };

  const copy = () => {
    copyValue(text ?? JSON.stringify(parsed.value, null, 2))
      .then(() => {
        if (fullScreen) setCopyStatus("Copied");
        else message.success("Copied to clipboard");
      })
      .catch(() => {
        if (fullScreen) setCopyStatus("Copy failed");
        else message.error("Copy failed. Select the text and copy it manually.");
      });
  };

  const toggleFullScreen = () => {
    if (fullScreen) {
      document.exitFullscreen();
    } else if (document.fullscreenEnabled && shellRef.current?.requestFullscreen) {
      shellRef.current.requestFullscreen().catch(() => setModalOpen(true));
    } else {
      setModalOpen(true);
    }
  };

  const tree =
    view.mode === "search" && view.visible.size === 0 ? (
      <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={`No keys or values contain "${query}".`} />
    ) : (
      <JsonTree
        key={`${generation}|${query}`}
        value={view.mode === "path" ? view.value : parsed.value}
        name={view.mode === "path" ? query : undefined}
        openDepth={depth}
        visible={view.mode === "search" ? view.visible : null}
      />
    );

  return (
    <div className="json-viewer" ref={shellRef}>
      <div className="jv-toolbar">
        <Input
          className="jv-filter"
          allowClear
          prefix={<SearchOutlined aria-hidden />}
          placeholder="Filter by path or text, e.g. resources[0].instances"
          aria-label="Filter by path or text"
          value={filter}
          onChange={(e) => {
            setFilter(e.target.value);
            setOpenDepth(null);
          }}
        />
        <Space wrap size={8}>
          <Button onClick={() => setDepth(Infinity)}>Expand all</Button>
          <Button onClick={() => setDepth(1)}>Collapse all</Button>
          <Button icon={<CopyOutlined />} onClick={copy}>
            Copy
          </Button>
          <span className="jv-copy-status" aria-live="polite">
            {copyStatus}
          </span>
          <Button icon={fullScreen ? <FullscreenExitOutlined /> : <FullscreenOutlined />} onClick={toggleFullScreen}>
            {fullScreen ? "Exit full screen" : "Full screen"}
          </Button>
        </Space>
      </div>
      <div className="jv-surface" role="region" aria-label={label}>
        {tree}
      </div>
      <Modal
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        footer={null}
        title={label}
        width="calc(100vw - 32px)"
        centered
      >
        <div className="json-viewer">
          <div className="jv-surface jv-surface--modal">{tree}</div>
        </div>
      </Modal>
    </div>
  );
}
