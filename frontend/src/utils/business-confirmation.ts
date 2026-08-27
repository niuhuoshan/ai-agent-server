import type { ExecutionEventView } from '@/service/api';

export type BusinessConfirmationValueType = 'string' | 'number' | 'boolean' | 'text';

export interface BusinessConfirmationField {
  key: string;
  label: string;
  value: unknown;
  editable: boolean;
  value_type: BusinessConfirmationValueType;
}

export interface BusinessConfirmationState {
  confirmation_id: string;
  title: string;
  summary: string;
  fields: BusinessConfirmationField[];
  confirm_label: string;
  cancel_label: string;
  risk_note: string;
  status: 'pending' | 'submitted' | 'stale';
  decision?: 'confirmed' | 'cancelled';
  event_id: string;
}

const MAX_DEPTH = 7;
const MAX_FIELDS = 32;

function text(value: unknown, fallback = '') {
  return typeof value === 'string' && value.trim() ? value.trim() : fallback;
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function parseJson(value: unknown): unknown {
  if (typeof value !== 'string' || !value.trim()) return value;
  try {
    return JSON.parse(value);
  } catch {
    return value;
  }
}

function normalizeFields(value: unknown): BusinessConfirmationField[] {
  if (!Array.isArray(value)) return [];
  return value
    .slice(0, MAX_FIELDS)
    .map(item => asRecord(parseJson(item)))
    .filter((item): item is Record<string, unknown> => Boolean(item))
    .map(item => {
      const rawType = text(item.value_type, 'string') as BusinessConfirmationValueType;
      const valueType: BusinessConfirmationValueType = ['string', 'number', 'boolean', 'text'].includes(rawType)
        ? rawType
        : 'string';
      return {
        key: text(item.key),
        label: text(item.label, text(item.key, '字段')),
        value: item.value ?? '',
        editable: item.editable !== false,
        value_type: valueType,
      };
    })
    .filter(item => item.key || item.label);
}

function candidates(value: unknown, depth = 0): Record<string, unknown>[] {
  if (depth > MAX_DEPTH || value === null || value === undefined) return [];
  const parsed = parseJson(value);
  const record = asRecord(parsed);
  if (record) {
    return [record, ...Object.values(record).flatMap(item => candidates(item, depth + 1))];
  }
  if (Array.isArray(parsed)) return parsed.flatMap(item => candidates(item, depth + 1));
  return [];
}

function toolName(event: ExecutionEventView) {
  const projection = event.projection || {};
  const payload = event.payload || {};
  return text(
    projection.toolName || projection.tool_name || payload.toolName || payload.tool_name,
  );
}

function hasConfirmationShape(value: Record<string, unknown>) {
  const type = text(value.type).toLowerCase();
  const status = text(value.status).toLowerCase();
  const id = text(value.confirmation_id || value.confirmationId);
  return type === 'business_confirmation'
    || (status === 'awaiting_user' && Boolean(id))
    || Boolean(id && (value.fields || value.ui));
}

/** Finds the structured Nhs confirmation result in a persisted runtime event. */
export function parseBusinessConfirmationEvent(event: ExecutionEventView): BusinessConfirmationState | null {
  const isConfirmationTool = toolName(event) === 'request_user_confirmation';
  if (!isConfirmationTool && event.eventType !== 'custom' && event.eventType !== 'approval_required') return null;
  const all = [
    ...candidates(event.payload),
    ...candidates(event.projection),
    ...candidates(event.summary),
  ];
  const source = all.find(hasConfirmationShape);
  if (!source) return null;
  const ui = asRecord(parseJson(source.ui)) || source;
  const id = text(source.confirmation_id || source.confirmationId || ui.confirmation_id || ui.confirmationId);
  const fields = normalizeFields(source.fields || ui.fields);
  if (!id || (!fields.length && text(source.status).toLowerCase() !== 'awaiting_user')) return null;
  return {
    confirmation_id: id,
    title: text(source.title || ui.title, '请确认以下信息'),
    summary: text(source.summary || ui.summary),
    fields,
    confirm_label: text(source.confirm_label || ui.confirm_label, '确定'),
    cancel_label: text(source.cancel_label || ui.cancel_label, '取消'),
    risk_note: text(source.risk_note || ui.risk_note),
    status: 'pending',
    event_id: event.eventId,
  };
}

export function formatBusinessConfirmationSnapshot(fields: BusinessConfirmationField[]) {
  return fields.map(field => {
    const value = field.value === null || field.value === undefined ? '' : String(field.value);
    return `- ${field.label} (${field.key}): ${value}`;
  }).join('\n');
}

export function buildBusinessConfirmationUserMessage(
  confirmed: boolean,
  confirmation: BusinessConfirmationState,
  fields: BusinessConfirmationField[],
) {
  const snapshot = formatBusinessConfirmationSnapshot(fields);
  if (confirmed) {
    return [
      '【业务确认】用户已确定',
      `confirmation_id: ${confirmation.confirmation_id}`,
      '请根据以下已确认字段继续执行（如需写入请调用相应工具）：',
      snapshot || '- （无可编辑字段）',
    ].join('\n');
  }
  return [
    '【业务确认】用户已取消',
    `confirmation_id: ${confirmation.confirmation_id}`,
    '请立即终止本次录入/变更，不要再次调用 request_user_confirmation；只用文字确认已取消。',
    '当时字段快照：',
    snapshot || '- （无可编辑字段）',
  ].join('\n');
}
