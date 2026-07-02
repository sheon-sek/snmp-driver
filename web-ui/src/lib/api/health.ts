import { baseApi } from "./index";
import type { HealthResponse } from "@/lib/types/api/health";

export const healthApi = baseApi.injectEndpoints({
  endpoints: (build) => ({
    health: build.query<HealthResponse, void>({
      query: () => ({
        url: "/health",
        method: "GET",
      }),
    }),
  }),
});

export const { useHealthQuery } = healthApi;
