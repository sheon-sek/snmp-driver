
import { type ReactNode } from "react";

import type { AppRouteDefinition, NavigationGroup } from "@/packages/webapp/types/appRoutes";
import { SidebarProvider } from "@/components/ui/sidebar";
import { useCurrentUser } from "../../query/auth";
import {
  useUiPreferencesDispatch,
  useUiPreferencesSelector,
} from "@/store/uiPreferencesHooks";
import { setSidebarCollapsed } from "@/store/uiPreferencesSlice";

import { AppSidebar } from "./AppSidebar";
import { AppContent } from "./AppContent";

export interface AppLayoutProps {
  appTitle: string;
  appSubtitle?: string;
  appLogo?: ReactNode;
  navigationGroups: NavigationGroup[];
  routes: AppRouteDefinition[];
  rightActions?: ReactNode;
  authContext?: unknown;
}

export function AppLayout({
  appTitle,
  appSubtitle,
  appLogo,
  navigationGroups,
  routes,
  rightActions,
}: AppLayoutProps) {
  const dispatch = useUiPreferencesDispatch();
  const sidebarCollapsed = useUiPreferencesSelector(
    (state) => state.uiPreferences.sidebarCollapsed,
  );

  const currentUserQuery = useCurrentUser();
  const hideContent =
    !import.meta.env.DEV &&
    (
      currentUserQuery.isLoading ||
      currentUserQuery.isError ||
      !currentUserQuery.data?.appAuthenticated
    );

  return (
    <SidebarProvider
      open={!sidebarCollapsed}
      onOpenChange={(open) => dispatch(setSidebarCollapsed(!open))}
    >
      <AppSidebar
        appTitle={appTitle}
        appSubtitle={appSubtitle}
        appLogo={appLogo}
        navigationGroups={navigationGroups}
        hideContent={hideContent}
      />
      
      <AppContent routes={routes} rightActions={rightActions}/>
    </SidebarProvider>
  );
}
