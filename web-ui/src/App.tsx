import { Route } from "react-router-dom";

import {
  appRoutes,
  flatAppRoutes,
  navigationGroups,
} from "@/app/appRoutes";
import type { NavigationGroup, NavigationItem } from "@/lib/types/appRoutes";
import { RouteGuard } from "@/components/auth/RouteGuard";
import { DynamicRouter } from "./components/foundation/DynamicRouter";
import { AppLayout } from "./components/layout/AppLayout";
import { hasAllPermissions, useCurrentUser } from "@/lib/query/auth";
import type { CurrentUserResponse } from "@/lib/types/api/auth";
import { useHealth } from "./lib/query/health";
import { useTranslation } from "react-i18next";

export default function App() {
  const { data: user } = useCurrentUser();
  const { data: health } = useHealth({autoRefresh: false});
  const { t } = useTranslation();
  const visibleNavigationGroups = filterNavigationGroups(
    navigationGroups,
    user,
  );

  return (
    <DynamicRouter>
      <Route
        element={
          <AppLayout
            appTitle={health?.module ?? t("general.layout.app.titleFallback")}
            appSubtitle={health?.version ?? t("general.layout.app.subtitleFallback")}
            navigationGroups={visibleNavigationGroups}
            routes={appRoutes}
          />
        }
      >
        {flatAppRoutes.map((route) => (
          <Route
            key={route.id}
            path={route.path}
            element={
              <RouteGuard requiredPermissions={route.requiredPermissions}>
                {route.element}
              </RouteGuard>
            }
          />
        ))}
      </Route>
    </DynamicRouter>
  );
}

function filterNavigationGroups(
  groups: NavigationGroup[],
  user: CurrentUserResponse | undefined,
) {
  return groups
    .filter((group) => import.meta.env.DEV || hasAllPermissions(user, group.requiredPermissions))
    .map((group) => ({
      ...group,
      items: filterNavigationItems(group.items, user),
    }))
    .filter((group) => group.items.length > 0);
}

function filterNavigationItems(
  items: NavigationItem[],
  user: CurrentUserResponse | undefined,
): NavigationItem[] {
  return items
    .filter((item) => import.meta.env.DEV || hasAllPermissions(user, item.requiredPermissions))
    .map((item) => ({
      ...item,
      children: item.children
        ? filterNavigationItems(item.children, user)
        : undefined,
    }))
    .filter((item) => item.href || (item.children && item.children.length > 0));
}
