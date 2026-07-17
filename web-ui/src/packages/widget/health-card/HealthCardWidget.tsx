import { Activity, Server } from "lucide-react";

import { AppProvider } from "@/components/foundation/AppProvider";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { setApiBase } from "@/embedContext";
import { getApiErrorMessage } from "@/lib/api";
import { cn } from "@/lib/utils";
import { useHealth } from "./query/health";
import { healthCardStore } from "./store";

export interface HealthCardWidgetProps {
  apiBase?: string;
  title?: string;
}

export function HealthCardWidget({
  apiBase = import.meta.env.VITE_API_BASE_URL,
  title = "Ignition SDK Boilerplate Health",
}: HealthCardWidgetProps) {
  setApiBase(apiBase);

  return (
    <AppProvider store={healthCardStore} toasterProps={false}>
      <HealthCard title={title} />
    </AppProvider>
  );
}

function HealthCard({ title }: { title: string }) {
  const { data, isLoading, isError, error } = useHealth({
    autoRefresh: true,
    pollingInterval: 10_000,
  });

  const status = isLoading
    ? "checking"
    : isError
      ? "offline"
      : "online";

  return (
    <Card className="w-full max-w-sm border-border/80 bg-background shadow-sm">
      <CardHeader className="space-y-2">
        <div className="flex items-center justify-between gap-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <Server className="h-4 w-4" />
            {title}
          </CardTitle>
          <Badge variant={status === "offline" ? "destructive" : "outline"}>
            {status}
          </Badge>
        </div>
        <CardDescription>
          {isError
            ? getApiErrorMessage(error, "Health check failed", true)
            : "Gateway health endpoint"}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        <HealthRow
          label="Status"
          value={data?.status ?? status}
          active={status === "online"}
        />
        <HealthRow label="Module" value={data?.module ?? "---"} />
        <HealthRow label="Version" value={data?.version ?? "---"} />
      </CardContent>
    </Card>
  );
}

function HealthRow({
  label,
  value,
  active = false,
}: {
  label: string;
  value: string;
  active?: boolean;
}) {
  return (
    <div className="flex items-center justify-between gap-4">
      <span className="text-muted-foreground">{label}</span>
      <span className="flex min-w-0 items-center gap-2 font-medium">
        {active ? (
          <Activity className={cn("h-3.5 w-3.5 text-status-completed")} />
        ) : null}
        <span className="truncate">{value}</span>
      </span>
    </div>
  );
}

export default HealthCardWidget;
