import { TagsOutlined } from "@ant-design/icons";
import { Popover, Tag } from "antd";
import type { CSSProperties } from "react";
import getDeterministicColors from "@/modules/utils/getDeterministicColors";
import { TagModel } from "@/modules/organizations/types";
import { WorkspaceTagBinding } from "@/modules/workspaces/types";
import { formatWorkspaceTag, sortWorkspaceTagBindings } from "@/modules/workspaces/utils/workspaceTags";
import WorkspaceTagLabel from "@/modules/workspaces/components/WorkspaceTagLabel";
import "./WorkspaceTagChips.css";

type Props = {
  bindings?: WorkspaceTagBinding[];
  tags: TagModel[];
  /** Shows only an icon that opens the chips in a popover, for a layout with no room for them. */
  collapsed?: boolean;
};

function TagChip({ binding, tags }: { binding: WorkspaceTagBinding; tags: TagModel[] }) {
  const colors = getDeterministicColors(binding.tagId);
  // The border is set inline: the dark theme gives filled tags a grey border at the same specificity
  const style = {
    "--tag-color": colors.background,
    "--tag-key-text": colors.color,
    borderColor: colors.background,
  } as CSSProperties;
  return (
    <Tag title={formatWorkspaceTag(binding, tags)} className="workspace-tag-chip" style={style}>
      <WorkspaceTagLabel binding={binding} tags={tags} />
    </Tag>
  );
}

/**
 * The tags of one workspace as chips, ordered by key. Collapsed, an icon button opens them in a popover. It is
 * a native button so that the keyboard and touch can open it too.
 */
export default function WorkspaceTagChips({ bindings, tags, collapsed = false }: Props) {
  if (!bindings?.length) return null;

  const chips = sortWorkspaceTagBindings(bindings, tags).map((binding) => (
    <TagChip key={binding.tagId} binding={binding} tags={tags} />
  ));
  if (!collapsed) return <>{chips}</>;

  return (
    <Popover
      trigger={["hover", "click"]}
      placement="bottomLeft"
      title={`Tags (${chips.length})`}
      content={<div className="workspace-tag-chip-overflow">{chips}</div>}
    >
      <button type="button" className="workspace-tag-chip-toggle" aria-label={`Show ${chips.length} tags`}>
        <TagsOutlined />
      </button>
    </Popover>
  );
}
