import { ReactNode } from "react";
import { Outlet } from "react-router-dom";
import type { AppRouteDefinition } from "@/packages/webapp/types/appRoutes";
import { SidebarInset } from "@/components/ui/sidebar";
import { AppContextTopPanel } from "@/packages/webapp/components/layout/AppContentTopPanel";

export interface AppContentProps {
  routes: AppRouteDefinition[];
  rightActions?: ReactNode;
}

export function AppContent({ routes, rightActions }: AppContentProps) {
  return (
    <SidebarInset className="h-full min-w-0 overflow-hidden">
      <AppContextTopPanel routes={routes} rightActions={rightActions} />
      <main className="relative min-h-0 min-w-full flex-1 overflow-auto bg-background">
        <Outlet />
      </main>
    </SidebarInset>
  );
}
