import { type ReactNode, useEffect, useMemo } from "react";
import { useAppRootContainer } from "./ShadowBoundary";
import { useTranslation } from "react-i18next";
import { defaultLanguage, type AppLanguage } from "@/i18n";

export type ThemeMode = "system" | "dark" | "light";

interface ThemeProviderProps {
  children: ReactNode;
  themeMode?: ThemeMode;
  language?: AppLanguage;
}

export function ThemeProvider({
  children,
  themeMode = "system",
  language = defaultLanguage,
}: ThemeProviderProps) {
  const { i18n } = useTranslation();
  const appRootContainer = useAppRootContainer();

  const resolvedTheme = useMemo(() => {
    if(themeMode !== 'system') return themeMode
    return typeof window !== "undefined" && window.matchMedia('(prefers-color-scheme: dark)').matches ?
      'dark' : 'light';
  }, [themeMode])
  
  useEffect(() => {
    if (appRootContainer) {
      // Refresh theme mode (dark/light)
      appRootContainer.setAttribute('data-theme', resolvedTheme)
      appRootContainer.classList.remove('light', 'dark')
      appRootContainer.classList.add(resolvedTheme)
      appRootContainer.style.colorScheme = resolvedTheme;

      // Refresh language
      appRootContainer.setAttribute('lang', language)
      void i18n.changeLanguage(language)
    }
  }, [appRootContainer, i18n, resolvedTheme, language])

  return <>{children}</>
}
