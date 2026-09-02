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
          // Preserve route-level dynamic-import boundaries. Forcing dependencies into global
          // manual chunks made optional editors/parsers dependencies of the application entry,
          // so the browser preloaded them before the user visited those routes.
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
