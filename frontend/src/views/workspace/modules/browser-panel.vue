<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { NAlert, NButton, NCard, NEmpty, NInput, NPopconfirm, NSelect, NSpace, NSpin, NTag } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { resolveGeneratedFileHref } from '@/utils/generated-file-url';
import {
  clickBrowserSession,
  clearBrowserProfiles,
  closeBrowserSession,
  createBrowserSession,
  fetchBrowserSessions,
  fetchBrowserSnapshot,
  fillBrowserSession,
  requestBrowserHandoff,
  takeBrowserHandoff,
  returnBrowserHandoff,
  hoverBrowserSession,
  activateBrowserTab,
  closeBrowserTab,
  openBrowserTab,
  navigateBrowserSession,
  pressBrowserSession,
  navigateBrowserHistory,
  readVisibleBrowserText,
  downloadBrowserSession,
  manualInputBrowserSession,
  scrollBrowserSession,
  uploadBrowserSession,
  fetchBrowserWorkerHealth,
  type BrowserActionResult,
  type BrowserSessionView,
  type BrowserSnapshotView,
  type BrowserHandoffStatus,
  type BrowserWorkerHealthView
} from '@/service/api';
import type { BrowserHistoryAction, BrowserManualInputPayload } from '@/service/api/browser';

const sessions = ref<BrowserSessionView[]>([]);
const selectedId = ref<string | null>(null);
const snapshot = ref<BrowserSnapshotView | null>(null);
const startUrl = ref('');
const selector = ref('');
const inputValue = ref('');
const keyValue = ref('Enter');
const hoverSelector = ref('');
const scrollY = ref('600');
const uploadSelector = ref('input[type=file]');
const uploadFiles = ref('');
const manualText = ref('');
const manualBusy = ref(false);
const manualDragging = ref(false);
const snapshotImage = ref<HTMLImageElement | null>(null);
let manualQueue: Promise<void> = Promise.resolve();
const loading = ref(false);
const action = ref('');
const errorMessage = ref('');
const workerHealth = ref<BrowserWorkerHealthView | null>(null);
const workerHealthError = ref('');
let pollTimer: ReturnType<typeof setInterval> | null = null;

const selected = computed(() => sessions.value.find(item => item.id === selectedId.value) || null);
const sessionOptions = computed(() => sessions.value.map(item => ({
  label: `${item.pageTitle || item.currentUrl || '新会话'} · ${statusText(item.status)}`,
  value: item.id
})));
const screenshot = computed(() => snapshot.value?.screenshotBase64
  ? `data:image/png;base64,${snapshot.value.screenshotBase64}`
  : '');
const handoffStatus = computed<BrowserHandoffStatus>(() => selected.value?.handoffStatus || 'none');
const handoffActive = computed(() => handoffStatus.value === 'requested' || handoffStatus.value === 'human_control');
const humanControl = computed(() => handoffStatus.value === 'human_control');

function applyBrowserResult(result: { error?: unknown; data?: BrowserActionResult | null }) {
  if (result.error) showError(result.error, '浏览器操作失败');
  else if (result.data) {
    snapshot.value = result.data.snapshot || null;
    sessions.value = sessions.value.map(item => item.id === result.data?.id ? result.data! : item);
  }
}

function statusText(status: BrowserSessionView['status']) {
  return ({ opening: '启动中', open: '运行中', closing: '关闭中', closed: '已关闭', failed: '失败' } as Record<string, string>)[status] || status;
}

function statusType(status: BrowserSessionView['status']) {
  if (status === 'open') return 'success';
  if (status === 'failed') return 'error';
  if (status === 'closed') return 'default';
  return 'warning';
}

function handoffText(status: BrowserHandoffStatus) {
  return ({ none: '', requested: '等待人工接管', human_control: '人工接管中', returned: '已交还 AI', expired: '接管已失效' } as Record<BrowserHandoffStatus, string>)[status];
}

function showError(error: unknown, fallback: string) {
  errorMessage.value = error instanceof Error && error.message ? error.message : fallback;
}

function normalizeNavigationUrl(raw: string) {
  const value = raw.trim();
  if (!value || /^https?:\/\//i.test(value)) return value;
  if (value.startsWith('//')) return `https:${value}`;
  if (/^[a-z][a-z\d+.-]*:/i.test(value)) return value;
  return `https://${value}`;
}

async function pollSnapshot() {
  await loadWorkerHealth();
  const id = selectedId.value;
  if (!id || action.value || selected.value?.status !== 'open' || handoffActive.value) return;
  const result = await fetchBrowserSnapshot(id);
  if (!result.error && result.data) {
    snapshot.value = result.data.snapshot || null;
    sessions.value = sessions.value.map(item => item.id === result.data?.id ? result.data : item);
  }
}

async function loadWorkerHealth() {
  const result = await fetchBrowserWorkerHealth();
  if (result.error || !result.data) {
    workerHealthError.value = result.error instanceof Error ? result.error.message : '浏览器 Worker 健康检查失败';
    workerHealth.value = null;
    return;
  }
  workerHealthError.value = '';
  workerHealth.value = result.data;
}

async function refresh() {
  loading.value = true;
  errorMessage.value = '';
  const result = await fetchBrowserSessions();
  if (result.error) showError(result.error, '浏览器会话加载失败');
  else {
    sessions.value = result.data || [];
    if (!selectedId.value || !sessions.value.some(item => item.id === selectedId.value)) {
      selectedId.value = sessions.value.find(item => item.status === 'open')?.id || sessions.value[0]?.id || null;
    }
    if (selectedId.value && sessions.value.find(item => item.id === selectedId.value)?.status === 'open') {
      await loadSnapshot(selectedId.value);
    }
  }
  await loadWorkerHealth();
  loading.value = false;
}

async function selectSession(id: string | null) {
  selectedId.value = id;
  snapshot.value = null;
  if (id) await loadSnapshot(id);
}

async function loadSnapshot(id = selectedId.value) {
  if (!id) return;
  action.value = 'snapshot';
  errorMessage.value = '';
  const result = await fetchBrowserSnapshot(id);
  if (result.error) showError(result.error, '页面快照获取失败');
  else if (result.data) {
    snapshot.value = result.data.snapshot || null;
    sessions.value = sessions.value.map(item => item.id === result.data?.id ? result.data : item);
  }
  action.value = '';
}

async function createSession() {
  loading.value = true;
  errorMessage.value = '';
  const result = await createBrowserSession({ startUrl: normalizeNavigationUrl(startUrl.value) || undefined });
  if (result.error) showError(result.error, '浏览器会话创建失败');
  else if (result.data) {
    sessions.value = [result.data, ...sessions.value.filter(item => item.id !== result.data?.id)];
    selectedId.value = result.data.id;
    snapshot.value = result.data.snapshot || null;
    startUrl.value = '';
  }
  loading.value = false;
}

async function runAction(name: 'navigate' | 'click' | 'fill' | 'press' | 'scroll' | 'hover' | 'upload') {
  const id = selectedId.value;
  if (!id) return;
  if (handoffStatus.value === 'requested') {
    errorMessage.value = '请先接管浏览器会话，AI 暂停期间不会执行浏览器操作';
    return;
  }
  errorMessage.value = '';
  action.value = name;
  const result = name === 'navigate'
    ? await navigateBrowserSession(id, normalizeNavigationUrl(startUrl.value))
    : name === 'click'
      ? await clickBrowserSession(id, selector.value.trim())
      : name === 'fill'
      ? await fillBrowserSession(id, selector.value.trim(), inputValue.value)
      : name === 'press'
        ? await pressBrowserSession(id, keyValue.value.trim())
        : name === 'scroll'
          ? await scrollBrowserSession(id, { y: Number(scrollY.value) || 600 })
          : name === 'hover'
            ? await hoverBrowserSession(id, hoverSelector.value.trim())
            : await uploadBrowserSession(id, uploadSelector.value.trim(), uploadFiles.value.split(/\r?\n|,/).map(item => item.trim()).filter(Boolean));
  if (result.error) showError(result.error, '浏览器操作失败');
  else if (result.data) {
    snapshot.value = result.data.snapshot || null;
    sessions.value = sessions.value.map(item => item.id === result.data?.id ? result.data : item);
  }
  action.value = '';
}

async function runHistoryAction(name: BrowserHistoryAction) {
  const id = selectedId.value;
  if (!id || handoffStatus.value === 'requested') return;
  action.value = name;
  errorMessage.value = '';
  const result = await navigateBrowserHistory(id, name);
  if (result.error) showError(result.error, '浏览器历史操作失败');
  else if (result.data) {
    snapshot.value = result.data.snapshot || null;
    sessions.value = sessions.value.map(item => item.id === result.data?.id ? result.data! : item);
  }
  action.value = '';
}

async function readVisibleText() {
  const id = selectedId.value;
  if (!id || handoffStatus.value === 'requested') return;
  action.value = 'read-visible';
  const result = await readVisibleBrowserText(id);
  if (result.error) showError(result.error, '可视区域读取失败');
  else if (result.data) snapshot.value = result.data.snapshot || null;
  action.value = '';
}

async function downloadPageFile() {
  const id = selectedId.value;
  if (!id || !selector.value.trim() || handoffStatus.value === 'requested') return;
  action.value = 'download';
  const result = await downloadBrowserSession(id, selector.value.trim());
  if (result.error) showError(result.error, '浏览器下载失败');
  else if (result.data) {
    snapshot.value = result.data.snapshot || null;
    const artifact = (result.data.worker as { artifact?: { download_url?: string } } | undefined)?.artifact;
    if (artifact?.download_url) window.open(resolveGeneratedFileHref(artifact.download_url), '_blank', 'noopener,noreferrer');
  }
  action.value = '';
}

async function runTabAction(actionName: 'open' | 'activate' | 'close', tabId?: string) {
  const id = selectedId.value;
  if (!id) return;
  if (handoffStatus.value === 'requested') {
    errorMessage.value = '请先接管浏览器会话，AI 暂停期间不会执行浏览器操作';
    return;
  }
  action.value = `tab-${actionName}`;
  errorMessage.value = '';
  const result = actionName === 'open'
    ? await openBrowserTab(id, startUrl.value.trim() || undefined)
    : actionName === 'activate'
      ? await activateBrowserTab(id, tabId || '')
      : await closeBrowserTab(id, tabId || '');
  if (result.error) showError(result.error, '浏览器标签页操作失败');
  else if (result.data) {
    snapshot.value = result.data.snapshot || null;
    sessions.value = sessions.value.map(item => item.id === result.data?.id ? result.data : item);
  }
  action.value = '';
}

async function closeSession() {
  const id = selectedId.value;
  if (!id) return;
  action.value = 'close';
  const result = await closeBrowserSession(id);
  if (result.error) showError(result.error, '浏览器会话关闭失败');
  else {
    sessions.value = sessions.value.map(item => item.id === id ? (result.data || { ...item, status: 'closed' }) : item);
    snapshot.value = null;
  }
  action.value = '';
}

async function clearProfiles() {
  if (action.value) return;
  action.value = 'profile-clear';
  errorMessage.value = '';
  const result = await clearBrowserProfiles();
  if (result.error) showError(result.error, '浏览器登录态清除失败');
  else {
    sessions.value = sessions.value.map(item => ({ ...item, status: 'closed', closedAt: new Date().toISOString() }));
    selectedId.value = null;
    snapshot.value = null;
  }
  action.value = '';
}

async function requestHandoff() {
  const id = selectedId.value;
  if (!id) return;
  action.value = 'handoff-request';
  errorMessage.value = '';
  const result = await requestBrowserHandoff(id, '需要人工完成浏览器操作');
  if (result.error) showError(result.error, '人工接管请求失败');
  else if (result.data) sessions.value = sessions.value.map(item => item.id === id ? result.data! : item);
  action.value = '';
}

async function takeHandoff() {
  const id = selectedId.value;
  if (!id) return;
  action.value = 'handoff-takeover';
  errorMessage.value = '';
  const result = await takeBrowserHandoff(id);
  if (result.error) showError(result.error, '接管浏览器失败');
  else if (result.data) sessions.value = sessions.value.map(item => item.id === id ? result.data! : item);
  action.value = '';
}

async function returnHandoff() {
  const id = selectedId.value;
  if (!id) return;
  action.value = 'handoff-return';
  errorMessage.value = '';
  const result = await returnBrowserHandoff(id);
  if (result.error) showError(result.error, '交还 AI 失败');
  else if (result.data) sessions.value = sessions.value.map(item => item.id === id ? result.data! : item);
  action.value = '';
}

async function sendManualInput(payload: BrowserManualInputPayload) {
  const id = selectedId.value;
  if (!id || !humanControl.value) return;
  manualQueue = manualQueue.then(async () => {
    if (!selectedId.value || !humanControl.value) return;
    manualBusy.value = true;
    errorMessage.value = '';
    const result = await manualInputBrowserSession(id, payload);
    applyBrowserResult(result);
    manualBusy.value = false;
  }).catch(error => {
    manualBusy.value = false;
    showError(error, '人工浏览器操作失败');
  });
  await manualQueue;
}

function pointerCoordinates(event: PointerEvent) {
  const rect = snapshotImage.value?.getBoundingClientRect();
  if (!rect || rect.width <= 0 || rect.height <= 0) return null;
  return {
    x: Math.max(0, Math.min(1280, ((event.clientX - rect.left) / rect.width) * 1280)),
    y: Math.max(0, Math.min(800, ((event.clientY - rect.top) / rect.height) * 800))
  };
}

function onManualPointerDown(event: PointerEvent) {
  if (!humanControl.value) return;
  const point = pointerCoordinates(event);
  if (!point) return;
  manualDragging.value = true;
  (event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId);
  void sendManualInput({ event: 'mouse_down', ...point });
}

function onManualPointerMove(event: PointerEvent) {
  if (!humanControl.value || !manualDragging.value) return;
  const point = pointerCoordinates(event);
  if (point) void sendManualInput({ event: 'mouse_move', ...point });
}

function onManualPointerUp(event: PointerEvent) {
  if (!humanControl.value || !manualDragging.value) return;
  const point = pointerCoordinates(event);
  manualDragging.value = false;
  if (point) void sendManualInput({ event: 'mouse_up', ...point });
}

function onManualKeydown(event: KeyboardEvent) {
  if (!humanControl.value || event.isComposing || event.repeat) return;
  const modifiers = [event.ctrlKey ? 'Control' : '', event.altKey ? 'Alt' : '', event.shiftKey ? 'Shift' : '', event.metaKey ? 'Meta' : '']
    .filter(Boolean);
  const key = [...modifiers, event.key === ' ' ? 'Space' : event.key].join('+');
  if (!key || ['Control', 'Alt', 'Shift', 'Meta'].includes(event.key)) return;
  event.preventDefault();
  void sendManualInput({ event: 'key', key });
}

function sendManualText() {
  const text = manualText.value;
  if (!text.trim()) return;
  void sendManualInput({ event: 'text', text });
  manualText.value = '';
}

onMounted(async () => {
  await refresh();
  pollTimer = setInterval(pollSnapshot, 5000);
});
onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer);
});
</script>

<template>
  <div class="browser-panel">
    <div class="panel-toolbar">
      <div class="panel-title">
        <SvgIcon icon="lucide:globe-2" />
        <span>浏览器控制</span>
      </div>
      <NButton quaternary circle title="刷新会话" aria-label="刷新会话" :loading="loading" @click="refresh">
        <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
      </NButton>
      <NPopconfirm
        positive-text="确认清除"
        negative-text="取消"
        @positive-click="clearProfiles"
      >
        <template #trigger>
          <NButton quaternary circle title="清除所有登录态与缓存" aria-label="清除所有登录态与缓存" :loading="action === 'profile-clear'">
            <template #icon><SvgIcon icon="lucide:eraser" /></template>
          </NButton>
        </template>
        将关闭当前用户的浏览器会话并清除 Cookie、Storage 与登录缓存，是否继续？
      </NPopconfirm>
    </div>

    <NAlert v-if="errorMessage" type="error" :show-icon="true" class="panel-alert">
      {{ errorMessage }}
    </NAlert>

    <NAlert v-if="workerHealthError" type="error" :show-icon="true" class="panel-alert">
      Worker 健康检查失败：{{ workerHealthError }}
    </NAlert>
    <NAlert v-else-if="workerHealth && !workerHealth.available" type="error" :show-icon="true" class="panel-alert">
      浏览器 Worker 当前不可用{{ workerHealth.error ? `：${workerHealth.error}` : '' }}。已有会话已标记失效，请稍后新建会话。
    </NAlert>
    <NAlert v-else-if="workerHealth?.invalidatedSessions" type="warning" :show-icon="true" class="panel-alert">
      Worker 重启后 {{ workerHealth.invalidatedSessions }} 个旧会话已失效，请重新创建会话。
    </NAlert>

    <NCard size="small" :bordered="false" class="control-card">
      <NSpace vertical :size="10">
        <NInput v-model:value="startUrl" placeholder="https://example.com" clearable @keyup.enter="createSession">
          <template #prefix><SvgIcon icon="lucide:link" /></template>
        </NInput>
        <NSpace justify="space-between" align="center" :wrap="true">
          <NSelect
            :value="selectedId"
            :options="sessionOptions"
            placeholder="选择浏览器会话"
            clearable
            class="session-select"
            @update:value="selectSession"
          />
          <NButton type="primary" :loading="loading" @click="createSession">
            <template #icon><SvgIcon icon="lucide:plus" /></template>
            新建会话
          </NButton>
        </NSpace>
      </NSpace>
    </NCard>

    <NEmpty v-if="!selected" description="还没有浏览器会话" class="empty-state" />
    <template v-else>
      <div class="session-meta">
        <div class="session-name">{{ selected.pageTitle || selected.currentUrl || '浏览器会话' }}</div>
        <NTag size="small" :type="statusType(selected.status)">{{ statusText(selected.status) }}</NTag>
        <NTag v-if="workerHealth" size="small" :type="workerHealth.available ? 'success' : 'error'">
          Worker {{ workerHealth.available ? '可用' : '不可用' }}
        </NTag>
        <NTag v-if="handoffText(handoffStatus)" size="small" type="warning">{{ handoffText(handoffStatus) }}</NTag>
      </div>
      <NAlert v-if="handoffActive" type="warning" :show-icon="true">
        <template v-if="humanControl">
          人工接管中{{ selected.handoffReason ? `：${selected.handoffReason}` : '' }}。完成验证码或敏感操作后，请明确交还 AI。
        </template>
        <template v-else>
          AI 已暂停浏览器操作，等待用户接管。
        </template>
      </NAlert>
      <NSpace v-if="selected.status === 'open'" justify="end">
        <NButton
          v-if="handoffStatus === 'none' || handoffStatus === 'returned' || handoffStatus === 'expired'"
          secondary
          :loading="action === 'handoff-request'"
          :disabled="Boolean(action)"
          @click="requestHandoff"
        >
          <template #icon><SvgIcon icon="lucide:hand" /></template>
          请求人工接管
        </NButton>
        <NButton
          v-if="handoffStatus === 'requested'"
          type="warning"
          :loading="action === 'handoff-takeover'"
          :disabled="Boolean(action)"
          @click="takeHandoff"
        >
          <template #icon><SvgIcon icon="lucide:hand" /></template>
          接管会话
        </NButton>
        <NButton
          v-if="humanControl"
          type="primary"
          :loading="action === 'handoff-return'"
          :disabled="Boolean(action)"
          @click="returnHandoff"
        >
          <template #icon><SvgIcon icon="lucide:undo-2" /></template>
          交还 AI
        </NButton>
      </NSpace>
      <div class="session-url">{{ selected.currentUrl || '尚未打开页面' }}</div>

      <NSpace v-if="humanControl" :wrap="true" align="center" class="manual-input-bar">
        <NInput v-model:value="manualText" type="textarea" :autosize="{ minRows: 1, maxRows: 3 }" placeholder="人工输入文本" class="manual-text-input" />
        <NButton type="primary" :loading="manualBusy" :disabled="!manualText.trim()" @click="sendManualText">
          <template #icon><SvgIcon icon="lucide:send" /></template>
          发送文字
        </NButton>
      </NSpace>
      <NSpace vertical :size="8" class="actions">
        <NInput v-model:value="startUrl" placeholder="输入网址后导航" clearable @keyup.enter="runAction('navigate')">
          <template #prefix><SvgIcon icon="lucide:compass" /></template>
          <template #suffix>
            <NButton text size="tiny" :disabled="!startUrl.trim() || Boolean(action)" @click="runAction('navigate')">打开</NButton>
          </template>
        </NInput>
        <NSpace :wrap="true">
          <NInput v-model:value="selector" placeholder="CSS 选择器" class="selector-input" />
          <NButton :loading="action === 'click'" :disabled="!selector.trim() || Boolean(action && action !== 'click') || handoffStatus === 'requested'" @click="runAction('click')">
            <template #icon><SvgIcon icon="lucide:mouse-pointer-2" /></template>
            点击
          </NButton>
          <NInput v-model:value="inputValue" placeholder="输入内容" class="value-input" />
          <NButton :loading="action === 'fill'" :disabled="!selector.trim() || Boolean(action && action !== 'fill') || handoffStatus === 'requested'" @click="runAction('fill')">
            <template #icon><SvgIcon icon="lucide:keyboard" /></template>
            输入
          </NButton>
          <NInput v-model:value="keyValue" placeholder="按键，如 Enter" class="key-input" @keyup.enter="runAction('press')" />
          <NButton :loading="action === 'press'" :disabled="!keyValue.trim() || Boolean(action && action !== 'press')" @click="runAction('press')">
            <template #icon><SvgIcon icon="lucide:corner-down-left" /></template>
            按键
          </NButton>
          <NButton quaternary :loading="action === 'back'" :disabled="Boolean(action)" title="返回上一页" aria-label="返回上一页" @click="runHistoryAction('back')">
            <template #icon><SvgIcon icon="lucide:arrow-left" /></template>
          </NButton>
          <NButton quaternary :loading="action === 'forward'" :disabled="Boolean(action)" title="前进到下一页" aria-label="前进到下一页" @click="runHistoryAction('forward')">
            <template #icon><SvgIcon icon="lucide:arrow-right" /></template>
          </NButton>
          <NButton quaternary :loading="action === 'reload'" :disabled="Boolean(action)" title="刷新页面" aria-label="刷新页面" @click="runHistoryAction('reload')">
            <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
          </NButton>
        </NSpace>
        <NSpace :wrap="true">
          <NInput v-model:value="scrollY" placeholder="滚动距离" class="key-input" />
          <NButton :loading="action === 'scroll'" :disabled="Boolean(action)" @click="runAction('scroll')">
            <template #icon><SvgIcon icon="lucide:arrow-down" /></template>
            滚动
          </NButton>
          <NInput v-model:value="hoverSelector" placeholder="悬停 CSS 选择器" class="selector-input" />
          <NButton :loading="action === 'hover'" :disabled="!hoverSelector.trim() || Boolean(action)" @click="runAction('hover')">
            <template #icon><SvgIcon icon="lucide:mouse-pointer" /></template>
            悬停
          </NButton>
        </NSpace>
        <NSpace :wrap="true">
          <NInput v-model:value="uploadSelector" placeholder="文件输入选择器" class="selector-input" />
          <NInput v-model:value="uploadFiles" type="textarea" placeholder="Worker 上传目录内相对路径，逗号或换行分隔" class="upload-input" />
          <NButton :loading="action === 'upload'" :disabled="!uploadFiles.trim() || Boolean(action)" @click="runAction('upload')">
            <template #icon><SvgIcon icon="lucide:upload" /></template>
            上传文件
          </NButton>
          <NButton secondary :loading="action === 'read-visible'" :disabled="Boolean(action)" @click="readVisibleText">
            <template #icon><SvgIcon icon="lucide:scan-text" /></template>
            读取可视文字
          </NButton>
          <NButton secondary :loading="action === 'download'" :disabled="!selector.trim() || Boolean(action)" @click="downloadPageFile">
            <template #icon><SvgIcon icon="lucide:download" /></template>
            下载选择器文件
          </NButton>
        </NSpace>
        <NSpace v-if="snapshot?.tabs?.length" :wrap="true" align="center">
          <NTag v-for="tab in snapshot.tabs" :key="tab.tabId" :type="tab.active ? 'success' : 'default'" size="small">
            <button class="tab-link" type="button" @click="runTabAction('activate', tab.tabId)">{{ tab.title || tab.url || tab.tabId }}</button>
            <button v-if="snapshot.tabs.length > 1" class="tab-close" type="button" @click="runTabAction('close', tab.tabId)">×</button>
          </NTag>
          <NButton size="small" :loading="action === 'tab-open'" :disabled="Boolean(action)" @click="runTabAction('open')">
            <template #icon><SvgIcon icon="lucide:plus" /></template>
            新建 Tab
          </NButton>
        </NSpace>
        <NSpace justify="space-between">
          <NButton secondary :loading="action === 'snapshot'" @click="loadSnapshot()">
            <template #icon><SvgIcon icon="lucide:camera" /></template>
            获取快照
          </NButton>
          <NButton tertiary type="error" :loading="action === 'close'" @click="closeSession">
            <template #icon><SvgIcon icon="lucide:x" /></template>
            关闭会话
          </NButton>
        </NSpace>
      </NSpace>

      <NSpin :show="action === 'snapshot'" class="snapshot-wrap">
        <div
          class="snapshot-interaction"
          :class="{ 'human-control': humanControl }"
          tabindex="0"
          @keydown="onManualKeydown"
          @pointerdown="onManualPointerDown"
          @pointermove="onManualPointerMove"
          @pointerup="onManualPointerUp"
        >
          <img v-if="screenshot" ref="snapshotImage" :src="screenshot" alt="浏览器页面快照" class="snapshot-image">
          <div v-else class="snapshot-placeholder">执行快照后显示页面预览</div>
        </div>
      </NSpin>
      <pre v-if="snapshot?.text" class="snapshot-text">{{ snapshot.text }}</pre>
    </template>
  </div>
</template>

<style scoped lang="scss">
.browser-panel { display: flex; flex-direction: column; gap: 12px; min-height: 100%; padding: 16px; background: var(--n-color); }
.panel-toolbar, .session-meta { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.panel-title { display: flex; align-items: center; gap: 8px; font-size: 16px; font-weight: 600; }
.panel-alert { margin-bottom: 0; }
.control-card { background: var(--n-color-modal); }
.session-select { min-width: 220px; flex: 1; }
.session-name { min-width: 0; overflow: hidden; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.session-url { overflow: hidden; color: var(--n-text-color-3); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.actions { width: 100%; }
.selector-input { min-width: 170px; flex: 1; }
.value-input { min-width: 150px; flex: 1; }
.key-input { min-width: 130px; flex: 1; }
.upload-input { min-width: 220px; flex: 1; }
.manual-input-bar { align-items: flex-end; }
.manual-text-input { min-width: 260px; flex: 1; }
.tab-link, .tab-close { border: 0; background: transparent; color: inherit; cursor: pointer; }
.tab-close { margin-left: 5px; padding: 0 2px; }
.empty-state { padding: 40px 0; }
.snapshot-wrap { min-height: 180px; overflow: hidden; border: 1px solid var(--n-border-color); border-radius: 6px; background: #f4f5f7; }
.snapshot-image { display: block; width: 100%; max-height: 360px; object-fit: contain; background: #fff; }
.snapshot-interaction { outline: none; }
.snapshot-interaction.human-control { cursor: crosshair; }
.snapshot-placeholder { display: grid; min-height: 180px; place-items: center; color: var(--n-text-color-3); font-size: 13px; }
.snapshot-text { max-height: 180px; margin: 0; overflow: auto; white-space: pre-wrap; word-break: break-word; color: var(--n-text-color-2); font-size: 12px; }

@media (max-width: 560px) {
  .browser-panel { padding: 12px; }
  .session-select { min-width: 0; width: 100%; }
  .selector-input, .value-input, .key-input, .upload-input, .manual-text-input { min-width: 100%; }
}
</style>
