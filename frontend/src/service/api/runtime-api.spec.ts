import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import {
  answerRuntimeUserQuestion,
  cancelRuntimeUserQuestion,
  fetchPendingRuntimeUserQuestions,
  fetchRuntimeUserQuestion,
} from './runtime';

vi.mock('../request', () => ({ request: vi.fn() }));

describe('runtime user question API contract', () => {
  beforeEach(() => vi.mocked(request).mockClear());

  it('keeps question reads owner-scoped and uses camel-case query params', () => {
    fetchRuntimeUserQuestion('question/1');
    fetchPendingRuntimeUserQuestions('42', 10);

    expect(request).toHaveBeenNthCalledWith(1, {
      url: '/platform/runtime-user-questions/question%2F1',
      method: 'get',
    });
    expect(request).toHaveBeenNthCalledWith(2, {
      url: '/platform/runtime-user-questions/pending',
      method: 'get',
      params: { conversationId: '42', limit: 10 },
    });
  });

  it('posts idempotent answer and cancel decisions to separate endpoints', () => {
    const payload = { idempotencyKey: 'idem-1', selectedOptionIds: ['a'], customInput: '' };
    answerRuntimeUserQuestion('q-1', payload);
    cancelRuntimeUserQuestion('q-1', { idempotencyKey: payload.idempotencyKey });

    expect(request).toHaveBeenNthCalledWith(1, {
      url: '/platform/runtime-user-questions/q-1/answer',
      method: 'post',
      data: payload,
    });
    expect(request).toHaveBeenNthCalledWith(2, {
      url: '/platform/runtime-user-questions/q-1/cancel',
      method: 'post',
      data: { idempotencyKey: payload.idempotencyKey },
    });
  });
});
