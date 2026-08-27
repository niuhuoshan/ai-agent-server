import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import { cancelConversationGlobally, finalizeNhsConversation } from './platform';

vi.mock('../request', () => ({ request: vi.fn() }));

describe('Nhs V1 conversation control API contract', () => {
  beforeEach(() => {
    vi.mocked(request).mockClear();
  });

  it('uses the global cancellation route with the active trace', () => {
    cancelConversationGlobally('7', 'trace-7', 'stop now');

    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/chat/cancel',
      method: 'post',
      data: {
        conversation_id: '7',
        trace_id: 'trace-7',
        reason: 'stop now'
      }
    });
  });

  it('omits optional cancellation fields instead of sending null values', () => {
    cancelConversationGlobally('8');

    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/chat/cancel',
      method: 'post',
      data: { conversation_id: '8' }
    });
  });

  it('finalizes one encoded private conversation', () => {
    finalizeNhsConversation('conversation/9');

    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/chat/conversation/conversation%2F9/finalize',
      method: 'post'
    });
  });
});
