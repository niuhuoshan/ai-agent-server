import type { ExecutionEventView } from '@/service/api/platform';
import type { RuntimeUserQuestionState } from './runtime-user-question';
import { parseRuntimeUserQuestionEvent } from './runtime-user-question';

export type RuntimeTimelineStatus = 'pending' | 'running' | 'success' | 'error' | 'warning' | 'cancelled';
export type RuntimeTimelineKind = 'run' | 'reasoning' | 'output' | 'tool' | 'approval' | 'subagent' | 'question' | 'todo' | 'error';

export interface RuntimeTimelineItem {
  id: string;
  kind: RuntimeTimelineKind;
  title: string;
  summary: string;
  status: RuntimeTimelineStatus;
  eventType: string;
  eventId: string;
  traceId: string;
  parentId?: string | null;
  occurredAt: string;
  durationMs?: number | null;
  details?: string;
  question?: RuntimeUserQuestionState;
  todo?: RuntimeTodoSnapshot;
}

export interface RuntimeTodoEntry {
  content: string;
  status: 'pending' | 'in_progress' | 'completed';
}

export interface RuntimeTodoSnapshot {
  id: string;
  title: string;
  todos: RuntimeTodoEntry[];
  counts: Record<'pending' | 'in_progress' | 'completed', number>;
}

const MAX_ITEMS = 120;
const MAX_TODOS = 20;

function text(value: unknown, fallback = '') {
  return typeof value === 'string' && value.trim() ? value.trim().slice(0, 1000) : fallback;
}

function object(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function candidates(value: unknown, depth = 0): Record<string, unknown>[] {
  if (depth > 6 || value === null || value === undefined) return [];
  if (typeof value === 'string') {
    try { return candidates(JSON.parse(value), depth + 1); } catch { return []; }
  }
  if (Array.isArray(value)) return value.flatMap(item => candidates(item, depth + 1));
  const source = object(value);
  if (!source) return [];
  return [source, ...Object.values(source).flatMap(item => candidates(item, depth + 1))];
}

function status(event: ExecutionEventView): RuntimeTimelineStatus {
  const eventType = event.eventType.toLowerCase();
  const raw = String(event.eventStatus || '').toLowerCase();
  if (eventType.includes('cancel') || raw === 'cancelled') return 'cancelled';
  if (eventType.includes('fail') || eventType.includes('error') || eventType.includes('denied') || raw === 'failed') return 'error';
  if (eventType.includes('approval') || eventType.includes('external') || raw === 'pending' || raw === 'running') return 'warning';
  if (eventType.endsWith('_started') || eventType.endsWith('_delta')) return 'running';
  return 'success';
}

function kind(event: ExecutionEventView): RuntimeTimelineKind {
  const type = event.eventType.toLowerCase();
  const projection = event.projection || {};
  if (type.includes('user_question') || type === 'waiting_input') return 'question';
  if (projection.todo || projection.todoType || projection.todo_type) return 'todo';
  if (type === 'todo' || type === 'todo_update' || type.includes('todo_')) return 'todo';
  if (type.includes('subagent') || type.includes('sub_agent')) return 'subagent';
  if (type.includes('approval') || type.includes('external')) return 'approval';
  if (type.includes('tool_')) return 'tool';
  if (type.includes('thinking') || type.includes('reasoning')) return 'reasoning';
  if (type.includes('text_') || type === 'result') return 'output';
  if (type.includes('fail') || type.includes('error') || type.includes('denied')) return 'error';
  return 'run';
}

function title(event: ExecutionEventView, itemKind: RuntimeTimelineKind) {
  const projection = event.projection || {};
  const toolName = text(projection.toolName || projection.tool_name);
  const agentName = text(projection.agentName || projection.agent_name);
  const labels: Record<RuntimeTimelineKind, string> = {
    run: '运行过程', reasoning: '模型思考', output: '生成回复', tool: toolName ? `工具 · ${toolName}` : '工具调用',
    approval: '等待审批', subagent: agentName ? `子 Agent · ${agentName}` : '子 Agent 委派', question: '等待用户回答',
    todo: '任务清单', error: '执行异常',
  };
  const eventType = event.eventType.toLowerCase();
  if (eventType === 'run_started') return '运行已启动';
  if (eventType === 'run_finished') return '运行已完成';
  if (eventType === 'tool_result_finished') return toolName ? `工具完成 · ${toolName}` : '工具执行完成';
  if (eventType === 'thinking_finished') return '思考完成';
  return labels[itemKind];
}

function safeDetails(event: ExecutionEventView) {
  const projection = event.projection || {};
  const details: string[] = [];
  const model = text(projection.model);
  const tool = text(projection.toolName || projection.tool_name);
  const duration = typeof projection.durationMs === 'number' ? projection.durationMs : projection.duration_ms;
  const count = projection.returnedCount ?? projection.returned_count ?? projection.count;
  if (model) details.push(`模型 ${model}`);
  if (tool) details.push(`工具 ${tool}`);
  if (typeof count === 'number') details.push(`返回 ${count} 条`);
  if (typeof duration === 'number' && Number.isFinite(duration)) details.push(`${Math.round(duration)} ms`);
  return details.join(' · ');
}

function normalizeTodo(value: unknown): RuntimeTodoSnapshot | undefined {
  const source = object(value);
  if (!source) return undefined;
  const raw = source.todos || source.items || source.todo;
  if (!Array.isArray(raw)) return undefined;
  const todos: RuntimeTodoEntry[] = [];
  const seen = new Set<string>();
  for (const item of raw.slice(0, MAX_TODOS)) {
    const row = object(item);
    if (!row) continue;
    const content = text(row.content || row.title || row.text);
    const itemStatus = text(row.status, 'pending') as RuntimeTodoEntry['status'];
    if (!content || !['pending', 'in_progress', 'completed'].includes(itemStatus) || seen.has(content)) continue;
    seen.add(content);
    todos.push({ content, status: itemStatus });
  }
  if (!todos.length) return undefined;
  const counts = {
    pending: todos.filter(item => item.status === 'pending').length,
    in_progress: todos.filter(item => item.status === 'in_progress').length,
    completed: todos.filter(item => item.status === 'completed').length,
  };
  return { id: text(source.id || source.todo_id, 'todo_current'), title: text(source.title, '任务清单'), todos, counts };
}

export function parseRuntimeTodo(event: ExecutionEventView): RuntimeTodoSnapshot | undefined {
  const type = event.eventType.toLowerCase();
  if (!(type === 'todo' || type === 'todo_update' || type.includes('todo_') || type === 'custom')) return undefined;
  for (const candidate of [...candidates(event.projection), ...candidates(event.payload)]) {
    const todo = normalizeTodo(candidate);
    if (todo) return todo;
  }
  return undefined;
}

export function latestRuntimeTodo(events: ExecutionEventView[]): RuntimeTodoSnapshot | undefined {
  let latest: RuntimeTodoSnapshot | undefined;
  for (const event of [...events].sort((left, right) => left.cursor - right.cursor)) {
    const todo = parseRuntimeTodo(event);
    if (todo) latest = todo;
  }
  return latest;
}

/** Convert durable execution events into a compact, safe, reusable timeline. */
export function parseRuntimeTimeline(events: ExecutionEventView[]): RuntimeTimelineItem[] {
  const items: RuntimeTimelineItem[] = [];
  for (const event of [...events].sort((left, right) => left.cursor - right.cursor).slice(-MAX_ITEMS)) {
    const eventKind = kind(event);
    // Delta events are rendered by the message body/reasoning panel. Keeping
    // them out of the compact timeline prevents one row per token.
    if (event.eventType.endsWith('_delta') && eventKind !== 'todo') continue;
    const question = eventKind === 'question' ? parseRuntimeUserQuestionEvent(event) || undefined : undefined;
    const todo = eventKind === 'todo' ? parseRuntimeTodo(event) : undefined;
    if (eventKind === 'question' && !question) continue;
    if (eventKind === 'todo' && !todo) continue;
    const projection = event.projection || {};
    const payload = event.payload || {};
    const rawParent = projection.parentEventId || projection.parent_event_id || projection.parentId || projection.parent_id
      || payload.parentEventId || payload.parent_event_id || payload.parentId || payload.parent_id;
    const parentId = typeof rawParent === 'string' || typeof rawParent === 'number' ? String(rawParent) : null;
    const item: RuntimeTimelineItem = {
      id: event.eventId,
      kind: eventKind,
      title: title(event, eventKind),
      summary: text(event.summary),
      status: status(event),
      eventType: event.eventType,
      eventId: event.eventId,
      traceId: event.traceId,
      parentId,
      occurredAt: event.occurredAt,
      durationMs: typeof projection.durationMs === 'number' ? projection.durationMs : null,
      details: safeDetails(event),
      ...(question ? { question } : {}),
      ...(todo ? { todo } : {}),
    };
    items.push(item);
  }
  return items;
}

export function latestRuntimeTodoFromTimeline(items: RuntimeTimelineItem[]) {
  return [...items].reverse().find(item => item.todo)?.todo;
}

export function runtimeTimelineHasPending(items: RuntimeTimelineItem[]) {
  return items.some(item => item.status === 'pending' || item.status === 'running' || item.todo?.todos.some(todo => todo.status !== 'completed'));
}
