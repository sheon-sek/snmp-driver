import { configureStore } from "@reduxjs/toolkit";

import type { ApiRuntimeContext } from "@/embedContext";
import { baseApi } from "../api";

export function createHealthCardStore(runtimeContext: ApiRuntimeContext) {
  return configureStore({
    reducer: {
      [baseApi.reducerPath]: baseApi.reducer,
    },
    middleware: (getDefaultMiddleware) =>
      getDefaultMiddleware({
        thunk: { extraArgument: runtimeContext },
      }).concat(baseApi.middleware),
  });
}

export type HealthCardStore = ReturnType<typeof createHealthCardStore>;
