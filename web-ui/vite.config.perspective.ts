import react from "@vitejs/plugin-react";
import path from "node:path";
import { defineConfig, loadEnv } from "vite";
import { inlineCssIntoUmd } from "./vite.inline-css";
import { requiredEnv } from "./vite.env";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");

  const entryFilename = requiredEnv(env, "APP_P_ENTRY_FILENAME");
  const libName = requiredEnv(env, "APP_P_LIB_NAME");
  const libEntry = requiredEnv(env, "APP_P_LIB_ENTRY");

  return {
    envPrefix: "VITE_",

    plugins: [
      react(),
      inlineCssIntoUmd(entryFilename, "eetarp-perspective-styles"),
    ],

    resolve: {
      alias: {
        "@": path.resolve(__dirname, "./src"),
      },
    },

    build: {
      outDir: "build/generated-resources/mounted",
      emptyOutDir: false,
      sourcemap: false,
      minify: false,
      target: "es2020",
      cssCodeSplit: false,

      lib: {
        entry: path.resolve(__dirname, libEntry),
        name: libName,
        formats: ["umd"],
        fileName: () => entryFilename,
      },

      rollupOptions: {
        external: [
          "react",
          "react-dom",
          "react-dom/client",
          "@inductiveautomation/perspective-client",
          "mobx",
          "mobx-react",
        ],

        output: {
          exports: "named",
          globals: {
            react: "React",
            "react-dom": "ReactDOM",
            "react-dom/client": "ReactDOM",
            "@inductiveautomation/perspective-client": "PerspectiveClient",
            mobx: "mobx",
            "mobx-react": "mobxReact",
          },
        },
      },
    },
  };
});
