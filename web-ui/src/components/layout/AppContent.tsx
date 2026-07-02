import { ReactNode } from "react";
import { Outlet } from "react-router-dom";
import type { AppRouteDefinition } from "@/lib/types/appRoutes";
import { SidebarInset } from "@/components/ui/sidebar";
import { AppContextTopPanel } from "@/components/layout/AppContentTopPanel";
import { ScrollArea, ScrollBar } from "@/components/ui/scroll-area";
export interface AppContentProps {
    routes: AppRouteDefinition[];
    rightActions?: ReactNode;
}

export function AppContent({
    routes, rightActions, ...props
}: AppContentProps) {
    return (
        <SidebarInset className="h-full min-w-0 overflow-hidden" {...props}>
            {/* Top Panel */}
            <AppContextTopPanel routes={routes} rightActions={rightActions}/>

            {/* Actual Page Content */}
            <main className="min-h-0 min-w-full flex-1 overflow-auto bg-background relative">
                <Outlet/>
            </main>
            
            {/* Obseleted but remain for roll-back case */}
            {/* <ScrollArea className="min-h-0 min-w-full flex-1 bg-background relative">
                <Outlet />
                
                <ScrollBar orientation="vertical"/>
                <ScrollBar orientation="horizontal"/>
            </ScrollArea> */}

            
        </SidebarInset>
    )
}
