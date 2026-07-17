import { fileURLToPath } from "node:url";

import { build } from "vite";
import {
  apiBaseUrl,
  moduleTargets,
  type ModuleTarget,
} from "./build-targets.mts";

const configFile = fileURLToPath(new URL("../vite.config.ts", import.meta.url));
const targets: ModuleTarget[] = Object.entries(moduleTargets)
  .filter(([, config]) => config.isExport)
  .map(([key]) => key as ModuleTarget);

// Set API base url
process.env.VITE_API_BASE_URL = apiBaseUrl;

// Run build for all targets
for (const target of targets) {
  process.env.APP_BUILD_TARGET = target;
  await build({
    mode: "embedded",
    configFile,
    clearScreen: false,
  });
}

delete process.env.APP_BUILD_TARGET;
