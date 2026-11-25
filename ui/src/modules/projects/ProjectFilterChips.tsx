import clsx from "classnames";
import type { ReactNode } from "react";
import {
  ClockCircleOutlined,
  ExclamationCircleOutlined,
  InboxOutlined,
  UnorderedListOutlined,
  UserOutlined,
} from "@ant-design/icons";
import { ProjectIndexCounts } from "./projectService";
import { ProjectChip } from "./projectHealth";
import "@/modules/workspaces/components/WorkspaceFilter.css";

type Props = {
  value: ProjectChip;
  onChange: (chip: ProjectChip) => void;
  /** Null while the counts are unavailable. A missing count is shown as a dash, never as 0. */
  counts: ProjectIndexCounts | null;
};

// The chips are the status pills of the workspace list: same look, same colours for the icons.
const CHIPS: {
  key: ProjectChip;
  label: string;
  icon: ReactNode;
  tone?: string;
  count: (counts: ProjectIndexCounts) => number;
}[] = [
  { key: "all", label: "All", icon: <UnorderedListOutlined aria-hidden />, count: (counts) => counts.all },
  {
    key: "attention",
    label: "Needs attention",
    icon: <ExclamationCircleOutlined aria-hidden />,
    tone: "waitingApproval",
    count: (counts) => counts.attention,
  },
  { key: "mine", label: "My projects", icon: <UserOutlined aria-hidden />, count: (counts) => counts.mine },
  { key: "empty", label: "Empty", icon: <InboxOutlined aria-hidden />, count: (counts) => counts.empty },
  { key: "stale", label: "Stale (90d)", icon: <ClockCircleOutlined aria-hidden />, count: (counts) => counts.stale },
];

export default function ProjectFilterChips({ value, onChange, counts }: Props) {
  return (
    <div className="workspace-status-pills project-chips" role="group" aria-label="Filter projects">
      {CHIPS.map((chip) => (
        <button
          key={chip.key}
          type="button"
          aria-pressed={value === chip.key}
          data-status={chip.tone}
          className={clsx("workspace-status-pill", { "workspace-status-pill--active": value === chip.key })}
          onClick={() => onChange(chip.key)}
        >
          {chip.icon}
          {chip.label} <span className="workspace-status-count">{counts ? chip.count(counts) : "-"}</span>
        </button>
      ))}
    </div>
  );
}
