import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import { fetchConnectorRuntime, fetchMcpConnectorUsage } from './platform';

vi.mock('../request', () => ({ request: vi.fn() }));

describe('MCP runtime observation API', () => {
  beforeEach(() => {
    vi.mocked(request).mockClear();
  });

  it('loads server-produced health, mounts and usage in one bounded request', () => {
    fetchConnectorRuntime('41');

    expect(request).toHaveBeenCalledWith({
      url: '/platform/connectors/41/runtime',
      method: 'get',
      params: { mountLimit: 20, usageLimit: 50 }
    });
  });

  it('loads Nhs-compatible configured Agent binding usage', () => {
    fetchMcpConnectorUsage('41');

    expect(request).toHaveBeenCalledWith({
      url: '/platform/connectors/41/usage',
      method: 'get'
    });
  });
});
