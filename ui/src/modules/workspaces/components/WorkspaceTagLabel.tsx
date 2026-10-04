import { TagModel } from "@/modules/organizations/types";
import { WorkspaceTagBinding } from "@/modules/workspaces/types";
import { workspaceTagKey } from "@/modules/workspaces/utils/workspaceTags";
import "./WorkspaceTagLabel.css";

type Props = {
  binding: WorkspaceTagBinding;
  tags: TagModel[];
};

/**
 * A tag's key and value as separate elements, so a chip can style them apart instead of showing `key:value`.
 * The space between them is a real one, so selecting the chip copies `key value`.
 */
export default function WorkspaceTagLabel({ binding, tags }: Props) {
  return (
    <>
      <span className="workspace-tag-label-key">{workspaceTagKey(binding, tags)}</span>
      {binding.value ? (
        <>
          {" "}
          <span className="workspace-tag-label-value">{binding.value}</span>
        </>
      ) : null}
    </>
  );
}
