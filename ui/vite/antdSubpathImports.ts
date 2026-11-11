// `import { Button } from "antd"` reaches antd/es/index.js, a barrel that re-exports every component.
// Rolldown keeps those edges, so the whole library (tables, pickers, trees...) landed in the chunk the
// login page preloads. Rewriting to per-component sub-paths at build time keeps the shell at ~350 KB
// gzip instead of ~665 KB. A transform keeps call sites as plain `import { X } from "antd"`, which is
// what Jest, tsc and the antd docs expect; ESLint forbids the default and namespace forms it would miss,
// and Prettier keeps quotes double. ActionLoader's dynamic `import("antd")` still gets the full
// namespace for user-defined actions.

/** antd's folder for a component export: `DatePicker` -> `date-picker`. */
export const antdFolder = (name: string) =>
  name === "QRCode" ? "qr-code" : name.replace(/([a-z0-9])([A-Z])/g, "$1-$2").toLowerCase();

/** Rewrites every `import { ... } from "antd"` in `code` into per-component default imports. */
export function rewriteAntdImports(code: string): string {
  if (!code.includes('from "antd"')) return code;
  return code.replace(/import\s*\{([^}]*)\}\s*from\s*"antd";?/g, (_match, names: string) => {
    const values: string[] = [];
    const types: string[] = [];
    for (const raw of names
      .split(",")
      .map((part) => part.trim())
      .filter(Boolean)) {
      if (raw.startsWith("type ")) {
        types.push(raw.slice(5).trim());
      } else {
        const [name, alias] = raw.split(/\s+as\s+/);
        values.push(`import ${alias ?? name} from "antd/es/${antdFolder(name)}";`);
      }
    }
    return values.join("\n") + (types.length ? `\nimport type { ${types.join(", ")} } from "antd";` : "");
  });
}
