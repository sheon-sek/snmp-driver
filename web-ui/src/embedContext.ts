import { createContext, useContext } from "react";

export type EmbedMode = "standalone" | "perspective";
export interface PerspectiveSessionContext {
  sessionId?: string;
  projectName?: string;
}
export interface ApiRuntimeContext {
  apiBase: string;
  embedMode: EmbedMode;
  perspectiveSession?: PerspectiveSessionContext;
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
