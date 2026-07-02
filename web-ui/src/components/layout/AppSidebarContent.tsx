import type { NavigationGroup, NavigationItem } from "@/lib/types/appRoutes"
import { useEffect, useState } from "react"
import { Link, useLocation } from "react-router-dom"
import { useTranslation } from "react-i18next"
import { ChevronRight } from "lucide-react"
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible"
import { 
    SidebarContent,
    SidebarGroup,
    SidebarGroupLabel,
    SidebarGroupContent,
    SidebarMenu,
    SidebarMenuItem,
    SidebarMenuButton,
    SidebarMenuAction,
    SidebarMenuSub,
    SidebarMenuSubItem,
    SidebarMenuSubButton
} from "@/components/ui/sidebar"
import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"

export interface AppSidebarContentProps {
    navigationGroups: NavigationGroup[]
}

export function AppSidebarContent({
    navigationGroups
}:AppSidebarContentProps) {
    const location = useLocation();
    const { t } = useTranslation();
    const translateNavigationLabel = (label: string, labelKey?: string) =>
      translateLabel(t, label, labelKey);

    return (
        <SidebarContent>
          <div className="w-full min-w-0">
              {navigationGroups.length > 0 ? (
                  navigationGroups.map((group) => (
                      <NavigationGroupBlock
                          key={group.id}
                          group={group}
                          pathname={location.pathname}
                          translateLabel={translateNavigationLabel}
                      />
                  ))
              ) : (
                  <div className="px-3 py-2 text-sm text-sidebar-foreground/70 group-data-[collapsible=icon]:hidden">
                      {t("general.layout.sidebar.noNavigation")}
                  </div>
              )}
          </div>
        </SidebarContent>
    )
}

function NavigationGroupBlock({
  group,
  pathname,
  translateLabel,
}: {
  group: NavigationGroup;
  pathname: string;
  translateLabel: (label: string, labelKey?: string) => string;
}) {
  const Icon = group.icon;
  const groupLabel = translateLabel(group.label, group.labelKey);

  return (
    <SidebarGroup className="min-w-0 max-w-full overflow-hidden">
      <SidebarGroupLabel className="gap-2">
        {Icon && <Icon aria-hidden="true" />}
        <span className="min-w-0 truncate text-xs uppercase tracking-wider text-muted-foreground">{groupLabel}</span>
      </SidebarGroupLabel>
      
      <SidebarGroupContent className="min-w-0 max-w-full overflow-hidden">
        <SidebarMenu className="min-w-0 max-w-full">
          {group.items.map((item) => (
            <NavigationMenuItem
              key={item.id}
              item={item}
              pathname={pathname}
              translateLabel={translateLabel}
            />
          ))}
        </SidebarMenu>
      </SidebarGroupContent>
    </SidebarGroup>
  );
}

function NavigationMenuItem({
  item,
  pathname,
  translateLabel,
}: {
  item: NavigationItem;
  pathname: string;
  translateLabel: (label: string, labelKey?: string) => string;
}) {
  const Icon = item.icon;
  const active = isNavigationItemActive(item, pathname);
  const label = translateLabel(item.label, item.labelKey);
  const hasChildren = (item.children ?? []).length > 0;
  const [open, setOpen] = useState(active);

  useEffect(() => {
    if (active && hasChildren) {
      setOpen(true);
    }
  }, [active, hasChildren]);

  const content = (
    <>
      {Icon && <Icon aria-hidden="true" />}
      <span className="min-w-0 truncate">{label}</span>
      {hasChildren && (
        <ChevronRight
          aria-hidden="true"
          className={`pointer-events-none ml-auto h-4 w-4 shrink-0 transition-transform group-data-[collapsible=icon]:hidden ${
            open ? "rotate-90" : ""
          }`}
        />
      )}
    </>
  );

  if (hasChildren) {
    return (
      <Collapsible asChild open={open} onOpenChange={setOpen}>
        <SidebarMenuItem>
          {item.href ? (
            <>
              <SidebarMenuButton asChild isActive={active} tooltip={label}>
                <Link to={item.href}>
                  {Icon && <Icon aria-hidden="true" />}
                  <span className="min-w-0 truncate">{label}</span>
                </Link>
              </SidebarMenuButton>
              <CollapsibleTrigger asChild>
                <SidebarMenuAction aria-label={`Toggle ${label}`} asChild>
                  <Button variant="ghost" size="icon" className={cn("h-5 w-5 p-0", active && "hover:bg-sidebar")}>
                    <ChevronRight
                      aria-hidden="true"
                      className={`transition-transform ${open ? "rotate-90" : ""}`}
                    />
                  </Button>
                </SidebarMenuAction>
              </CollapsibleTrigger>
            </>
          ) : (
            <CollapsibleTrigger asChild>
              <SidebarMenuButton isActive={active} tooltip={label}>
                {content}
              </SidebarMenuButton>
            </CollapsibleTrigger>
          )}

          <CollapsibleContent>
            <NavigationMenuSubItems items={item.children ?? []} pathname={pathname} translateLabel={translateLabel} />
          </CollapsibleContent>
        </SidebarMenuItem>
      </Collapsible>
    );
  }

  return (
    <SidebarMenuItem>
      {item.href ? (
        <SidebarMenuButton asChild isActive={active} tooltip={label}>
          <Link to={item.href}>
            {content}
          </Link>
        </SidebarMenuButton>
      ) : (
        <SidebarMenuButton isActive={active} tooltip={label}>
          {content}
        </SidebarMenuButton>
      )}
    </SidebarMenuItem>
  );
}

function NavigationMenuSubItems({
  items,
  pathname,
  translateLabel,
}: {
  items: NavigationItem[];
  pathname: string;
  translateLabel: (label: string, labelKey?: string) => string;
}) {
  return (
    <SidebarMenuSub>
      {items.map((item) => (
        <NavigationMenuSubItem key={item.id} item={item} pathname={pathname} translateLabel={translateLabel} />
      ))}
    </SidebarMenuSub>
  );
}

function NavigationMenuSubItem({
  item,
  pathname,
  translateLabel,
}: {
  item: NavigationItem;
  pathname: string;
  translateLabel: (label: string, labelKey?: string) => string;
}) {
  const Icon = item.icon;
  const active = isNavigationItemActive(item, pathname);
  const label = translateLabel(item.label, item.labelKey);
  const hasChildren = (item.children ?? []).length > 0;
  const [open, setOpen] = useState(active);

  useEffect(() => {
    if (active && hasChildren) {
      setOpen(true);
    }
  }, [active, hasChildren]);

  const content = (
    <>
      {Icon && <Icon aria-hidden="true" />}
      <span className="min-w-0 truncate">{label}</span>
      {hasChildren && (
        <ChevronRight
          aria-hidden="true"
          className={`pointer-events-none ml-auto h-4 w-4 shrink-0 transition-transform ${
            open ? "rotate-90" : ""
          }`}
        />
      )}
    </>
  );

  if (hasChildren) {
    return (
      <Collapsible asChild open={open} onOpenChange={setOpen}>
        <SidebarMenuSubItem className="relative">
          {item.href ? (
            <>
              <SidebarMenuSubButton asChild isActive={active} className="pr-8">
                <Link to={item.href}>
                  {Icon && <Icon aria-hidden="true" />}
                  <span className="min-w-0 truncate">{label}</span>
                </Link>
              </SidebarMenuSubButton>
              <CollapsibleTrigger asChild>
                <SidebarMenuAction aria-label={`Toggle ${label}`} asChild>
                  <Button variant="ghost" size="icon" className={cn("h-5 w-5 p-0", active && "hover:bg-sidebar")}>
                    <ChevronRight
                      aria-hidden="true"
                      className={`transition-transform ${open ? "rotate-90" : ""}`}
                    />
                  </Button>
                </SidebarMenuAction>
              </CollapsibleTrigger>
            </>
          ) : (
            <CollapsibleTrigger asChild>
              <SidebarMenuSubButton asChild isActive={active}>
                <button type="button" className="w-full text-left">
                  {content}
                </button>
              </SidebarMenuSubButton>
            </CollapsibleTrigger>
          )}

          <CollapsibleContent>
            <NavigationMenuSubItems items={item.children ?? []} pathname={pathname} translateLabel={translateLabel} />
          </CollapsibleContent>
        </SidebarMenuSubItem>
      </Collapsible>
    );
  }

  return (
    <SidebarMenuSubItem>
      {item.href ? (
        <SidebarMenuSubButton asChild isActive={active}>
          <Link to={item.href}>
            {content}
          </Link>
        </SidebarMenuSubButton>
      ) : (
        <SidebarMenuSubButton isActive={active}>
          {content}
        </SidebarMenuSubButton>
      )}
    </SidebarMenuSubItem>
  );
}

function translateLabel(
  t: ReturnType<typeof useTranslation>["t"],
  label: string,
  labelKey?: string,
) {
  return labelKey ? t(labelKey, { defaultValue: label }) : label;
}

function isNavigationItemActive(item: NavigationItem, pathname: string):boolean {
  return (
    isPathActive(pathname, item.href) ||
    (item.children ?? []).some((child) => isNavigationItemActive(child, pathname))
  );
}

function isPathActive(pathname: string, href?: string) {
  if (!href) {
    return false;
  }

  const normalizedHref = href.replace(/\/$/, "") || "/";

  return pathname === href || pathname.startsWith(`${normalizedHref}/`);
}
