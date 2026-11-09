import { Tag } from "antd";
import { ProjectIndexCounts } from "./projectService";
import { ProjectChip } from "./projectHealth";

type Props = {
  value: ProjectChip;
  onChange: (chip: ProjectChip) => void;
  /** Null while the counts are unavailable. A missing count is shown as a dash, never as 0. */
  counts: ProjectIndexCounts | null;
};

const CHIPS: { key: ProjectChip; label: string; count: (counts: ProjectIndexCounts) => number }[] = [
  { key: "all", label: "All", count: (counts) => counts.all },
  { key: "attention", label: "Needs attention", count: (counts) => counts.attention },
  { key: "mine", label: "My projects", count: (counts) => counts.mine },
  { key: "empty", label: "Empty", count: (counts) => counts.empty },
  { key: "stale", label: "Stale (90d)", count: (counts) => counts.stale },
];

export default function ProjectFilterChips({ value, onChange, counts }: Props) {
  return (
    <div className="project-chips" role="group" aria-label="Filter projects">
      {CHIPS.map((chip) => (
        <Tag.CheckableTag
          key={chip.key}
          checked={value === chip.key}
          onChange={() => onChange(chip.key)}
          className="project-chip"
        >
          {chip.label} {counts ? chip.count(counts) : "-"}
        </Tag.CheckableTag>
      ))}
    </div>
  );
}
