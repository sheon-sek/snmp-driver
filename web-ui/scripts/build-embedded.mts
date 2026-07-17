import { fileURLToPath } from "node:url";

import { build } from "vite";
import {
  apiBaseUrl,
  moduleTargets,
  type ModuleTarget,
} from "./build-targets.mts";

const configFile = fileURLToPath(new URL("../vite.config.ts", import.meta.url));
const targets = Object.keys(moduleTargets) as ModuleTarget[];

// Set API base url
process.env.VITE_API_BASE_URL = apiBaseUrl;

// Clear stale output once, then append the remaining bundles.
try {
  for (const [index, target] of targets.entries()) {
    process.env.APP_BUILD_TARGET = target;
    process.env.APP_EMPTY_OUT_DIR = index === 0 ? "true" : "false";
    await build({
      mode: "embedded",
      configFile,
      clearScreen: false,
    });
  }
} finally {
  delete process.env.APP_BUILD_TARGET;
  delete process.env.APP_EMPTY_OUT_DIR;
}
