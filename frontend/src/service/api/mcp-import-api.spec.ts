import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import { fetchConnectors, importMcpServer, previewMcpServersImport } from './platform';

vi.mock('../request', () => ({ request: vi.fn() }));

describe('scoped connector and MCP import API', () => {
  beforeEach(() => {
    vi.mocked(request).mockClear();
  });

  it('forwards the explicit personal scope filter', () => {
    fetchConnectors(true, 'personal');

    expect(request).toHaveBeenCalledWith({
      url: '/platform/connectors',
      method: 'get',
      params: { includeInactive: true, scope: 'personal', limit: 200 }
    });
  });

  it('uses server-side parsing for pasted mcpServers documents', () => {
    const document = { mcpServers: { reports: { url: 'https://mcp.example/rpc' } } };
    previewMcpServersImport(document);

    expect(request).toHaveBeenCalledWith({
      url: '/platform/connectors/mcp/import/preview',
      method: 'post',
      data: { document }
    });
  });

  it('imports with an environment reference instead of an inline secret', () => {
    const document = {
      mcpServers: {
        reports: { url: 'https://mcp.example/rpc', headers: { Authorization: 'Bearer ${MCP_REPORTS_TOKEN}' } }
      }
    };
    importMcpServer({
      document,
      sourceKey: 'reports',
      connectorKey: 'mcp-reports',
      name: 'Reports',
      scope: 'personal',
      credentialRef: 'env:MCP_REPORTS_TOKEN',
      status: 'active'
    });

    expect(request).toHaveBeenCalledWith({
      url: '/platform/connectors/mcp/import',
      method: 'post',
      data: expect.objectContaining({
        sourceKey: 'reports',
        scope: 'personal',
        credentialRef: 'env:MCP_REPORTS_TOKEN'
      })
    });
  });
});
