import { afterEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import { fetchExecutionHistory, fetchNhsV1ExecutionHistory, fetchTaskRun } from './platform';

vi.mock('../request', () => ({ request: vi.fn() }));

afterEach(() => {
  vi.mocked(request).mockReset();
});

describe('Nhs V1 execution history API', () => {
  it('requests the filtered, paginated execution-history projection', () => {
    const params = {
      page: 2,
      page_size: 20,
      status: 'failed',
      task_id: '42',
      q: 'timeout',
      start_at: '2026-08-01T00:00:00',
      end_at: '2026-08-18T23:59:59'
    };

    fetchExecutionHistory(params);

    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/tasks/execution-history',
      method: 'get',
      params
    });
  });

  it('keeps the explicit Nhs V1 alias on the same route', () => {
    fetchNhsV1ExecutionHistory({ page: 1, page_size: 10 });

    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/tasks/execution-history',
      method: 'get',
      params: { page: 1, page_size: 10 }
    });
  });

  it('supports opening a run that is outside the task list page', () => {
    fetchTaskRun('42', '9001');

    expect(request).toHaveBeenCalledWith({
      url: '/platform/tasks/42/runs/9001',
      method: 'get'
    });
  });
});
