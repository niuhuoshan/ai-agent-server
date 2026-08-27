import { describe, expect, it } from 'vitest';
import type { AutomationTriggerView } from '@/service/api';
import {
  automationFormFromTrigger,
  automationStatusLabel,
  buildAutomationStatusPayload,
  canFireAutomation,
  isAutomationRevisionConflict,
  resolveCronExpression
} from './automation-state';

function trigger(overrides: Partial<AutomationTriggerView> = {}): AutomationTriggerView {
  return {
    id: '30',
    triggerKey: 'daily-report',
    name: '日报',
    triggerType: 'manual',
    taskId: '10',
    taskVersionId: '11',
    taskRevisionNo: '3',
    serviceAccountId: '20',
    cronExpression: null,
    timezone: 'UTC',
    status: 'active',
    misfirePolicy: 'fire_once',
    maxCatchupCount: 1,
    maxAttempts: 3,
    inputTemplate: null,
    lastRunAt: null,
    nextRunAt: null,
    revisionNo: '7',
    config: {},
    createdAt: '2026-08-17T09:00:00',
    ...overrides
  };
}

describe('automation page state', () => {
  it('localizes every backend state and only enables active manual fires', () => {
    expect(automationStatusLabel('active')).toBe('启用');
    expect(automationStatusLabel('paused')).toBe('已暂停');
    expect(automationStatusLabel('error')).toBe('异常');
    expect(automationStatusLabel('archived')).toBe('已归档');
    expect(canFireAutomation(trigger())).toBe(true);
    expect(canFireAutomation(trigger({ status: 'paused' }))).toBe(false);
    expect(canFireAutomation(trigger({ triggerType: 'cron' }))).toBe(false);
  });

  it('round-trips supported schedules and preserves custom cron expressions', () => {
    const weekly = automationFormFromTrigger(trigger({
      triggerType: 'cron',
      cronExpression: '0 15 8 ? * MON,WED,FRI',
      timezone: 'Asia/Shanghai',
      inputTemplate: '生成日报'
    }));
    expect(weekly).toMatchObject({
      scheduleMode: 'weekly',
      hour: 8,
      minute: 15,
      weekdays: ['MON', 'WED', 'FRI']
    });
    expect(resolveCronExpression(weekly)).toBe('0 15 8 ? * MON,WED,FRI');

    const custom = automationFormFromTrigger(trigger({
      triggerType: 'cron',
      cronExpression: '0 0 9 L * ?',
      inputTemplate: '生成月报'
    }));
    expect(custom.scheduleMode).toBe('custom');
    expect(resolveCronExpression(custom)).toBe('0 0 9 L * ?');
  });

  it('carries the current revision through status transitions', () => {
    expect(buildAutomationStatusPayload(trigger(), 'paused')).toMatchObject({
      status: 'paused',
      revisionNo: '7',
      taskVersionId: '11'
    });
  });

  it('recognizes conflicts from either HTTP status or the R response code', () => {
    expect(isAutomationRevisionConflict({ response: { status: 409 } })).toBe(true);
    expect(isAutomationRevisionConflict({ response: { status: 200, data: { code: 409 } } })).toBe(true);
    expect(isAutomationRevisionConflict({ response: { status: 400, data: { code: 400 } } })).toBe(false);
  });
});
