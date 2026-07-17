import { configureStore } from "@reduxjs/toolkit";

import type { ApiRuntimeContext } from "@/embedContext";
import {
  initialUiPreferencesState,
  loadPersistedUiPreferences,
  persistUiPreferences,
  uiPreferencesReducer,
} from "@/store/uiPreferencesSlice";
import { baseApi } from "@/packages/webapp/api";

export function createWebAppStore(runtimeContext: ApiRuntimeContext) {
  const persistPreferences = runtimeContext.embedMode === "standalone";
  const store = configureStore({
    reducer: {
      uiPreferences: uiPreferencesReducer,
      [baseApi.reducerPath]: baseApi.reducer,
    },
    preloadedState: {
      uiPreferences: persistPreferences
        ? loadPersistedUiPreferences()
        : initialUiPreferencesState,
    },
    middleware: (getDefaultMiddleware) =>
      getDefaultMiddleware({
        thunk: { extraArgument: runtimeContext },
      }).concat(baseApi.middleware),
  });

  if (persistPreferences) {
    let lastUiPreferencesState = store.getState().uiPreferences;
    store.subscribe(() => {
      const nextUiPreferencesState = store.getState().uiPreferences;

      if (nextUiPreferencesState !== lastUiPreferencesState) {
        lastUiPreferencesState = nextUiPreferencesState;
        persistUiPreferences(nextUiPreferencesState);
      }
    });
  }

  return store;
}

export type WebAppStore = ReturnType<typeof createWebAppStore>;
export type WebAppRootState = ReturnType<WebAppStore["getState"]>;
export type WebAppDispatch = WebAppStore["dispatch"];
