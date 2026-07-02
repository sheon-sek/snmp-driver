import { createSlice, type PayloadAction } from "@reduxjs/toolkit";
import { toast } from "sonner";
import {
  defaultLanguage,
  supportedLanguageCodes,
  type AppLanguage,
} from "@/i18n";

export type AppThemeMode = "system" | "dark" | "light";
export type PerspectiveThemeMode = "Dark-Mode" | "Light-Mode";

export interface UiPreferencesState {
  themeMode: AppThemeMode;
  language: AppLanguage;
  sidebarCollapsed: boolean;
}

export const uiPreferencesStorageKey = "app-state-storage";

export const initialUiPreferencesState: UiPreferencesState = {
  themeMode: "system",
  language: defaultLanguage,
  sidebarCollapsed: false,
};

const themeModes = new Set<AppThemeMode>(["system", "dark", "light"]);
const languages = new Set<AppLanguage>(supportedLanguageCodes);
const perspectiveThemeModes = new Map<PerspectiveThemeMode, AppThemeMode>([
  ["Dark-Mode", "dark"],
  ["Light-Mode", "light"],
]);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

export function toAppLanguage(value: string | undefined): AppLanguage {
  if (!languages.has(value as AppLanguage)) {
    toast.warning(
      `Unsupported language: ${value}. Falling back to default language '${initialUiPreferencesState.language}'.`,
    );
    return initialUiPreferencesState.language;
  }

  return value as AppLanguage;
}

export function toAppThemeMode(value: string | undefined): AppThemeMode {
  // Fall back to default theme mode if the value is not a valid AppThemeMode or PerspectiveThemeMode
  if (
    !themeModes.has(value as AppThemeMode) &&
    !perspectiveThemeModes.has(value as PerspectiveThemeMode)
  ) {
    toast.warning(
      `Unsupported theme mode: ${value}. Falling back to default theme mode '${initialUiPreferencesState.themeMode}'.`,
    );

    return initialUiPreferencesState.themeMode;
  }

  return (
    perspectiveThemeModes.get(value as PerspectiveThemeMode) ??
    (value as AppThemeMode)
  );
}

function readPersistedState(value: unknown): UiPreferencesState {
  const state = isRecord(value) && isRecord(value.state) ? value.state : value;

  if (!isRecord(state)) {
    return initialUiPreferencesState;
  }

  const language = toAppLanguage(
    typeof state.language === "string" ? state.language : undefined,
  );
  const themeMode = toAppThemeMode(
    typeof state.themeMode === "string" ? state.themeMode : undefined,
  );
  const sidebarCollapsed =
    typeof state.sidebarCollapsed === "boolean"
      ? state.sidebarCollapsed
      : initialUiPreferencesState.sidebarCollapsed;

  return {
    themeMode,
    language,
    sidebarCollapsed,
  };
}

export function loadPersistedUiPreferences(): UiPreferencesState {
  if (typeof window === "undefined") {
    return initialUiPreferencesState;
  }

  try {
    const rawValue = window.localStorage.getItem(uiPreferencesStorageKey);
    return rawValue
      ? readPersistedState(JSON.parse(rawValue))
      : initialUiPreferencesState;
  } catch {
    return initialUiPreferencesState;
  }
}

export function persistUiPreferences(state: UiPreferencesState) {
  if (typeof window === "undefined") {
    return;
  }

  try {
    window.localStorage.setItem(
      uiPreferencesStorageKey,
      JSON.stringify({
        state,
        version: 1,
      }),
    );
  } catch {
    // Ignore storage failures so private-mode or full-storage browsers still run.
  }
}

const uiPreferencesSlice = createSlice({
  name: "uiPreferences",
  initialState: initialUiPreferencesState,
  reducers: {
    setThemeMode(state, action: PayloadAction<AppThemeMode>) {
      state.themeMode = action.payload;
    },
    setLanguage(state, action: PayloadAction<AppLanguage>) {
      state.language = action.payload;
    },
    setSidebarCollapsed(state, action: PayloadAction<boolean>) {
      state.sidebarCollapsed = action.payload;
    },
    toggleSidebar(state) {
      state.sidebarCollapsed = !state.sidebarCollapsed;
    },
  },
});

export const { setThemeMode, setLanguage, setSidebarCollapsed, toggleSidebar } =
  uiPreferencesSlice.actions;

export const uiPreferencesReducer = uiPreferencesSlice.reducer;
