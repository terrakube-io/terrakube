import dynamicImportVars from "@rollup/plugin-dynamic-import-vars";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";
import commonjs from "vite-plugin-commonjs";
import tsconfigPaths from "vite-tsconfig-paths";


export default defineConfig(() => {
  return {
    server: {
      host: true,
      allowedHosts: true,
      port: 3000,
    },
    build: {
      outDir: "build",
      rollupOptions: {
        output: {
          manualChunks: function (id) {
            if (id.includes('node_modules')) {
              if (id.includes('react-icons')) return 'icons';
              if (id.includes('reactflow')) return 'reactflow';
              if (id.includes('react-vis')) return 'charts';
              if (id.includes('@monaco-editor/react')) return 'monaco';
              // Markdown ecosystem
              if (id.includes('react-markdown') || id.includes('remark-') || id.includes('rehype-') || id.includes('unified') || id.includes('mdast') || id.includes('hast')) return 'markdown';
              // HCL parser
              if (id.includes('hcl2-parser')) return 'hcl-parser';
              // Unzipit
              if (id.includes('unzipit')) return 'unzipit';
              // Everything else including antd, react, react-dom goes to vendor
              return 'vendor';
            }
          },
        },
      },
    },
    plugins: [
      react(),
      commonjs(),
      // Ensure path aliases are loaded from tsconfig.app.json where the paths are defined
      tsconfigPaths({ projects: ["./tsconfig.app.json"] }),

    ],
    rollup: {
      plugins: [dynamicImportVars()],
    },
  };
});
