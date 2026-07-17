import type { GlobalsOption } from "rollup";

export interface ModuleTargetsProps {
  isExport: boolean;
  entryFilename: string;
  libName: string;
  libEntry: string;
  rollupOptions?: { external?: string[]; globals?: GlobalsOption };
}

export const apiBaseUrl = "/data/ignition-sdk-boilerplate/api/v1"
export const moduleTargets: Record<string, ModuleTargetsProps> = {
  webapp: {
    isExport: true,
    entryFilename: "webapp.js",
    libName: "IgnitionSdkBoilerplateWebApp",
    libEntry: "./src/packages/webapp/index.ts",
  },
  widget: {
    isExport: true,
    entryFilename: "widget.js",
    libName: "IgnitionSdkBoilerplateWidget",
    libEntry: "./src/packages/widget/index.ts",
  },
  page: {
    isExport: false,
    entryFilename: "page.js",
    libName: "IgnitionSdkBoilerplatePage",
    libEntry: "./src/packages/page/index.ts",
  },
  perspective: {
    isExport: true,
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
