export interface ApiResponse<T> {
  success: boolean;
  data: T;
  meta?: any | PaginationMeta;
}

export interface ApiErrorResponse {
  status: number | string;
  success: false;
  error: {
    code: string;
    message: string;
    details?: unknown;
  };
  timestamp?: string;
  correlationId?: string;
}

export interface PaginationMeta {
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

export interface PaginationParams {
  page?: number;
  pageSize?: number;
}

export * from "./auth";
