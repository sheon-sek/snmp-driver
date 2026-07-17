import { ReactNode } from "react";
import { useApiRuntime } from "@/embedContext";
import { MemoryRouter, HashRouter, Routes } from "react-router-dom";

export interface DynamicRouterProps {
    children: ReactNode
}

export function DynamicRouter({ children }:DynamicRouterProps) {
    const { embedMode } = useApiRuntime();

    if(embedMode === "perspective") {
        return (
            <MemoryRouter initialEntries={['/']}>
                <Routes>
                    {children}
                </Routes>
            </MemoryRouter>
        );
    } else {
        return (
            <HashRouter>
                <Routes>
                    {children}
                </Routes>
            </HashRouter>
        )
    }
}
