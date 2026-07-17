import type { ReactNode } from "react";

import type { NavigationGroup } from "@/packages/webapp/types/appRoutes";
import {
  Sidebar,
  SidebarRail,
  useSidebar
} from "@/components/ui/sidebar";
import { AppSidebarHeader } from "./AppSidebarHeader";
import { AppSidebarContent } from "./AppSidebarContent";
import { AppSidebarFooter } from "./AppSidebarFooter";
import { useTranslation } from "react-i18next";

export interface AppSidebarProps {
  appTitle: string;
  appSubtitle?: string;
  appLogo?: ReactNode;
  navigationGroups: NavigationGroup[];
  hideContent?: boolean;
}

export function AppSidebar({
  appTitle,
  appSubtitle,
  appLogo,
  navigationGroups,
  hideContent = false,
}: AppSidebarProps) {
  const { t } = useTranslation();
  const { state } = useSidebar();

  return (
    <Sidebar collapsible="icon" className="relative">
      {hideContent ? (
        <div className="my-auto w-full p-4 text-center text-xs text-muted-foreground">
          {state === "expanded" ? t("general.layout.sidebar.navigationHidden") : ""}
        </div>
      ) : (
        <>
          <AppSidebarHeader appTitle={appTitle} appSubtitle={appSubtitle} appLogo={appLogo} />
          <AppSidebarContent navigationGroups={navigationGroups}/>
          <AppSidebarFooter/>
        </>
      )}

      <SidebarRail/>
    </Sidebar>
  );
}
