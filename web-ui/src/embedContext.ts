export type EmbedMode = "standalone" | "perspective";
export interface PerspectiveSessionContext {
  sessionId?: string;
  projectName?: string;
}

let apiBase: string = "";
let mode: EmbedMode = "standalone";
let perspectiveSessionContext: PerspectiveSessionContext = {};

export function setApiBase(next: string) {
  apiBase = next;
}
export function getApiBase(): string {
  return apiBase;
}

export function setEmbedMode(next: EmbedMode) {
  mode = next;
}
export function getEmbedMode(): EmbedMode {
  return mode;
}
export function isPerspectiveEmbed(): boolean {
  return mode === "perspective";
}

export function setPerspectiveSessionContext(
  context: PerspectiveSessionContext,
) {
  perspectiveSessionContext = context;
}
export function getPerspectiveSessionContext(): PerspectiveSessionContext {
  return perspectiveSessionContext;
}
