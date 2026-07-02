import { type ReactNode } from 'react';

import { ShadowBoundary } from './ShadowBoundary';
import { ThemeProvider } from './ThemeProvider';
import { Provider as ReduxProvider } from 'react-redux';
import { store } from '@/store';
import { Toaster } from'@/components/ui/sonner';

interface AppProviderProps {
    children: ReactNode
}
export function AppProvider({ children }:AppProviderProps) {
    return (
        <ShadowBoundary>
            <ReduxProvider store={store}>
                <ThemeProvider>
					<Toaster richColors position='top-right'/>
                    {children}
                </ThemeProvider>
            </ReduxProvider>
        </ShadowBoundary>
    )
}
