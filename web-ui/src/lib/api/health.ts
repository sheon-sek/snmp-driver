import type { BaseApi } from "./index";
import type { HealthResponse } from "@/lib/types/api/health";

export function injectHealthEndpoints(api: BaseApi) {
  return api.injectEndpoints({
    endpoints: (build) => ({
      health: build.query<HealthResponse, void>({
        query: () => ({
          url: "/health",
          method: "GET",
        }),
      }),
    }),
  });
}

export type HealthApi = ReturnType<typeof injectHealthEndpoints>;
