import { ReactNode } from "react";
import { LucideIcon } from "lucide-react";

export interface AppRouteDefinition {
  id: string;
  path: string;
  element?: ReactNode;
  label: string;
  labelKey?: string;
  icon?: LucideIcon;
  breadcrumbLabel?: string;
  breadcrumbLabelKey?: string;
  navGroupId?: string;
  showInNav?: boolean;
  requiredPermissions?: string[];
  children?: AppRouteDefinition[];
}

export interface NavigationItem {
  id: string;
  label: string;
  labelKey?: string;
  href?: string;
  icon?: LucideIcon;
  children?: NavigationItem[];
  breadcrumbLabel?: string;
  breadcrumbLabelKey?: string;
  requiredPermissions?: string[];
}

export interface NavigationGroup {
  id: string;
  label: string;
  labelKey?: string;
  icon?: LucideIcon;
  items: NavigationItem[];
  requiredPermissions?: string[];
}

export interface FlatAppRouteDefinition extends Omit<
  AppRouteDefinition,
  "children" | "element"
> {
  path: string;
  element: ReactNode;
}
