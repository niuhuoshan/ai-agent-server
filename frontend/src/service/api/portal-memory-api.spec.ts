import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import {
  fetchPortalMemoryConfig,
  fetchPortalMemoryIndexStatus,
  testPortalMemoryEmbedding,
  testPortalMemoryRedisVector,
  testPortalMemorySearch,
  updatePortalMemoryConfig,
  verifyPortalMemoryIndex
} from './portal';

vi.mock('../request', () => ({ request: vi.fn() }));

describe('portal memory operations API contract', () => {
  beforeEach(() => {
    vi.mocked(request).mockClear();
  });

  it('loads and updates the persisted search configuration', () => {
    fetchPortalMemoryConfig();
    updatePortalMemoryConfig(80);

    expect(request).toHaveBeenNthCalledWith(1, {
      url: '/api/portal/memory/configs',
      method: 'get'
    });
    expect(request).toHaveBeenNthCalledWith(2, {
      url: '/api/portal/memory/configs',
      method: 'put',
      data: { default_search_limit: 80 }
    });
  });

  it('uses the owner-scoped search and index maintenance endpoints', () => {
    testPortalMemorySearch('审批偏好', 20);
    fetchPortalMemoryIndexStatus();
    verifyPortalMemoryIndex();

    expect(request).toHaveBeenNthCalledWith(1, {
      url: '/api/portal/memory/search-test',
      method: 'post',
      data: { query: '审批偏好', limit: 20 }
    });
    expect(request).toHaveBeenNthCalledWith(2, {
      url: '/api/portal/memory/index/status',
      method: 'get'
    });
    expect(request).toHaveBeenNthCalledWith(3, {
      url: '/api/portal/memory/index/rebuild',
      method: 'post'
    });
  });

  it('keeps Redis Vector and Embedding diagnostics as distinct provider probes', () => {
    testPortalMemoryRedisVector();
    testPortalMemoryEmbedding();

    expect(request).toHaveBeenNthCalledWith(1, {
      url: '/api/portal/memory/redis-vector-test',
      method: 'get'
    });
    expect(request).toHaveBeenNthCalledWith(2, {
      url: '/api/portal/memory/test-embedding',
      method: 'post'
    });
  });
});
