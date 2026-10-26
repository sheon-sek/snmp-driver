import {
  BaseQueryFn,
  createApi,
  FetchArgs,
  fetchBaseQuery,
  FetchBaseQueryError,
} from "@reduxjs/toolkit/query/react";
import type { ApiRuntimeContext } from "@/embedContext";
import { ApiErrorResponse, ApiResponse } from "../types";
import { SerializedError } from "@reduxjs/toolkit";

export interface CreateBaseApiOptions {
  reducerPath?: string;
  tagTypes?: string[];
}

export function createBaseApi({
  reducerPath = "api",
  tagTypes = [],
}: CreateBaseApiOptions = {}) {
  return createApi({
    reducerPath,
    baseQuery: dynamicQuery,
    tagTypes,
    endpoints: () => ({}),
  });
}

export type BaseApi = ReturnType<typeof createBaseApi>;

const rawBaseQuery = fetchBaseQuery({
  credentials: "include",
  prepareHeaders: async (headers, { arg, extra }) => {
    const runtime = runtimeContext(extra);
    if (isUnsafeRequest(arg)) {
      headers.set("x-csrf-token", await fetchCsrfToken(runtime.apiBase));
    }

    return headers;
  },
});

const dynamicQuery: BaseQueryFn<
  string | FetchArgs,
  unknown,
  ApiErrorResponse
> = async (args, api, extraOptions) => {
  const baseUrl = runtimeContext(api.extra).apiBase;

  const adjustedArgs =
    typeof args === "string"
      ? joinUrl(baseUrl, args)
      : {
          ...args,
          url: joinUrl(baseUrl, args.url),
        };

  const result = await rawBaseQuery(adjustedArgs, api, extraOptions);

  if (result.error) {
    return {
      error: normalizeApiErrorResponse(result.error),
    };
  }

  if (result.data && typeof result.data === "object") {
    const response = result.data as ApiResponse<unknown>;
    if (response.success === true && "data" in response) {
      return {
        success: response.success,
        data: response.data,
        meta: response.meta,
      };
    }
  }

  return { data: result.data, meta: result.meta };
};

function runtimeContext(value: unknown): ApiRuntimeContext {
  if (
    value &&
    typeof value === "object" &&
    "apiBase" in value &&
    typeof value.apiBase === "string"
  ) {
    return value as ApiRuntimeContext;
  }
  return { apiBase: "" };
}

function joinUrl(baseUrl: string, path: string) {
  const normalizedBase = baseUrl.replace(/\/+$/, "");
  const normalizedPath = path.replace(/^\/+/, "");

  return `${normalizedBase}/${normalizedPath}`;
}

function isUnsafeRequest(arg: string | FetchArgs) {
  const method = typeof arg === "string" ? "GET" : (arg.method ?? "GET");
  return !["GET", "HEAD", "OPTIONS"].includes(method.toUpperCase());
}

async function fetchCsrfToken(apiBase: string) {
  const apiUrl = new URL(apiBase || "/", window.location.href);
  const response = await fetch(new URL("/data/app/session", apiUrl), {
    credentials: "include",
    cache: "no-store",
  });

  if (!response.ok) {
    throw new Error(`Unable to load CSRF token: HTTP ${response.status}`);
  }

  const session = (await response.json()) as { csrfToken?: unknown };
  if (typeof session.csrfToken !== "string" || !session.csrfToken) {
    throw new Error("Unable to load CSRF token: missing csrfToken");
  }

  return session.csrfToken;
}

function normalizeApiErrorResponse(
  error: FetchBaseQueryError,
): ApiErrorResponse {
  if (
    typeof error.status === "number" &&
    error.data &&
    typeof error.data === "object"
  ) {
    const data = error.data as Partial<ApiErrorResponse>;

    if (data.success === false && data.error?.message) {
      return data as ApiErrorResponse;
    }
  }

  return {
    status: error.status,
    success: false,
    error: {
      code: String(error.status ?? "REQUEST_FAILED"),
      message:
        "error" in error && typeof error.error === "string"
          ? error.error
          : "REQUEST_FAILED",
      details: error,
    },
    timestamp: new Date().toISOString(),
  } as ApiErrorResponse;
}

export function isApiErrorResponse(error: unknown): error is ApiErrorResponse {
  return (
    !!error &&
    typeof error === "object" &&
    "success" in error &&
    error.success === false &&
    "error" in error &&
    !!error.error &&
    typeof error.error === "object" &&
    "message" in error.error &&
    typeof error.error.message === "string"
  );
}

export function getApiErrorMessage(
  error: ApiErrorResponse | SerializedError | undefined,
  fallback = "REQUEST_FAILED",
  includeStatus = false,
): string {
  if (isApiErrorResponse(error)) {
    const message =
      error.error.message === "REQUEST_FAILED" ? fallback : error.error.message;

    return (includeStatus ? `HTTP ${error.status}: ` : "") + message;
  }

  if (error?.message) {
    return error.message;
  }

  return fallback;
}
