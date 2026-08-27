<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NAlert, NButton, NDivider, NDrawer, NDrawerContent, NEmpty, NInput, NResult, NScrollbar, NSelect, NSpin, NSpace, NTag } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import BrowserPanel from '@/views/workspace/modules/browser-panel.vue';
import {
  createAgentDebugRun,
  fetchAgentDebugEvents,
  fetchAgentDebugOptions,
  fetchAgentDebugRun,
  fetchAgentDebugRuns,
  fetchApprovals,
  resumeAgentDebugRun,
  retryAgentDebugRun,
  stopAgentDebugRun,
  streamAgentDebugEvents,
  decideApproval,
  type AgentDebugExecutionEvent,
  type AgentDebugOption,
  type AgentDebugRunDetail,
  type AgentDebugRunSummary,
  type AgentDebugVersionOption
} from '@/service/api';
import {
  debugEventKind,
  debugEventTitle,
  debugFinalOutput,
  debugRunStatusText,
  debugRunStatusType,
  formatDebugDuration,
  isDebugRunResumable,
  isDebugRunRetryable,
  isDebugRunStoppable,
  mergeAgentDebugEvents,
  shouldStreamDebugRun
} from './presentation';
import type { ApprovalView } from '@/service/api/platform';

const route = useRoute();
const router = useRouter();

const options = ref<AgentDebugOption[]>([]);
const history = ref<AgentDebugRunSummary[]>([]);
const selectedAgentId = ref<string | null>(null);
const selectedVersionId = ref<string | null>(null);
const input = ref('');
const activeDebugRunId = ref<string | null>(null);
const activeDetail = ref<AgentDebugRunDetail | null>(null);
const events = ref<AgentDebugExecutionEvent[]>([]);
const pendingApprovals = ref<ApprovalView[]>([]);
const loading = ref(true);
const historyLoading = ref(false);
const detailLoading = ref(false);
const submitting = ref(false);
const actionLoading = ref(false);
const replayLoading = ref(false);
const streamError = ref('');
const pageError = ref('');
const detailError = ref('');
const streaming = ref(false);
const streamCursor = ref(0);
const browserVisible = ref(false);
let streamAbort: AbortController | null = null;
let generation = 0;

const selectedAgent = computed(() => options.value.find(item => item.id === selectedAgentId.value) || null);
const versionOptions = computed(() => selectedAgent.value?.versions || []);
const selectedVersion = computed<AgentDebugVersionOption | null>(() =>
  versionOptions.value.find(item => item.id === selectedVersionId.value) || null
);
const activeRun = computed(() => activeDetail.value?.summary.run || null);
const activeStatus = computed(() => activeRun.value?.status || 'idle');
const output = computed(() => debugFinalOutput(events.value, activeDetail.value?.finalOutput || ''));
const displayEventCount = computed(() => activeDetail.value
  ? Math.max(activeDetail.value.metrics.eventCount, events.value.length)
  : events.value.length);
const liveApprovals = computed(() => pendingApprovals.value.filter(item => item.runId === activeRun.value?.id));
const activeRunTitle = computed(() => {
  if (!activeDetail.value) return '尚未开始调试';
  return `${activeDetail.value.summary.agentName} · v${activeDetail.value.summary.versionNo}`;
});
const canSubmit = computed(() => Boolean(
  selectedAgentId.value && selectedVersionId.value && input.value.trim() && !submitting.value
));
const historyGroups = computed(() => {
  const groups = new Map<string, AgentDebugRunSummary[]>();
  history.value.forEach(item => {
    const key = item.createdAt.slice(0, 10);
    const values = groups.get(key) || [];
    values.push(item);
    groups.set(key, values);
  });
  return [...groups.entries()];
});

function versionLabel(version: AgentDebugVersionOption) {
  return `v${version.versionNo} · ${version.status === 'published' ? '当前发布' : '历史发布'}`;
}

function selectAgent(value: string | null) {
  selectedAgentId.value = value;
  const agent = options.value.find(item => item.id === value);
  selectedVersionId.value = agent?.publishedVersionId && agent.versions.some(item => item.id === agent.publishedVersionId)
    ? agent.publishedVersionId
    : agent?.versions[0]?.id || null;
}

function selectVersion(value: string | null) {
  selectedVersionId.value = value;
}

function statusTag(status: string) {
  return debugRunStatusType(status);
}

function formatTime(value: string | null | undefined) {
  if (!value) return '-';
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
}

function eventTime(event: AgentDebugExecutionEvent) {
  return new Date(event.occurredAt).toLocaleTimeString('zh-CN', { hour12: false });
}

function eventClass(event: AgentDebugExecutionEvent) {
  return `event-${debugEventKind(event.eventType)}`;
}

function eventDetails(event: AgentDebugExecutionEvent) {
  const projection = event.projection || {};
  const details: string[] = [];
  if (projection.model) details.push(`模型 ${String(projection.model)}`);
  if (projection.toolName) details.push(`工具 ${String(projection.toolName)}`);
  if (projection.totalTokens != null) details.push(`Token ${String(projection.totalTokens)}`);
  if (projection.durationMs != null) details.push(`${Math.round(Number(projection.durationMs))} ms`);
  if (event.eventStatus === 'failed') details.push('失败');
  return details.join(' · ');
}

function eventSummary(event: AgentDebugExecutionEvent) {
  if (event.eventType === 'text_delta' || event.eventType === 'thinking_delta') return '';
  return event.summary || '';
}

function newIdempotencyKey(prefix: string) {
  const random = typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID().replaceAll('-', '')
    : `${Date.now()}${Math.random().toString(16).slice(2)}`;
  return `${prefix}-${random}`.slice(0, 96);
}

async function loadPage() {
  loading.value = true;
  pageError.value = '';
  const [optionResult, historyResult] = await Promise.all([
    fetchAgentDebugOptions(), fetchAgentDebugRuns(50)
  ]);
  if (optionResult.error) pageError.value = optionResult.error.message || 'Agent 选项加载失败';
  else options.value = optionResult.data;
  if (!historyResult.error) history.value = historyResult.data;
  else if (!pageError.value) pageError.value = historyResult.error.message || '调试历史加载失败';
  loading.value = false;

  const routeAgent = typeof route.query.agent_id === 'string' ? route.query.agent_id : null;
  const routeVersion = typeof route.query.version_id === 'string' ? route.query.version_id : null;
  const routeRunId = typeof route.query.run_id === 'string' ? route.query.run_id : null;
  const initialAgent = options.value.find(item => item.id === routeAgent) || options.value[0];
  if (initialAgent) {
    selectedAgentId.value = initialAgent.id;
    const version = initialAgent.versions.find(item => item.id === routeVersion)
      || initialAgent.versions.find(item => item.id === initialAgent.publishedVersionId)
      || initialAgent.versions[0];
    selectedVersionId.value = version?.id || null;
  }
  // Resolve a deep link after history is loaded; a watcher alone would miss
  // the initial route because the query value itself does not change.
  const initialRun = routeRunId ? history.value.find(item => item.id === routeRunId) : undefined;
  if (initialRun) {
    await openRun(initialRun);
  } else {
    await refreshApprovals();
  }
}

async function refreshHistory() {
  historyLoading.value = true;
  const result = await fetchAgentDebugRuns(50);
  if (!result.error) history.value = result.data;
  historyLoading.value = false;
}

async function refreshApprovals() {
  const result = await fetchApprovals('pending', 100);
  if (!result.error) pendingApprovals.value = result.data;
}

async function refreshActiveState(debugRunId: string) {
  const [detailResult] = await Promise.all([
    fetchAgentDebugRun(debugRunId),
    refreshApprovals()
  ]);
  if (activeDebugRunId.value === debugRunId && !detailResult.error && detailResult.data) {
    activeDetail.value = detailResult.data;
  }
}

async function loadReplay(debugRunId: string) {
  replayLoading.value = true;
  const replayed: AgentDebugExecutionEvent[] = [];
  let cursor = 0;
  try {
    while (true) {
      const result = await fetchAgentDebugEvents(debugRunId, cursor, 500);
      if (result.error) throw new Error(result.error.message || '事件重放失败');
      replayed.push(...result.data);
      const last = result.data.at(-1);
      if (!last || result.data.length < 500 || last.cursor <= cursor) break;
      cursor = last.cursor;
    }
    events.value = mergeAgentDebugEvents([], replayed);
    streamCursor.value = events.value.at(-1)?.cursor || 0;
  } catch (error) {
    detailError.value = error instanceof Error ? error.message : '事件重放失败';
  } finally {
    replayLoading.value = false;
  }
}

async function openRun(summary: AgentDebugRunSummary, detail?: AgentDebugRunDetail) {
  stopStream();
  activeDebugRunId.value = summary.id;
  activeDetail.value = null;
  detailError.value = '';
  streamError.value = '';
  events.value = [];
  input.value = summary.input;
  selectAgent(summary.agentId);
  selectedVersionId.value = summary.agentVersionId;
  void router.replace({
    query: {
      ...route.query,
      run_id: summary.id,
      agent_id: summary.agentId,
      version_id: summary.agentVersionId
    }
  });
  detailLoading.value = true;
  const runGeneration = ++generation;
  try {
    const result = detail ? { data: detail, error: null } : await fetchAgentDebugRun(summary.id);
    if (runGeneration !== generation) return;
    if (result.error || !result.data) throw new Error(result.error?.message || '运行详情加载失败');
    activeDetail.value = result.data;
    await loadReplay(summary.id);
    if (shouldStreamDebugRun(result.data.summary.run.status)) startStream(summary.id, streamCursor.value);
    await refreshApprovals();
  } catch (error) {
    detailError.value = error instanceof Error ? error.message : '运行详情加载失败';
  } finally {
    detailLoading.value = false;
  }
}

async function submitRun() {
  if (!canSubmit.value || !selectedAgentId.value || !selectedVersionId.value) return;
  submitting.value = true;
  pageError.value = '';
  try {
    const result = await createAgentDebugRun({
      idempotencyKey: newIdempotencyKey('debug'),
      agentId: selectedAgentId.value,
      agentVersionId: selectedVersionId.value,
      input: input.value
    });
    if (result.error) throw new Error(result.error.message || '调试运行创建失败');
    await refreshHistory();
    if (result.data) await openRun(result.data.summary, result.data);
    window.$message?.success('调试运行已提交，正在接收持久化事件');
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '调试运行创建失败';
  } finally {
    submitting.value = false;
  }
}

async function stopActiveRun() {
  if (!activeDebugRunId.value || !isDebugRunStoppable(activeStatus.value)) return;
  actionLoading.value = true;
  try {
    const result = await stopAgentDebugRun(activeDebugRunId.value, 'Agent调试台主动停止');
    if (result.error) throw new Error(result.error.message || '停止失败');
    if (result.data) {
      activeDetail.value = result.data;
      await loadReplay(activeDebugRunId.value);
    }
    stopStream();
    await refreshHistory();
  } catch (error) {
    streamError.value = error instanceof Error ? error.message : '停止失败';
  } finally {
    actionLoading.value = false;
  }
}

async function resumeActiveRun() {
  if (!activeDebugRunId.value || !isDebugRunResumable(activeStatus.value)) return;
  actionLoading.value = true;
  try {
    const result = await resumeAgentDebugRun(activeDebugRunId.value);
    if (result.error) throw new Error(result.error.message || '恢复失败');
    if (result.data) activeDetail.value = result.data;
    await loadReplay(activeDebugRunId.value);
    startStream(activeDebugRunId.value, streamCursor.value);
    await refreshHistory();
  } catch (error) {
    streamError.value = error instanceof Error ? error.message : '恢复失败';
  } finally {
    actionLoading.value = false;
  }
}

async function retryActiveRun() {
  if (!activeDebugRunId.value || !isDebugRunRetryable(activeStatus.value)) return;
  actionLoading.value = true;
  try {
    const result = await retryAgentDebugRun(activeDebugRunId.value, newIdempotencyKey('retry'));
    if (result.error) throw new Error(result.error.message || '重试失败');
    await refreshHistory();
    if (result.data) await openRun(result.data.summary, result.data);
  } catch (error) {
    streamError.value = error instanceof Error ? error.message : '重试失败';
  } finally {
    actionLoading.value = false;
  }
}

async function decidePendingApproval(approval: ApprovalView, decision: 'approve' | 'reject') {
  actionLoading.value = true;
  try {
    const result = await decideApproval(approval.id, decision, newIdempotencyKey(`approval-${decision}`));
    if (result.error) throw new Error(result.error.message || '审批操作失败');
    await refreshApprovals();
    if (activeDebugRunId.value) {
      await loadReplay(activeDebugRunId.value);
      const refreshed = await fetchAgentDebugRun(activeDebugRunId.value);
      if (!refreshed.error && refreshed.data) {
        activeDetail.value = refreshed.data;
        if (shouldStreamDebugRun(refreshed.data.summary.run.status)) {
          startStream(activeDebugRunId.value, streamCursor.value);
        }
      }
    }
  } catch (error) {
    streamError.value = error instanceof Error ? error.message : '审批操作失败';
  } finally {
    actionLoading.value = false;
  }
}

function reuseInput(summary: AgentDebugRunSummary) {
  selectAgent(summary.agentId);
  selectedVersionId.value = summary.agentVersionId;
  input.value = summary.input;
  window.$message?.info('已复用该次调试的 Agent、版本和输入，可编辑后重新运行');
}

function clearComposer() {
  stopStream();
  activeDebugRunId.value = null;
  activeDetail.value = null;
  events.value = [];
  input.value = '';
  detailError.value = '';
  streamError.value = '';
  const query = { ...route.query };
  delete query.run_id;
  void router.replace({ query });
}

function openApiPlayground() {
  void router.push({ path: '/open-api', query: { tab: 'playground' } });
}

function stopStream() {
  streamAbort?.abort();
  streamAbort = null;
  streaming.value = false;
}

function startStream(debugRunId: string, cursor: number) {
  stopStream();
  streamError.value = '';
  const controller = new AbortController();
  streamAbort = controller;
  streaming.value = true;
  void streamAgentDebugEvents(
    debugRunId,
    cursor,
    event => {
      if (activeDebugRunId.value !== debugRunId) return;
      events.value = mergeAgentDebugEvents(events.value, [event]);
      streamCursor.value = Math.max(streamCursor.value, event.cursor);
      if (['approval_required', 'approval_resolved', 'external_execution_required']
        .includes(event.eventType)) {
        void refreshActiveState(debugRunId);
      }
    },
    async () => {
      streaming.value = false;
      if (activeDebugRunId.value !== debugRunId) return;
      const refreshed = await fetchAgentDebugRun(debugRunId);
      if (!refreshed.error && refreshed.data) activeDetail.value = refreshed.data;
      await refreshHistory();
      await refreshApprovals();
    },
    controller.signal
  ).catch(error => {
    if (controller.signal.aborted || activeDebugRunId.value !== debugRunId) return;
    streaming.value = false;
    streamError.value = error instanceof Error ? error.message : '事件流连接失败';
  });
}

onMounted(() => void loadPage());
onUnmounted(() => stopStream());
watch(() => route.query.run_id, value => {
  if (typeof value !== 'string') return;
  if (value === activeDebugRunId.value) return;
  const summary = history.value.find(item => item.id === value);
  if (summary) void openRun(summary);
});
</script>

<template>
  <div class="debug-page">
    <header class="debug-header">
      <div>
        <div class="eyebrow">AGENT STUDIO</div>
        <h1>Agent 调试台</h1>
        <p>使用与正式任务相同的权限、工具和持久化运行时，复现一次真实 Agent 调用。</p>
      </div>
      <NSpace align="center" :size="8">
        <NButton secondary @click="browserVisible = true">
          <template #icon><SvgIcon icon="lucide:globe-2" /></template>
          浏览器控制
        </NButton>
        <NButton secondary @click="openApiPlayground">
          <template #icon><SvgIcon icon="lucide:braces" /></template>
          API Playground
        </NButton>
        <NButton secondary @click="clearComposer">
          <template #icon><SvgIcon icon="lucide:plus" /></template>
          新建调试
        </NButton>
      </NSpace>
    </header>

    <NDrawer v-model:show="browserVisible" placement="right" width="min(560px, 100vw)">
      <NDrawerContent title="浏览器控制" closable>
        <BrowserPanel />
      </NDrawerContent>
    </NDrawer>

    <NAlert v-if="pageError" type="error" closable class="page-alert" @close="pageError = ''">
      {{ pageError }}
    </NAlert>

    <div v-if="loading" class="page-loading"><NSpin size="large" /><span>正在加载可用 Agent 与调试历史...</span></div>
    <NResult v-else-if="!options.length && !history.length" status="warning" title="暂无可调试 Agent" description="当前账号没有可执行的已发布 Agent 版本，或 AgentScope 运行时尚未配置。">
      <template #footer><NButton @click="loadPage">重新加载</NButton></template>
    </NResult>
    <NAlert v-if="!options.length && history.length" type="warning" class="page-alert">
      当前没有可执行的 Agent 权限；历史调试运行仍可查看，但不能新建运行。
    </NAlert>

    <div v-if="!loading && (options.length || history.length)" class="debug-grid">
      <aside class="history-pane">
        <div class="pane-heading">
          <div><strong>调试历史</strong><span>{{ history.length }} 次</span></div>
          <NButton text :loading="historyLoading" title="刷新历史" @click="refreshHistory">
            <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
          </NButton>
        </div>
        <NScrollbar class="history-scroll">
          <div v-if="!history.length" class="empty-pane"><NEmpty description="还没有调试运行" /></div>
          <template v-for="[date, items] in historyGroups" :key="date">
            <div class="history-date">{{ date }}</div>
            <button
              v-for="item in items"
              :key="item.id"
              class="history-item"
              :class="{ selected: activeDebugRunId === item.id }"
              type="button"
              @click="openRun(item)"
            >
              <div class="history-item-top">
                <span>{{ item.agentName }} · v{{ item.versionNo }}</span>
                <NTag size="small" :type="statusTag(item.run.status)">{{ debugRunStatusText(item.run.status) }}</NTag>
              </div>
              <div class="history-input">{{ item.input }}</div>
              <div class="history-meta">{{ formatTime(item.createdAt) }} · {{ item.inputSha256.slice(0, 8) }}</div>
            </button>
          </template>
        </NScrollbar>
      </aside>

      <main class="composer-pane">
        <section class="selector-row">
          <div class="selector-field">
            <label>Agent</label>
            <NSelect
              :value="selectedAgentId"
              :options="options.map(item => ({ label: `${item.name} · ${item.agentKey}`, value: item.id }))"
              placeholder="选择可执行 Agent"
              @update:value="selectAgent"
            />
          </div>
          <div class="selector-field">
            <label>版本</label>
            <NSelect
              :value="selectedVersionId"
              :options="versionOptions.map(item => ({ label: versionLabel(item), value: item.id }))"
              placeholder="选择已发布版本"
              :disabled="!selectedAgentId"
              @update:value="selectVersion"
            />
          </div>
          <div v-if="selectedVersion" class="version-hash" :title="selectedVersion.contentHash">
            <SvgIcon icon="lucide:lock-keyhole" /> v{{ selectedVersion.versionNo }} 已冻结
          </div>
        </section>

        <NAlert v-if="streamError" type="warning" closable class="stream-alert" @close="streamError = ''">
          {{ streamError }}
        </NAlert>
        <div v-if="streamError && activeDebugRunId" class="reconnect-row">
          <NButton size="small" @click="startStream(activeDebugRunId, streamCursor)">重新连接事件流</NButton>
        </div>
        <NAlert v-if="detailError" type="error" closable class="stream-alert" @close="detailError = ''">{{ detailError }}</NAlert>

        <section class="conversation-panel">
          <div class="conversation-heading">
            <div>
              <span class="eyebrow">LIVE RUN</span>
              <h2>{{ activeRunTitle }}</h2>
            </div>
            <NSpace align="center" :size="8">
              <NTag v-if="activeRun" :type="statusTag(activeStatus)" size="small">
                {{ debugRunStatusText(activeStatus) }}
              </NTag>
              <span v-if="streaming" class="streaming-indicator"><i /> 持久化事件流</span>
            </NSpace>
          </div>

          <div v-if="detailLoading" class="detail-loading"><NSpin /><span>正在读取运行快照与事件...</span></div>
          <div v-else-if="!activeDetail" class="conversation-empty">
            <strong>选择一个历史运行，或提交新的调试输入</strong>
            <span>回复、工具调用、审批和错误会在右侧按事件顺序呈现。</span>
          </div>
          <NScrollbar v-else class="output-scroll">
            <div class="output-content">
              <div v-if="output" class="assistant-output">{{ output }}</div>
              <div v-else-if="activeStatus === 'waiting_approval'" class="waiting-state">
                <SvgIcon icon="lucide:shield-alert" /><span>运行已暂停，等待工具审批。</span>
              </div>
              <div v-else-if="activeStatus === 'failed'" class="waiting-state error-text">
                <SvgIcon icon="lucide:circle-alert" /><span>{{ activeRun?.errorSummary || '运行失败，请查看右侧错误事件。' }}</span>
              </div>
              <div v-else class="waiting-state"><NSpin size="small" /><span>等待 Agent 输出...</span></div>
            </div>
          </NScrollbar>

          <div class="composer-actions">
            <NInput
              v-model:value="input"
              type="textarea"
              :autosize="{ minRows: 4, maxRows: 10 }"
              maxlength="100000"
              show-count
              placeholder="输入要交给 Agent 的真实问题或任务，可从历史运行复用后修改。"
              :disabled="submitting || streaming"
              @keydown.ctrl.enter.prevent="submitRun"
            />
            <div class="action-row">
              <span class="composer-hint">Ctrl/⌘ + Enter 运行 · 输入只对当前用户可见</span>
              <NSpace :size="8">
                <NButton
                  v-if="activeRun && isDebugRunStoppable(activeStatus)"
                  secondary
                  type="warning"
                  :loading="actionLoading"
                  @click="stopActiveRun"
                >
                  <template #icon><SvgIcon icon="lucide:square" /></template>停止
                </NButton>
                <NButton
                  v-if="activeRun && isDebugRunResumable(activeStatus)"
                  secondary
                  type="info"
                  :loading="actionLoading"
                  @click="resumeActiveRun"
                >
                  <template #icon><SvgIcon icon="lucide:play" /></template>恢复
                </NButton>
                <NButton
                  v-if="activeRun && isDebugRunRetryable(activeStatus)"
                  secondary
                  type="warning"
                  :loading="actionLoading"
                  @click="retryActiveRun"
                >
                  <template #icon><SvgIcon icon="lucide:rotate-ccw" /></template>重试
                </NButton>
                <NButton class="run-button" type="primary" :loading="submitting" :disabled="!canSubmit" @click="submitRun">
                  <template #icon><SvgIcon icon="lucide:send" /></template>运行
                </NButton>
              </NSpace>
            </div>
          </div>
        </section>

        <div class="run-footer" v-if="activeDetail">
          <span>Trace {{ activeRun?.traceId || '-' }}</span>
          <span>事件 {{ displayEventCount }}</span>
          <span>耗时 {{ formatDebugDuration(activeDetail.metrics.elapsedMs) }}</span>
          <span>Token {{ activeDetail.metrics.totalTokens.toLocaleString() }}</span>
          <NButton text size="small" @click="reuseInput(activeDetail.summary)">
            <template #icon><SvgIcon icon="lucide:copy" /></template>复用输入
          </NButton>
        </div>
      </main>

      <aside class="trace-pane">
        <div class="pane-heading">
          <div><strong>运行详情</strong><span>{{ events.length }} 个事件</span></div>
          <NButton v-if="activeDebugRunId" text :loading="replayLoading" title="从数据库重放事件" @click="loadReplay(activeDebugRunId)">
            <template #icon><SvgIcon icon="lucide:history" /></template>
          </NButton>
        </div>
        <NScrollbar class="trace-scroll">
          <div v-if="activeDetail" class="metrics-strip">
            <div><strong>{{ activeDetail.metrics.totalTokens.toLocaleString() }}</strong><span>总 Token</span></div>
            <div><strong>{{ activeDetail.metrics.modelCalls }}</strong><span>模型调用</span></div>
            <div><strong>{{ activeDetail.metrics.toolCalls }}</strong><span>工具调用</span></div>
            <div><strong>{{ formatDebugDuration(activeDetail.metrics.elapsedMs) }}</strong><span>耗时</span></div>
          </div>

          <div v-if="liveApprovals.length" class="approval-list">
            <div class="section-label"><SvgIcon icon="lucide:shield-check" /> 待处理审批</div>
            <div v-for="approval in liveApprovals" :key="approval.id" class="approval-item">
              <div class="approval-title">{{ approval.actionSummary }}</div>
              <div class="approval-meta">风险 {{ approval.riskLevel }} · {{ approval.inputSummary || '参数已脱敏' }}</div>
              <NSpace :size="8" class="approval-actions">
                <NButton size="small" type="primary" :loading="actionLoading" @click="decidePendingApproval(approval, 'approve')">批准</NButton>
                <NButton size="small" secondary type="error" :loading="actionLoading" @click="decidePendingApproval(approval, 'reject')">拒绝</NButton>
              </NSpace>
            </div>
          </div>

          <div v-if="!events.length" class="empty-pane"><NEmpty description="暂无事件" /></div>
          <ol v-else class="event-list">
            <li v-for="event in events" :key="event.eventId" class="event-item" :class="eventClass(event)">
              <div class="event-marker"><span /></div>
              <div class="event-body">
                <div class="event-head"><strong>{{ debugEventTitle(event) }}</strong><time>{{ eventTime(event) }}</time></div>
                <div v-if="eventDetails(event)" class="event-details">{{ eventDetails(event) }}</div>
                <div v-if="eventSummary(event)" class="event-summary">{{ eventSummary(event) }}</div>
              </div>
            </li>
          </ol>

          <NDivider v-if="activeDetail" />
          <div v-if="activeDetail?.steps.length" class="steps-section">
            <div class="section-label"><SvgIcon icon="lucide:list-tree" /> 执行步骤</div>
            <div v-for="step in activeDetail.steps" :key="step.id" class="step-item">
              <div><strong>{{ step.key }}</strong><NTag size="tiny" :type="statusTag(step.status)">{{ debugRunStatusText(step.status) }}</NTag></div>
              <span v-if="step.errorSummary" class="error-text">{{ step.errorSummary }}</span>
              <span v-else>{{ step.outputSummary || step.inputSummary || '等待步骤结果' }}</span>
            </div>
          </div>
        </NScrollbar>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.debug-page {
  min-height: calc(100vh - 112px);
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 32px;
  padding: 32px 28px 28px;
  color: #18212f;
}

.debug-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
}

.debug-header h1 {
  margin: 6px 0 8px;
  font-size: 34px;
  font-weight: 700;
  line-height: 1.12;
  letter-spacing: -0.02em;
}

.debug-header p {
  margin: 0;
  color: #64748b;
  font-size: 16px;
  line-height: 1.5;
}

.eyebrow {
  color: #6d4cc2;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: .14em;
}

.page-alert, .stream-alert { flex: none; }
.page-loading, .detail-loading { min-height: 260px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; color: #64748b; }
.debug-grid {
  min-height: 720px;
  flex: 1;
  display: grid;
  grid-template-columns: 360px minmax(520px, 1fr) 400px;
  overflow: hidden;
  border: 1px solid #dfe5ec;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 8px 24px rgb(15 23 42 / 4%);
}

.history-pane, .trace-pane {
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: #f8fafc;
}

.history-pane { border-right: 1px solid #dfe5ec; }
.trace-pane { border-left: 1px solid #dfe5ec; }

.pane-heading {
  min-height: 84px;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 28px;
  border-bottom: 1px solid #dfe5ec;
  background: #fff;
}

.pane-heading > div { display: flex; align-items: baseline; gap: 10px; }
.pane-heading strong { font-size: 16px; }
.pane-heading span { color: #94a3b8; font-size: 13px; }

.history-scroll, .trace-scroll { flex: 1; height: auto; min-height: 0; }
.history-date, .section-label { padding: 18px 28px 8px; color: #94a3b8; font-size: 12px; font-weight: 700; }
.history-item { width: 100%; border: 0; border-left: 3px solid transparent; padding: 14px 28px; text-align: left; background: transparent; cursor: pointer; transition: background .15s, border-color .15s; }
.history-item:hover { background: #f0f4f8; }
.history-item.selected { border-left-color: #6d4cc2; background: #f0edff; }
.history-item-top { display: flex; align-items: center; justify-content: space-between; gap: 8px; font-size: 13px; }
.history-input { margin-top: 8px; color: #334155; font-size: 13px; line-height: 1.5; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; }
.history-meta { margin-top: 8px; color: #94a3b8; font-size: 11px; }
.empty-pane { min-height: 360px; display: flex; align-items: center; justify-content: center; padding: 32px; }
.composer-pane { min-width: 0; display: flex; flex-direction: column; background: #fff; }
.selector-row { display: flex; align-items: end; gap: 16px; min-height: 142px; box-sizing: border-box; padding: 30px 30px 28px; border-bottom: 1px solid #dfe5ec; }
.selector-field { flex: 1; min-width: 0; }
.selector-field label { display: block; margin: 0 0 10px; color: #334155; font-size: 14px; }
.selector-field :deep(.n-base-selection) { min-height: 48px; border-radius: 8px; }
.version-hash { display: flex; align-items: center; gap: 5px; max-width: 160px; padding-bottom: 9px; color: #64748b; font-size: 10px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.conversation-panel { min-height: 0; flex: 1; display: flex; flex-direction: column; }
.conversation-heading { display: flex; justify-content: space-between; align-items: center; padding: 30px 30px 16px; }
.conversation-heading h2 { margin: 7px 0 0; font-size: 24px; font-weight: 700; }
.streaming-indicator { color: #059669; font-size: 11px; display: inline-flex; align-items: center; gap: 5px; }
.streaming-indicator i { width: 6px; height: 6px; border-radius: 50%; background: #10b981; box-shadow: 0 0 0 3px #d1fae5; }
.output-scroll { min-height: 280px; flex: 1; border-top: 1px solid #f1f5f9; border-bottom: 1px solid #f1f5f9; }
.output-content { padding: 22px; min-height: 280px; }
.assistant-output { white-space: pre-wrap; word-break: break-word; color: #1e293b; font-size: 14px; line-height: 1.75; }
.conversation-empty { display: flex; flex-direction: column; align-items: flex-start; gap: 10px; padding: 0 30px 26px; color: #475569; }
.conversation-empty strong { font-size: 16px; line-height: 1.45; }
.conversation-empty span { color: #64748b; font-size: 14px; line-height: 1.6; }
.waiting-state { min-height: 220px; display: flex; align-items: center; justify-content: center; gap: 8px; color: #64748b; font-size: 13px; }
.waiting-state svg { width: 18px; color: #7c3aed; }
.error-text { color: #dc2626 !important; }
.composer-actions { padding: 0 30px 30px; }
.composer-actions :deep(.n-input) { min-height: 176px; border-radius: 8px; background: #fbfcfe; }
.composer-actions :deep(textarea) { min-height: 142px !important; }
.action-row { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-top: 16px; }
.composer-hint { color: #8492a6; font-size: 13px; }
.run-button { min-width: 108px; height: 48px; border-radius: 8px; }
.run-footer { display: flex; align-items: center; gap: 14px; padding: 8px 14px; border-top: 1px solid #e2e8f0; color: #64748b; font-size: 10px; }
.run-footer span:first-child { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.trace-scroll { padding: 0 28px; }
.metrics-strip { display: grid; grid-template-columns: repeat(2, 1fr); gap: 1px; margin: 22px 0; border: 1px solid #dfe5ec; background: #dfe5ec; }
.metrics-strip div { padding: 10px; background: #fff; }
.metrics-strip strong { display: block; color: #1e293b; font-size: 16px; }
.metrics-strip span { display: block; margin-top: 2px; color: #94a3b8; font-size: 10px; }
.section-label { display: flex; align-items: center; gap: 5px; padding-left: 0; padding-right: 0; color: #475569; }
.approval-list { margin-bottom: 12px; }
.approval-item { margin: 0 0 8px; padding: 10px; border-left: 3px solid #f59e0b; background: #fffbeb; }
.approval-title { color: #92400e; font-size: 12px; font-weight: 650; }
.approval-meta { margin-top: 4px; color: #a16207; font-size: 10px; line-height: 1.4; }
.approval-actions { margin-top: 8px; }
.event-list { position: relative; list-style: none; margin: 8px 0 0; padding: 0 0 6px 18px; }
.event-list::before { content: ''; position: absolute; top: 8px; bottom: 8px; left: 4px; width: 1px; background: #e2e8f0; }
.event-item { position: relative; display: flex; gap: 9px; padding: 7px 0; }
.event-marker { position: relative; z-index: 1; flex: none; width: 9px; margin-left: -18px; padding-top: 4px; background: #f8fafc; }
.event-marker span { display: block; width: 8px; height: 8px; border: 2px solid #94a3b8; border-radius: 50%; background: #fff; }
.event-output .event-marker span { border-color: #7c3aed; }
.event-tool .event-marker span { border-color: #0ea5e9; }
.event-model .event-marker span { border-color: #059669; }
.event-approval .event-marker span { border-color: #f59e0b; }
.event-error .event-marker span { border-color: #dc2626; }
.event-body { min-width: 0; flex: 1; }
.event-head { display: flex; justify-content: space-between; gap: 8px; }
.event-head strong { color: #334155; font-size: 11px; line-height: 1.4; }
.event-head time { flex: none; color: #94a3b8; font-size: 9px; }
.event-details { margin-top: 3px; color: #7c3aed; font-size: 10px; }
.event-summary { margin-top: 4px; color: #64748b; font-size: 11px; line-height: 1.45; word-break: break-word; }
.steps-section { padding-bottom: 16px; }
.step-item { margin: 0 0 8px; padding: 9px; border: 1px solid #e2e8f0; background: #fff; }
.step-item > div { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.step-item strong { font-size: 11px; }
.step-item > span { display: block; margin-top: 5px; color: #64748b; font-size: 10px; line-height: 1.4; }
@media (max-width: 1200px) {
  .debug-grid { grid-template-columns: 280px minmax(360px, 1fr) 330px; }
}
@media (max-width: 900px) {
  .debug-page { gap: 22px; padding: 22px 16px 18px; }
  .debug-header { flex-direction: column; }
  .debug-grid { grid-template-columns: 1fr; }
  .history-pane, .trace-pane { border: 0; }
  .history-pane { border-bottom: 1px solid #e2e8f0; }
  .trace-pane { border-top: 1px solid #e2e8f0; }
  .history-scroll, .trace-scroll { height: auto; max-height: 360px; }
  .selector-row { flex-wrap: wrap; }
  .selector-field { min-width: 220px; }
  .action-row { align-items: flex-start; flex-direction: column; }
}
</style>
