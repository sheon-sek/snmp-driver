import type { GlobalsOption } from "rollup";

export interface ModuleTargetsProps {
  entryFilename: string;
  libName: string;
  libEntry: string;
  rollupOptions?: { external?: string[]; globals?: GlobalsOption };
}

export const apiBaseUrl = "/data/ignition-sdk-boilerplate/api/v1";
export const moduleTargets: Record<string, ModuleTargetsProps> = {
  webapp: {
    entryFilename: "webapp.js",
    libName: "IgnitionSdkBoilerplateWebApp",
    libEntry: "./src/packages/webapp/index.ts",
  },
  widget: {
    entryFilename: "widget.js",
    libName: "IgnitionSdkBoilerplateWidget",
    libEntry: "./src/packages/widget/index.ts",
  },
  page: {
    entryFilename: "page.js",
    libName: "IgnitionSdkBoilerplatePage",
    libEntry: "./src/packages/page/index.ts",
  },
  perspective: {
    entryFilename: "perspective.js",
    libName: "IgnitionSdkBoilerplatePerspective",
    libEntry: "./src/packages/perspective/index.ts",
    rollupOptions: {
      external: ["@inductiveautomation/perspective-client"],
      globals: {
        "@inductiveautomation/perspective-client": "PerspectiveClient",
      },
    },
  },
} satisfies Record<string, ModuleTargetsProps>;

export type ModuleTarget = keyof typeof moduleTargets;
