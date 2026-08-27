import type { AgentDebugExecutionEvent } from '@/service/api/agent-debug';

export type DebugEventKind = 'run' | 'model' | 'thinking' | 'output' | 'tool' | 'approval' | 'error';

const STATUS_TEXT: Record<string, string> = {
  queued: '排队中',
  preparing: '准备中',
  running: '运行中',
  waiting_approval: '等待审批',
  waiting_input: '等待输入',
  blocked: '已阻塞',
  paused: '已停止，可恢复',
  succeeded: '已完成',
  failed: '失败',
  cancelled: '已取消',
  expired: '已过期'
};

export function debugRunStatusText(status: string) {
  return STATUS_TEXT[status] || status;
}

export function debugRunStatusType(status: string): 'default' | 'success' | 'warning' | 'error' | 'info' {
  if (status === 'succeeded') return 'success';
  if (['failed', 'cancelled', 'expired'].includes(status)) return 'error';
  if (['waiting_approval', 'waiting_input', 'blocked', 'paused'].includes(status)) return 'warning';
  if (['queued', 'preparing', 'running'].includes(status)) return 'info';
  return 'default';
}

export function isDebugRunStoppable(status: string) {
  return ['queued', 'preparing', 'running', 'waiting_approval', 'waiting_input', 'blocked'].includes(status);
}

export function isDebugRunResumable(status: string) {
  return ['paused', 'blocked', 'waiting_input'].includes(status);
}

export function isDebugRunRetryable(status: string) {
  return ['failed', 'cancelled', 'expired'].includes(status);
}

export function shouldStreamDebugRun(status: string) {
  return ['queued', 'preparing', 'running', 'waiting_approval'].includes(status);
}

export function debugEventKind(eventType: string): DebugEventKind {
  if (eventType.includes('approval') || eventType.includes('external_execution')) return 'approval';
  if (eventType.includes('tool')) return 'tool';
  if (eventType.includes('model')) return 'model';
  if (eventType.includes('thinking')) return 'thinking';
  if (eventType.includes('text') || eventType === 'result') return 'output';
  if (['failed', 'cancelled', 'permission_denied', 'iteration_limit_reached'].includes(eventType)) return 'error';
  return 'run';
}

export function debugEventTitle(event: AgentDebugExecutionEvent) {
  const type = event.eventType;
  const projection = event.projection || {};
  if (type === 'model_call_started') return `模型调用 · ${String(projection.model || '未命名模型')}`;
  if (type === 'model_call_finished') return `模型返回 · ${String(projection.model || '未命名模型')}`;
  if (type === 'tool_call_started') return `调用工具 · ${String(projection.toolName || '未命名工具')}`;
  if (type === 'tool_call_finished') return `工具参数完成 · ${String(projection.toolName || '未命名工具')}`;
  if (type === 'tool_result_finished') return `工具返回 · ${String(projection.toolName || '未命名工具')}`;
  if (type === 'approval_required') return '工具调用等待审批';
  if (type === 'approval_resolved') return '工具审批已处理';
  if (type === 'run_started') return '运行已启动';
  if (type === 'run_finished') return '运行已完成';
  if (type === 'text_started') return '开始生成回复';
  if (type === 'text_finished') return '回复生成完成';
  if (type === 'thinking_started') return '开始推理';
  if (type === 'thinking_finished') return '推理完成';
  if (type === 'permission_denied') return '权限校验拒绝';
  if (type === 'failed') return '运行失败';
  if (type === 'cancelled') return '运行已停止';
  return type.replaceAll('_', ' ');
}

export function mergeAgentDebugEvents(
  current: AgentDebugExecutionEvent[],
  incoming: AgentDebugExecutionEvent[]
) {
  const byId = new Map(current.map(event => [event.eventId, event]));
  incoming.forEach(event => byId.set(event.eventId, event));
  return [...byId.values()].sort((left, right) => left.cursor - right.cursor);
}

export function debugFinalOutput(events: AgentDebugExecutionEvent[], fallback = '') {
  const retraction = [...events].reverse().find(event =>
    event.eventType === 'custom' && event.payload?.retraction === true
  );
  if (retraction) return retraction.summary || '此响应因安全策略已撤回';
  const deltas = events.filter(event => event.eventType === 'text_delta').map(event => event.summary || '').join('');
  if (deltas) return deltas;
  const result = [...events].reverse().find(event => event.eventType === 'result' && event.summary);
  return result?.summary || fallback;
}

export function formatDebugDuration(milliseconds: number) {
  if (!Number.isFinite(milliseconds) || milliseconds <= 0) return '0 ms';
  if (milliseconds < 1000) return `${Math.round(milliseconds)} ms`;
  if (milliseconds < 60_000) return `${(milliseconds / 1000).toFixed(1)} s`;
  return `${Math.floor(milliseconds / 60_000)}m ${Math.round((milliseconds % 60_000) / 1000)}s`;
}
