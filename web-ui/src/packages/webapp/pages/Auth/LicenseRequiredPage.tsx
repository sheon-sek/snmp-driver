import { Link } from "react-router-dom";
import { Home, KeyRound } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import type { LicenseRequiredPageProps } from "../../components/auth/RouteGuard";

export default function LicenseRequiredPage({
  status,
  missingFeatures = [],
}: LicenseRequiredPageProps) {
  return (
    <div className="flex min-h-full w-full items-center justify-center p-6">
      <Card className="w-full max-w-md">
        <CardHeader className="space-y-2">
          <CardTitle className="flex flex-row items-center gap-3">
            <span className="flex h-10 w-10 items-center justify-center rounded-lg border bg-muted text-muted-foreground">
              <KeyRound className="h-5 w-5 stroke-status-failed" aria-hidden="true" />
            </span>
            <span>License required</span>
          </CardTitle>
          <CardDescription>
            This module or feature is not licensed.
          </CardDescription>
        </CardHeader>

        <CardContent className="space-y-4">
          <div className="rounded-md border bg-muted/40 p-3">
            <InfoRow label="Status" value={status?.code ?? "UNAVAILABLE"} />
            <InfoRow
              label="Message"
              value={status?.message || "A valid module license is required."}
            />
          </div>

          {missingFeatures.length > 0 ? (
            <div className="space-y-2 rounded-md border bg-muted/40 p-3 text-sm">
              <p className="font-medium text-foreground">Missing features</p>
              <ul className="list-disc space-y-1 pl-5">
                {missingFeatures.map((feature) => (
                  <li key={feature}>{feature}</li>
                ))}
              </ul>
            </div>
          ) : null}
        </CardContent>

        <CardFooter>
          <div className="flex w-full justify-center">
            <Button asChild size="sm" variant="ghost">
              <Link to="/">
                <Home className="h-4 w-4" aria-hidden="true" />
                Back to home
              </Link>
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
      <div className="max-w-[65%] truncate text-right text-sm font-medium">
        {value}
      </div>
    </div>
  );
}
