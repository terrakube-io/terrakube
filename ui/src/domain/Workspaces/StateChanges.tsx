import { useState } from "react";
import { Caret, JsonKey, JsonScalar, JsonTree } from "@/components/display/JsonViewer";
import type { DiffNode } from "./stateDiff";

const isContainer = (v: unknown) => v !== null && typeof v === "object";

// One side of a changed path: scalars inline, objects and arrays as their own collapsible tree.
function Side({ name, value, removed }: { name?: string | number; value: unknown; removed: boolean }) {
  const Tag = removed ? "del" : "ins";
  return (
    <Tag className="jv-block">
      <JsonTree name={name} value={value} openDepth={1} />
    </Tag>
  );
}

function DiffEntry({ name, node }: { name?: string | number; node: DiffNode }) {
  const [open, setOpen] = useState(true);

  if (node.kind === "object" || node.kind === "array") {
    const [opener, closer] = node.kind === "array" ? ["[", "]"] : ["{", "}"];
    const n = node.children.length;
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
              <span className="jv-count"> {`${n} ${n === 1 ? "change" : "changes"}`}</span>
            </>
          )}
        </button>
        {open && (
          <>
            <div className="jv-children">
              {node.children.map(([key, child]) => (
                <DiffEntry key={key} name={key} node={child} />
              ))}
            </div>
            <div className="jv-row">
              <span className="jv-punct">{closer}</span>
            </div>
          </>
        )}
      </div>
    );
  }

  if (node.kind === "added") return <Side name={name} value={node.next} removed={false} />;
  if (node.kind === "removed") return <Side name={name} value={node.prev} removed />;
  if (isContainer(node.prev) || isContainer(node.next)) {
    return (
      <>
        <Side name={name} value={node.prev} removed />
        <Side name={name} value={node.next} removed={false} />
      </>
    );
  }
  return (
    <div className="jv-row">
      <JsonKey name={name} />
      <del>
        <JsonScalar value={node.prev} />
      </del>{" "}
      <ins>
        <JsonScalar value={node.next} />
      </ins>
    </div>
  );
}

/** Changed paths between two state versions, in the JSON viewer look. */
export function StateChanges({ diff }: { diff: DiffNode }) {
  return (
    <div className="json-viewer">
      <div className="jv-surface" role="region" aria-label="Changes from the previous version">
        <DiffEntry node={diff} />
      </div>
    </div>
  );
}
