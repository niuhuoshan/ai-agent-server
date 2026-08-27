import type { ExecutionEventView } from '@/service/api/platform';
import type { RuntimeUserQuestionView } from '@/service/api/runtime';

export type RuntimeUserQuestionStatus = 'pending' | 'submitted' | 'cancelled' | 'expired' | 'stale';

export interface RuntimeUserQuestionOption {
  id: string;
  label: string;
  description?: string;
}

/** A question raised by an Agent, intentionally separate from a business approval. */
export interface RuntimeUserQuestionState {
  question_id: string;
  tool_call_id?: string;
  question: string;
  options: RuntimeUserQuestionOption[];
  is_multi_select: boolean;
  allow_custom_input: boolean;
  context?: string;
  status: RuntimeUserQuestionStatus;
  selected_option_ids?: string[];
  custom_input?: string;
  event_id: string;
  trace_id?: string;
}

export function runtimeUserQuestionFromView(view: RuntimeUserQuestionView): RuntimeUserQuestionState {
  return {
    question_id: view.questionId,
    tool_call_id: view.toolCallId || undefined,
    question: view.question || 'Agent 需要你的补充',
    options: (view.options || []).map(option => ({ id: option.id, label: option.label, description: option.description })),
    is_multi_select: Boolean(view.multiSelect),
    allow_custom_input: Boolean(view.allowCustomInput),
    context: view.context || undefined,
    status: view.status === 'superseded' ? 'stale' : view.status,
    selected_option_ids: view.selectedOptionIds,
    custom_input: view.customInput || undefined,
    event_id: `question:${view.questionId}`,
    trace_id: view.executionId || undefined,
  };
}

const MAX_DEPTH = 7;
const MAX_OPTIONS = 12;
const MAX_TEXT = 2000;

function text(value: unknown, fallback = '') {
  if (typeof value !== 'string') return fallback;
  const valueText = value.trim();
  return valueText ? valueText.slice(0, MAX_TEXT) : fallback;
}

function record(value: unknown): Record<string, unknown> | null {
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

function candidates(value: unknown, depth = 0): Record<string, unknown>[] {
  if (depth > MAX_DEPTH || value === null || value === undefined) return [];
  const parsed = parseJson(value);
  const object = record(parsed);
  if (object) {
    return [object, ...Object.values(object).flatMap(item => candidates(item, depth + 1))];
  }
  if (Array.isArray(parsed)) return parsed.flatMap(item => candidates(item, depth + 1));
  return [];
}

function normalizeOptions(value: unknown): RuntimeUserQuestionOption[] {
  const parsed = parseJson(value);
  if (!Array.isArray(parsed)) return [];
  const seen = new Set<string>();
  return parsed.slice(0, MAX_OPTIONS).flatMap(item => {
    const option = record(parseJson(item));
    if (!option) return [];
    const id = text(option.id || option.value || option.key);
    const label = text(option.label || option.title || option.name, id || '选项');
    if (!id || seen.has(id)) return [];
    seen.add(id);
    const description = text(option.description || option.help);
    return [{ id, label, ...(description ? { description } : {}) }];
  });
}

function normalizeIds(value: unknown) {
  const parsed = parseJson(value);
  if (!Array.isArray(parsed)) return undefined;
  return parsed.slice(0, MAX_OPTIONS).map(item => text(item)).filter(Boolean);
}

function questionStatus(value: unknown, eventType: string): RuntimeUserQuestionStatus {
  const status = text(value).toLowerCase();
  if (['submitted', 'answered', 'resolved', 'success'].includes(status)) return 'submitted';
  if (['cancelled', 'canceled'].includes(status)) return 'cancelled';
  if (['expired', 'timeout', 'timed_out'].includes(status)) return 'expired';
  if (['stale', 'superseded'].includes(status)) return 'stale';
  if (eventType.includes('answer') || eventType.includes('resolved')) return 'submitted';
  if (eventType.includes('cancel')) return 'cancelled';
  if (eventType.includes('expire') || eventType.includes('timeout')) return 'expired';
  return 'pending';
}

function looksLikeQuestion(object: Record<string, unknown>, eventType: string) {
  const type = text(object.type).toLowerCase();
  const hasQuestion = Boolean(text(object.question || object.prompt || object.title));
  const hasOptions = Array.isArray(parseJson(object.options || object.choices));
  return type === 'user_question'
    || type === 'ask_user_question'
    || eventType.includes('user_question')
    || (hasQuestion && hasOptions && Boolean(object.question_id || object.questionId));
}

function questionIdFromEvent(event: ExecutionEventView) {
  const eventType = String(event.eventType || '').toLowerCase();
  const source = [...candidates(event.projection), ...candidates(event.payload)].find(item =>
    Boolean(
      (item.question_id || item.questionId || item.interaction_id || item.interactionId)
      && (eventType.includes('user_question') || eventType.includes('answer') || eventType.includes('cancel') || eventType.includes('expire')
        || ['submitted', 'answered', 'resolved', 'cancelled', 'canceled', 'expired', 'superseded'].includes(text(item.status).toLowerCase())),
    ),
  );
  return text(source?.question_id || source?.questionId || source?.interaction_id || source?.interactionId);
}

function statusFromEvent(event: ExecutionEventView) {
  const eventType = String(event.eventType || '').toLowerCase();
  const source = [...candidates(event.projection), ...candidates(event.payload)].find(item =>
    Boolean(item.question_id || item.questionId || item.interaction_id || item.interactionId),
  );
  return questionStatus(source?.status, eventType);
}

/** Parse one durable execution event without exposing its raw payload. */
export function parseRuntimeUserQuestionEvent(event: ExecutionEventView): RuntimeUserQuestionState | null {
  const eventType = String(event.eventType || '').toLowerCase();
  const allowed = eventType.includes('user_question')
    || eventType === 'waiting_input'
    || eventType === 'custom'
    || eventType.startsWith('tool_result_')
    || eventType.startsWith('tool_call_');
  if (!allowed) return null;

  const source = [
    ...candidates(event.projection),
    ...candidates(event.payload),
  ].find(item => {
    if (!looksLikeQuestion(item, eventType)) return false;
    const nested = record(parseJson(item.userQuestion || item.user_question)) || item;
    return Boolean(text(nested.question || nested.prompt || nested.title))
      && normalizeOptions(nested.options || nested.choices).length > 0;
  });
  if (!source) return null;

  const nested = record(parseJson(source.userQuestion || source.user_question)) || source;
  const question = text(nested.question || nested.prompt || nested.title);
  const options = normalizeOptions(nested.options || nested.choices);
  if (!question || options.length < 1) return null;

  // Do not fall back to the event id as the server question id. A card can be
  // displayed for observability, but submission is disabled until the server
  // provides a real question id.
  const questionId = text(
    nested.question_id || nested.questionId || nested.interaction_id || nested.interactionId,
  );
  const selected = normalizeIds(nested.selected_option_ids || nested.selectedOptionIds);
  const status = questionStatus(nested.status, eventType);
  return {
    question_id: questionId,
    tool_call_id: text(nested.tool_call_id || nested.toolCallId) || undefined,
    question,
    options,
    is_multi_select: Boolean(nested.is_multi_select ?? nested.isMultiSelect ?? nested.multi_select),
    allow_custom_input: Boolean(nested.allow_custom_input ?? nested.allowCustomInput ?? nested.allow_input),
    context: text(nested.context || nested.description) || undefined,
    status,
    selected_option_ids: selected,
    custom_input: text(nested.custom_input || nested.customInput) || undefined,
    event_id: event.eventId,
    trace_id: event.traceId,
  };
}

/** Returns the latest state for each question in cursor order. */
export function parseRuntimeUserQuestions(events: ExecutionEventView[]): RuntimeUserQuestionState[] {
  const states = new Map<string, RuntimeUserQuestionState>();
  for (const event of [...events].sort((left, right) => left.cursor - right.cursor)) {
    const state = parseRuntimeUserQuestionEvent(event);
    if (state) {
      const key = state.question_id || `event:${state.event_id}`;
      states.set(key, state);
      continue;
    }
    // Answer/cancel events may only carry questionId and status. Preserve the
    // original safe question projection while updating its lifecycle state.
    const questionId = questionIdFromEvent(event);
    if (!questionId || !states.has(questionId)) continue;
    const current = states.get(questionId)!;
    states.set(questionId, {
      ...current,
      status: statusFromEvent(event),
      event_id: event.eventId,
      trace_id: event.traceId,
    });
  }
  return [...states.values()];
}

export function buildRuntimeUserQuestionPayload(
  state: RuntimeUserQuestionState,
  selectedOptionIds: string[],
  customInput: string,
  cancelled = false,
) {
  return {
    idempotencyKey: crypto.randomUUID(),
    selectedOptionIds: cancelled ? [] : selectedOptionIds,
    customInput: cancelled ? '' : customInput.trim(),
    ...(cancelled ? { cancelled: true } : {}),
    questionId: state.question_id,
  };
}
