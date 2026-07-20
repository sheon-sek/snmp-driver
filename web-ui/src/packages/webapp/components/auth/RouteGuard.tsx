import type { ComponentType, ReactNode } from "react";
import { getApiErrorMessage, isApiErrorResponse } from "@/lib/api";
import type { CurrentUserResponse } from "@/lib/types";
import type {
  LicenseAccessSnapshot,
  RequiredLicense,
} from "@/lib/types";
import { useCurrentUser } from "../../query/auth";
import {
  meetsLicenseRequirement,
  missingLicenseFeatures,
  useLicenseAccess,
} from "../../query/license";
import { useTranslation } from "react-i18next";

export interface LoginRequiredPageProps {
  message?: string;
}

export interface SessionErrorPageProps {
  status?: string | number;
  message?: string;
  details?: string;
}

export interface UserDeniedPageProps {
  missingPermissions?: string[];
  user?: CurrentUserResponse;
}

export interface LicenseRequiredPageProps {
  status?: LicenseAccessSnapshot;
  requiredLicense?: RequiredLicense;
  missingFeatures?: string[];
}

export interface RouteGuardProps {
  requiredPermissions?: string[];
  requiredLicense?: RequiredLicense;
  LoginRequiredPage: ComponentType<LoginRequiredPageProps>;
  SessionErrorPage: ComponentType<SessionErrorPageProps>;
  UserDeniedPage: ComponentType<UserDeniedPageProps>;
  LicenseRequiredPage: ComponentType<LicenseRequiredPageProps>;
  children: ReactNode;
}

export function RouteGuard({
  requiredPermissions = [],
  requiredLicense,
  LoginRequiredPage,
  SessionErrorPage,
  UserDeniedPage,
  LicenseRequiredPage,
  children,
}: RouteGuardProps) {
  const { t } = useTranslation();
  const { data: user, error, isError, isLoading } = useCurrentUser();

  // Render children immediately in development mode to avoid unnecessary loading states and errors during development.
  if(import.meta.env.DEV) 
    return <>{children}</>;

  if (isLoading) {
    return (
      <div className="flex min-h-full items-center justify-center text-sm text-muted-foreground">
        {t("general.auth.guard.loadingSession")}
      </div>
    );
  }

  if (isError && isApiErrorResponse(error) && Number(error.status) === 401) {
    return (
      <LoginRequiredPage
        message={getApiErrorMessage(error, t("general.auth.loginRequired.message"), true)}
      />
    );
  }

  if (isError) {
    return (
      <SessionErrorPage
        message={getApiErrorMessage(error, t("general.auth.sessionError.message"), true)}
      />
    );
  }

  if (!user?.appAuthenticated) {
    return <LoginRequiredPage message={t("general.auth.loginRequired.message")} />;
  }

  const missingPermissions = requiredPermissions.filter(
    (permission) => user.permissions?.[permission] !== true,
  );

  if (missingPermissions.length > 0) {
    return (
      <UserDeniedPage missingPermissions={missingPermissions} user={user} />
    );
  }

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

export interface LicenseGuardProps {
  requiredLicense?: RequiredLicense;
  LicenseRequiredPage: ComponentType<LicenseRequiredPageProps>;
  SessionErrorPage: ComponentType<SessionErrorPageProps>;
  children: ReactNode;
}

export function LicenseGuard({
  requiredLicense,
  LicenseRequiredPage,
  SessionErrorPage,
  children,
}: LicenseGuardProps) {
  const licenseQuery = useLicenseAccess({
    skip:
      import.meta.env.DEV ||
      requiredLicense === undefined ||
      requiredLicense === false,
  });

  if (
    import.meta.env.DEV ||
    requiredLicense === undefined ||
    requiredLicense === false
  ) {
    return <>{children}</>;
  }

  if (licenseQuery.isLoading) {
    return (
      <div className="flex min-h-full items-center justify-center text-sm text-muted-foreground">
        Loading license status...
      </div>
    );
  }

  if (licenseQuery.isError) {
    return (
      <SessionErrorPage
        message={getApiErrorMessage(
          licenseQuery.error,
          "Unable to load module license status.",
          true,
        )}
      />
    );
  }

  if (!meetsLicenseRequirement(licenseQuery.data, requiredLicense)) {
    return (
      <LicenseRequiredPage
        status={licenseQuery.data}
        requiredLicense={requiredLicense}
        missingFeatures={missingLicenseFeatures(
          licenseQuery.data,
          requiredLicense,
        )}
      />
    );
  }

  return <>{children}</>;
}
