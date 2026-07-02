import { type ReactNode, useEffect, useMemo } from "react";
import { useAppSelector } from "@/store";
import { useAppRootContainer } from "./ShadowBoundary";
import { useTranslation } from "react-i18next";

interface ThemeProviderProps {
  children: ReactNode
}

export function ThemeProvider({ children }:ThemeProviderProps) {
  const { i18n } = useTranslation();
  const appRootContainer = useAppRootContainer();

  const themeMode = useAppSelector((state) => state.uiPreferences.themeMode)
  const language = useAppSelector((state) => state.uiPreferences.language)

  const resolvedThme = useMemo(() => {
    if(themeMode !== 'system') return themeMode
    return window.matchMedia('(prefers-color-scheme: dark)').matches ?
      'dark' : 'light';
  }, [themeMode])
  
  useEffect(() => {
    if (appRootContainer) {
      // Refresh theme mode (dark/light)
      appRootContainer.setAttribute('data-theme', resolvedThme)
      appRootContainer.classList.remove('light', 'dark')
      appRootContainer.classList.add(resolvedThme)
      appRootContainer.style.colorScheme = resolvedThme;

      // Refresh language
      appRootContainer.setAttribute('lang', language)
      void i18n.changeLanguage(language)
    }
  }, [appRootContainer, i18n, resolvedThme, language])

  return <>{children}</>
}
