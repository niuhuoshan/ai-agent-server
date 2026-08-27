import { describe, expect, it } from 'vitest';
import type { McpImportEntryView } from '@/service/api';
import { buildSafeMcpImportDocument } from './mcp-import';

function entry(overrides: Partial<McpImportEntryView> = {}): McpImportEntryView {
  return {
    sourceKey: 'reports',
    suggestedConnectorKey: 'mcp-reports',
    suggestedName: 'Reports',
    endpointUrl: 'https://mcp.example/rpc',
    transport: 'streamable_http',
    authType: 'bearer',
    authHeader: null,
    credentialRef: null,
    credentialRequired: true,
    importable: true,
    diagnostics: [],
    ...overrides
  };
}

describe('safe MCP import document', () => {
  it('rebuilds bearer authentication with an environment placeholder only', () => {
    const document = buildSafeMcpImportDocument(entry(), 'MCP_REPORTS_TOKEN');
    const serialized = JSON.stringify(document);

    expect(serialized).toContain('Bearer ${MCP_REPORTS_TOKEN}');
    expect(serialized).not.toContain('inline-secret');
  });

  it('preserves a custom header name without accepting a credential value', () => {
    const document = buildSafeMcpImportDocument(
      entry({ authType: 'header', authHeader: 'X-API-Key' }),
      'MCP_API_KEY'
    );

    expect(document).toEqual({
      mcpServers: {
        reports: {
          url: 'https://mcp.example/rpc',
          type: 'streamable_http',
          headers: { 'X-API-Key': '${MCP_API_KEY}' }
        }
      }
    });
  });

  it('rejects an invalid environment variable name', () => {
    expect(() => buildSafeMcpImportDocument(entry(), 'raw token')).toThrow('环境变量无效');
  });
});
