import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";
import { visualizer } from "rollup-plugin-visualizer";

const projectRoot = dirname(fileURLToPath(import.meta.url));

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
      rollupOptions: {
        output: {
          manualChunks(id) {
            if (!id.includes("node_modules")) return undefined;

            // React core — small, changes rarely, shared by everything
            if (id.includes("/react-dom/") || id.includes("\\react-dom\\")) return "react-dom";
            if (id.includes("/scheduler/") || id.includes("\\scheduler\\")) return "react-core";
            if (/[\\/]react[\\/]/.test(id)) return "react-core";

            // Router & auth
            if (id.includes("react-router") || id.includes("@remix-run")) return "router";
            if (id.includes("oidc-client-ts") || id.includes("react-oidc-context")) return "auth";

            // Antd ecosystem — biggest single dep, isolate it
            if (id.includes("/antd/") || id.includes("\\antd\\")) return "antd";
            if (id.includes("@ant-design/icons") || id.includes("@ant-design/cssinjs") || id.includes("@rc-component"))
              return "antd";
            if (/[\\/]rc-[a-z-]+[\\/]/.test(id)) return "antd";

            // Heavy/optional libs — only loaded by specific routes
            if (id.includes("react-icons")) return "icons";
            if (id.includes("reactflow") || id.includes("@reactflow")) return "reactflow";
            if (id.includes("react-vis")) return "charts";
            if (id.includes("@monaco-editor/react") || id.includes("monaco-editor")) return "monaco";
            if (id.includes("/sucrase/") || id.includes("\\sucrase\\")) return "sucrase";

            // Markdown ecosystem
            if (
              id.includes("react-markdown") ||
              id.includes("remark-") ||
              id.includes("rehype-") ||
              id.includes("/unified/") ||
              id.includes("/mdast") ||
              id.includes("/hast") ||
              id.includes("micromark")
            )
              return "markdown";

            // Specialty parsers
            if (id.includes("hcl2-parser")) return "hcl-parser";
            if (id.includes("unzipit")) return "unzipit";
            if (id.includes("html-to-image")) return "html-to-image";
            if (id.includes("html-react-parser") || id.includes("/parse5") || id.includes("domhandler"))
              return "html-parser";

            // Utilities
            if (id.includes("/luxon/")) return "luxon";
            if (id.includes("/axios/")) return "axios";
            if (id.includes("cron-to-quartz") || id.includes("cronstrue") || id.includes("react-js-cron"))
              return "cron";

            return "vendor";
          },
        },
        plugins: analyze
          ? [
              // Treemap report at build/bundle-stats.html. Enabled via `ANALYZE=1 bun run build`
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
    plugins: [react()],
  };
});
