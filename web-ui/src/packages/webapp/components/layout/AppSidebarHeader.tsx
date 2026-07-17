import { ReactNode } from "react";
import { Boxes } from "lucide-react";
import {
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem
} from "@/components/ui/sidebar";

export interface AppSidebarHeaderProps {
    appTitle: string;
    appSubtitle?: string;
    appLogo?: ReactNode;
}

export function AppSidebarHeader({ 
    appTitle, appSubtitle, appLogo, ...props
}:AppSidebarHeaderProps) {
    return (
        <SidebarHeader {...props}>
            <SidebarMenu>
                <SidebarMenuItem>
                    <SidebarMenuButton
                        size="lg"
                        className="data-[state=open]:bg-sidebar-accent data-[state=open]:text-sidebar-accent-foreground"
                    >
                        <div className="flex aspect-square size-8 items-center justify-center rounded-lg bg-sidebar-primary text-white">
                            {appLogo ?? <Boxes className="size-4 stroke-white" aria-hidden="true" />}
                        </div>
                        <div className="grid flex-1 text-left text-sm leading-tight">
                            <span className="truncate font-medium">{appTitle}</span>
                            <span className="truncate text-xs text-muted-foreground">{appSubtitle}</span>
                        </div>
                    </SidebarMenuButton>
                </SidebarMenuItem>
            </SidebarMenu>
        </SidebarHeader>
    )
}