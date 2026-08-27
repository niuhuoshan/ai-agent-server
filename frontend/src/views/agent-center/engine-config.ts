import type { AgentEngineType } from '@/service/api';

interface AgentEngineFormValue {
  engineType: AgentEngineType;
  maxIterations: number;
  timeoutSeconds: number;
  workspaceAccess: 'none' | 'read_only' | 'read_write';
  responseFormat: 'text' | 'json';
}

export function buildAgentEngineConfig(value: AgentEngineFormValue): Record<string, unknown> {
  return {
    maxIterations: value.maxIterations,
    timeoutSeconds: value.timeoutSeconds,
    workspaceAccess: value.workspaceAccess,
    responseFormat: value.responseFormat
  };
}

export function editableRuntimeConfig(value?: Record<string, unknown>) {
  const result = { ...value };
  delete result.modelSnapshot;
  delete result.engineType;
  delete result.engineConfigSnapshot;
  return result;
}
