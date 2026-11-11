import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { rewriteAntdImports } from "./antdSubpathImports";

describe("rewriteAntdImports", () => {
  it("rewrites named imports, aliases and type-only names", () => {
    expect(rewriteAntdImports('import { Button, Table as AntTable, type FormInstance } from "antd";')).toBe(
      [
        'import Button from "antd/es/button";',
        'import AntTable from "antd/es/table";',
        'import type { FormInstance } from "antd";',
      ].join("\n")
    );
  });

  it("handles multi-line imports, multi-word names and QRCode", () => {
    const code = 'import {\n  DatePicker,\n  QRCode,\n  ConfigProvider,\n} from "antd";\nconst x = 1;';
    expect(rewriteAntdImports(code)).toBe(
      [
        'import DatePicker from "antd/es/date-picker";',
        'import QRCode from "antd/es/qr-code";',
        'import ConfigProvider from "antd/es/config-provider";',
        "const x = 1;",
      ].join("\n")
    );
  });

  it("leaves type-only imports, sub-path imports and other modules alone", () => {
    const code = [
      'import type { TableProps } from "antd";',
      'import Button from "antd/es/button";',
      'import { Foo } from "antd-extra";',
    ].join("\n");
    expect(rewriteAntdImports(code)).toBe(code);
  });
});

// The rewrite assumes antd's folder naming; check it for every name src imports from "antd", so a
// mismatch (or a type imported without `type`, which would become a bogus path) fails here and not
// only in `yarn build`.
describe("antd components imported from src", () => {
  const files = (dir: string): string[] =>
    readdirSync(dir, { withFileTypes: true }).flatMap((entry) =>
      entry.isDirectory()
        ? files(join(dir, entry.name))
        : /\.(tsx?|jsx?)$/.test(entry.name)
          ? [join(dir, entry.name)]
          : []
    );
  const imported = new Set<string>();
  for (const file of files(join(__dirname, "../src"))) {
    for (const [statement] of readFileSync(file, "utf8").matchAll(/import\s*\{[^}]*\}\s*from\s*"antd";?/g)) {
      for (const [, folder] of rewriteAntdImports(statement).matchAll(/from "antd\/es\/([^"]+)"/g)) {
        imported.add(folder);
      }
    }
  }

  it("finds some", () => expect(imported.size).toBeGreaterThan(20));

  it.each([...imported].sort())("antd/es/%s exists", (folder) => {
    expect(existsSync(join(__dirname, "../node_modules/antd/es", folder, "index.js"))).toBe(true);
  });
});
