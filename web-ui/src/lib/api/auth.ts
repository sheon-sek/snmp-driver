import type { BaseApi } from "./index";
import type {
  AppAuthConfig,
  CurrentUserResponse,
  PermissionConfigResponse,
  SecurityLevelRolesResponse,
  UpdatePermissionConfigRequest,
} from "@/lib/types/api/auth";

export function injectAuthEndpoints(api: BaseApi) {
  return api.injectEndpoints({
    endpoints: (build) => ({
      currentUser: build.query<CurrentUserResponse, void>({
        query: () => ({ url: "/auth/me", method: "GET" }),
        providesTags: ["CurrentUser"],
      }),
      permissionConfig: build.query<PermissionConfigResponse, void>({
        query: () => ({ url: "/authorization/config", method: "GET" }),
        providesTags: ["PermissionConfig"],
      }),
      securityLevelRoles: build.query<SecurityLevelRolesResponse, void>({
        query: () => ({
          url: "/authorization/security-level-roles",
          method: "GET",
        }),
        providesTags: ["SecurityLevelRoles"],
      }),
      updatePermissionConfig: build.mutation<
        AppAuthConfig,
        UpdatePermissionConfigRequest
      >({
        query: (body) => ({
          url: "/authorization/config",
          method: "POST",
          body,
        }),
        invalidatesTags: ["CurrentUser", "PermissionConfig"],
      }),
      resetPermissionConfig: build.mutation<AppAuthConfig, void>({
        query: () => ({
          url: "/authorization/config/reset",
          method: "POST",
        }),
        invalidatesTags: ["CurrentUser", "PermissionConfig"],
      }),
    }),
  });
}

export type AuthApi = ReturnType<typeof injectAuthEndpoints>;
