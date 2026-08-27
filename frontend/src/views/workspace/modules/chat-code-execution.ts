export type ChatCodeExecutionStatus =
  | "connecting"
  | "queued"
  | "leased"
  | "running"
  | "reconnecting"
  | "succeeded"
  | "failed"
  | "cancelled"
  | "expired"
  | "timed_out";

export interface ChatCodeExecutionState {
  key: string;
  executionId: string | null;
  conversationId: string;
  language: string;
  code: string;
  status: ChatCodeExecutionStatus;
  stdout: string;
  stderr: string;
  outputLoaded: boolean;
  streamComplete: boolean;
  cursor: number;
  exitCode: number | null;
  elapsedMs: number | null;
  truncated: boolean;
  errorCode: string | null;
  errorMessage: string | null;
}

export const ACTIVE_CODE_EXECUTION_STATUSES = new Set<ChatCodeExecutionStatus>([
  "connecting",
  "queued",
  "leased",
  "running",
  "reconnecting",
]);

export function chatCodeExecutionKey(language: string, code: string) {
  const source = `${language.trim().toLowerCase()}\u0000${code}`;
  let hash = 2166136261;
  for (let index = 0; index < source.length; index += 1) {
    hash ^= source.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return (hash >>> 0).toString(36);
}
