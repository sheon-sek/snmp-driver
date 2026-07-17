import { configureStore } from "@reduxjs/toolkit";

import { baseApi } from "../api";

export function createHealthCardStore() {
  return configureStore({
    reducer: {
      [baseApi.reducerPath]: baseApi.reducer,
    },
    middleware: (getDefaultMiddleware) =>
      getDefaultMiddleware().concat(baseApi.middleware),
  });
}

export const healthCardStore = createHealthCardStore();

export type HealthCardStore = ReturnType<typeof createHealthCardStore>;
