import { afterEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import { createPortalChatBIQuery, fetchPortalChatBIFederatedRun } from './portal';

vi.mock('../request', () => ({ request: vi.fn() }));

afterEach(() => vi.mocked(request).mockReset());

describe('ChatBI federated query API', () => {
  it('keeps the primary dataset and selected source IDs in the durable request', () => {
    createPortalChatBIQuery({
      dataset_id: '101',
      dataset_ids: ['101', '202'],
      question: '比较两个区域的数据趋势',
      conversation_id: '55'
    });

    expect(request).toHaveBeenCalledWith({
      url: '/api/portal/chatbi/queries',
      method: 'post',
      data: {
        dataset_id: '101',
        dataset_ids: ['101', '202'],
        question: '比较两个区域的数据趋势',
        conversation_id: '55'
      }
    });
  });

  it('loads an owner-scoped federated run using the opaque run key', () => {
    fetchPortalChatBIFederatedRun('fed_run_abc');

    expect(request).toHaveBeenCalledWith({
      url: '/api/portal/chatbi/federated-runs/fed_run_abc',
      method: 'get'
    });
  });
});
