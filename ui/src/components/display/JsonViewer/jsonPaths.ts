// Path helpers for JsonViewer, kept out of the component file so fast refresh keeps working.
export type Container = Record<string, unknown> | unknown[];
export type Key = string | number;

export const isContainer = (v: unknown): v is Container => v !== null && typeof v === "object";
export const entriesOf = (v: Container): [Key, unknown][] =>
  Array.isArray(v) ? v.map((c, i) => [i, c]) : Object.entries(v);
export const childPath = (path: string, key: Key) =>
  typeof key === "number" ? `${path}[${key}]` : path ? `${path}.${key}` : key;

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
