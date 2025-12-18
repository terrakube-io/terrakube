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
            if (id.includes("react-icons")) return "icons";
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
