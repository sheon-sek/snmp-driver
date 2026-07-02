import {
  useCurrentUserQuery,
  usePermissionConfigQuery,
  useResetPermissionConfigMutation,
  useSecurityLevelRolesQuery,
  useUpdatePermissionConfigMutation,
} from "@/lib/api/auth";
import type { CurrentUserResponse } from "@/lib/types/api/auth";

export function useCurrentUser() {
  return useCurrentUserQuery();
}

export function usePermissionConfig() {
  return usePermissionConfigQuery();
}

export function useSecurityLevelRoles() {
  return useSecurityLevelRolesQuery();
}

export function useUpdatePermissionConfig() {
  return useUpdatePermissionConfigMutation();
}

export function useResetPermissionConfig() {
  return useResetPermissionConfigMutation();
}

export function hasPermission(
  user: CurrentUserResponse | undefined,
  permissionKey: string,
) {
  return user?.permissions?.[permissionKey] === true;
}

export function hasAllPermissions(
  user: CurrentUserResponse | undefined,
  permissionKeys: string[] | undefined,
) {
  if (!permissionKeys || permissionKeys.length === 0) {
    return true;
  }
  return permissionKeys.every((key) => hasPermission(user, key));
}

export function buildRoleAuthority(rolePath: string) {
  return `role:${rolePath}`;
}
