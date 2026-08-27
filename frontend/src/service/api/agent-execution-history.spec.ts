import { afterEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import { fetchAgentExecutionHistory } from './platform';

vi.mock('../request', () => ({ request: vi.fn() }));

afterEach(() => {
  vi.mocked(request).mockReset();
});

describe('Agent execution history API', () => {
  it('uses the owner-authorized per-agent projection and bounded limit', () => {
    fetchAgentExecutionHistory('42', 80);

    expect(request).toHaveBeenCalledWith({
      url: '/platform/agents/42/executions',
      method: 'get',
      params: { limit: 80 }
    });
  });
});
