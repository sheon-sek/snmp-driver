import '@/i18n';

import App from './App';
import { setApiBase } from '@/embedContext';
import { AppProvider } from './components/foundation/AppProvider';
import { useEffect } from 'react';
import {
  setLanguage,
  setThemeMode,
  toAppLanguage,
  toAppThemeMode,
} from '@/store/uiPreferencesSlice';
import { store } from '@/store';

export interface WebAppProps {
  apiBase?: string;
  locale?: string;
  theme?: string;
}

export function IgnitionSdkBoilerplate({
  apiBase = import.meta.env.VITE_API_BASE_URL,
  locale,
  theme,
  }: WebAppProps) {

  setApiBase(apiBase);

  useEffect(() => {
    if (locale !== undefined) {
      store.dispatch(setLanguage(toAppLanguage(locale)));
    }
  }, [locale])
  useEffect(() => {
    if (theme !== undefined) {
      store.dispatch(setThemeMode(toAppThemeMode(theme)));
    }
  }, [theme])

  return (
    <AppProvider>
      <App/>
    </AppProvider>
  );
}

export default IgnitionSdkBoilerplate;
