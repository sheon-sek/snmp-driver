import { configureStore } from "@reduxjs/toolkit";

import {
  loadPersistedUiPreferences,
  persistUiPreferences,
  uiPreferencesReducer,
} from "@/store/uiPreferencesSlice";
import { baseApi } from "@/packages/webapp/api";

export function createWebAppStore() {
  const store = configureStore({
    reducer: {
      uiPreferences: uiPreferencesReducer,
      [baseApi.reducerPath]: baseApi.reducer,
    },
    preloadedState: {
      uiPreferences: loadPersistedUiPreferences(),
    },
    middleware: (getDefaultMiddleware) =>
      getDefaultMiddleware().concat(baseApi.middleware),
  });

  let lastUiPreferencesState = store.getState().uiPreferences;
  store.subscribe(() => {
    const nextUiPreferencesState = store.getState().uiPreferences;

    if (nextUiPreferencesState !== lastUiPreferencesState) {
      lastUiPreferencesState = nextUiPreferencesState;
      persistUiPreferences(nextUiPreferencesState);
    }
  });

  return store;
}

export const webAppStore = createWebAppStore();

export type WebAppStore = ReturnType<typeof createWebAppStore>;
export type WebAppRootState = ReturnType<WebAppStore["getState"]>;
export type WebAppDispatch = WebAppStore["dispatch"];
