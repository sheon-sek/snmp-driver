import { useHealthQuery } from "@/lib/api/health";

export type UseHealthOptions = {
  autoRefresh?: boolean;
  pollingInterval?: number;
};

export function useHealth(options: UseHealthOptions = {}) {
  const { autoRefresh = true, pollingInterval = 5_000 } = options;

  return useHealthQuery(undefined, {
    pollingInterval: autoRefresh ? pollingInterval : 0,
    skipPollingIfUnfocused: true,
  });
}
