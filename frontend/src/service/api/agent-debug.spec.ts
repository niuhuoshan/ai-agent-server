import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import {
  AgentDebugSseDecoder,
  createAgentDebugRun,
  resumeAgentDebugRun,
  retryAgentDebugRun,
  stopAgentDebugRun
} from './agent-debug';

vi.mock('../request', () => ({ request: vi.fn() }));

describe('Agent Debug API', () => {
  beforeEach(() => vi.mocked(request).mockClear());

  it('starts a real persisted debug run with the selected immutable version', () => {
    createAgentDebugRun({
      idempotencyKey: 'debug-1',
      agentId: '10',
      agentVersionId: '11',
      input: '检查本月订单'
    });

    expect(request).toHaveBeenCalledWith({
      url: '/platform/agent-debug/runs',
      method: 'post',
      data: {
        idempotencyKey: 'debug-1',
        agentId: '10',
        agentVersionId: '11',
        input: '检查本月订单'
      }
    });
  });

  it('uses dedicated durable stop, resume and retry actions', () => {
    stopAgentDebugRun('20', '人工停止');
    resumeAgentDebugRun('20');
    retryAgentDebugRun('20', 'retry-20');

    expect(request).toHaveBeenNthCalledWith(1, {
      url: '/platform/agent-debug/runs/20/stop', method: 'post', data: { reason: '人工停止' }
    });
    expect(request).toHaveBeenNthCalledWith(2, {
      url: '/platform/agent-debug/runs/20/resume', method: 'post'
    });
    expect(request).toHaveBeenNthCalledWith(3, {
      url: '/platform/agent-debug/runs/20/retry', method: 'post', data: { idempotencyKey: 'retry-20' }
    });
  });

  it('decodes split named SSE frames and the Nhs done sentinel', () => {
    const decoder = new AgentDebugSseDecoder();
    const first = decoder.feed(
      'id: 8\r\nevent: text_delta\r\ndata: {"eventId":"evt-8","cursor":8,"eventType":"text_'
    );
    const second = decoder.feed(
      'delta","summary":"你好","projection":{}}\r\n\r\ndata: [DONE]\r\n\r\n'
    );

    expect(first).toEqual([]);
    expect(second).toHaveLength(2);
    expect(second[0]).toMatchObject({
      id: '8', event: 'text_delta', done: false,
      data: { eventId: 'evt-8', cursor: 8, summary: '你好' }
    });
    expect(second[1]).toMatchObject({ done: true, data: null });
  });
});
