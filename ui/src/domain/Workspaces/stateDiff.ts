// Structural diff of two JSON documents (state files): objects are compared by key, arrays by index,
// and only the paths that differ are kept.
export type DiffNode =
  | { kind: "added"; next: unknown }
  | { kind: "removed"; prev: unknown }
  | { kind: "changed"; prev: unknown; next: unknown }
  | { kind: "object"; children: [key: string, node: DiffNode][] }
  | { kind: "array"; children: [index: number, node: DiffNode][] };

const has = (o: object, key: string) => Object.prototype.hasOwnProperty.call(o, key);

const isObject = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === "object" && !Array.isArray(v);

export const SENSITIVE_MASK = "(sensitive value)";

/** A resource attribute listed in its instance's `sensitive_attributes`; compared raw, always shown masked. */
class Secret {
  constructor(readonly raw: unknown) {}
}

type PathStep = { type?: string; value?: unknown };

// Copy of `value` with the attribute at `path` wrapped in a Secret; `get_attr` steps read a key, `index`
// steps a list index or map key (`{ value, type }`). A path that does not exist leaves `value` as it is.
function wrapAt(value: unknown, path: PathStep[]): unknown {
  if (path.length === 0) return value instanceof Secret ? value : new Secret(value);
  const [step, ...rest] = path;
  const key = step.type === "index" && isObject(step.value) ? step.value.value : step.value;
  if (Array.isArray(value) && typeof key === "number" && key in value) {
    return value.map((item, i) => (i === key ? wrapAt(item, rest) : item));
  }
  if (isObject(value) && typeof key === "string" && has(value, key)) {
    return { ...value, [key]: wrapAt(value[key], rest) };
  }
  return value;
}

// Raw state: every `{ attributes, sensitive_attributes }` instance gets its sensitive attributes wrapped.
function markSensitiveAttributes(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(markSensitiveAttributes);
  if (!isObject(v)) return v;
  const marked = Object.fromEntries(Object.entries(v).map(([k, c]) => [k, markSensitiveAttributes(c)]));
  if (isObject(marked.attributes) && Array.isArray(v.sensitive_attributes)) {
    marked.attributes = v.sensitive_attributes
      .filter(Array.isArray)
      .reduce<unknown>((attributes, path) => wrapAt(attributes, path as PathStep[]), marked.attributes);
  }
  return marked;
}

/** Copy of a state value with sensitive resource attributes and the `value` of every `{ sensitive: true }` object (state outputs) masked. */
export function maskSensitive(v: unknown): unknown {
  if (v instanceof Secret) return SENSITIVE_MASK;
  if (Array.isArray(v)) return v.map(maskSensitive);
  if (!isObject(v)) return v;
  return Object.fromEntries(
    Object.entries(v).map(([k, c]) => [k, k === "value" && v.sensitive === true ? SENSITIVE_MASK : maskSensitive(c)])
  );
}

/**
 * Returns undefined when both values are equal. Sensitive output values and the resource attributes listed in
 * `sensitive_attributes` (on either side) are compared raw but shown masked.
 */
export function diffState(prev: unknown, next: unknown): DiffNode | undefined {
  return diff(markSensitiveAttributes(prev), markSensitiveAttributes(next));
}

function diff(prev: unknown, next: unknown): DiffNode | undefined {
  if (prev instanceof Secret || next instanceof Secret) {
    const unwrap = (v: unknown) => (v instanceof Secret ? v.raw : v);
    return diff(unwrap(prev), unwrap(next))
      ? { kind: "changed", prev: SENSITIVE_MASK, next: SENSITIVE_MASK }
      : undefined;
  }
  if (Array.isArray(prev) && Array.isArray(next)) {
    const children: [number, DiffNode][] = [];
    for (let i = 0; i < Math.max(prev.length, next.length); i++) {
      const child =
        i >= next.length
          ? ({ kind: "removed", prev: maskSensitive(prev[i]) } as const)
          : i >= prev.length
            ? ({ kind: "added", next: maskSensitive(next[i]) } as const)
            : diff(prev[i], next[i]);
      if (child) children.push([i, child]);
    }
    return children.length ? { kind: "array", children } : undefined;
  }
  if (isObject(prev) && isObject(next)) {
    const children: [string, DiffNode][] = [];
    for (const key of new Set([...Object.keys(prev), ...Object.keys(next)])) {
      const secret = key === "value" && (prev.sensitive === true || next.sensitive === true);
      const shownPrev = () => (secret && prev.sensitive === true ? SENSITIVE_MASK : maskSensitive(prev[key]));
      const shownNext = () => (secret && next.sensitive === true ? SENSITIVE_MASK : maskSensitive(next[key]));
      const child = !has(next, key)
        ? ({ kind: "removed", prev: shownPrev() } as const)
        : !has(prev, key)
          ? ({ kind: "added", next: shownNext() } as const)
          : secret
            ? diff(prev[key], next[key]) && ({ kind: "changed", prev: shownPrev(), next: shownNext() } as const)
            : diff(prev[key], next[key]);
      if (child) children.push([key, child]);
    }
    return children.length ? { kind: "object", children } : undefined;
  }
  return prev === next ? undefined : { kind: "changed", prev: maskSensitive(prev), next: maskSensitive(next) };
}
