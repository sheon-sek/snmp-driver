import react from "@vitejs/plugin-react";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig, loadEnv } from "vite";

import { moduleTargets, type ModuleTarget } from "./scripts/build-targets.mts";
import { inlineCssIntoUmd } from "./scripts/vite.inline-css";
import { requiredEnv } from "./scripts/vite.env";

const rootDir = path.dirname(fileURLToPath(import.meta.url));

function isModuleTarget(value: string): value is ModuleTarget {
  return Object.prototype.hasOwnProperty.call(moduleTargets, value);
}

export default defineConfig(({ command, mode }) => {
  const env = loadEnv(mode, rootDir, "");

  const requestedTarget =
    command === "build"
      ? (process.env.APP_BUILD_TARGET ?? requiredEnv(env, "APP_BUILD_TARGET"))
      : "webapp";

  if (!isModuleTarget(requestedTarget)) {
    throw new Error(
      `Unsupported APP_BUILD_TARGET: "${requestedTarget}". ` +
        `Supported targets: ${Object.keys(moduleTargets).join(", ")}`,
    );
  }

  const target = moduleTargets[requestedTarget];

  return {
    envPrefix: "VITE_",

    plugins: [react(), inlineCssIntoUmd(target.entryFilename, "eetarp-styles")],

    resolve: {
      alias: {
        "@": path.resolve(rootDir, "src"),
      },
    },

    build: {
      outDir: path.resolve(rootDir, "build/generated-resources/mounted"),
      emptyOutDir: false,
      sourcemap: false,
      minify: false,
      target: "es2020",
      cssCodeSplit: false,

      lib: {
        entry: path.resolve(rootDir, target.libEntry),
        name: target.libName,
        formats: ["umd"],
        fileName: () => target.entryFilename,
      },

      rollupOptions: {
        external: [
          "react",
          "react-dom",
          "react-dom/client",
          ...(target.rollupOptions?.external ?? []),
        ],

        output: {
          exports: "named",

          globals: {
            react: "React",
            "react-dom": "ReactDOM",
            "react-dom/client": "ReactDOM",
            ...(target.rollupOptions?.globals ?? {}),
          },
        },
      },
    },

    server: {
      port: 9999,
    },
  };
});
