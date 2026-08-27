import type {
  AutomationTriggerView,
  CreateAutomationTriggerPayload,
  UpdateAutomationTriggerPayload
} from '@/service/api';

export type AutomationScheduleMode = 'hourly' | 'daily' | 'weekdays' | 'weekly' | 'custom';

export interface AutomationFormState {
  triggerKey: string;
  name: string;
  triggerType: CreateAutomationTriggerPayload['triggerType'];
  taskId: string | null;
  serviceAccountId: string | null;
  scheduleMode: AutomationScheduleMode;
  rawCronExpression: string;
  hour: number;
  minute: number;
  weekdays: string[];
  timezone: string;
  misfirePolicy: NonNullable<CreateAutomationTriggerPayload['misfirePolicy']>;
  maxCatchupCount: number;
  maxAttempts: number;
  inputTemplate: string;
}

export function createEmptyAutomationForm(): AutomationFormState {
  return {
    triggerKey: '',
    name: '',
    triggerType: 'manual',
    taskId: null,
    serviceAccountId: null,
    scheduleMode: 'daily',
    rawCronExpression: '',
    hour: 9,
    minute: 0,
    weekdays: ['MON'],
    timezone: 'Asia/Shanghai',
    misfirePolicy: 'fire_once',
    maxCatchupCount: 1,
    maxAttempts: 3,
    inputTemplate: ''
  };
}

export function automationStatusLabel(status: AutomationTriggerView['status']) {
  return {
    active: '启用',
    paused: '已暂停',
    error: '异常',
    archived: '已归档'
  }[status];
}

export function automationStatusType(status: AutomationTriggerView['status']) {
  if (status === 'active') return 'success' as const;
  if (status === 'paused') return 'warning' as const;
  if (status === 'error') return 'error' as const;
  return 'default' as const;
}

export function canFireAutomation(trigger: AutomationTriggerView) {
  return trigger.triggerType === 'manual' && trigger.status === 'active';
}

export function resolveCronExpression(form: AutomationFormState) {
  if (form.scheduleMode === 'custom') return form.rawCronExpression.trim();
  const minute = Math.max(0, Math.min(59, Number(form.minute) || 0));
  const hour = Math.max(0, Math.min(23, Number(form.hour) || 0));
  if (form.scheduleMode === 'hourly') return `0 ${minute} * * * ?`;
  if (form.scheduleMode === 'weekdays') return `0 ${minute} ${hour} ? * MON-FRI`;
  if (form.scheduleMode === 'weekly') return `0 ${minute} ${hour} ? * ${form.weekdays.join(',') || 'MON'}`;
  return `0 ${minute} ${hour} * * ?`;
}

export function automationFormFromTrigger(trigger: AutomationTriggerView): AutomationFormState {
  const schedule = parseCronExpression(trigger.cronExpression);
  return {
    triggerKey: trigger.triggerKey,
    name: trigger.name,
    triggerType: trigger.triggerType,
    taskId: trigger.taskId,
    serviceAccountId: trigger.serviceAccountId,
    scheduleMode: schedule.mode,
    rawCronExpression: trigger.cronExpression || '',
    hour: schedule.hour,
    minute: schedule.minute,
    weekdays: schedule.weekdays,
    timezone: trigger.timezone || 'Asia/Shanghai',
    misfirePolicy: trigger.misfirePolicy || 'fire_once',
    maxCatchupCount: trigger.maxCatchupCount || 1,
    maxAttempts: trigger.maxAttempts || 3,
    inputTemplate: trigger.inputTemplate || ''
  };
}

export function buildAutomationUpdatePayload(
  trigger: AutomationTriggerView,
  form: AutomationFormState,
  taskVersionId: string
): UpdateAutomationTriggerPayload {
  const cron = form.triggerType === 'cron';
  return {
    name: form.name.trim(),
    taskId: form.taskId || trigger.taskId,
    taskVersionId,
    serviceAccountId: form.serviceAccountId || trigger.serviceAccountId,
    cronExpression: cron ? resolveCronExpression(form) : undefined,
    timezone: cron ? form.timezone : undefined,
    misfirePolicy: cron ? form.misfirePolicy : undefined,
    maxCatchupCount: cron && form.misfirePolicy === 'catch_up' ? form.maxCatchupCount : 1,
    maxAttempts: form.maxAttempts,
    inputTemplate: form.inputTemplate.trim() || undefined,
    status: trigger.status,
    revisionNo: trigger.revisionNo,
    config: { ...trigger.config }
  };
}

export function buildAutomationStatusPayload(
  trigger: AutomationTriggerView,
  status: AutomationTriggerView['status']
): UpdateAutomationTriggerPayload {
  return {
    name: trigger.name,
    taskId: trigger.taskId,
    taskVersionId: trigger.taskVersionId,
    serviceAccountId: trigger.serviceAccountId,
    cronExpression: trigger.cronExpression || undefined,
    timezone: trigger.timezone || undefined,
    misfirePolicy: trigger.misfirePolicy || undefined,
    maxCatchupCount: trigger.maxCatchupCount || undefined,
    maxAttempts: trigger.maxAttempts || undefined,
    inputTemplate: trigger.inputTemplate || undefined,
    status,
    revisionNo: trigger.revisionNo,
    config: { ...trigger.config }
  };
}

export function isAutomationRevisionConflict(error: unknown) {
  if (!error || typeof error !== 'object') return false;
  const response = (error as { response?: unknown }).response;
  if (!response || typeof response !== 'object') return false;
  const status = (response as { status?: unknown }).status;
  const data = (response as { data?: unknown }).data;
  const backendCode = data && typeof data === 'object' ? (data as { code?: unknown }).code : undefined;
  return Number(status) === 409 || Number(backendCode) === 409;
}

function parseCronExpression(expression: string | null) {
  const fallback = { mode: 'custom' as const, hour: 9, minute: 0, weekdays: ['MON'] };
  if (!expression) return fallback;
  const fields = expression.trim().split(/\s+/);
  if (fields.length !== 6 || fields[0] !== '0') return fallback;
  const minute = Number(fields[1]);
  const hour = Number(fields[2]);
  if (!Number.isInteger(minute) || minute < 0 || minute > 59) return fallback;
  if (fields[2] === '*' && fields[3] === '*' && fields[4] === '*' && fields[5] === '?') {
    return { mode: 'hourly' as const, hour: 9, minute, weekdays: ['MON'] };
  }
  if (!Number.isInteger(hour) || hour < 0 || hour > 23 || fields[4] !== '*') return fallback;
  if (fields[3] === '*' && fields[5] === '?') {
    return { mode: 'daily' as const, hour, minute, weekdays: ['MON'] };
  }
  if (fields[3] !== '?') return fallback;
  if (fields[5] === 'MON-FRI') {
    return { mode: 'weekdays' as const, hour, minute, weekdays: ['MON', 'TUE', 'WED', 'THU', 'FRI'] };
  }
  const weekdays = fields[5].split(',');
  if (weekdays.length && weekdays.every(value => /^(MON|TUE|WED|THU|FRI|SAT|SUN)$/.test(value))) {
    return { mode: 'weekly' as const, hour, minute, weekdays };
  }
  return fallback;
}
