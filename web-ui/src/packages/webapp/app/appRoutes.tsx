import type {
  AppRouteDefinition,
  NavigationGroup,
  NavigationItem,
  FlatAppRouteDefinition
} from '../types/appRoutes';
import { BookOpen, Home, Settings } from "lucide-react";
import { HomePage, PermissionControlPage } from "../pages";

export function createNavigationGroups(
  routes: AppRouteDefinition[],
): NavigationGroup[] {
  return appNavigationGroupDefinitions
    .map((group) => ({
      ...group,
      items: routes
        .filter((route) => route.navGroupId === group.id)
        .filter((route) => route.showInNav !== false)
        .map((route) => createNavigationItem(route)),
    }))
    .filter((group) => group.items.length > 0);
}

export function flattenAppRoutes(
  routes: AppRouteDefinition[],
  parentPath = "",
): FlatAppRouteDefinition[] {
  return routes.flatMap((route) => {
    const path = resolveRoutePath(parentPath, route.path);
    const { children: childRoutes, element, ...routeMeta } = route;
    const current = element ? [{ ...routeMeta, path, element }] : [];
    const children = flattenAppRoutes(childRoutes ?? [], path);

    return [...current, ...children];
  });
}

function createNavigationItem(
  route: AppRouteDefinition,
  parentPath = "",
): NavigationItem {
  const routePath = resolveRoutePath(parentPath, route.path);
  const children = (route.children ?? [])
    .filter((child) => child.showInNav !== false)
    .map((child) => createNavigationItem(child, routePath));

  return {
    id: route.id,
    label: route.label,
    labelKey: route.labelKey,
    ...(route.element ? { href: routePath } : {}),
    icon: route.icon,
    breadcrumbLabel: route.breadcrumbLabel,
    breadcrumbLabelKey: route.breadcrumbLabelKey,
    requiredPermissions: route.requiredPermissions,
    ...(children.length > 0 ? { children } : {}),
  };
}

function resolveRoutePath(parentPath: string, path: string) {
  if (path.startsWith("/")) {
    return path;
  }

  const normalizedParent = parentPath.replace(/\/+$/, "");
  const normalizedPath = path.replace(/^\/+/, "");

  if (!normalizedParent) {
    return `/${normalizedPath}`;
  }

  return `${normalizedParent}/${normalizedPath}`;
}

export const appNavigationGroupDefinitions: Omit<NavigationGroup, "items">[] = [
  {
    id: "general",
    label: "General",
    labelKey: "general.layout.navigation.groups.general",
  },
  {
    id: "setting",
    label: "Setting",
    labelKey: "general.layout.navigation.groups.setting",
  },
];

export const appRoutes: AppRouteDefinition[] = [
  {
    id: "home",
    navGroupId: "general",
    path: "/",
    element: <HomePage />,
    label: "Home",
    labelKey: "general.layout.navigation.routes.home.label",
    breadcrumbLabel: "Home",
    breadcrumbLabelKey: "general.layout.navigation.routes.home.breadcrumb",
    icon: Home,
  },
  {
    id: "dummy",
    navGroupId: "general",
    path: "/dummy",
    element: <HomePage />,
    label: "Dummy",
    labelKey: "general.layout.navigation.routes.dummy.label",
    breadcrumbLabel: "Dummy",
    breadcrumbLabelKey: "general.layout.navigation.routes.dummy.breadcrumb",
    icon: BookOpen,
    children: [
      {
        id: "dummy-subpage1",
        path: "dummy-subpage1",
        label: "Subpage 1",
        labelKey: "general.layout.navigation.routes.dummySubpage1.label",
        breadcrumbLabel: "Subpage 1",
        breadcrumbLabelKey: "general.layout.navigation.routes.dummySubpage1.breadcrumb",
        children: [
          {
            id: "dummy-subpage1-1",
            path: "dummy-subpage1-1",
            element: <HomePage />,
            label: "Subpage 1-1",
            labelKey: "general.layout.navigation.routes.dummySubpage1-1.label",
            breadcrumbLabel: "Subpage 1-1",
            breadcrumbLabelKey: "general.layout.navigation.routes.dummySubpage1-1.breadcrumb",
            children: [
              {
                id: "dummy-subpage1-1-1",
                path: "dummy-subpage1-1-1",
                element: <HomePage />,
                label: "Subpage 1-1-1",
                labelKey: "general.layout.navigation.routes.dummySubpage1-1-1.label",
                breadcrumbLabel: "Subpage 1-1-1",
                breadcrumbLabelKey: "general.layout.navigation.routes.dummySubpage1-1-1.breadcrumb",
              },
            ]
          },
        ]
      },
      {
        id: "dummy-subpage2",
        path: "dummy-subpage2",
        element: <HomePage />,
        label: "Subpage 2",
        labelKey: "general.layout.navigation.routes.dummySubpage2.label",
        breadcrumbLabel: "Subpage 2",
        breadcrumbLabelKey: "general.layout.navigation.routes.dummySubpage2.breadcrumb",
        children: [
          {
            id: "dummy-subpage2-1",
            path: "dummy-subpage2-1",
            element: <HomePage />,
            label: "Subpage 2-1",
            labelKey: "general.layout.navigation.routes.dummySubpage2-1.label",
            breadcrumbLabel: "Subpage 2-1",
            breadcrumbLabelKey: "general.layout.navigation.routes.dummySubpage2-1.breadcrumb",
          },
        ]
      },
    ],
  },
  {
    id: "settings",
    navGroupId: "setting",
    path: "/settings",
    label: "Settings",
    labelKey: "general.layout.navigation.routes.settings.label",
    breadcrumbLabel: "Settings",
    breadcrumbLabelKey: "general.layout.navigation.routes.settings.breadcrumb",
    icon: Settings,
    children: [
      {
        id: "settings-permissions",
        path: "permissions",
        element: <PermissionControlPage />,
        label: "Permission Control",
        labelKey: "general.layout.navigation.routes.settingsPermissions.label",
        breadcrumbLabel: "Permission Control",
        breadcrumbLabelKey: "general.layout.navigation.routes.settingsPermissions.breadcrumb",
        icon: Settings,
        requiredPermissions: ["authorization.manage"],
      },
    ],
  },
];

export const navigationGroups = createNavigationGroups(appRoutes);
export const flatAppRoutes = flattenAppRoutes(appRoutes);
