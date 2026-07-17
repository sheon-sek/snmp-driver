import { type ReactNode, useState } from 'react';

import { ShadowBoundary } from './ShadowBoundary';
import { ThemeProvider, type ThemeMode } from './ThemeProvider';
import { Provider as ReduxProvider } from 'react-redux';
import type { Store } from '@reduxjs/toolkit';
import {
    Toaster,
    ToasterIdProvider,
    type ToasterProps,
} from '@/components/ui/sonner';
import { createAppI18n, defaultLanguage, type AppLanguage } from '@/i18n';
import { I18nextProvider } from 'react-i18next';

let toasterIdSequence = 0;

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
    const [i18n] = useState(createAppI18n);
    const [toasterId] = useState(createToasterId);

    return (
        <ShadowBoundary>
            <ToasterIdProvider value={toasterId}>
                <I18nextProvider i18n={i18n}>
                    <ReduxProvider store={store}>
                        <ThemeProvider themeMode={themeMode} language={language}>
                            {toasterProps === false ? null : (
                                <Toaster
                                    theme={themeMode}
                                    {...toasterProps}
                                    id={toasterId}
                                />
                            )}
                            {children}
                        </ThemeProvider>
                    </ReduxProvider>
                </I18nextProvider>
            </ToasterIdProvider>
        </ShadowBoundary>
    )
}

function createToasterId() {
    const uuid = globalThis.crypto?.randomUUID?.();
    toasterIdSequence += 1;
    return `eetarp-${uuid ?? `${Date.now().toString(36)}-${toasterIdSequence}`}`;
}
