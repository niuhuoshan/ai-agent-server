<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import type { SelectOption } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { AgentEmbedWidget } from '@/embed/widget-sdk';
import type { EmbedHostCommand, EmbedEnvelope } from '@/embed/protocol';
import { fetchAllowedAgents } from '@/service/api';
import { issueEmbedBrowserCredential } from '@/service/api/embed-widget';
import type { AgentOptionView } from '@/service/api';
import {
  NHS_HOST_COMMANDS,
  buildFloatingSnippet,
  buildIframeSnippet,
  buildMultiInstanceSnippet,
  buildPostMessageSnippet,
  buildWidgetIntegrationCode,
  parseCommandPayload,
  prettyProtocolPayload,
  redactProtocolPayload
} from './protocol-debugger';
import type { ProtocolLogEntry } from './protocol-debugger';

defineOptions({ name: 'WidgetDebugger' });

type FrameMode = 'desktop' | 'mobile';
const route = useRoute();
const agents = ref<AgentOptionView[]>([]);
const loadingAgents = ref(false);
const apiKey = ref('');
const agentVersionId = ref<string | null>(typeof route.query.agentVersionId === 'string' ? route.query.agentVersionId : null);
const externalUserKey = ref('debugger-user');
const sessionMinutes = ref(30);
const contextText = ref('{\n  "source": "widget-debugger"\n}');
const commandType = ref<EmbedHostCommand>('SYNC_STATE');
const commandText = ref('{\n  "state": {\n    "page": "debugger"\n  }\n}');
const commandName = ref('SEND_MESSAGE');
const messageText = ref('你好，请确认 Widget 协议已经连接。');
const selectedTheme = ref<'light' | 'dark' | 'auto'>('auto');
const frameMode = ref<FrameMode>('desktop');
const frameFullscreen = ref(false);
const connectLoading = ref(false);
const connected = ref(false);
const status = ref('未连接');
const sessionId = ref('');
const instanceId = ref('');
const logs = ref<ProtocolLogEntry[]>([]);
const logFilter = ref('');
const container = ref<HTMLElement | null>(null);
let widget: AgentEmbedWidget | null = null;
let launchCredential = '';
let sequence = 0;

const agentOptions = computed<SelectOption[]>(() => agents.value
  .filter(agent => agent.status === 'active' && agent.publishedVersionId)
  .map(agent => ({
    label: `${agent.name} · ${agent.agentKey}`,
    value: agent.publishedVersionId as string
  })));
const visibleLogs = computed(() => {
  const filter = logFilter.value.trim().toLowerCase();
  if (!filter) return logs.value;
  return logs.value.filter(item => `${item.direction} ${item.type} ${item.correlationId} ${prettyProtocolPayload(item.payload)}`
    .toLowerCase().includes(filter));
});
const frameStyle = computed(() => ({
  width: frameMode.value === 'mobile' ? '390px' : '100%',
  maxWidth: '100%',
  minHeight: frameFullscreen.value ? 'calc(100vh - 180px)' : '520px'
}));
const integrationCode = computed(() => buildWidgetIntegrationCode({
  origin: window.location.origin,
  agentVersionId: agentVersionId.value || undefined
}));
const iframeCode = computed(() => buildIframeSnippet({ origin: window.location.origin }));
const floatingCode = computed(() => buildFloatingSnippet({ origin: window.location.origin }));
const postMessageCode = computed(() => buildPostMessageSnippet({ origin: window.location.origin }));
const multiInstanceCode = computed(() => buildMultiInstanceSnippet({ origin: window.location.origin }));

function addLog(direction: ProtocolLogEntry['direction'], type: string, correlationId: string, payload: unknown) {
  logs.value.unshift({
    id: `log_${Date.now()}_${sequence++}`,
    direction,
    type,
    correlationId,
    timestamp: new Date().toISOString(),
    payload: redactProtocolPayload(payload)
  });
  if (logs.value.length > 500) logs.value.length = 500;
}

function formatTime(value: string) {
  return new Date(value).toLocaleTimeString();
}

function statusType(value: string) {
  if (value === '已连接' || value === 'ready') return 'success';
  if (value === '生成中' || value === 'initializing') return 'warning';
  if (value === '错误') return 'error';
  return 'default';
}

async function loadAgents() {
  loadingAgents.value = true;
  try {
    const result = await fetchAllowedAgents(200);
    if (!result.error) {
      agents.value = result.data;
      if (!agentVersionId.value) agentVersionId.value = agentOptions.value[0]?.value as string | undefined || null;
    }
  } finally {
    loadingAgents.value = false;
  }
}

async function connect() {
  if (!apiKey.value.trim() || !agentVersionId.value || !externalUserKey.value.trim()) {
    window.$message?.warning('API Key、Agent 发布版本和外部用户 Key 不能为空');
    return;
  }
  connectLoading.value = true;
  try {
    const context = parseCommandPayload(contextText.value);
    const launch = await issueEmbedBrowserCredential(apiKey.value, {
      origin: window.location.origin,
      agentVersionId: agentVersionId.value,
      externalUserKey: externalUserKey.value.trim(),
      sessionMinutes: sessionMinutes.value
    });
    launchCredential = launch.credential;
    apiKey.value = '';
    disconnect(false);
    if (!container.value) throw new Error('Widget 容器尚未就绪');
    widget = new AgentEmbedWidget({
      container: container.value,
      embedUrl: `${window.location.origin}/embed/chat`,
      credential: launch.credential,
      protocolMode: 'nhs-v1',
      context,
      theme: selectedTheme.value,
      minHeight: 420,
      maxHeight: 900
    });
    instanceId.value = widget.instanceId;
    connected.value = true;
    status.value = '连接中';
    addLog('TX', 'INIT_CONFIG', `init_${widget.instanceId}`, { contract: 'nhs-v1', context, theme: selectedTheme.value });
    widget.on('*', (payload, envelope) => consumeWidgetEvent(payload, envelope));
    window.$message?.success('Widget 已连接，正在等待握手');
  } catch (error) {
    status.value = '错误';
    window.$message?.error(error instanceof Error ? error.message : 'Widget 连接失败');
  } finally {
    connectLoading.value = false;
  }
}

function consumeWidgetEvent(payload: unknown, envelope: EmbedEnvelope) {
  addLog('RX', envelope.type, envelope.correlationId, payload);
  if (envelope.type === 'NHS_WIDGET_READY' || envelope.type === 'READY') status.value = '握手就绪';
  if (envelope.type === 'INIT_SUCCESS' || envelope.type === 'INITIALIZED') {
    status.value = '已连接';
    const state = payload as { session?: { id?: string } };
    sessionId.value = state.session?.id || sessionId.value;
  }
  if (envelope.type === 'INIT_FAILURE') status.value = '错误';
  if (envelope.type === 'CONNECTION_STATUS') {
    const next = payload && typeof payload === 'object' ? String((payload as { status?: unknown }).status || '') : '';
    if (next) status.value = next === 'streaming' ? '生成中' : next;
  }
  if (envelope.type === 'GENERATION_STOPPED') status.value = '已连接';
  if (envelope.type === 'ERROR') status.value = '错误';
}

function disconnect(showMessage = true) {
  widget?.destroy();
  widget = null;
  connected.value = false;
  status.value = '未连接';
  sessionId.value = '';
  instanceId.value = '';
  launchCredential = '';
  if (showMessage) window.$message?.success('Widget 已断开，已清理本地会话');
}

async function runCommand() {
  if (!widget || !connected.value) {
    window.$message?.warning('请先连接 Widget');
    return;
  }
  let payload: Record<string, unknown>;
  try {
    payload = parseCommandPayload(commandText.value);
  } catch (error) {
    window.$message?.error(error instanceof Error ? error.message : '命令 JSON 无效');
    return;
  }
  const correlationId = `debug_${Date.now()}_${sequence++}`;
  addLog('TX', commandType.value, correlationId, commandType.value === 'INIT_CONFIG'
    ? { ...payload, credential: '[redacted]', contract: 'nhs-v1' }
    : payload);
  try {
    switch (commandType.value) {
      case 'INIT_CONFIG':
        await widget.sendRawCommand('INIT_CONFIG', { ...payload, credential: launchCredential, contract: 'nhs-v1' });
        break;
      case 'OPEN_SAVED_REPORT':
        await widget.openSavedReport(payload);
        break;
      case 'SYNC_STATE':
        await widget.syncState(payload.state && typeof payload.state === 'object' ? payload.state as Record<string, unknown> : payload);
        break;
      case 'UPDATE_CONTEXT':
        await widget.updateContext(payload.context && typeof payload.context === 'object' ? payload.context as Record<string, unknown> : payload);
        break;
      case 'SET_THEME':
        if (payload.theme !== 'light' && payload.theme !== 'dark' && payload.theme !== 'auto') throw new Error('theme 必须为 light、dark 或 auto');
        await widget.setTheme(payload.theme);
        break;
      case 'STOP_GENERATION':
        await widget.stopGeneration();
        break;
      case 'CLEAR_SESSION':
        await widget.clearSession();
        break;
      case 'RESET_SESSION':
        await widget.resetSession();
        break;
      case 'SEND_COMMAND': {
        const name = typeof payload.command === 'string' ? payload.command : commandName.value;
        const nested = payload.payload && typeof payload.payload === 'object' ? payload.payload as Record<string, unknown> : payload;
        await widget.sendCommand(name, nested);
        break;
      }
      default:
        throw new Error('不支持的协议命令');
    }
  } catch (error) {
    window.$message?.error(error instanceof Error ? error.message : '协议命令执行失败');
  }
}

async function sendQuickMessage() {
  commandType.value = 'SEND_COMMAND';
  commandText.value = JSON.stringify({ command: 'SEND_MESSAGE', input: messageText.value, attachments: [] }, null, 2);
  await runCommand();
}

async function applyTheme() {
  commandType.value = 'SET_THEME';
  commandText.value = JSON.stringify({ theme: selectedTheme.value }, null, 2);
  await runCommand();
}

async function copyText(value: string) {
  try {
    await navigator.clipboard.writeText(value);
    window.$message?.success('代码已复制');
  } catch {
    window.$message?.error('复制失败，请手工复制');
  }
}

function clearLogs() {
  logs.value = [];
}

onMounted(() => { void loadAgents(); });
onBeforeUnmount(() => disconnect(false));
</script>

<template>
  <div class="page-container widget-debugger-page">
    <header class="page-header">
      <div>
        <div class="eyebrow">NHS EMBED PROTOCOL</div>
        <h2>Widget Debugger</h2>
        <p>在真实凭证、真实 iframe 和真实 SSE 会话上验证宿主协议。敏感凭证只在内存中使用。</p>
      </div>
      <NSpace align="center">
        <NTag :type="statusType(status)" size="small">{{ status }}</NTag>
        <NButton v-if="connected" secondary @click="disconnect()"><template #icon><SvgIcon icon="lucide:plug-zap" /></template>断开</NButton>
        <NButton v-else type="primary" :loading="connectLoading" :disabled="loadingAgents" @click="connect"><template #icon><SvgIcon icon="lucide:plug" /></template>连接 Widget</NButton>
      </NSpace>
    </header>

    <NAlert v-if="!agentOptions.length && !loadingAgents" type="warning" :bordered="false" class="mb-16px">
      当前用户没有可嵌入的已发布 Agent。请先完成 Agent 发布和 Embed Access 授权。
    </NAlert>

    <div class="debugger-grid">
      <section class="control-column">
        <NCard title="连接配置" :bordered="false">
          <NForm label-placement="top">
            <NFormItem label="应用 API Key"><NInput v-model:value="apiKey" type="password" show-password-on="click" placeholder="仅用于换取短期浏览器凭证" /></NFormItem>
            <NFormItem label="Agent 发布版本"><NSelect v-model:value="agentVersionId" :options="agentOptions" :loading="loadingAgents" filterable clearable placeholder="选择已发布版本" /></NFormItem>
            <NFormItem label="外部用户 Key"><NInput v-model:value="externalUserKey" placeholder="debugger-user" /></NFormItem>
            <NFormItem label="会话有效期（分钟）"><NInputNumber v-model:value="sessionMinutes" :min="1" :max="1440" class="full-width" /></NFormItem>
            <NFormItem label="初始 Context JSON"><NInput v-model:value="contextText" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" /></NFormItem>
          </NForm>
          <NDescriptions v-if="connected" bordered size="small" :column="1">
            <NDescriptionsItem label="Instance ID"><code>{{ instanceId }}</code></NDescriptionsItem>
            <NDescriptionsItem label="Session ID"><code>{{ sessionId || '等待 INIT_SUCCESS' }}</code></NDescriptionsItem>
          </NDescriptions>
        </NCard>

        <NCard title="宿主指令" :bordered="false" class="command-card">
          <NForm label-placement="top">
            <NFormItem label="指令类型"><NSelect v-model:value="commandType" :options="NHS_HOST_COMMANDS.map(value => ({ label: value, value }))" /></NFormItem>
            <NFormItem v-if="commandType === 'SEND_COMMAND'" label="内层命令名"><NInput v-model:value="commandName" /></NFormItem>
            <NFormItem label="JSON 载荷"><NInput v-model:value="commandText" type="textarea" :autosize="{ minRows: 6, maxRows: 14 }" spellcheck="false" /></NFormItem>
          </NForm>
          <NSpace>
            <NButton type="primary" :disabled="!connected" @click="runCommand"><template #icon><SvgIcon icon="lucide:send" /></template>发送指令</NButton>
            <NButton secondary :disabled="!connected" @click="sendQuickMessage"><template #icon><SvgIcon icon="lucide:message-square" /></template>快速发送</NButton>
            <NButton quaternary :disabled="!connected" @click="applyTheme"><template #icon><SvgIcon icon="lucide:palette" /></template>应用主题</NButton>
          </NSpace>
          <NInput v-model:value="messageText" class="quick-message" placeholder="快速发送内容" />
        </NCard>
      </section>

      <section class="preview-column">
        <NCard :bordered="false" class="preview-card">
          <template #header>
            <div class="card-heading"><div><strong>实时 Widget</strong><span>同源 iframe · instance 隔离 · 精确 targetOrigin</span></div><NSpace><NButton size="small" quaternary @click="frameMode = frameMode === 'desktop' ? 'mobile' : 'desktop'"><template #icon><SvgIcon :icon="frameMode === 'desktop' ? 'lucide:smartphone' : 'lucide:monitor'" /></template>{{ frameMode === 'desktop' ? '移动尺寸' : '桌面尺寸' }}</NButton><NButton size="small" quaternary @click="frameFullscreen = !frameFullscreen"><template #icon><SvgIcon :icon="frameFullscreen ? 'lucide:minimize-2' : 'lucide:maximize-2'" /></template>{{ frameFullscreen ? '退出全屏' : '全屏预览' }}</NButton></NSpace></div>
          </template>
          <div class="widget-frame-shell" :class="{ fullscreen: frameFullscreen }"><div ref="container" class="widget-frame" :style="frameStyle"><NEmpty v-if="!connected" description="连接后显示真实 Widget iframe" /></div></div>
        </NCard>
        <NCard title="快捷上下文同步" :bordered="false" class="sync-card">
          <NSpace><NButton size="small" secondary :disabled="!connected" @click="commandType = 'SYNC_STATE'; commandText = '{\n  &quot;state&quot;: {\n    &quot;page&quot;: &quot;debugger&quot;\n  }\n}'">同步状态</NButton><NButton size="small" secondary :disabled="!connected" @click="commandType = 'CLEAR_SESSION'; commandText = '{}'">清空会话</NButton><NButton size="small" secondary :disabled="!connected" @click="commandType = 'RESET_SESSION'; commandText = '{}'">重置会话</NButton></NSpace>
        </NCard>
      </section>

      <section class="logs-column">
        <NCard :bordered="false" class="logs-card">
          <template #header><div class="card-heading"><div><strong>协议收发日志</strong><span>{{ visibleLogs.length }} 条，自动脱敏凭证字段</span></div><NSpace><NInput v-model:value="logFilter" size="small" clearable placeholder="过滤事件" /><NButton size="small" quaternary @click="clearLogs"><template #icon><SvgIcon icon="lucide:trash-2" /></template>清空</NButton></NSpace></div></template>
          <NEmpty v-if="!visibleLogs.length" description="暂无协议事件" />
          <div v-else class="log-list">
            <article v-for="entry in visibleLogs" :key="entry.id" class="log-entry">
              <div class="log-meta"><NTag size="tiny" :type="entry.direction === 'TX' ? 'warning' : 'info'">{{ entry.direction }}</NTag><code>{{ entry.type }}</code><span>{{ formatTime(entry.timestamp) }}</span><small>{{ entry.correlationId }}</small></div>
              <NCode :code="prettyProtocolPayload(entry.payload)" language="json" word-wrap />
            </article>
          </div>
        </NCard>
      </section>
    </div>

    <NCard title="集成代码" :bordered="false" class="code-card">
      <NTabs type="line" animated>
        <NTabPane name="sdk" tab="SDK"><template #tab><span>SDK</span></template><div class="code-pane"><NButton size="small" secondary @click="copyText(integrationCode)"><template #icon><SvgIcon icon="lucide:copy" /></template>复制</NButton><NCode :code="integrationCode" language="html" word-wrap /></div></NTabPane>
        <NTabPane name="postmessage" tab="PostMessage"><div class="code-pane"><NButton size="small" secondary @click="copyText(postMessageCode)"><template #icon><SvgIcon icon="lucide:copy" /></template>复制</NButton><NCode :code="postMessageCode" language="html" word-wrap /></div></NTabPane>
        <NTabPane name="floating" tab="悬浮窗"><div class="code-pane"><NButton size="small" secondary @click="copyText(floatingCode)"><template #icon><SvgIcon icon="lucide:copy" /></template>复制</NButton><NCode :code="floatingCode" language="html" word-wrap /></div></NTabPane>
        <NTabPane name="multi" tab="同页多实例"><div class="code-pane"><NButton size="small" secondary @click="copyText(multiInstanceCode)"><template #icon><SvgIcon icon="lucide:copy" /></template>复制</NButton><NCode :code="multiInstanceCode" language="html" word-wrap /></div></NTabPane>
        <NTabPane name="iframe" tab="iframe"><div class="code-pane"><NButton size="small" secondary @click="copyText(iframeCode)"><template #icon><SvgIcon icon="lucide:copy" /></template>复制</NButton><NCode :code="iframeCode" language="html" word-wrap /></div></NTabPane>
      </NTabs>
    </NCard>
  </div>
</template>

<style scoped lang="scss">
.page-container { padding: 20px; }
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; margin-bottom: 18px; }
.eyebrow { color: var(--n-primary-color); font-size: 11px; font-weight: 700; letter-spacing: .08em; }
.page-header h2 { margin: 4px 0; font-size: 22px; }
.page-header p { margin: 0; color: var(--n-text-color-3); font-size: 13px; }
.debugger-grid { display: grid; grid-template-columns: minmax(300px, .72fr) minmax(420px, 1.28fr); gap: 16px; align-items: start; }
.control-column, .preview-column { display: flex; min-width: 0; flex-direction: column; gap: 16px; }
.logs-column { grid-column: 1 / -1; min-width: 0; }
.command-card { min-height: 360px; }
.quick-message { margin-top: 12px; }
.full-width { width: 100%; }
.card-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.card-heading > div { display: flex; min-width: 0; flex-direction: column; gap: 3px; }
.card-heading strong { font-size: 15px; }
.card-heading span { color: var(--n-text-color-3); font-size: 12px; font-weight: 400; }
.widget-frame-shell { display: flex; min-height: 520px; padding: 12px; background: repeating-conic-gradient(#f2f3f5 0 25%, #fafafa 0 50%) 50% / 16px 16px; align-items: flex-start; justify-content: center; overflow: auto; }
.widget-frame-shell.fullscreen { position: fixed; z-index: 1000; inset: 0; padding: 40px; background: var(--n-color); }
.widget-frame { display: flex; min-height: 520px; border: 1px solid var(--n-border-color); background: var(--n-color); align-items: center; justify-content: center; transition: width .2s ease; }
.sync-card { padding-block: 2px; }
.logs-card { min-height: 260px; }
.log-list { display: flex; max-height: 560px; flex-direction: column; gap: 10px; overflow: auto; }
.log-entry { padding: 10px; border: 1px solid var(--n-border-color); background: color-mix(in srgb, var(--n-color) 92%, var(--n-primary-color)); }
.log-meta { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; font-size: 12px; }
.log-meta span, .log-meta small { color: var(--n-text-color-3); }
.log-meta small { margin-left: auto; overflow: hidden; text-overflow: ellipsis; }
.code-card { margin-top: 16px; }
.code-pane { position: relative; min-height: 240px; padding-top: 8px; }
.code-pane > .n-button { position: absolute; z-index: 1; top: 12px; right: 8px; }
@media (max-width: 1024px) { .debugger-grid { grid-template-columns: 1fr; } .logs-column { grid-column: auto; } }
@media (max-width: 680px) { .page-container { padding: 12px; } .page-header { flex-direction: column; } .card-heading { align-items: flex-start; flex-direction: column; } .widget-frame-shell { padding: 6px; } }
</style>
