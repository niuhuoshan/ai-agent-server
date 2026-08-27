import { afterEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import {
  fetchAutomationTrigger,
  fetchAutomationTriggers,
  updateAutomationTrigger
} from './platform';
import type { UpdateAutomationTriggerPayload } from './platform';

vi.mock('../request', () => ({ request: vi.fn() }));

afterEach(() => {
  vi.mocked(request).mockReset();
});

describe('automation trigger API', () => {
  it('uses list and detail routes', () => {
    fetchAutomationTriggers('paused');
    fetchAutomationTrigger('42');

    expect(request).toHaveBeenNthCalledWith(1, {
      url: '/platform/automation/triggers',
      method: 'get',
      params: { status: 'paused', limit: 200 }
    });
    expect(request).toHaveBeenNthCalledWith(2, {
      url: '/platform/automation/triggers/42',
      method: 'get'
    });
  });

  it('updates the full trigger configuration with its optimistic revision', () => {
    const payload: UpdateAutomationTriggerPayload = {
      name: '日报',
      taskId: '10',
      taskVersionId: '11',
      serviceAccountId: '20',
      cronExpression: '0 0 9 * * ?',
      timezone: 'Asia/Shanghai',
      misfirePolicy: 'fire_once',
      maxCatchupCount: 1,
      maxAttempts: 3,
      inputTemplate: '生成日报',
      status: 'paused',
      revisionNo: '7',
      config: {}
    };

    updateAutomationTrigger('42', payload);

    expect(request).toHaveBeenCalledWith({
      url: '/platform/automation/triggers/42',
      method: 'put',
      data: payload
    });
  });
});
