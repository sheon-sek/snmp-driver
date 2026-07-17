import { useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import type { LucideIcon } from "lucide-react";
import {
  Boxes,
  Braces,
  CheckCircle2,
  ChevronRight,
  Code2,
  Cpu,
  Database,
  FileCode2,
  Gauge,
  Layers3,
  LockKeyhole,
  MonitorCog,
  PanelTop,
  Play,
  RefreshCw,
  Route,
  ShieldCheck,
  Sparkles,
  SquareTerminal,
  UserRound,
} from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  CardFooter
} from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { getApiErrorMessage } from "@/lib/api";
import { useCurrentUser } from "../../query/auth";
import { useHealth } from "../../query/health";
import { cn } from "@/lib/utils";
import { useApiRuntime } from "@/embedContext";
import { Separator } from "@/components/ui/separator";

type FeatureItem = {
  title: string;
  description: string;
  icon: LucideIcon;
};

type GuideItem = {
  title: string;
  description: string;
  command?: string;
  icon: LucideIcon;
};

type MechanismStep = {
  title: string;
  description: string;
  icon: LucideIcon;
};

function StatusDot({
  state,
}: {
  state: "loading" | "success" | "error" | "idle";
}) {
  return (
    <span
      className={cn(
        "h-2.5 w-2.5 rounded-full",
        state === "loading" && "bg-status-processing",
        state === "success" && "bg-status-completed",
        state === "error" && "bg-status-failed",
        state === "idle" && "bg-status-pending",
      )}
    />
  );
}

function MetricTile({
  icon: Icon,
  label,
  value,
  description,
}: {
  icon: LucideIcon;
  label: string;
  value: ReactNode;
  description: string;
}) {
  return (
    <div className="rounded-lg border bg-background p-4">
      <div className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
        <Icon className="h-4 w-4" />
        {label}
      </div>
      <div className="mt-3 text-2xl font-semibold tracking-normal text-foreground">
        {value}
      </div>
      <div className="mt-1 text-xs leading-5 text-muted-foreground">
        {description}
      </div>
    </div>
  );
}

function CodeBlock({ code }: { code: string }) {
  return (
    <pre className="mt-3 overflow-x-auto rounded-lg border bg-muted/50 p-3 text-xs leading-5 text-muted-foreground">
      <code>{code}</code>
    </pre>
  );
}

export default function HomePage() {
  const [autoRefresh, setAutoRefresh] = useState(false);
  const { data, refetch, isLoading, isSuccess, isError, error } = useHealth({
    autoRefresh,
  });
  const currentUser = useCurrentUser();
  const permissionCount = currentUser.data
    ? Object.values(currentUser.data.permissions).filter(Boolean).length
    : 0;
  const { t } = useTranslation();
  const { embedMode } = useApiRuntime();
  const renderMode = embedMode === "perspective"
    ? t("homePage.renderMode.perspective")
    : t("homePage.renderMode.gateway");

  const mechanismSteps: MechanismStep[] = [
    {
      title: t("homePage.mechanism.gatewayHook.title"),
      description: t("homePage.mechanism.gatewayHook.description"),
      icon: Cpu,
    },
    {
      title: t("homePage.mechanism.apiRoutes.title"),
      description: t("homePage.mechanism.apiRoutes.description"),
      icon: Route,
    },
    {
      title: t("homePage.mechanism.reactShell.title"),
      description: t("homePage.mechanism.reactShell.description"),
      icon: PanelTop,
    },
    {
      title: t("homePage.mechanism.perspectiveEmbed.title"),
      description: t("homePage.mechanism.perspectiveEmbed.description"),
      icon: Layers3,
    },
  ];

  const guideItems: GuideItem[] = [
    {
      title: t("homePage.guides.moduleBuild.title"),
      description: t("homePage.guides.moduleBuild.description"),
      command: ".\\gradlew.bat build",
      icon: SquareTerminal,
    },
    {
      title: t("homePage.guides.webUi.title"),
      description: t("homePage.guides.webUi.description"),
      command: "yarn --cwd web-ui run dev",
      icon: Play,
    },
    {
      title: t("homePage.guides.securedPage.title"),
      description: t("homePage.guides.securedPage.description"),
      icon: LockKeyhole,
    },
  ];

  const featureItems: FeatureItem[] = [
    {
      title: t("homePage.features.gatewayFoundation.title"),
      description: t("homePage.features.gatewayFoundation.description"),
      icon: MonitorCog,
    },
    {
      title: t("homePage.features.perspectivePath.title"),
      description: t("homePage.features.perspectivePath.description"),
      icon: Boxes,
    },
    {
      title: t("homePage.features.authPermissions.title"),
      description: t("homePage.features.authPermissions.description"),
      icon: ShieldCheck,
    },
    {
      title: t("homePage.features.apiStarter.title"),
      description: t("homePage.features.apiStarter.description"),
      icon: Braces,
    },
  ];

  const gatewayState = isLoading
    ? "loading"
    : isSuccess
      ? "success"
      : isError
        ? "error"
        : "idle";

  return (
    <main className="min-h-full bg-background">
      <section className="">
        <div className="mx-auto grid w-full max-w-7xl grid-cols-1 gap-8 px-6 py-8 lg:grid-cols-[minmax(0,1fr)_420px] lg:px-8">
          <div className="flex min-w-0 flex-col justify-center">
            <div className="flex flex-wrap items-center gap-2">
              <Badge variant="secondary" className="gap-1.5">
                <Sparkles className="h-3.5 w-3.5" />
                {t("homePage.badges.starter")}
              </Badge>
              <Badge variant="outline">
                {t("homePage.badges.renderPath", { mode: renderMode })}
              </Badge>
            </div>

            <h1 className="mt-5 max-w-4xl text-4xl font-semibold tracking-normal text-foreground md:text-5xl">
              {t("homePage.title")}
            </h1>
            <p className="mt-4 max-w-3xl text-base leading-7 text-muted-foreground md:text-lg">
              {t("homePage.description")}
            </p>

            <div className="mt-6 grid grid-cols-1 gap-3 sm:grid-cols-3">
              <MetricTile
                icon={Gauge}
                label={t("homePage.metrics.gateway.label")}
                value={
                  <span className="flex items-center gap-2 text-xl">
                    <StatusDot state={gatewayState} />
                    {isSuccess
                      ? t("homePage.metrics.gateway.online")
                      : isError
                        ? t("homePage.metrics.gateway.offline")
                        : t("homePage.status.checking")}
                  </span>
                }
                description={
                  isSuccess
                    ? t("homePage.metrics.gateway.version", {
                        version: data?.version ?? t("homePage.common.unknown"),
                      })
                    : isError
                      ? getApiErrorMessage(
                          error,
                          t("homePage.errors.healthCheckFailed"),
                          true,
                        )
                      : t("homePage.metrics.gateway.checking")
                }
              />
              <MetricTile
                icon={UserRound}
                label={t("homePage.metrics.actor.label")}
                value={currentUser.data?.username ?? t("homePage.metrics.actor.fallback")}
                description={
                  currentUser.data
                    ? t("homePage.metrics.actor.loaded", {
                        source: currentUser.data.source,
                        count: currentUser.data.roles.length,
                      })
                    : currentUser.isError
                      ? t("homePage.metrics.actor.unavailable")
                      : t("homePage.metrics.actor.waiting")
                }
              />
              <MetricTile
                icon={ShieldCheck}
                label={t("homePage.metrics.permissions.label")}
                value={permissionCount}
                description={t("homePage.metrics.permissions.description")}
              />
            </div>
          </div>

          <Card className="self-start h-full border-border/80 shadow-sm flex flex-col">
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <Database className="h-4 w-4" />
                {t("homePage.liveContract.title")}
              </CardTitle>
              <CardDescription>
                {t("homePage.liveContract.description")}
              </CardDescription>
            </CardHeader>

            <CardContent className="space-y-4 grow">
              <div className="rounded-lg border bg-muted/30 p-4">
                <div className="flex items-center justify-between gap-3">
                  <div className="flex items-center gap-2 text-sm font-medium">
                    <StatusDot state={gatewayState} />
                    {t("homePage.liveContract.healthRoute")}
                  </div>
                  <Badge
                    variant={isError ? "destructive" : "outline"}
                    className="shrink-0"
                  >
                    {isSuccess
                      ? t("homePage.liveContract.connected")
                      : isError
                        ? t("homePage.liveContract.failed")
                        : t("homePage.liveContract.checking")}
                  </Badge>
                </div>
                <CodeBlock
                  code={
                    isSuccess
                      ? JSON.stringify(data, null, 2)
                      : isError
                        ? getApiErrorMessage(
                            error,
                            t("homePage.errors.healthCheckFailed"),
                            true,
                          )
                        : "{\n  \"status\": \"checking\"\n}"
                  }
                />
              </div>
            </CardContent>

            <CardFooter>
              <div className="w-full flex flex-wrap items-center justify-between gap-3">
                <Button
                  type="button"
                  size="sm"
                  onClick={() => void refetch()}
                  className="gap-2"
                  variant="outline"
                >
                  <RefreshCw className="h-4 w-4" />
                  {t("homePage.actions.manualFetch")}
                </Button>
                <div className="flex items-center gap-2">
                  <Switch
                    id="auto-refresh"
                    checked={autoRefresh}
                    onCheckedChange={setAutoRefresh}
                  />
                  <Label htmlFor="auto-refresh" className="text-sm">
                    {t("homePage.actions.autoRefresh")}
                  </Label>
                </div>
              </div>
            </CardFooter>
          </Card>
        </div>
      </section>

      <Separator className="my-4 h-0"/>

      <section className="mx-auto w-full max-w-7xl px-6 py-8 lg:px-8">
        <div className="grid gap-8 lg:grid-cols-[280px_minmax(0,1fr)]">
          <div>
            <div className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
              <Code2 className="h-4 w-4" />
              {t("homePage.mechanism.eyebrow")}
            </div>
            <h2 className="mt-2 text-2xl font-semibold tracking-normal">
              {t("homePage.mechanism.title")}
            </h2>
            <p className="mt-3 text-sm leading-6 text-muted-foreground">
              {t("homePage.mechanism.description")}
            </p>
          </div>

          <div className="grid grid-cols-1 gap-3 md:grid-cols-4">
            {mechanismSteps.map((step, index) => (
              <div
                key={step.title}
                className="relative rounded-lg border bg-background p-4"
              >
                {index < mechanismSteps.length - 1 ? (
                  <ChevronRight className="absolute right-3 top-4 hidden h-4 w-4 text-muted-foreground md:block" />
                ) : null}
                <step.icon className="h-5 w-5 text-foreground" />
                <h3 className="mt-4 pr-5 text-sm font-semibold">
                  {step.title}
                </h3>
                <p className="mt-2 text-xs leading-5 text-muted-foreground">
                  {step.description}
                </p>
              </div>
            ))}
          </div>
        </div>
      </section>

      <Separator className="my-4 h-0"/>

      <section className="">
        <div className="mx-auto w-full max-w-7xl px-6 py-8 lg:px-8">
          <div className="flex flex-col gap-2 md:flex-row md:items-end md:justify-between">
            <div>
              <div className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
                <FileCode2 className="h-4 w-4" />
                {t("homePage.guides.eyebrow")}
              </div>
              <h2 className="mt-2 text-2xl font-semibold tracking-normal">
                {t("homePage.guides.title")}
              </h2>
            </div>
            <p className="max-w-2xl text-sm leading-6 text-muted-foreground">
              {t("homePage.guides.description")}
            </p>
          </div>

          <div className="mt-5 grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
            {guideItems.map((item, index) => (
              <Card key={item.title} className="border-border/80 shadow-sm">
                <CardHeader>
                  <div className="flex items-center justify-between gap-3">
                    <div className="flex h-9 w-9 items-center justify-center rounded-lg border bg-background">
                      <item.icon className="h-4 w-4" />
                    </div>
                    <Badge variant="outline">0{index + 1}</Badge>
                  </div>
                  <CardTitle className="text-base">{item.title}</CardTitle>
                  <CardDescription>{item.description}</CardDescription>
                </CardHeader>
                {item.command ? (
                  <CardContent>
                    <CodeBlock code={item.command} />
                  </CardContent>
                ) : null}
              </Card>
            ))}
          </div>
        </div>
      </section>

      <Separator className="my-4 h-0"/>

      <section className="mx-auto w-full max-w-7xl px-6 py-8 lg:px-8">
        <div className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
          <CheckCircle2 className="h-4 w-4" />
          {t("homePage.features.eyebrow")}
        </div>
        <h2 className="mt-2 text-2xl font-semibold tracking-normal">
          {t("homePage.features.title")}
        </h2>

        <div className="mt-5 grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-4">
          {featureItems.map((item) => (
            <Card key={item.title} className="border-border/80 shadow-sm">
              <CardHeader>
                <item.icon className="h-5 w-5 text-foreground" />
                <CardTitle className="text-base">{item.title}</CardTitle>
                <CardDescription>{item.description}</CardDescription>
              </CardHeader>
            </Card>
          ))}
        </div>
      </section>
    </main>
  );
}
