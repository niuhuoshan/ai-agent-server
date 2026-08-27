import { describe, expect, it } from 'vitest';
import type { AgentDebugExecutionEvent } from '@/service/api/agent-debug';
import {
  debugEventKind,
  debugFinalOutput,
  isDebugRunResumable,
  isDebugRunRetryable,
  mergeAgentDebugEvents
} from './presentation';

function event(id: string, cursor: number, eventType: string, summary = ''): AgentDebugExecutionEvent {
  return {
    eventId: id,
    traceId: 'trace',
    conversationId: null,
    runId: '20',
    stepId: '30',
    cursor,
    eventType,
    eventStatus: 'success',
    summary,
    payload: {},
    projection: {},
    sensitiveLevel: 'public',
    occurredAt: '2026-08-17T00:00:00'
  };
}

describe('Agent Debug presentation', () => {
  it('replays persisted output in cursor order without duplicate SSE events', () => {
    const merged = mergeAgentDebugEvents(
      [event('b', 2, 'text_delta', '界')],
      [event('a', 1, 'text_delta', '世'), event('b', 2, 'text_delta', '界')]
    );

    expect(merged.map(item => item.eventId)).toEqual(['a', 'b']);
    expect(debugFinalOutput(merged)).toBe('世界');
  });

  it('returns only the safe withdrawal notice after retracted output', () => {
    const retraction = event('retract', 3, 'custom', '此响应因安全策略已撤回');
    retraction.payload = { retraction: true, code: 'output_retracted' };
    expect(debugFinalOutput([
      event('a', 1, 'text_delta', 'secret'),
      event('b', 2, 'text_delta', 'token'),
      retraction
    ])).toBe('此响应因安全策略已撤回');
  });

  it('keeps recoverable and retryable terminal states distinct', () => {
    expect(isDebugRunResumable('paused')).toBe(true);
    expect(isDebugRunResumable('failed')).toBe(false);
    expect(isDebugRunRetryable('failed')).toBe(true);
    expect(isDebugRunRetryable('paused')).toBe(false);
  });

  it('maps tool, approval and permission events to operational timeline groups', () => {
    expect(debugEventKind('tool_call_started')).toBe('tool');
    expect(debugEventKind('approval_required')).toBe('approval');
    expect(debugEventKind('permission_denied')).toBe('error');
  });
});
