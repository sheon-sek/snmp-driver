import type {
  ApiErrorResponse,
  ApiResponse,
  LicenseAccessSnapshot,
  LicenseStatus,
} from "@/lib/types";

export function createLicenseManagementClient(apiBase: string) {
  const endpoints = {
    status: apiUrl(apiBase, "/license/status"),
    machineRequest: apiUrl(apiBase, "/license/machine-request"),
    upload: apiUrl(apiBase, "/license/upload"),
    revalidate: apiUrl(apiBase, "/license/revalidate"),
  };

  return {
    machineRequestUrl: endpoints.machineRequest,

    status() {
      return request<LicenseStatus>(endpoints.status);
    },

    revalidate() {
      return request<LicenseStatus>(endpoints.revalidate, { method: "POST" });
    },

    upload(file: File) {
      const formData = new FormData();
      formData.append("file", file);
      return request<LicenseStatus>(endpoints.upload, {
        method: "POST",
        body: formData,
      });
    },
  };
}

export function fetchLicenseAccess(
  apiBase: string,
  signal?: AbortSignal,
) {
  return request<LicenseAccessSnapshot>(apiUrl(apiBase, "/license/access"), {
    signal,
  });
}

function apiUrl(apiBase: string, path: string) {
  return new URL(
    `${apiBase.replace(/\/+$/, "")}/${path.replace(/^\/+/, "")}`,
    window.location.href,
  );
}

async function request<T>(url: URL, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers ?? {});
  if ((options.method ?? "GET").toUpperCase() !== "GET") {
    const token = await csrf();
    if (token) headers.set("X-CSRF-Token", token);
  }

  const response = await fetch(url, {
    credentials: "include",
    ...options,
    headers,
  });
  const contentType = response.headers.get("content-type") ?? "";
  const body: unknown = contentType.includes("application/json")
    ? await response.json()
    : await response.text();

  if (!response.ok) {
    const apiError = body as Partial<ApiErrorResponse>;
    const message =
      apiError.error?.message ||
      (body && typeof body === "object" && "message" in body
        ? String(body.message)
        : "") ||
      response.statusText ||
      "Request failed.";
    throw new Error(message);
  }

  const wrapped = body as Partial<ApiResponse<T>>;
  return wrapped.success === true && "data" in wrapped
    ? (wrapped.data as T)
    : (body as T);
}

async function csrf() {
  const response = await fetch(new URL("/data/app/session", window.location.origin), {
    credentials: "include",
  });
  if (!response.ok) return null;
  const payload = (await response.json()) as {
    csrfToken?: string;
    data?: { csrfToken?: string };
  };
  return payload.csrfToken || payload.data?.csrfToken || null;
}
