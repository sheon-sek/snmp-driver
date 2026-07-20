import type { LicenseApi } from "@/lib/api/license";
import type {
  LicenseAccessSnapshot,
  RequiredLicense,
} from "@/lib/types/api/license";

export const LICENSE_ACCESS_POLLING_INTERVAL_MS = 5_000;

export function createUseLicense(licenseApi: LicenseApi) {
  const {
    useLicenseAccessQuery,
    useLicenseStatusQuery,
    useRevalidateLicenseMutation,
  } = licenseApi;

  return {
    useLicenseAccess(options?: { skip?: boolean; pollingInterval?: number }) {
      return useLicenseAccessQuery(undefined, {
        pollingInterval: LICENSE_ACCESS_POLLING_INTERVAL_MS,
        ...options,
      });
    },

    useLicenseStatus(options?: { skip?: boolean }) {
      return useLicenseStatusQuery(undefined, options);
    },

    useRevalidateLicense() {
      return useRevalidateLicenseMutation();
    },

    meetsLicenseRequirement,
    missingLicenseFeatures,
  };
}

export function meetsLicenseRequirement(
  status: LicenseAccessSnapshot | undefined,
  requirement: RequiredLicense | undefined,
): boolean {
  if (requirement === undefined || requirement === false) {
    return true;
  }
  if (!status?.licensed) {
    return false;
  }
  if (requirement === true) {
    return true;
  }

  const required = normalizeFeatures(requirement.features);
  if (required.length === 0) {
    return true;
  }
  const available = new Set(normalizeFeatures(status.features));
  return requirement.match === "any"
    ? required.some((feature) => available.has(feature))
    : required.every((feature) => available.has(feature));
}

export function missingLicenseFeatures(
  status: LicenseAccessSnapshot | undefined,
  requirement: RequiredLicense | undefined,
): string[] {
  if (!requirement || requirement === true) {
    return [];
  }
  const available = new Set(normalizeFeatures(status?.features ?? []));
  return normalizeFeatures(requirement.features).filter(
    (feature) => !available.has(feature),
  );
}

function normalizeFeatures(features: readonly string[]) {
  return Array.from(
    new Set(
      features
        .map((feature) => feature.trim().toLowerCase())
        .filter(Boolean),
    ),
  );
}
