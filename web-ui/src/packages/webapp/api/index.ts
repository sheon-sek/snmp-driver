import { createAuthBaseApi } from "@/lib/api";
import { injectHealthEndpoints } from "@/lib/api/health";
import { injectAuthEndpoints } from "@/lib/api/auth";
import { injectLicenseEndpoints } from "@/lib/api/license";

export const baseApi = createAuthBaseApi({
  reducerPath: "webAppApi",
});

export const healthApi = injectHealthEndpoints(baseApi);
export const authApi = injectAuthEndpoints(healthApi);
export const licenseApi = injectLicenseEndpoints(authApi);
