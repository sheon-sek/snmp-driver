import type { AuthApi } from "@/lib/api/auth";
import type { CurrentUserResponse } from "@/lib/types/api/auth";

export function createUseAuth(authApi: AuthApi) {
  const {
    useCurrentUserQuery,
    usePermissionConfigQuery,
    useSecurityLevelRolesQuery,
    useUpdatePermissionConfigMutation,
    useResetPermissionConfigMutation,
  } = authApi;

  function hasPermission(
    user: CurrentUserResponse | undefined,
    permissionKey: string,
  ): boolean {
    return user?.permissions?.[permissionKey] === true;
  }

  function hasAllPermissions(
    user: CurrentUserResponse | undefined,
    permissionKeys: readonly string[] | undefined,
  ): boolean {
    if (!permissionKeys?.length) {
      return true;
    }

    return permissionKeys.every((key) => hasPermission(user, key));
  }

  function buildRoleAuthority(rolePath: string): string {
    return `role:${rolePath}`;
  }

  return {
    useCurrentUser() {
      return useCurrentUserQuery();
    },

    usePermissionConfig() {
      return usePermissionConfigQuery();
    },

    useSecurityLevelRoles() {
      return useSecurityLevelRolesQuery();
    },

    useUpdatePermissionConfig() {
      return useUpdatePermissionConfigMutation();
    },

    useResetPermissionConfig() {
      return useResetPermissionConfigMutation();
    },

    hasPermission,
    hasAllPermissions,
    buildRoleAuthority,
  };
}
