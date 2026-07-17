import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { LogIn, RefreshCw } from "lucide-react";
import { useTranslation } from "react-i18next";
import { LoginRequiredPageProps } from "../../components/auth/RouteGuard";

export default function LoginRequiredPage({
  message,
}: LoginRequiredPageProps) {
  const { t } = useTranslation();
  const displayMessage = message ?? t("general.auth.loginRequired.message");

  return (
    <div className="flex min-h-full w-full items-center justify-center p-6">
      <Card className="w-full max-w-md">
        <CardHeader className="space-y-2">
          <CardTitle className="flex flex-row items-center gap-3">
            <span className="flex h-10 w-10 items-center justify-center rounded-lg border bg-muted text-muted-foreground">
              <LogIn className="h-5 w-5" aria-hidden="true" />
            </span>
            <span>{t("general.auth.loginRequired.title")}</span>
          </CardTitle>

          <CardDescription>
            {t("general.auth.loginRequired.description")}
          </CardDescription>
        </CardHeader>

        <CardContent className="space-y-4">
          <div className="rounded-md border bg-muted/40 p-3">
            <InfoRow label={t("general.auth.fields.message")} value={displayMessage} />
          </div>
        </CardContent>

        <CardFooter>
          <div className="flex w-full justify-center">
            <Button onClick={() => window.location.reload()} size="sm" variant={"ghost"}>
              <RefreshCw className="h-4 w-4" aria-hidden="true" />
              {t("general.actions.refresh")}
            </Button>
          </div>
        </CardFooter>
      </Card>
    </div>
  );
}

function InfoRow({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="flex items-start justify-between gap-4 py-1">
      <div className="text-sm text-muted-foreground">{label}</div>
      <div className="max-w-[65%] truncate text-right text-sm font-medium">{value}</div>
    </div>
  );
}
