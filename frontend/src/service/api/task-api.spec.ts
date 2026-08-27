import { afterEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import { fetchTaskVersion, fetchTaskVersions, updateTask } from './platform';

vi.mock('../request', () => ({ request: vi.fn() }));

afterEach(() => vi.mocked(request).mockReset());

describe('shared task definition API', () => {
  it('loads immutable version history and an individual snapshot', () => {
    fetchTaskVersions('42', 20);
    fetchTaskVersion('42', '7');

    expect(request).toHaveBeenNthCalledWith(1, {
      url: '/platform/tasks/42/versions',
      method: 'get',
      params: { limit: 20 }
    });
    expect(request).toHaveBeenNthCalledWith(2, {
      url: '/platform/tasks/42/versions/7',
      method: 'get'
    });
  });

  it('replaces the task definition through the versioned update endpoint', () => {
    const payload = {
      title: '交付任务',
      objective: '完成目标',
      agentVersionId: '11',
      visibility: 'enterprise_shared' as const,
      category: 'general' as const,
      orchestrationMode: 'single_agent' as const,
      lifecycleLevel: 'L1_short_task' as const,
      riskLevel: 'R1' as const,
      acceptanceMode: 'human' as const,
      importance: 0,
      urgency: 0,
      contextSnapshot: {},
      resources: [],
      acceptanceSnapshot: {},
      inputSnapshot: {},
      budget: {},
      externalRefs: {},
      tags: []
    };

    updateTask('42', payload);

    expect(request).toHaveBeenCalledWith({
      url: '/platform/tasks/42',
      method: 'put',
      data: payload
    });
  });
});
