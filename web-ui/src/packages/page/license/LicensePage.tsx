import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";

import { createLicenseManagementClient } from "@/lib/api/licenseClient";
import type { LicenseStatus } from "@/lib/types";

import styles from "./LicensePage.module.css";

interface PageMessage {
  message: string;
  type: "info" | "warning" | "error";
}

const MODULE_NAME = "Ignition SDK Boilerplate";

export interface LicensePageProps {
  apiBase?: string;
  moduleName?: string;
}

export function LicensePage({
  apiBase = import.meta.env.VITE_API_BASE_URL,
  moduleName = MODULE_NAME,
}: LicensePageProps) {
  const client = useMemo(
    () => createLicenseManagementClient(apiBase),
    [apiBase],
  );
  const [status, setStatus] = useState<LicenseStatus>();
  const [messages, setMessages] = useState<PageMessage[]>([]);
  const [selectedFile, setSelectedFile] = useState<File>();
  const [busy, setBusy] = useState(false);

  const renderStatus = useCallback((nextStatus: LicenseStatus) => {
    setStatus(nextStatus);
    setMessages([
      ...(nextStatus.errors ?? []).map((message) => ({
        message,
        type: "error" as const,
      })),
      ...(nextStatus.warnings ?? []).map((message) => ({
        message,
        type: "warning" as const,
      })),
    ]);
  }, []);

  const addMessage = useCallback((message: string, type: PageMessage["type"]) => {
    setMessages((current) => [...current, { message, type }]);
  }, []);

  const loadStatus = useCallback(async () => {
    setMessages([]);
    renderStatus(await client.status());
  }, [client, renderStatus]);

  useEffect(() => {
    loadStatus().catch((error: unknown) =>
      addMessage(errorMessage(error), "error"),
    );
  }, [addMessage, loadStatus]);

  async function revalidate() {
    setBusy(true);
    try {
      const nextStatus = await client.revalidate();
      renderStatus(nextStatus);
      addMessage("License revalidated.", "info");
    } finally {
      setBusy(false);
    }
  }

  async function upload(event: FormEvent) {
    event.preventDefault();
    if (!selectedFile) {
      addMessage("Choose a license file first.", "warning");
      return;
    }

    setBusy(true);
    try {
      const nextStatus = await client.upload(selectedFile);
      renderStatus(nextStatus);
      addMessage("License uploaded and activated.", "info");
    } finally {
      setBusy(false);
    }
  }

  const title = `${moduleName} License`;
  const ratio = Math.max(0, Math.min(100, Number(status?.matchRatio ?? 0) * 100));
  const identifierEntries = Object.entries(status?.identifierMatches ?? {});

  return (
    <div className={styles.page}>
      <main className={styles.main}>
        <header className={styles.header}>
          <div>
            <h1>{title}</h1>
            <p className={styles.subtitle}>Gateway module license management</p>
          </div>
          <div className={styles.actions}>
            <button
              type="button"
              disabled={busy}
              onClick={() =>
                loadStatus().catch((error: unknown) =>
                  addMessage(errorMessage(error), "error"),
                )
              }
            >
              Refresh
            </button>
            <button
              className={styles.primary}
              type="button"
              disabled={busy}
              onClick={() =>
                revalidate().catch((error: unknown) =>
                  addMessage(errorMessage(error), "error"),
                )
              }
            >
              Revalidate
            </button>
            <button
              type="button"
              disabled={busy}
              onClick={() => window.location.assign(client.machineRequestUrl.href)}
            >
              Download Machine Information File
            </button>
            <button
              className={styles.danger}
              type="button"
              onClick={backToIgnition}
            >
              Back to Ignition
            </button>
          </div>
        </header>

        <section className={styles.grid}>
          <div className={styles.panel}>
            <h2>License Status</h2>
            <div className={styles.statusRow}>
              <span className={`${styles.badge} ${statusClass(status)}`}>
                {status ? status.code || "UNKNOWN" : "Loading"}
              </span>
              <span className={styles.subtitle}>
                {status ? value(status.message) : "Reading license status..."}
              </span>
            </div>
            <dl>
              <dt>License ID</dt><dd>{value(status?.licenseId)}</dd>
              <dt>Client</dt><dd>{value(status?.client)}</dd>
              <dt>Module ID</dt><dd>{value(status?.moduleId)}</dd>
              <dt>Features</dt><dd>{status?.features?.join(", ") || "-"}</dd>
              <dt>Expires At</dt><dd>{status?.expiresAt || "Lifetime"}</dd>
              <dt>Verified At</dt><dd>{value(status?.verifiedAt)}</dd>
              <dt>License File</dt><dd>{value(status?.licensePath)}</dd>
              <dt>Matched Identifiers</dt>
              <dd>{matchText(status, ratio)}</dd>
            </dl>
            <div className={styles.score} aria-hidden="true">
              <div className={styles.scoreFill} style={{ width: `${ratio.toFixed(0)}%` }} />
            </div>
            <div className={styles.messages}>
              {messages.map((item, index) => (
                <div
                  className={`${styles.message} ${
                    item.type === "error"
                      ? styles.error
                      : item.type === "warning"
                        ? styles.warning
                        : ""
                  }`}
                  key={`${item.type}-${index}-${item.message}`}
                >
                  {item.message}
                </div>
              ))}
            </div>
          </div>

          <div className={styles.panel}>
            <h2>Upload License</h2>
            <form
              onSubmit={(event) =>
                upload(event).catch((error: unknown) =>
                  addMessage(errorMessage(error), "error"),
                )
              }
            >
              <div className={styles.fileRow}>
                <label className={styles.fileLabel} htmlFor="licenseFile">
                  Choose File
                </label>
                <input
                  id="licenseFile"
                  name="file"
                  type="file"
                  accept=".json,application/json"
                  onChange={(event) => setSelectedFile(event.target.files?.[0])}
                />
                <span className={styles.fileName}>
                  {selectedFile?.name ?? "No file selected"}
                </span>
              </div>
              <div className={`${styles.actions} ${styles.uploadActions}`}>
                <button className={styles.primary} type="submit" disabled={busy}>
                  Upload
                </button>
              </div>
            </form>
          </div>
        </section>

        <section className={`${styles.panel} ${styles.identifierPanel}`}>
          <h2>Identifier Match Detail</h2>
          <table>
            <thead>
              <tr>
                <th>Identifier</th>
                <th>Current Available</th>
                <th>Matched</th>
              </tr>
            </thead>
            <tbody>
              {identifierEntries.length === 0 ? (
                <tr><td colSpan={3}>No identifier data available.</td></tr>
              ) : (
                identifierEntries.map(([key, detail]) => (
                  <tr key={key}>
                    <td>{key}</td>
                    <td>{detail.currentAvailable ? "Yes" : "No"}</td>
                    <td>{detail.matched ? "Yes" : "No"}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </section>
      </main>
    </div>
  );
}

function statusClass(status: LicenseStatus | undefined) {
  if (!status) return "";
  if (status.licensed) return styles.good;
  return status.code === "UNVERIFIED" ? styles.warn : styles.bad;
}

function matchText(status: LicenseStatus | undefined, ratio: number) {
  if (!status?.licenseIdentifierCount) return "-";
  return `${status.matchedIdentifiers}/${status.licenseIdentifierCount} matched, ${status.requiredMatchedIdentifiers} required (${ratio.toFixed(1)}%)`;
}

function value(value: string | undefined) {
  return value || "-";
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : "Request failed.";
}

function backToIgnition() {
  const fallback = new URL("/web/home", window.location.origin).href;
  if (document.referrer) {
    try {
      const referrer = new URL(document.referrer);
      if (
        referrer.origin === window.location.origin &&
        referrer.href !== window.location.href
      ) {
        window.location.assign(referrer.href);
        return;
      }
    } catch {
      // Fall through to the Gateway home page.
    }
  }
  window.location.assign(fallback);
}

export default LicensePage;
