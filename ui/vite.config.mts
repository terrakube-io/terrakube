import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import react from "@vitejs/plugin-react";
import { defineConfig, type Plugin } from "vite";
import { visualizer } from "rollup-plugin-visualizer";

const projectRoot = dirname(fileURLToPath(import.meta.url));

const kebab = (name: string) =>
  name === "QRCode" ? "qr-code" : name.replace(/([a-z0-9])([A-Z])/g, "$1-$2").toLowerCase();

// `import { Button } from "antd"` reaches antd/es/index.js, a barrel that re-exports every component.
// Rolldown keeps those edges, so the whole library (tables, pickers, trees...) landed in the chunk the
// login page preloads. Rewriting to per-component sub-paths at build time keeps the shell at ~350 KB
// gzip instead of ~665 KB. Jest and tsc never see this; ActionLoader's dynamic `import("antd")` still
// gets the full namespace for user-defined actions.
const antdSubpathImports = (): Plugin => ({
  name: "antd-subpath-imports",
  enforce: "pre",
  transform(code, id) {
    if (!/\/src\/.*\.(tsx?|jsx?)$/.test(id) || !code.includes('from "antd"')) return null;
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
          values.push(`import ${alias ?? name} from "antd/es/${kebab(name)}";`);
        }
      }
      return values.join("\n") + (types.length ? `\nimport type { ${types.join(", ")} } from "antd";` : "");
    });
  },
});

export default defineConfig(({ mode }) => {
  const isProd = mode === "production";
  const analyze = process.env.ANALYZE === "1" || process.env.ANALYZE === "true";
  return {
    // Absolute base so asset and env-config.js URLs resolve from the site root on
    // any route. A relative base ("./") breaks deep-link hard reloads: the browser
    // resolves assets against the current path (e.g. /organizations/.../workspaces/)
    // and the server returns index.html instead, failing the page load. To host
    // under a subpath, set VITE_BASE to an absolute prefix such as "/ui/".
    base: process.env.VITE_BASE ?? "/",
    server: {
      host: true,
      allowedHosts: true,
      port: 3000,
    },
    // rolldown-vite uses oxc and ignores `esbuild` options. Replace console/debugger calls
    // at compile-time via `define` so they tree-shake out of the production bundle.
    define: isProd
      ? {
          "console.log": "(()=>{})",
          "console.debug": "(()=>{})",
          "console.info": "(()=>{})",
          "console.warn": "(()=>{})",
        }
      : undefined,
    resolve: {
      alias: {
        "@": resolve(projectRoot, "src"),
      },
    },
    build: {
      outDir: "build",
      sourcemap: false,
      target: "es2021",
      cssCodeSplit: true,
      // Heavy chunks (antd, babel-standalone, icons, hcl-parser, charts) are intentionally
      // isolated and lazy-loaded; the default 500 KB warning is noise here.
      chunkSizeWarningLimit: 1500,
      rolldownOptions: {
        plugins: analyze
          ? [
              // Treemap report at build/bundle-stats.html. Enabled via `ANALYZE=1 yarn build`
              // so default builds stay fast (visualizer otherwise dominates plugin time).
              visualizer({
                filename: "build/bundle-stats.html",
                gzipSize: true,
                brotliSize: true,
                template: "treemap",
                open: true,
              }),
            ]
          : [],
      },
    },
    plugins: [antdSubpathImports(), react()],
  };
});
