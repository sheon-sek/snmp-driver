import { SidebarFooter, SidebarMenu, SidebarMenuItem, useSidebar } from "@/components/ui/sidebar";
import { HoverCard, HoverCardContent, HoverCardTrigger } from "../ui/hover-card";
import { Button } from "../ui/button";
import { Server, Activity } from "lucide-react";
import { cn } from "@/lib/utils";
import { useHealth } from "@/lib/query/health";
import type { HealthResponse } from "@/lib/types/api/health";
import { forwardRef, type ComponentPropsWithoutRef, type ReactNode } from "react";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "../ui/tooltip";
import { Separator } from "../ui/separator";
import { badgeVariants } from "../ui/badge";
import { getApiBase } from "@/embedContext";
import { useTranslation } from "react-i18next";

type HealthQueryStatus = "loading" | "connected" | "error";

interface HealthStatusPresentation {
    label: string;
    description: string;
    colorClassName: string;
    pulse: boolean;
}

const EMPTY_VALUE = "---";

function classifyConnectionStatus(
    data: HealthResponse | undefined,
    isLoading: boolean,
    isError: boolean,
): HealthQueryStatus {
    if (isError) return "error";
    if (isLoading) return "loading";
    if (!data) return "error";
    return "connected";
}

function formatErrorMessage(
    error: unknown,
    fallback: string,
) {
    if (!error) return undefined;
    if (typeof error === "string") return error;
    if (typeof error === "object" && "message" in error && typeof error.message === "string") {
        return error.message;
    }
    return fallback;
}

export function AppSidebarFooter() {
    const { state } = useSidebar();
    const { t } = useTranslation();
    const {
        data,
        isLoading,
        isError,
        error,
    } = useHealth();

    const healthQueryStatus = classifyConnectionStatus(data, isLoading, isError);
    const presentation = getStatusPresentation(healthQueryStatus, t);

    return (
        <SidebarFooter className={cn(state === "expanded" ? "p-4" : "p-2")}>
            <SidebarMenu>
                <SidebarMenuItem>
                    <div className="w-full flex justify-center">
                        <HealthHoverCard
                            apiBase={getApiBase()}
                            data={data}
                            errorMessage={formatErrorMessage(error, t("general.layout.footer.health.errorFallback"))}
                            presentation={presentation}
                        >
                            {state === "expanded"
                                ? <TextTrigger presentation={presentation}/>
                                : <IconButtonTrigger presentation={presentation}/>
                            }
                        </HealthHoverCard>
                    </div>
                </SidebarMenuItem>
            </SidebarMenu>
        </SidebarFooter>
    );
}

function HealthHoverCard({
    children,
    apiBase,
    data,
    errorMessage,
    presentation,
}: {
    children: ReactNode;
    apiBase: string;
    data?: HealthResponse;
    errorMessage?: string;
    presentation: HealthStatusPresentation;
}) {
    const { t } = useTranslation();
    const rows = [
        { label: t("general.layout.footer.health.rows.apiBaseUrl"), value: apiBase, badge: true },
        { label: t("general.layout.footer.health.rows.status"), value: data?.status ?? presentation.label },
        { label: t("general.layout.footer.health.rows.moduleName"), value: data?.module },
        { label: t("general.layout.footer.health.rows.moduleVersion"), value: data?.version },
        { label: t("general.layout.footer.health.rows.error"), value: errorMessage },
    ].filter(({ value }) => value);

    return (
        <HoverCard>
            <HoverCardTrigger asChild>{children}</HoverCardTrigger>
            <HoverCardContent side="right" align="end" className="z-50 w-72 p-4">
                <div>
                    <h4 className="flex items-center text-sm font-semibold">
                        <Activity className="mr-2 h-4 w-4"/>
                        {t("general.layout.footer.health.title")}
                    </h4>
                    <p className="text-sm text-muted-foreground">{presentation.description}</p>
                </div>

                <Separator className="my-2"/>

                <TooltipProvider>
                    {rows.map(({ label, value, badge }) => (
                        <StatusRow key={label} label={label}>
                            <DetailTooltip
                                content={value}
                                className={cn(
                                    "max-w-[9rem] text-xs",
                                    badge && badgeVariants({variant: "secondary"}),
                                )}
                            >
                                {value}
                            </DetailTooltip>
                        </StatusRow>
                    ))}
                </TooltipProvider>
            </HoverCardContent>
        </HoverCard>
    );
}

function getStatusPresentation(
    status: HealthQueryStatus,
    t: ReturnType<typeof useTranslation>["t"],
): HealthStatusPresentation {
    const statusPresentation: Record<HealthQueryStatus, HealthStatusPresentation> = {
        loading: {
            label: t("general.layout.footer.health.status.loading.label"),
            description: t("general.layout.footer.health.status.loading.description"),
            colorClassName: "bg-status-pending",
            pulse: true,
        },
        connected: {
            label: t("general.layout.footer.health.status.connected.label"),
            description: t("general.layout.footer.health.status.connected.description"),
            colorClassName: "bg-status-completed",
            pulse: false,
        },
        error: {
            label: t("general.layout.footer.health.status.error.label"),
            description: t("general.layout.footer.health.status.error.description"),
            colorClassName: "bg-status-failed",
            pulse: true,
        },
    };

    return statusPresentation[status];
}

interface TriggerProps extends ComponentPropsWithoutRef<"button"> {
    presentation: HealthStatusPresentation;
}

const TextTrigger = forwardRef<HTMLButtonElement, TriggerProps>(
    ({presentation, className, ...props}, ref) => (
        <Button
            ref={ref}
            type="button"
            size="sm"
            variant="ghost"
            className={cn("h-auto max-w-full cursor-help px-2 py-1", className)}
            aria-label={presentation.label}
            {...props}
        >
            <StatusDot presentation={presentation}/>
            <span className="text-sm text-muted-foreground">{presentation.label}</span>
        </Button>
    ),
);
TextTrigger.displayName = "TextTrigger";

const IconButtonTrigger = forwardRef<HTMLButtonElement, TriggerProps>(
    ({presentation, className, ...props}, ref) => (
        <Button
            ref={ref}
            type="button"
            size="icon"
            variant="ghost"
            className={cn("group relative", className)}
            aria-label={presentation.label}
            {...props}
        >
            <Server className="size-4"/>
            <span className="absolute right-[6px] top-[6px] flex h-2.5 w-2.5">
                <StatusDot
                    presentation={presentation}
                    className="h-2.5 w-2.5 border-2 border-sidebar"
                    withPulse
                />
            </span>
        </Button>
    ),
);
IconButtonTrigger.displayName = "IconButtonTrigger";
function StatusDot({
    presentation,
    className,
    withPulse = false,
}: {
    presentation: HealthStatusPresentation;
    className?: string;
    withPulse?: boolean;
}) {
    const dot = (
        <span
            className={cn(
                "relative inline-flex h-[0.35rem] w-[0.35rem] rounded-full",
                presentation.colorClassName,
                className,
            )}
        />
    );

    if (!withPulse || !presentation.pulse) return dot;

    return (
        <span className="relative flex h-2.5 w-2.5">
            <span className={cn("absolute inline-flex h-full w-full animate-ping rounded-full opacity-75", presentation.colorClassName)}/>
            {dot}
        </span>
    );
}

function StatusRow({label, children}: {label: string; children: ReactNode}) {
    return (
        <div className="flex items-center justify-between gap-4 py-1">
            <span className="shrink-0 text-xs font-medium text-muted-foreground">{label}</span>
            <div className="min-w-0 text-right">{children}</div>
        </div>
    );
}

function DetailTooltip({
    children,
    content,
    className,
}: {
    children: ReactNode;
    content?: ReactNode;
    className?: string;
}) {
    return (
        <Tooltip>
            <TooltipTrigger asChild>
                <span
                    className={cn(
                        className,
                        "block min-w-0 cursor-help overflow-hidden text-ellipsis whitespace-nowrap",
                    )}
                >
                    {children}
                </span>
            </TooltipTrigger>
            <TooltipContent
                side="top"
                align="center"
                className="pointer-events-none max-w-[16rem] whitespace-normal text-left"
            >
                {content ?? EMPTY_VALUE}
            </TooltipContent>
        </Tooltip>
    );
}
