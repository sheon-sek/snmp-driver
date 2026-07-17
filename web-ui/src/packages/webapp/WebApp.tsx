import "@/i18n";

import { useEffect, useSyncExternalStore } from "react";

import { setApiBase } from "@/embedContext";
import { AppProvider } from "@/components/foundation/AppProvider";
import {
  initialUiPreferencesState,
  setLanguage,
  setThemeMode,
  toAppLanguage,
  toAppThemeMode,
} from "@/store/uiPreferencesSlice";
import { webAppStore } from "@/packages/webapp/store";

import App from "./App";

export interface WebAppProps {
  apiBase?: string;
  locale?: string;
  theme?: string;
}

export function WebApp({
  apiBase = import.meta.env.VITE_API_BASE_URL,
  locale,
  theme,
}: WebAppProps) {
  setApiBase(apiBase);
  const uiPreferences = useSyncExternalStore(
    webAppStore.subscribe,
    () => webAppStore.getState().uiPreferences,
    () => initialUiPreferencesState,
  );

  useEffect(() => {
    if (locale !== undefined) {
      webAppStore.dispatch(setLanguage(toAppLanguage(locale)));
    }
  }, [locale]);

  useEffect(() => {
    if (theme !== undefined) {
      webAppStore.dispatch(setThemeMode(toAppThemeMode(theme)));
    }
  }, [theme]);

  return (
    <AppProvider
      store={webAppStore}
      themeMode={uiPreferences.themeMode}
      language={uiPreferences.language}
    >
      <App />
    </AppProvider>
  );
}

export default WebApp;
