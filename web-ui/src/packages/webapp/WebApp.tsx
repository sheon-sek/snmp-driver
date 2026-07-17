import { useEffect, useState, useSyncExternalStore } from "react";
import { toast } from "sonner";

import {
  ApiRuntimeProvider,
  type EmbedMode,
  type PerspectiveSessionContext,
} from "@/embedContext";
import { AppProvider } from "@/components/foundation/AppProvider";
import { useToasterId } from "@/components/ui/sonner";
import {
  initialUiPreferencesState,
  setLanguage,
  setThemeMode,
  toAppLanguage,
  toAppThemeMode,
} from "@/store/uiPreferencesSlice";
import { createWebAppStore } from "@/packages/webapp/store";
import { useUiPreferencesDispatch } from "@/store/uiPreferencesHooks";

import App from "./App";

export interface WebAppProps {
  apiBase?: string;
  embedMode?: EmbedMode;
  locale?: string;
  theme?: string;
  perspectiveSession?: PerspectiveSessionContext;
}

export function WebApp(props: WebAppProps) {
  const apiBase = props.apiBase ?? import.meta.env.VITE_API_BASE_URL;
  const embedMode = props.embedMode ?? "standalone";
  const sessionId = props.perspectiveSession?.sessionId ?? "";
  const projectName = props.perspectiveSession?.projectName ?? "";
  const requestScope = JSON.stringify([
    embedMode,
    apiBase,
    sessionId,
    projectName,
  ]);

  return (
    <WebAppInstance
      key={requestScope}
      {...props}
      apiBase={apiBase}
      embedMode={embedMode}
    />
  );
}

function WebAppInstance({
  apiBase = import.meta.env.VITE_API_BASE_URL,
  embedMode = "standalone",
  locale,
  theme,
  perspectiveSession,
}: WebAppProps) {
  const [runtimeContext] = useState(() => ({
    apiBase,
    embedMode,
    perspectiveSession,
  }));
  const [store] = useState(() => createWebAppStore(runtimeContext));
  const uiPreferences = useSyncExternalStore(
    store.subscribe,
    () => store.getState().uiPreferences,
    () => initialUiPreferencesState,
  );

  return (
    <ApiRuntimeProvider value={runtimeContext}>
      <AppProvider
        store={store}
        themeMode={uiPreferences.themeMode}
        language={uiPreferences.language}
      >
        <ExternalPreferenceSync locale={locale} theme={theme} />
        <App />
      </AppProvider>
    </ApiRuntimeProvider>
  );
}

function ExternalPreferenceSync({
  locale,
  theme,
}: Pick<WebAppProps, "locale" | "theme">) {
  const dispatch = useUiPreferencesDispatch();
  const toasterId = useToasterId();

  useEffect(() => {
    if (locale !== undefined) {
      dispatch(
        setLanguage(
          toAppLanguage(locale, (message) =>
            toast.warning(message, { toasterId }),
          ),
        ),
      );
    }
  }, [dispatch, locale, toasterId]);

  useEffect(() => {
    if (theme !== undefined) {
      dispatch(
        setThemeMode(
          toAppThemeMode(theme, (message) =>
            toast.warning(message, { toasterId }),
          ),
        ),
      );
    }
  }, [dispatch, theme, toasterId]);

  return null;
}

export default WebApp;
