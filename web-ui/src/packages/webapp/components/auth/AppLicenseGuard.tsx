import type { ReactNode } from "react";

import type { RequiredLicense } from "@/lib/types";
import LicenseRequiredPage from "../../pages/Auth/LicenseRequiredPage";
import SessionErrorPage from "../../pages/Auth/SessionErrorPage";
import { LicenseGuard } from "./RouteGuard";

export interface AppLicenseGuardProps {
  requiredLicense?: RequiredLicense;
  children: ReactNode;
}

export function AppLicenseGuard({
  requiredLicense = true,
  children,
}: AppLicenseGuardProps) {
  return (
    <LicenseGuard
      requiredLicense={requiredLicense}
      LicenseRequiredPage={LicenseRequiredPage}
      SessionErrorPage={SessionErrorPage}
    >
      {children}
    </LicenseGuard>
  );
}
