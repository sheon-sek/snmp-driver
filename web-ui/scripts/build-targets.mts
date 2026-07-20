export interface ModuleTargetsProps {
  entryFilename: string;
  libName: string;
  libEntry: string;
  rollupOptions?: {
    external?: string[];
    globals?: Record<string, string>;
  };
}

export const apiBaseUrl = "/data/snmp-driver/api/v1";

export const moduleTargets = {
  page: {
    entryFilename: "page.js",
    libName: "SnmpDriverPage",
    libEntry: "./src/packages/page/index.ts",
  } as ModuleTargetsProps,
} satisfies Record<string, ModuleTargetsProps>;

export type ModuleTarget = keyof typeof moduleTargets;
