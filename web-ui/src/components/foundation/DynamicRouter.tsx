import { ReactNode } from "react";
import { isPerspectiveEmbed } from "@/embedContext";
import { MemoryRouter, HashRouter, Routes } from "react-router-dom";

export interface DynamicRouterProps {
    children: ReactNode
}

export function DynamicRouter({ children }:DynamicRouterProps) {
    if(isPerspectiveEmbed()) {
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