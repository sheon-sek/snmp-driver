import { useEffect, useState } from "react";
import type { ReactNode } from "react";

import {
  LICENSE_ACCESS_POLLING_INTERVAL_MS,
  meetsLicenseRequirement,
  missingLicenseFeatures,
} from "@/lib/query/license";
import { fetchLicenseAccess } from "@/lib/api/licenseClient";
import type {
  LicenseAccessSnapshot,
  RequiredLicense,
} from "@/lib/types";

export interface StandaloneLicenseGuardProps {
  apiBase?: string;
  requiredLicense?: RequiredLicense;
  children: ReactNode;
}

export function StandaloneLicenseGuard({
  apiBase = import.meta.env.VITE_API_BASE_URL,
  requiredLicense = true,
  children,
}: StandaloneLicenseGuardProps) {
  const [status, setStatus] = useState<LicenseAccessSnapshot>();
  const [error, setError] = useState<string>();
  const enabled = requiredLicense !== false;

  useEffect(() => {
    if (import.meta.env.DEV || !enabled) return;

    const controller = new AbortController();
    const refresh = () => {
      fetchLicenseAccess(apiBase, controller.signal)
        .then((nextStatus) => {
          setStatus(nextStatus);
          setError(undefined);
        })
        .catch((requestError: unknown) => {
          if (
            requestError instanceof DOMException &&
            requestError.name === "AbortError"
          ) {
            return;
          }
          setError(
            requestError instanceof Error
              ? requestError.message
              : "Unable to load module license status.",
          );
        });
    };
    refresh();
    const polling = window.setInterval(
      refresh,
      LICENSE_ACCESS_POLLING_INTERVAL_MS,
    );

    return () => {
      window.clearInterval(polling);
      controller.abort();
    };
  }, [apiBase, enabled]);

  if (import.meta.env.DEV || !enabled) {
    return <>{children}</>;
  }

  if (error) {
    return <StandaloneMessage title="License status unavailable" message={error} />;
  }

  if (!status) {
    return <StandaloneMessage title="Loading" message="Reading license status..." />;
  }

  if (!meetsLicenseRequirement(status, requiredLicense)) {
    const missing = missingLicenseFeatures(status, requiredLicense);
    return (
      <StandaloneMessage
        title="License required"
        message={status.message || "A valid module license is required."}
        details={missing.length > 0 ? `Missing features: ${missing.join(", ")}` : undefined}
      />
    );
  }

  return <>{children}</>;
}

function StandaloneMessage({
  title,
  message,
  details,
}: {
  title: string;
  message: string;
  details?: string;
}) {
  return (
    <main
      style={{
        alignItems: "center",
        background: "#f6f7f9",
        color: "#17202a",
        display: "flex",
        fontFamily: "Arial, Helvetica, sans-serif",
        justifyContent: "center",
        minHeight: "100vh",
        padding: 24,
      }}
    >
      <section
        style={{
          background: "#fff",
          border: "1px solid #d8dde6",
          borderRadius: 8,
          maxWidth: 460,
          padding: 24,
          width: "100%",
        }}
      >
        <h1 style={{ fontSize: 22, margin: 0 }}>{title}</h1>
        <p style={{ color: "#667085", marginBottom: details ? 8 : 0 }}>{message}</p>
        {details ? <p style={{ color: "#b42318", margin: 0 }}>{details}</p> : null}
      </section>
    </main>
  );
}
