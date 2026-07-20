import { licenseApi } from "../api";

import { createUseLicense } from "@/lib/query/license";

const licenses = createUseLicense(licenseApi);

export const {
  useLicenseAccess,
  useLicenseStatus,
  useRevalidateLicense,
  meetsLicenseRequirement,
  missingLicenseFeatures,
} = licenses;
