import { describe, expect, it } from 'vitest';
import type { ExecutionEventView } from '@/service/api';
import {
  buildBusinessConfirmationUserMessage,
  parseBusinessConfirmationEvent,
} from './business-confirmation';

function event(payload: Record<string, unknown>): ExecutionEventView {
  return {
    eventId: 'evt-confirmation-1',
    traceId: 'trace-1',
    conversationId: '1',
    runId: null,
    stepId: null,
    cursor: 1,
    eventType: 'tool_result_finished',
    eventStatus: 'success',
    summary: '',
    payload,
    sensitiveLevel: 'internal',
    occurredAt: '2026-08-17T00:00:00Z',
    projection: { toolName: 'request_user_confirmation' },
  };
}

describe('business confirmation contract', () => {
  it('parses the structured UI envelope nested in a tool result', () => {
    const result = parseBusinessConfirmationEvent(event({
      result: {
        status: 'awaiting_user',
        confirmation_id: 'bc-42',
        ui: {
          title: '发布变更',
          fields: [{ key: 'version', label: '版本', value: '1.2.0', editable: true }],
          risk_note: '将影响生产环境',
        },
      },
    }));

    expect(result?.confirmation_id).toBe('bc-42');
    expect(result?.title).toBe('发布变更');
    expect(result?.fields[0]).toMatchObject({ key: 'version', value: '1.2.0' });
  });

  it('parses the safe runtime projection while the arbitrary result stays redacted', () => {
    const source = event({ redacted: true });
    source.projection = {
      toolName: 'request_user_confirmation',
      resultStatus: 'awaiting_user',
      businessConfirmation: {
        confirmationId: 'bc-43',
        status: 'awaiting_user',
        ui: {
          title: '确认工单',
          fields: [{ key: 'summary', label: '标题', value: '服务告警' }],
        },
      },
    };

    expect(parseBusinessConfirmationEvent(source)).toMatchObject({
      confirmation_id: 'bc-43',
      title: '确认工单',
    });
  });

  it('builds a normal user turn with an editable field snapshot', () => {
    const confirmation = {
      confirmation_id: 'bc-42',
      title: '发布变更',
      summary: '',
      fields: [],
      confirm_label: '确定',
      cancel_label: '取消',
      risk_note: '',
      status: 'pending' as const,
      event_id: 'evt-confirmation-1',
    };
    const message = buildBusinessConfirmationUserMessage(true, confirmation, [
      { key: 'version', label: '版本', value: '1.2.0', editable: true, value_type: 'string' },
    ]);
    expect(message).toContain('【业务确认】用户已确定');
    expect(message).toContain('confirmation_id: bc-42');
    expect(message).toContain('版本 (version): 1.2.0');
  });

  it('does not turn unrelated tool events into confirmation cards', () => {
    expect(parseBusinessConfirmationEvent(event({ status: 'ok', data: { value: 1 } }))).toBeNull();
  });
});
