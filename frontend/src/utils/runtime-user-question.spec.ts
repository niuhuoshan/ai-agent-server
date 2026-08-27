import { describe, expect, it } from 'vitest';
import type { ExecutionEventView } from '@/service/api/platform';
import { parseRuntimeTimeline } from './runtime-timeline';
import { parseRuntimeUserQuestions } from './runtime-user-question';

function event(payload: Record<string, unknown>, eventType = 'user_question'): ExecutionEventView {
  return {
    eventId: 'event-1', traceId: 'trace-1', conversationId: '42', runId: 'run-1', stepId: null,
    cursor: 1, eventType, eventStatus: 'pending', summary: null, payload, sensitiveLevel: 'none',
    occurredAt: '2026-08-19T10:00:00Z', projection: {},
  };
}

describe('runtime user question projection', () => {
  it('parses the server camel-case question payload and keeps the real id', () => {
    const [question] = parseRuntimeUserQuestions([event({
      userQuestion: {
        questionId: 'uq-7', question: '选择执行范围', options: [{ id: 'all', label: '全部' }, { id: 'mine', label: '我的' }],
        multiSelect: false, allowCustomInput: true, status: 'pending',
      },
    })]);
    expect(question.question_id).toBe('uq-7');
    expect(question.options).toHaveLength(2);
    expect(question.allow_custom_input).toBe(true);
  });

  it('projects tool and parent-child execution events without token deltas', () => {
    const items = parseRuntimeTimeline([
      event({ toolName: 'lookup' }, 'tool_call_started'),
      event({ delta: 'secret token' }, 'text_delta'),
      event({ toolName: 'lookup', parentId: 'event-1' }, 'tool_result_finished'),
    ]);
    expect(items.map(item => item.eventType)).toEqual(['tool_call_started', 'tool_result_finished']);
    expect(items[1].parentId).toBe('event-1');
  });
});
