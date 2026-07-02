import { CodeSnippet } from "@/components/common/CodeSnippet";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { AlertTriangle, RefreshCw } from "lucide-react";
import { useTranslation } from "react-i18next";

interface SessionErrorPageProps {
  status?: string | number;
  message?: string;
  details?: string;
}

export default function SessionErrorPage({
  status,
  message,
  details,
}: SessionErrorPageProps) {
  const { t } = useTranslation();
  const detailsValue = details?.trim();
  const hasDetails = Boolean(detailsValue && detailsValue !== "-");
  const displayMessage = message ?? t("general.auth.sessionError.message");

  return (
    <div className="flex min-h-full w-full items-center justify-center p-6">
      <Card className="w-full max-w-md">
        <CardHeader className="space-y-2">
          <CardTitle className="flex flex-row items-center gap-3">
            <span className="flex h-10 w-10 items-center justify-center rounded-lg border bg-muted text-muted-foreground">
              <AlertTriangle className="h-5 w-5 stroke-status-failed" aria-hidden="true" />
            </span>
            <span>{t("general.auth.sessionError.title")}</span>
          </CardTitle>
          
          <CardDescription>
            {t("general.auth.sessionError.description")}
          </CardDescription>
        </CardHeader>

        <CardContent className="space-y-4">
          <div className="rounded-md border bg-muted/40 p-3">
            <InfoRow label={t("general.auth.fields.status")} value={status ?? t("general.common.unavailable")} />
            <InfoRow label={t("general.auth.fields.message")} value={displayMessage ?? t("general.common.unavailable")} />
          </div>

          {hasDetails && (
            <div className="flex flex-col gap-2">
              <h3 className="text-sm font-medium text-foreground">{t("general.auth.sessionError.responseDetails")}</h3>
              <CodeSnippet code={detailsValue ?? ""} language="json" />
            </div>
          )}
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
