import { describe, expect, it } from 'vitest';
import { buildAgentEngineConfig, editableRuntimeConfig } from './engine-config';

describe('Agent engine configuration', () => {
  it('builds an AgentScope configuration without a connector', () => {
    expect(buildAgentEngineConfig({
      engineType: 'agentscope_java',
      maxIterations: 20,
      timeoutSeconds: 300,
      workspaceAccess: 'read_only',
      responseFormat: 'text'
    })).toEqual({
      maxIterations: 20,
      timeoutSeconds: 300,
      workspaceAccess: 'read_only',
      responseFormat: 'text'
    });
  });

  it('removes frozen snapshot fields before updating a version', () => {
    expect(editableRuntimeConfig({
      temperature: 0.4,
      modelSnapshot: { model: 'frozen' },
      engineConfigSnapshot: { connectorId: 1001 }
    })).toEqual({ temperature: 0.4 });
  });
});
