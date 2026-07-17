import { useSyncExternalStore } from "react";

import { HealthApi } from "../api/health";
import { UseHealthOptions } from "../types/api/health";

export function createUseHealth(healthApi: HealthApi) {
  const { useHealthQuery } = healthApi;

  function useHealth(options: UseHealthOptions = {}) {
    const { autoRefresh = true, pollingInterval = 5_000 } = options;
    const pageVisible = useSyncExternalStore(
      autoRefresh ? subscribeToVisibility : subscribeNever,
      autoRefresh ? isPageVisible : alwaysVisible,
      alwaysVisible,
    );

    return useHealthQuery(undefined, {
      pollingInterval: autoRefresh && pageVisible ? pollingInterval : 0,
    });
  }

  return { useHealth };
}

function subscribeNever() {
  return () => undefined;
}

function alwaysVisible() {
  return true;
}

function subscribeToVisibility(onChange: () => void) {
  if (typeof document === "undefined") {
    return () => undefined;
  }
  document.addEventListener("visibilitychange", onChange);
  return () => document.removeEventListener("visibilitychange", onChange);
}

function isPageVisible() {
  return typeof document === "undefined" || document.visibilityState === "visible";
}
