import type { ComponentType, ReactNode } from "react";
import { getApiErrorMessage, isApiErrorResponse } from "@/lib/api";
import { CurrentUserResponse } from "@/lib/types";
import { useCurrentUser } from "../../query/auth";
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

export interface RouteGuardProps {
  requiredPermissions?: string[];
  LoginRequiredPage: ComponentType<LoginRequiredPageProps>;
  SessionErrorPage: ComponentType<SessionErrorPageProps>;
  UserDeniedPage: ComponentType<UserDeniedPageProps>;
  children: ReactNode;
}

export function RouteGuard({
  requiredPermissions = [],
  LoginRequiredPage,
  SessionErrorPage,
  UserDeniedPage,
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

  return <>{children}</>;
}
