import { type ReactNode } from 'react';

import { ShadowBoundary } from './ShadowBoundary';
import { ThemeProvider, type ThemeMode } from './ThemeProvider';
import { Provider as ReduxProvider } from 'react-redux';
import type { Store } from '@reduxjs/toolkit';
import { Toaster, type ToasterProps } from '@/components/ui/sonner';
import { defaultLanguage, type AppLanguage } from '@/i18n';

interface AppProviderProps {
    children: ReactNode
    store: Store
    themeMode?: ThemeMode
    language?: AppLanguage
    toasterProps?: ToasterProps | false
}
export function AppProvider({
    children,
    store,
    themeMode = 'system',
    language = defaultLanguage,
    toasterProps = {
        richColors: true,
        position: 'top-right',
    },
}: AppProviderProps) {
    return (
        <ShadowBoundary>
            <ReduxProvider store={store}>
                <ThemeProvider themeMode={themeMode} language={language}>
                    {toasterProps === false ? null : (
                        <Toaster
                            theme={themeMode}
                            {...toasterProps}
                        />
                    )}
                    {children}
                </ThemeProvider>
            </ReduxProvider>
        </ShadowBoundary>
    )
}
