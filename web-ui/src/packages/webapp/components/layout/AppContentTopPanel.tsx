import { Fragment, ReactNode } from "react";
import { Link, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import type { AppRouteDefinition } from "@/packages/webapp/types/appRoutes";
import { SidebarTrigger } from "@/components/ui/sidebar";
import { Separator } from "@/components/ui/separator";
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from "@/components/ui/breadcrumb";
import { LanguageSwitcher } from "@/components/common/LanguageSwitcher";
import { ThemeModeSwitcher } from "@/components/common/ThemeModeSwitcher";
import { getEmbedMode } from '@/embedContext';

interface BreadcrumbEntry {
    label: string;
    href?: string;
}

export interface AppContextTopPanelProps {
    routes: AppRouteDefinition[],
    rightActions?: ReactNode
}

export function AppContextTopPanel({
    routes, rightActions, ...props
}:AppContextTopPanelProps) {
    const location = useLocation();
    const { t } = useTranslation();
    const breadcrumbItems = getBreadcrumbItems(routes, location.pathname, t);

    return (
        <header className="flex h-14 shrink-0 items-center justify-between gap-3 bg-background px-4" {...props}>
          <div className="flex min-w-0 items-center gap-3">
            <SidebarTrigger className="shrink-0" />
            <Separator orientation="vertical" className="h-5" />
            <AppBreadcrumb items={breadcrumbItems} />
          </div>
          <div className="flex shrink-0 items-center gap-2">
            {
              (import.meta.env.DEV || getEmbedMode() === "standalone") && (
                <>
                  {
                    rightActions ?? (
                      <>
                        <LanguageSwitcher />
                        <ThemeModeSwitcher />
                      </>
                    )
                  }
                </>
              )
            }
          </div>
        </header>
    )
}

function AppBreadcrumb({ items }: { items: BreadcrumbEntry[] }) {
  return (
    <Breadcrumb className="min-w-0">
      <BreadcrumbList className="flex-nowrap overflow-hidden">
        {items.map((item, index) => {
          const isLast = index === items.length - 1;

          return (
            <Fragment key={`${item.label}-${index}`}>
              {index > 0 && <BreadcrumbSeparator className="shrink-0" />}
              <BreadcrumbItem className="min-w-0">
                {item.href && !isLast ? (
                  <BreadcrumbLink asChild className="truncate">
                    <Link to={item.href}>{item.label}</Link>
                  </BreadcrumbLink>
                ) : isLast ? (
                  <BreadcrumbPage className="truncate">
                    {item.label}
                  </BreadcrumbPage>
                ) : (
                  <span className="truncate">{item.label}</span>
                )}
              </BreadcrumbItem>
            </Fragment>
          );
        })}
      </BreadcrumbList>
    </Breadcrumb>
  );
}

function getBreadcrumbItems(
  routes: AppRouteDefinition[],
  pathname: string,
  t: ReturnType<typeof useTranslation>["t"],
): BreadcrumbEntry[] {
  for (const route of routes) {
    const routeMatch = findRouteBreadcrumb(route, pathname, t);

    if (routeMatch) {
      return routeMatch;
    }
  }

  return [{ label: getPathFallback(pathname, t) }];
}

function findRouteBreadcrumb(
  route: AppRouteDefinition,
  pathname: string,
  t: ReturnType<typeof useTranslation>["t"],
  parentPath = "",
): BreadcrumbEntry[] | undefined {
  const href = resolveRoutePath(parentPath, route.path);
  const current = {
    label: translateRouteLabel(t, route.breadcrumbLabel ?? route.label, route.breadcrumbLabelKey ?? route.labelKey),
    href: route.element ? href : undefined,
  };

  for (const child of route.children ?? []) {
    const childMatch = findRouteBreadcrumb(child, pathname, t, href);

    if (childMatch) {
      return [current, ...childMatch];
    }
  }

  if (route.element && isPathActive(pathname, href)) {
    return [current];
  }

  return undefined;
}

function getPathFallback(
  pathname: string,
  t: ReturnType<typeof useTranslation>["t"],
) {
  const segment = pathname.split("/").filter(Boolean).at(-1);

  if (!segment) {
    return t("general.layout.topPanel.fallback.home");
  }

  const pathLabel = segment
    .split("-")
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");

  return t("general.layout.topPanel.fallback.path", {
    path: pathLabel,
    defaultValue: pathLabel,
  });
}

function translateRouteLabel(
  t: ReturnType<typeof useTranslation>["t"],
  label: string,
  labelKey?: string,
) {
  return labelKey ? t(labelKey, { defaultValue: label }) : label;
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

function isPathActive(pathname: string, href: string) {
  const normalizedHref = href.replace(/\/$/, "") || "/";

  return pathname === href || pathname.startsWith(`${normalizedHref}/`);
}
