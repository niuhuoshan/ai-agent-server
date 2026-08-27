<script setup lang="ts">
import { computed } from 'vue';
import SvgIcon from '@/components/custom/svg-icon.vue';
import type { ExecutionEventView } from '@/service/api';
import MessageConfirmationCard from './message-confirmation-card.vue';
import ChatExecutionTimeline from '@/components/chat/ChatExecutionTimeline.vue';
import ChatTodoCard from '@/components/chat/ChatTodoCard.vue';
import UserQuestionCard from '@/components/UserQuestionCard.vue';
import {
  parseBusinessConfirmationEvent,
  type BusinessConfirmationField,
  type BusinessConfirmationState,
} from '@/utils/business-confirmation';
import {
  parseRuntimeUserQuestions,
  type RuntimeUserQuestionState,
  type RuntimeUserQuestionStatus,
} from '@/utils/runtime-user-question';
import { parseRuntimeTimeline } from '@/utils/runtime-timeline';
import { resolveGeneratedFileHref } from '@/utils/generated-file-url';

const props = withDefaults(defineProps<{
  events: ExecutionEventView[];
  active?: boolean;
  resolvedConfirmations?: Record<string, 'confirmed' | 'cancelled'>;
  staleConfirmations?: Record<string, true>;
  submittingConfirmationId?: string | null;
  questionStates?: Record<string, RuntimeUserQuestionStatus>;
  submittingQuestionId?: string | null;
  questionSnapshots?: RuntimeUserQuestionState[];
  hideTodo?: boolean;
}>(), {
  active: false,
  resolvedConfirmations: () => ({}),
  staleConfirmations: () => ({}),
  submittingConfirmationId: null,
  questionStates: () => ({}),
  submittingQuestionId: null,
  questionSnapshots: () => [],
  hideTodo: false,
});

const emit = defineEmits<{
  confirmationSubmit: [payload: {
    confirmation: BusinessConfirmationState;
    confirmed: boolean;
    fields: BusinessConfirmationField[];
  }];
  userQuestionSubmit: [payload: {
    question: RuntimeUserQuestionState;
    selectedOptionIds: string[];
    customInput: string;
    cancelled: boolean;
  }];
  retry: [traceId: string];
}>();

const reasoning = computed(() => {
  const events = props.events
    .filter(event => event.eventType.startsWith('thinking_'))
    .sort((left, right) => left.cursor - right.cursor);
  if (!events.length) return null;
  const content = events
    .filter(event => event.eventType === 'thinking_delta')
    .map(event => typeof event.payload?.delta === 'string' ? event.payload.delta : '')
    .join('');
  return {
    complete: events.some(event => event.eventType === 'thinking_finished'),
    redacted: !content && events.some(event => event.payload?.redacted === true),
    content,
  };
});

const confirmations = computed(() => {
  const states = new Map<string, BusinessConfirmationState>();
  for (const event of [...props.events].sort((left, right) => left.cursor - right.cursor)) {
    const parsed = parseBusinessConfirmationEvent(event);
    if (!parsed) continue;
    const stale = props.staleConfirmations[parsed.confirmation_id];
    const decision = props.resolvedConfirmations[parsed.confirmation_id];
    states.set(parsed.confirmation_id, stale
      ? { ...parsed, status: 'stale' }
      : decision
      ? { ...parsed, status: 'submitted', decision }
      : parsed);
  }
  return [...states.values()].slice(-4);
});

const toolEvents = computed(() => props.events.filter(event =>
  event.eventType.startsWith('tool_call_')
  || event.eventType.startsWith('tool_result_')
  || event.eventType.startsWith('external_execution_')
  || event.eventType.startsWith('approval_')
  || event.eventType === 'subagent_event'
).filter((event, index, values) => {
  if (!event.eventType.endsWith('_delta')) return true;
  return index === values.length - 1 || values[index + 1]?.eventType !== event.eventType;
}).slice(-32));

const failures = computed(() => props.events.filter(event => [
  'failed',
  'permission_denied',
  'iteration_limit_reached',
  'cancelled',
].includes(event.eventType)).slice(-8));

function isRetryable(event: ExecutionEventView) {
  const values = [event.payload?.retryable, event.projection?.retryable];
  return values.some(value => value === true || value === 'true');
}

const userQuestions = computed(() => {
  const byId = new Map<string, RuntimeUserQuestionState>();
  [...parseRuntimeUserQuestions(props.events), ...props.questionSnapshots].forEach(question => {
    byId.set(question.question_id || `event:${question.event_id}`, question);
  });
  return [...byId.values()].map(question => {
    const override = question.question_id ? props.questionStates[question.question_id] : undefined;
    return override ? { ...question, status: override } : question;
  });
});

const timelineVisible = computed(() => parseRuntimeTimeline(props.events).some(item => item.kind !== 'question' && item.kind !== 'todo'));
const todoVisible = computed(() => !props.hideTodo
  && parseRuntimeTimeline(props.events).some(item => item.kind === 'todo' && item.todo));

function recordValues(event: ExecutionEventView): Record<string, unknown>[] {
  const result: Record<string, unknown>[] = [];
  const visit = (value: unknown, depth: number) => {
    if (depth > 5 || value === null || value === undefined) return;
    if (typeof value === 'string') {
      try { visit(JSON.parse(value), depth + 1); } catch { /* plain text */ }
      return;
    }
    if (Array.isArray(value)) {
      value.slice(0, 32).forEach(item => visit(item, depth + 1));
      return;
    }
    if (typeof value !== 'object') return;
    const object = value as Record<string, unknown>;
    result.push(object);
    Object.values(object).forEach(item => visit(item, depth + 1));
  };
  visit(event.payload, 0);
  visit(event.projection, 0);
  return result;
}

function valueFor(event: ExecutionEventView, ...keys: string[]) {
  for (const object of recordValues(event)) {
    for (const key of keys) {
      const value = object[key];
      if (value !== undefined && value !== null && String(value).trim() !== '') return value;
    }
  }
  return null;
}

function toolName(event: ExecutionEventView) {
  if (event.eventType === 'subagent_event') return 'sub_agent_call';
  const projection = event.projection || {};
  const payload = event.payload || {};
  const value = projection.toolName || projection.tool_name || payload.toolName || payload.tool_name;
  if (typeof value === 'string' && value.trim()) return value.trim();
  const match = (event.summary || '').match(/(?:Tool call started:\s*|tool\s*[=:]\s*)([^\s,]+)/i);
  return match?.[1] || '';
}

const TOOL_LABELS: Record<string, string> = {
  update_dashboard_context: '仪表盘上下文',
  request_user_confirmation: '业务确认',
  sub_agent_call: '子 Agent 委派',
  sub_agent_batch_call: '批量子 Agent 委派',
  jira_search: 'Jira 查询',
  jira_get_projects: 'Jira 项目',
  jira_create_issue: 'Jira 创建工单',
  sqlite_scratchpad: 'SQLite 临时沙盒',
  excel_document_read: 'Excel 读取',
  excel_document_write: 'Excel 写入',
  word_document_read: 'Word 读取',
  word_document_write: 'Word 写入',
};

function toolLabel(name: string) {
  return TOOL_LABELS[name] || name || '工具';
}

function formatValue(value: unknown) {
  if (value === null || value === undefined || value === '') return '';
  if (Array.isArray(value)) return `${value.length} 项`;
  if (typeof value === 'object') return '结构化结果';
  return String(value).slice(0, 220);
}

function eventDetail(event: ExecutionEventView) {
  const name = toolName(event);
  if (name === 'update_dashboard_context') {
    const context = [
      valueFor(event, 'room_name', 'roomName'),
      valueFor(event, 'metric_name', 'metricName'),
      valueFor(event, 'time_range', 'timeRange'),
    ].filter(Boolean).map(formatValue);
    return context.length ? `已更新：${context.join(' · ')}` : '已更新当前会话的仪表盘上下文';
  }
  if (name === 'sub_agent_call') {
    const agent = formatValue(valueFor(event, 'agent_name', 'agentName'));
    const status = formatValue(valueFor(event, 'delegationStatus', 'status', 'state'));
    return [agent && `目标 ${agent}`, status && `状态 ${status}`].filter(Boolean).join(' · ') || '委派已记录，等待子 Agent 返回';
  }
  if (name === 'sub_agent_batch_call') {
    const completed = valueFor(event, 'delegationCompletedCount', 'completed_count');
    const failed = valueFor(event, 'delegationFailedCount', 'failed_count');
    const pending = valueFor(event, 'delegationPendingCount', 'pending_count');
    const total = valueFor(event, 'delegationCount', 'count');
    const summary = [
      completed !== null && `${formatValue(completed)} 个成功`,
      failed !== null && `${formatValue(failed)} 个失败`,
      pending !== null && `${formatValue(pending)} 个等待中`,
    ].filter(Boolean).join('、');
    return summary || (total !== null ? `共 ${formatValue(total)} 个子 Agent` : '批量委派已记录，等待子 Agent 返回');
  }
  if (name.startsWith('jira_')) {
    const error = valueFor(event, 'error', 'error_summary', 'errorMessage', 'resultMessage');
    if (error) return `Provider 错误：${formatValue(error)}`;
    const issues = valueFor(event, 'issues', 'total', 'issue_count');
    return issues !== null ? `返回 ${formatValue(issues)} 条记录` : name === 'jira_create_issue' ? '工单写入请求已提交' : 'Jira 查询完成';
  }
  if (name === 'sqlite_scratchpad') {
    const rows = valueFor(event, 'rows', 'row_count', 'affected_rows');
    const session = valueFor(event, 'session_id', 'sessionId');
    return [session && `会话 ${formatValue(session)}`, rows !== null && `结果 ${formatValue(rows)}`].filter(Boolean).join(' · ') || 'SQLite 临时沙盒已执行';
  }
  if (name.startsWith('excel_document_') || name.startsWith('word_document_')) {
    const file = valueFor(event, 'filename', 'file_name', 'fileName', 'output_filename', 'artifact_name');
    const url = artifactUrl(event);
    return file ? `制品：${formatValue(file)}${url ? ' · 可下载' : ''}` : (url ? '文档制品已生成 · 可下载' : '文档操作已完成');
  }
  return event.summary || `${toolLabel(name)}${event.eventStatus ? ` · ${event.eventStatus}` : ''}`;
}

function artifactUrl(event: ExecutionEventView) {
  const value = valueFor(event, 'download_url', 'downloadUrl', 'artifact_url', 'artifactUrl', 'file_url', 'fileUrl', 'url');
  if (typeof value !== 'string' || !value.trim()) return null;
  const normalized = value.trim();
  if (normalized.startsWith('/') || /^https?:\/\//i.test(normalized)) {
    return resolveGeneratedFileHref(normalized);
  }
  return null;
}

function eventLabel(eventType: string) {
  const labels: Record<string, string> = {
    tool_call_started: '工具调用开始',
    tool_call_delta: '工具参数生成中',
    tool_call_finished: '工具调用已提交',
    tool_result_started: '工具执行中',
    tool_result_delta: '工具结果返回中',
    tool_result_finished: '工具执行完成',
    approval_required: '等待审批',
    approval_resolved: '审批已处理',
    external_execution_required: '等待外部执行',
    external_execution_resolved: '外部执行完成',
    subagent_event: '子 Agent 状态',
    failed: '执行失败',
    permission_denied: '权限不足',
    iteration_limit_reached: '达到迭代上限',
    cancelled: '执行已停止',
  };
  return labels[eventType] || eventType;
}

function statusType(status: string, event: ExecutionEventView) {
  const resultStatus = String(event.projection?.resultStatus || '').toLowerCase();
  const failedCount = Number(valueFor(event, 'delegationFailedCount', 'failed_count') || 0);
  const summary = `${event.summary || ''} ${JSON.stringify(event.payload || {})}`.toLowerCase();
  if (failedCount > 0) return 'error';
  if (['awaiting_user', 'queued', 'running', 'pending'].includes(resultStatus)) return 'warning';
  if (resultStatus === 'degraded') return 'warning';
  if (['failed', 'failure', 'error', 'unavailable', 'timeout', 'timed_out',
    'provider_error', 'transport_error', 'query_error', 'tool_unavailable',
    'authorization_error', 'invalid_arguments', 'conflict', 'rejected', 'denied',
    'cancelled', 'expired', 'partial_failure'].includes(resultStatus)) return 'error';
  if (status === 'failed' || summary.includes('error') || summary.includes('unavailable')) return 'error';
  if (status === 'pending' || status === 'running') return 'warning';
  return 'success';
}

function evidenceLabel(event: ExecutionEventView) {
  const status = String(valueFor(event, 'evidenceStatus', 'evidence_status') || '').toLowerCase();
  if (status === 'failed') return '证据未签发';
  if (status === 'degraded') return '检索降级，证据未签发';
  if (status !== 'verified') return null;
  const labels: Record<string, string> = {
    internal_data: '数据证据',
    internal_knowledge: '知识证据',
    public_web: '网页证据',
    runtime_state: '运行状态证据',
    user_file: '文件证据',
    conversation_memory: '记忆证据',
    external_tool: '外部工具证据',
  };
  const type = String(valueFor(event, 'evidenceType', 'evidence_type') || '');
  return labels[type] || '已验证证据';
}

function submitConfirmation(
  confirmation: BusinessConfirmationState,
  payload: { confirmed: boolean; fields: BusinessConfirmationField[] },
) {
  if (props.submittingConfirmationId === confirmation.confirmation_id
    || props.staleConfirmations[confirmation.confirmation_id]
    || props.resolvedConfirmations[confirmation.confirmation_id]) return;
  emit('confirmationSubmit', { confirmation, ...payload });
}
</script>

<template>
  <div v-if="reasoning || confirmations.length || toolEvents.length || failures.length || userQuestions.length || timelineVisible || todoVisible" class="message-events">
    <ChatExecutionTimeline :events="events" :active="active" />

    <UserQuestionCard
      v-for="question in userQuestions"
      :key="question.question_id || question.event_id"
      :payload="question"
      :disabled="submittingQuestionId === question.question_id"
      @submit="emit('userQuestionSubmit', { question, ...$event })"
    />

    <ChatTodoCard v-if="!hideTodo" :events="events" />

    <div v-if="reasoning" class="reasoning-status">
      <SvgIcon icon="lucide:brain-circuit" />
      <span>{{ reasoning.complete ? '推理完成' : '正在推理' }}</span>
      <NTag v-if="reasoning.redacted" size="tiny" :bordered="false">内容受保护</NTag>
    </div>

    <NCollapse v-if="reasoning?.content" class="reasoning-panel">
      <NCollapseItem name="reasoning" title="查看推理过程">
        <pre>{{ reasoning.content }}</pre>
      </NCollapseItem>
    </NCollapse>

    <MessageConfirmationCard
      v-for="confirmation in confirmations"
      :key="confirmation.confirmation_id"
      :confirmation="confirmation"
      :disabled="submittingConfirmationId === confirmation.confirmation_id"
      @submit="submitConfirmation(confirmation, $event)"
    />

    <NCollapse v-if="toolEvents.length" class="tool-events">
      <NCollapseItem name="tools">
        <template #header>
          <span class="collapse-title">
            <SvgIcon icon="lucide:wrench" />
            工具与审批 · {{ toolEvents.length }} 项事件
          </span>
        </template>
        <div class="event-list">
          <div v-for="event in toolEvents" :key="event.eventId" class="event-row">
            <div class="event-copy">
              <strong>
                {{ toolLabel(toolName(event)) }}
                <span class="event-kind">{{ eventLabel(event.eventType) }}</span>
              </strong>
              <span>{{ eventDetail(event) }}</span>
              <a
                v-if="artifactUrl(event)"
                class="artifact-link"
                :href="artifactUrl(event) || undefined"
                target="_blank"
                rel="noopener noreferrer"
              >
                打开制品
              </a>
            </div>
            <NTag v-if="evidenceLabel(event)" size="tiny" :bordered="false" type="info">
              {{ evidenceLabel(event) }}
            </NTag>
            <NTag size="tiny" :type="statusType(event.eventStatus, event)" :bordered="false">
              {{ event.eventStatus }}
            </NTag>
          </div>
        </div>
      </NCollapseItem>
    </NCollapse>

    <NAlert
      v-for="event in failures"
      :key="event.eventId"
      :type="event.eventType === 'cancelled' ? 'warning' : 'error'"
      :title="eventLabel(event.eventType)"
      :show-icon="true"
    >
      <div class="failure-content">
        <span>{{ event.summary || '执行未完成' }}</span>
        <NButton
          v-if="!active && isRetryable(event) && event.traceId"
          size="tiny"
          secondary
          type="error"
          @click="emit('retry', event.traceId)"
        >
          <template #icon><SvgIcon icon="lucide:rotate-ccw" /></template>
          重试
        </NButton>
      </div>
    </NAlert>

    <div v-if="active && !failures.length" class="event-running">
      <SvgIcon icon="lucide:loader-circle" />
      <span>执行中</span>
    </div>
  </div>
</template>

<style scoped>
.message-events { display: grid; margin-bottom: 10px; gap: 8px; }
.reasoning-status, .event-running, .collapse-title { display: inline-flex; align-items: center; gap: 7px; }
.reasoning-status, .event-running { min-height: 30px; color: var(--n-text-color-2); font-size: 12px; }
.reasoning-panel { padding: 0 10px; border: 1px solid var(--n-border-color); border-radius: 6px; }
.reasoning-panel pre { margin: 0; color: var(--n-text-color-2); font: inherit; line-height: 1.65; white-space: pre-wrap; overflow-wrap: anywhere; }
.event-running svg { animation: spin 1s linear infinite; }
.tool-events { padding: 0 10px; border: 1px solid var(--n-border-color); border-radius: 6px; }
.event-list { display: grid; gap: 8px; }
.event-row { display: flex; padding: 8px 0; border-top: 1px solid var(--n-border-color); align-items: flex-start; justify-content: space-between; gap: 12px; }
.event-row:first-child { border-top: 0; }
.event-copy { display: grid; min-width: 0; gap: 2px; }
.event-copy strong { font-size: 12px; overflow-wrap: anywhere; }
.event-copy span { color: var(--n-text-color-3); font-size: 12px; overflow-wrap: anywhere; }
.event-kind { margin-left: 6px; color: var(--n-text-color-3); font-weight: 400; }
.artifact-link { color: var(--n-primary-color); font-size: 12px; }
.failure-content { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
