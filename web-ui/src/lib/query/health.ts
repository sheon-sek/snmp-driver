import { HealthApi } from "../api/health";
import { UseHealthOptions } from "../types/api/health";

export function createUseHealth(healthApi: HealthApi) {
  const { useHealthQuery } = healthApi;

  function useHealth(options: UseHealthOptions = {}) {
    const { autoRefresh = true, pollingInterval = 5_000 } = options;

    return useHealthQuery(undefined, {
      pollingInterval: autoRefresh ? pollingInterval : 0,
      skipPollingIfUnfocused: true,
    });
  }

  return { useHealth };
}
