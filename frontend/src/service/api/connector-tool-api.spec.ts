import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import {
  deleteTool,
  fetchBuiltinTools,
  fetchConnectorTools,
  testConnector,
  testConnectorDraft,
  testTool
} from './platform';

vi.mock('../request', () => ({ request: vi.fn() }));

describe('connector and tool test API', () => {
  beforeEach(() => {
    vi.mocked(request).mockClear();
  });

  it('starts a real MCP connector handshake', () => {
    testConnector('41');

    expect(request).toHaveBeenCalledWith({
      url: '/platform/connectors/41/test',
      method: 'post'
    });
  });

  it('tests unsaved MCP settings and keeps the credential as an environment reference', () => {
    testConnectorDraft({
      name: 'Reports',
      endpointUrl: 'https://mcp.example/rpc',
      credentialRef: 'env:MCP_REPORTS_TOKEN',
      config: { transport: 'streamable_http', authType: 'bearer' }
    });

    expect(request).toHaveBeenCalledWith({
      url: '/platform/connectors/mcp/test',
      method: 'post',
      data: {
        name: 'Reports',
        endpointUrl: 'https://mcp.example/rpc',
        credentialRef: 'env:MCP_REPORTS_TOKEN',
        config: { transport: 'streamable_http', authType: 'bearer' }
      }
    });
  });

  it('loads the complete MCP tool catalog for one server', () => {
    fetchConnectorTools('41');

    expect(request).toHaveBeenCalledWith({
      url: '/platform/tools',
      method: 'get',
      params: { toolType: 'mcp', connectorId: '41', includeInactive: true, limit: 500 }
    });
  });

  it('preserves arguments and explicit risk confirmation for an online tool test', () => {
    testTool('73', {
      arguments: { query: 'quarterly revenue', limit: 10 },
      confirmRisk: true
    });

    expect(request).toHaveBeenCalledWith({
      url: '/platform/tools/73/test',
      method: 'post',
      data: {
        arguments: { query: 'quarterly revenue', limit: 10 },
        confirmRisk: true
      }
    });
  });

  it('uses the protected backend delete endpoint', () => {
    deleteTool('73');

    expect(request).toHaveBeenCalledWith({
      url: '/platform/tools/73',
      method: 'delete'
    });
  });

  it('loads the backend built-in executor catalog for the resource center', () => {
    fetchBuiltinTools();

    expect(request).toHaveBeenCalledWith({
      url: '/platform/tools/builtins',
      method: 'get'
    });
  });
});
