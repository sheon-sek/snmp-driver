import { useMemo, useState } from "react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { getApiErrorMessage } from "@/lib/api";
import {
  buildRoleAuthority,
  useCurrentUser,
  usePermissionConfig,
  useSecurityLevelRoles,
  useUpdatePermissionConfig,
} from "@/lib/query/auth";
import type {
  AppAuthConfig,
  PermissionCatalogItem,
  SecurityLevelRole,
} from "@/lib/types/api/auth";
import { useTranslation } from "react-i18next";

export default function PermissionControlPage() {
  const { t } = useTranslation();
  const currentUserQuery = useCurrentUser();
  const configQuery = usePermissionConfig();
  const rolesQuery = useSecurityLevelRoles();
  const [updatePermissionConfig, updateState] = useUpdatePermissionConfig();
  const [selectedRole, setSelectedRole] = useState<SecurityLevelRole>();
  const [draft, setDraft] = useState<AppAuthConfig>();

  const catalog = configQuery.data?.permissionCatalog ?? [];
  const roles = rolesQuery.data?.roles ?? [];
  const isLoading = configQuery.isLoading || rolesQuery.isLoading;
  const isFetching = configQuery.isFetching || rolesQuery.isFetching;
  const error = configQuery.error ?? rolesQuery.error;

  const enabledCounts = useMemo(() => {
    const counts = new Map<string, number>();

    roles.forEach((role) => {
      const authority = buildRoleAuthority(role.path);
      const count = Object.values(configQuery.data?.config.permissions ?? {})
        .filter((authorities) => authorities.includes(authority)).length;

      counts.set(role.path, count);
    });

    return counts;
  }, [configQuery.data?.config.permissions, roles]);

  function openRoleDialog(role: SecurityLevelRole) {
    if (!configQuery.data?.config) {
      return;
    }

    setSelectedRole(role);
    setDraft(cloneConfig(configQuery.data.config));
  }

  function closeRoleDialog() {
    setSelectedRole(undefined);
    setDraft(undefined);
  }

  function togglePermission(permission: PermissionCatalogItem, enabled: boolean) {
    if (!selectedRole) {
      return;
    }

    if (
      isAuthorizationSelfLock(
        draft,
        permission,
        selectedRole,
        currentUserQuery.data,
        enabled,
      )
    ) {
      toast.error(t("permissionControlPage.toasts.keepAuthorizationRole"));
      return;
    }

    const authority = buildRoleAuthority(selectedRole.path);

    setDraft((current) => {
      if (!current) {
        return current;
      }

      const permissions = { ...current.permissions };
      const currentAuthorities = permissions[permission.key] ?? [];
      const nextAuthorities = enabled
        ? Array.from(new Set([...currentAuthorities, authority]))
        : currentAuthorities.filter((item) => item !== authority);

      permissions[permission.key] = nextAuthorities;

      return {
        ...current,
        permissions,
      };
    });
  }

  async function saveDraft() {
    if (!draft) {
      return;
    }

    try {
      await updatePermissionConfig(draft).unwrap();
      toast.success(t("permissionControlPage.toasts.saved"));
      closeRoleDialog();
    } catch (err) {
      toast.error(
        getApiErrorMessage(
          err as Parameters<typeof getApiErrorMessage>[0],
          t("permissionControlPage.errors.saveFailed"),
        ),
      );
    }
  }

  function refresh() {
    void configQuery.refetch();
    void rolesQuery.refetch();
  }

  return (
    <div className="w-full p-6">
      <div className="mx-auto flex w-full max-w-5xl flex-col gap-4">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h1 className="text-2xl font-semibold tracking-normal">
              {t("permissionControlPage.title")}
            </h1>
            <p className="text-sm text-muted-foreground">
              {t("permissionControlPage.description")}
            </p>
          </div>
          <Button variant="outline" disabled={isFetching} onClick={refresh}>
            {isFetching ? t("general.actions.refreshing") : t("general.actions.refresh")}
          </Button>
        </div>

        {isLoading && <PermissionControlSkeleton />}

        {error && (
          <Card>
            <CardHeader>
              <CardTitle>{t("permissionControlPage.errors.loadTitle")}</CardTitle>
              <CardDescription>
                {getApiErrorMessage(error, t("general.errors.requestFailed"), true)}
              </CardDescription>
            </CardHeader>
          </Card>
        )}

        {!isLoading && !error && roles.length === 0 && (
          <Card>
            <CardHeader>
              <CardTitle>{t("permissionControlPage.empty.title")}</CardTitle>
              <CardDescription>
                {t("permissionControlPage.empty.description")}
              </CardDescription>
            </CardHeader>
          </Card>
        )}

        {!isLoading && !error && roles.length > 0 && (
          <div className="grid gap-3">
            {roles.map((role) => (
              <Card key={role.path}>
                <CardHeader className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                  <div className="space-y-1">
                    <CardTitle className="text-base">{role.label}</CardTitle>
                    <CardDescription>{role.path}</CardDescription>
                  </div>
                  <div className="flex items-center gap-3">
                    <Badge variant="secondary">
                      {t("permissionControlPage.roles.enabledCount", {
                        count: enabledCounts.get(role.path) ?? 0,
                      })}
                    </Badge>
                    <Button size="sm" onClick={() => openRoleDialog(role)}>
                      {t("permissionControlPage.actions.configure")}
                    </Button>
                  </div>
                </CardHeader>
              </Card>
            ))}
          </div>
        )}
      </div>

      <Dialog
        open={!!selectedRole && !!draft}
        onOpenChange={(open) => {
          if (!open) {
            closeRoleDialog();
          }
        }}
      >
        <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>{selectedRole?.path ?? t("permissionControlPage.dialog.titleFallback")}</DialogTitle>
            <DialogDescription>
              {t("permissionControlPage.dialog.description", {
                rolePath: selectedRole?.path ?? t("permissionControlPage.dialog.roleFallback"),
              })}
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-3">
            {catalog.map((permission) => {
              const checked = isPermissionEnabled(draft, permission, selectedRole);
              const disabled =
                checked &&
                isAuthorizationSelfLock(
                  draft,
                  permission,
                  selectedRole,
                  currentUserQuery.data,
                  false,
                );

              return (
                <div
                  className="flex items-start justify-between gap-4 rounded-md border p-3"
                  key={permission.key}
                >
                  <div className="space-y-1">
                    <p className="text-sm font-medium">
                      {translatePermissionCatalogField(t, permission, "label")}
                    </p>
                    <p className="text-sm text-muted-foreground">
                      {translatePermissionCatalogField(t, permission, "description")}
                    </p>
                    <p className="text-xs text-muted-foreground">
                      {permission.key}
                    </p>
                    {disabled && (
                      <p className="text-xs text-muted-foreground">
                        {t("permissionControlPage.selfLockWarning")}
                      </p>
                    )}
                  </div>
                  <Switch
                    checked={checked}
                    disabled={disabled}
                    onCheckedChange={(enabled) =>
                      togglePermission(permission, enabled)
                    }
                  />
                </div>
              );
            })}
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={closeRoleDialog}>
              {t("general.actions.cancel")}
            </Button>
            <Button disabled={updateState.isLoading} onClick={saveDraft}>
              {t("general.actions.saveChanges")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function cloneConfig(config: AppAuthConfig): AppAuthConfig {
  return {
    ...config,
    superAdminRoles: [...config.superAdminRoles],
    superAdminSecurityLevels: [...config.superAdminSecurityLevels],
    permissions: Object.fromEntries(
      Object.entries(config.permissions).map(([permission, authorities]) => [
        permission,
        [...authorities],
      ]),
    ),
  };
}

function isPermissionEnabled(
  config: AppAuthConfig | undefined,
  permission: PermissionCatalogItem,
  role: SecurityLevelRole | undefined,
) {
  if (!config || !role) {
    return false;
  }

  return (config.permissions[permission.key] ?? []).includes(
    buildRoleAuthority(role.path),
  );
}

function isAuthorizationSelfLock(
  config: AppAuthConfig | undefined,
  permission: PermissionCatalogItem,
  role: SecurityLevelRole | undefined,
  user: ReturnType<typeof useCurrentUser>["data"],
  enabled: boolean,
) {
  if (
    enabled ||
    !config ||
    !role ||
    !user ||
    permission.key !== "authorization.manage"
  ) {
    return false;
  }

  const selectedAuthority = buildRoleAuthority(role.path);
  const userRoleAuthorities = (user.roles ?? []).flatMap((userRole) => [
    userRole,
    buildRoleAuthority(userRole),
  ]);
  const userAuthorities = new Set([
    ...(user.authorities ?? []),
    ...userRoleAuthorities,
  ]);
  const selectedRoleBelongsToCurrentUser =
    user.roles?.includes(role.path) ||
    user.roles?.includes(role.name) ||
    user.roles?.includes(role.label) ||
    userAuthorities.has(selectedAuthority);

  if (!selectedRoleBelongsToCurrentUser) {
    return false;
  }

  return !(config.permissions["authorization.manage"] ?? [])
    .filter((authority) => authority !== selectedAuthority)
    .some((authority) => userAuthorities.has(authority));
}

function translatePermissionCatalogField(
  t: ReturnType<typeof useTranslation>["t"],
  permission: PermissionCatalogItem,
  field: "label" | "description",
) {
  const knownKey = permissionCatalogTranslationKey(permission.key, field);
  const fallback = field === "label" ? permission.label : permission.description;

  return knownKey ? t(knownKey, { defaultValue: fallback }) : fallback;
}

function permissionCatalogTranslationKey(
  permissionKey: string,
  field: "label" | "description",
) {
  if (permissionKey === "authorization.manage") {
    return `permissionControlPage.catalog.authorizationManage.${field}`;
  }

  return undefined;
}

function PermissionControlSkeleton() {
  return (
    <div className="grid gap-3">
      {[0, 1, 2].map((item) => (
        <Card key={item}>
          <CardHeader>
            <Skeleton className="h-5 w-48" />
            <Skeleton className="h-4 w-72" />
          </CardHeader>
          <CardContent>
            <Skeleton className="h-8 w-28" />
          </CardContent>
        </Card>
      ))}
    </div>
  );
}
