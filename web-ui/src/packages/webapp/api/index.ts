import { createAuthBaseApi } from "@/lib/api";
import { injectHealthEndpoints } from "@/lib/api/health";
import { injectAuthEndpoints } from "@/lib/api/auth";

export const baseApi = createAuthBaseApi({
  reducerPath: "webAppApi",
});

export const healthApi = injectHealthEndpoints(baseApi);
export const authApi = injectAuthEndpoints(healthApi);

// export const { useHealthQuery } = webAppHealthApi;
// export const {
//   useCurrentUserQuery,
//   usePermissionConfigQuery,
//   useSecurityLevelRolesQuery,
//   useUpdatePermissionConfigMutation,
//   useResetPermissionConfigMutation,
// } = webAppAuthApi;
