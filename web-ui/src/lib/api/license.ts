import type { BaseApi } from "./index";
import type { LicenseAccessSnapshot, LicenseStatus } from "@/lib/types/api/license";

export function injectLicenseEndpoints(api: BaseApi) {
  return api.injectEndpoints({
    endpoints: (build) => ({
      licenseAccess: build.query<LicenseAccessSnapshot, void>({
        query: () => ({ url: "/license/access", method: "GET" }),
        providesTags: ["LicenseAccess"],
      }),
      licenseStatus: build.query<LicenseStatus, void>({
        query: () => ({ url: "/license/status", method: "GET" }),
        providesTags: ["LicenseStatus"],
      }),
      revalidateLicense: build.mutation<LicenseStatus, void>({
        query: () => ({ url: "/license/revalidate", method: "POST" }),
        invalidatesTags: ["LicenseAccess", "LicenseStatus"],
      }),
    }),
  });
}

export type LicenseApi = ReturnType<typeof injectLicenseEndpoints>;
