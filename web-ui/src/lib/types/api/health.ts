export type UseHealthOptions = {
  autoRefresh?: boolean;
  pollingInterval?: number;
};

export interface HealthResponse {
  status: string;
  module: string;
  version: string;
}
