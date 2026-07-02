export interface CurrentUserResponse {
  source: string;
  appAuthenticated: boolean;
  username: string;
  roles: string[];
  securityLevels: string[];
  authorities: string[];
  permissions: Record<string, boolean>;
}

export interface PermissionCatalogItem {
  key: string;
  label: string;
  description: string;
}

export interface AppAuthConfig {
  version: number;
  revision: number;
  superAdminRoles: string[];
  superAdminSecurityLevels: string[];
  permissions: Record<string, string[]>;
}

export interface PermissionConfigResponse {
  config: AppAuthConfig;
  permissionCatalog: PermissionCatalogItem[];
}

export interface UpdatePermissionConfigRequest extends AppAuthConfig {}

export interface SecurityLevelRole {
  path: string;
  name: string;
  label: string;
}

export interface SecurityLevelRolesResponse {
  roles: SecurityLevelRole[];
}
