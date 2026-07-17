import { authApi } from "../api";

import { createUseAuth } from "@/lib/query/auth";

const auths = createUseAuth(authApi);

export const {
  useCurrentUser,
  usePermissionConfig,
  useSecurityLevelRoles,
  useUpdatePermissionConfig,
  useResetPermissionConfig,
  hasPermission,
  hasAllPermissions,
  buildRoleAuthority,
} = auths;
