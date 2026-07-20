import { createContext, useContext } from "react";

export interface ApiRuntimeContext {
  apiBase: string;
}

const ApiRuntimeReactContext = createContext<ApiRuntimeContext | null>(null);

export const ApiRuntimeProvider = ApiRuntimeReactContext.Provider;

export function useApiRuntime(): ApiRuntimeContext {
  const context = useContext(ApiRuntimeReactContext);
  if (!context) {
    throw new Error("ApiRuntimeProvider is missing");
  }
  return context;
}
