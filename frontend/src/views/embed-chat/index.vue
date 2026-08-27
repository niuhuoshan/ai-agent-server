<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import {
  createEmbedWidgetSession,
  fetchEmbedWidgetState,
  resetEmbedWidgetSession,
  resumeEmbedWidgetTurn,
  submitEmbedFeedback,
  stopEmbedWidgetTurn,
  streamEmbedWidgetMessage,
  uploadEmbedAttachment
} from '@/service/api/embed-widget';
import type {
  EmbedApplicationTheme,
  EmbedMessage,
  EmbedSession,
  EmbedSseEvent,
  EmbedState,
  EmbedWelcomeCard
} from '@/service/api/embed-widget';
import { correlationId, createEmbedEnvelope, exactHttpOrigin, isEmbedEnvelope } from '@/embed/protocol';
import type { EmbedEnvelope, EmbedHostCommand } from '@/embed/protocol';

defineOptions({ name: 'EmbedChat' });

type WidgetStatus =
  | 'waiting'
  | 'initializing'
  | 'ready'
  | 'uploading'
  | 'streaming'
  | 'stopping'
  | 'expired'
  | 'forbidden'
  | 'conflict'
  | 'error';

const root = ref<HTMLElement | null>(null);
const messagesElement = ref<HTMLElement | null>(null);
const status = ref<WidgetStatus>('waiting');
const statusMessage = ref('正在建立安全连接');
const draft = ref('');
const selectedFiles = ref<File[]>([]);
const session = ref<EmbedSession | null>(null);
const messages = ref<EmbedMessage[]>([]);
const welcomeCards = ref<EmbedWelcomeCard[]>([]);
const application = ref<EmbedApplicationTheme | null>(null);
const context = ref<Record<string, unknown>>({});
const theme = ref<'light' | 'dark' | 'auto'>('auto');
const styleVars = ref<Record<string, string>>({});
const activeTurnId = ref<string | null>(null);
const lastCursor = ref(0);
const credential = ref('');
let streamController: AbortController | null = null;
let resizeObserver: ResizeObserver | null = null;
let operationGeneration = 0;
let resetInFlight = false;
let pendingStop: {
  generation: number;
  started: boolean;
  promise: Promise<void>;
  resolve: () => void;
  reject: (reason: unknown) => void;
} | null = null;
const feedbackByMessageId = new Map<string, 'up' | 'down' | null>();

type EmbedCommandError = Error & {
  status?: number;
  preserveState?: boolean;
};

const query = new URLSearchParams(window.location.search);
const instanceId = query.get('instanceId') || query.get('instance_id') || '';
let parentOrigin = '';
let routeError = '';
const contractMode = ref<'legacy' | 'nhs-v1'>('legacy');
try {
  parentOrigin = exactHttpOrigin(query.get('parentOrigin') || '');
  if (!/^[A-Za-z0-9_-]{8,80}$/.test(instanceId)) throw new Error('Embed 实例标识无效');
} catch (error) {
  routeError = error instanceof Error ? error.message : 'Embed 启动参数无效';
}

const canSend = computed(() => status.value === 'ready' && Boolean(draft.value.trim()));
const busy = computed(() => ['initializing', 'uploading', 'streaming', 'stopping'].includes(status.value));
const displayName = computed(() => application.value?.displayName || '智能助手');
const resolvedTheme = computed(() =>
  theme.value === 'auto' ? (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light') : theme.value
);
const themeStyle = computed(() => ({
  '--embed-primary': application.value?.primaryColor || '#18a058',
  ...styleVars.value
}));

function post(type: EmbedEnvelope['type'], correlation: string, payload: unknown) {
  if (!parentOrigin || window.parent === window) return;
  const outputType = contractMode.value === 'nhs-v1' ? canonicalOutput(type) : legacyOutput(type);
  window.parent.postMessage(createEmbedEnvelope(instanceId, outputType, correlation, payload), parentOrigin);
}

/** The old names are retained for existing embeds; new hosts consume the frozen Nhs names. */
function canonicalOutput(type: EmbedEnvelope['type']): EmbedEnvelope['type'] {
  return ({
    READY: 'NHS_WIDGET_READY',
    INITIALIZED: 'INIT_SUCCESS',
    STATE: 'CONVERSATION_CHANGED',
    MESSAGE_START: 'CONNECTION_STATUS',
    MESSAGE_COMPLETE: 'CONVERSATION_CHANGED'
  } as Record<string, EmbedEnvelope['type']>)[type] || type;
}

function legacyOutput(type: EmbedEnvelope['type']): EmbedEnvelope['type'] {
  return ({
    NHS_WIDGET_READY: 'READY',
    INIT_SUCCESS: 'INITIALIZED',
    CONVERSATION_CHANGED: 'STATE',
    GENERATION_STOPPED: 'STATE'
  } as Record<string, EmbedEnvelope['type']>)[type] || type;
}

function statePayload() {
  return {
    status: status.value,
    session: session.value,
    messages: messages.value,
    welcomeCards: welcomeCards.value,
    activeTurnId: activeTurnId.value,
    cursor: lastCursor.value,
    context: context.value,
    theme: theme.value,
    styleVars: styleVars.value
  };
}

async function handleCommand(command: EmbedEnvelope) {
  try {
    switch (command.type as EmbedHostCommand) {
      case 'INIT_CONFIG':
        await initialize(command);
        break;
      case 'OPEN_SAVED_REPORT':
        await openSavedReport(command);
        break;
      case 'SYNC_STATE':
        await syncState(command);
        break;
      case 'UPDATE_CONTEXT':
        ensureReady();
        context.value = { ...context.value, ...recordPayload(command.payload, 'context') };
        post('STATE', command.correlationId, statePayload());
        break;
      case 'CLEAR_SESSION':
        await clearSession(command.correlationId);
        break;
      case 'RESET_SESSION':
        await reset(command.correlationId);
        break;
      case 'SEND_COMMAND':
        await dispatchCommand(command);
        break;
      case 'SEND_MESSAGE':
        await sendFromHost(command);
        break;
      case 'STOP':
      case 'STOP_GENERATION':
        await stop(command.correlationId);
        break;
      case 'RESUME':
        await resume(command.correlationId);
        break;
      case 'SET_THEME':
        setTheme(command.payload);
        post('STATE', command.correlationId, statePayload());
        break;
      default:
        throw new Error('不支持的 Embed 指令');
    }
  } catch (error) {
    if ((command.type as EmbedHostCommand) === 'INIT_CONFIG') {
      post('INIT_FAILURE', command.correlationId, {
        reason: error instanceof Error ? error.message : 'initialization_failed',
        status: error && typeof error === 'object' && 'status' in error
          ? Number((error as { status?: unknown }).status) || undefined
          : undefined
      });
    }
    fail(error, command.correlationId);
  }
}

async function openSavedReport(command: EmbedEnvelope) {
  ensureReady(true);
  const payload = objectPayload(command.payload);
  const rawReport = payload.open_saved_report || payload.report;
  const report = rawReport && typeof rawReport === 'object'
    ? structuredClone(rawReport as Record<string, unknown>)
    : payload;
  const reportId = report.report_id ?? report.reportId;
  if (reportId === undefined || reportId === null || String(reportId).trim() === '') {
    throw new Error('保存的报表标识不能为空');
  }
  context.value = { ...context.value, openSavedReport: report };
  post('OPEN_DATA_PORTAL_FULL', command.correlationId, { report, state: statePayload() });
  post('STATE', command.correlationId, statePayload());
}

async function syncState(command: EmbedEnvelope) {
  ensureReady(true);
  const payload = objectPayload(command.payload);
  const next = payload.state && typeof payload.state === 'object'
    ? payload.state as Record<string, unknown>
    : payload.context && typeof payload.context === 'object'
      ? payload.context as Record<string, unknown>
      : {};
  context.value = { ...context.value, ...structuredClone(next) };
  await reconcile(operationGeneration);
  post('STATE', command.correlationId, statePayload());
}

async function clearSession(correlation: string) {
  ensureReady(true);
  // Clearing must rotate the server-backed conversation; local-only clearing
  // would resurrect the old history after a refresh.
  await reset(correlation);
}

async function dispatchCommand(command: EmbedEnvelope) {
  const payload = objectPayload(command.payload);
  const name = String(payload.command || payload.name || payload.type || '').toUpperCase();
  const nested = payload.payload && typeof payload.payload === 'object'
    ? payload.payload as Record<string, unknown>
    : payload;
  switch (name) {
    case 'SEND_MESSAGE':
    case 'CHAT': {
      const input = typeof nested.input === 'string' ? nested.input : '';
      const attachments = Array.isArray(nested.attachments)
        ? nested.attachments.filter((file): file is File => file instanceof File)
        : [];
      await send(input, attachments, command.correlationId);
      break;
    }
    case 'STOP':
    case 'STOP_GENERATION':
      await stop(command.correlationId);
      break;
    case 'RESUME':
      await resume(command.correlationId);
      break;
    case 'RESET_SESSION':
      await reset(command.correlationId);
      break;
    case 'CLEAR_SESSION':
      await clearSession(command.correlationId);
      break;
    case 'OPEN_SAVED_REPORT':
      await openSavedReport({ ...command, payload: nested });
      break;
    case 'USER_FEEDBACK':
      ensureReady(true);
      if (nested.rating !== 'up' && nested.rating !== 'down') throw new Error('反馈类型必须为 up 或 down');
      {
        const messageId = String(nested.messageId ?? nested.message_id ?? '');
        const message = messages.value.find(item => String(item.id) === messageId);
        if (!message) throw new Error('反馈消息不存在');
        await submitFeedback(message, nested.rating, command.correlationId, {
          comment: typeof nested.comment === 'string' ? nested.comment.slice(0, 2000) : undefined
        });
      }
      break;
    default:
      throw new Error(`不支持的 Embed 命令：${name || '空命令'}`);
  }
}

async function initialize(command: EmbedEnvelope) {
  const payload = objectPayload(command.payload);
  if (payload.contract === 'nhs-v1' || payload.protocolVersion === 'nhs-v1') {
    contractMode.value = 'nhs-v1';
  }
  const launchCredential = typeof payload.credential === 'string' ? payload.credential.trim() : '';
  if (session.value) {
    if (!launchCredential || !/^ebt_[A-Za-z0-9_-]{43}$/.test(launchCredential)) {
      throw new Error('Embed 启动凭证无效或已过期');
    }
    if (launchCredential === credential.value) {
      post('INITIALIZED', command.correlationId, statePayload());
      return;
    }
    const generation = ++operationGeneration;
    streamController?.abort();
    streamController = null;
    pendingStop?.resolve();
    pendingStop = null;
    status.value = 'initializing';
    statusMessage.value = '正在轮换安全会话';
    const current = requireSession();
    const bootstrap = await resetEmbedWidgetSession(launchCredential, parentOrigin, current.id);
    if (superseded(generation, command.correlationId, '凭证轮换')) return;
    const initialContext = payload.context || payload.business_context || payload.page_info;
    context.value = initialContext && typeof initialContext === 'object'
      ? structuredClone(initialContext as Record<string, unknown>)
      : {};
    setTheme(payload);
    credential.value = bootstrap.browserCredential.credential;
    session.value = bootstrap.session;
    application.value = bootstrap.config;
    welcomeCards.value = bootstrap.welcomeCards || [];
    feedbackByMessageId.clear();
    messages.value = [];
    activeTurnId.value = null;
    lastCursor.value = 0;
    status.value = 'ready';
    statusMessage.value = '';
    post('INITIALIZED', command.correlationId, statePayload());
    if (payload.open_saved_report && typeof payload.open_saved_report === 'object') {
      await openSavedReport({ ...command, payload: { open_saved_report: payload.open_saved_report } });
    }
    await scrollToBottom();
    return;
  }
  if (!/^ebt_[A-Za-z0-9_-]{43}$/.test(launchCredential)) throw new Error('Embed 启动凭证无效');
  status.value = 'initializing';
  statusMessage.value = '正在创建安全会话';
  const initialContext = payload.context || payload.business_context || payload.page_info;
  context.value = initialContext && typeof initialContext === 'object'
    ? structuredClone(initialContext as Record<string, unknown>)
    : {};
  setTheme(payload);
  const bootstrap = await createEmbedWidgetSession(launchCredential, parentOrigin);
  credential.value = bootstrap.browserCredential.credential;
  session.value = bootstrap.session;
  application.value = bootstrap.config;
  welcomeCards.value = bootstrap.welcomeCards || [];
  feedbackByMessageId.clear();
  messages.value = [];
  status.value = 'ready';
  statusMessage.value = '';
  post('INITIALIZED', command.correlationId, statePayload());
  if (payload.open_saved_report && typeof payload.open_saved_report === 'object') {
    await openSavedReport({ ...command, payload: { open_saved_report: payload.open_saved_report } });
  }
  await scrollToBottom();
}

async function reset(correlation: string) {
  ensureReady(true);
  if (resetInFlight) throw commandConflict('Embed 当前正在重置会话');
  const generation = ++operationGeneration;
  resetInFlight = true;
  const previousStop = pendingStop;
  pendingStop = null;
  streamController?.abort();
  streamController = null;
  status.value = 'initializing';
  statusMessage.value = '正在重置安全会话';
  const current = requireSession();
  const currentCredential = credential.value;
  let bootstrap: Awaited<ReturnType<typeof resetEmbedWidgetSession>>;
  try {
    bootstrap = await resetEmbedWidgetSession(currentCredential, parentOrigin, current.id);
  } finally {
    resetInFlight = false;
    previousStop?.resolve();
  }
  if (superseded(generation, correlation, '会话重置')) return;
  credential.value = bootstrap.browserCredential.credential;
  session.value = bootstrap.session;
  application.value = bootstrap.config;
  welcomeCards.value = bootstrap.welcomeCards || [];
  feedbackByMessageId.clear();
  messages.value = [];
  activeTurnId.value = null;
  lastCursor.value = 0;
  selectedFiles.value = [];
  status.value = 'ready';
  statusMessage.value = '';
  post('STATE', correlation, statePayload());
}

async function sendFromHost(command: EmbedEnvelope) {
  const payload = objectPayload(command.payload);
  const input = typeof payload.input === 'string' ? payload.input.trim() : '';
  const files = Array.isArray(payload.attachments)
    ? payload.attachments.filter((file): file is File => file instanceof File)
    : [];
  await send(input, files, command.correlationId);
}

async function sendFromUi() {
  const input = draft.value.trim();
  if (!input) return;
  draft.value = '';
  const files = selectedFiles.value;
  selectedFiles.value = [];
  const correlation = correlationId('ui');
  try {
    await send(input, files, correlation);
  } catch (error) {
    fail(error, correlation);
  }
}

async function sendWelcomeCard(card: EmbedWelcomeCard) {
  const correlation = correlationId('welcome');
  try {
    await send(card.prompt, [], correlation);
  } catch (error) {
    fail(error, correlation);
  }
}

function welcomeCardIcon(icon: string) {
  return ({
    chart: 'lucide:chart-column',
    knowledge: 'lucide:book-open',
    workspace: 'lucide:folder-open',
    report: 'lucide:file-chart-column',
    alert: 'lucide:triangle-alert',
    chat: 'lucide:message-circle'
  } as Record<string, string>)[icon] || 'lucide:message-circle';
}

async function resetFromUi() {
  const correlation = correlationId('ui_reset');
  try {
    await reset(correlation);
  } catch (error) {
    fail(error, correlation);
  }
}

async function stopFromUi() {
  const correlation = correlationId('ui_stop');
  try {
    await stop(correlation);
  } catch (error) {
    fail(error, correlation);
  }
}

async function send(input: string, files: File[], correlation: string) {
  ensureReady();
  if (!input || input.length > 65_536) throw new Error('消息不能为空或超过 64KB');
  if (files.length > 5 || files.some(file => file.size <= 0 || file.size > 10 * 1024 * 1024)) {
    throw new Error('每条消息最多 5 个附件，单个附件不能超过 10MB');
  }
  const current = requireSession();
  const currentCredential = credential.value;
  const generation = operationGeneration;
  status.value = files.length ? 'uploading' : 'streaming';
  statusMessage.value = files.length ? '正在上传附件' : '';
  post('CONNECTION_STATUS', correlation, { status: files.length ? 'uploading' : 'streaming' });
  const attachmentIds: string[] = [];
  let controller: AbortController | null = null;
  try {
    for (const file of files) {
      const attachment = await uploadEmbedAttachment(currentCredential, parentOrigin, current.id, file);
      if (superseded(generation, correlation, '消息发送')) return;
      attachmentIds.push(attachment.id);
    }

    messages.value.push({
      id: correlationId('local'),
      traceId: '',
      role: 'user',
      content: input,
      status: 'sending',
      createdAt: new Date().toISOString()
    });
    messages.value.push({
      id: correlationId('draft'),
      traceId: '',
      role: 'assistant',
      content: '',
      status: 'streaming',
      createdAt: new Date().toISOString()
    });
    status.value = pendingStop?.generation === generation ? 'stopping' : 'streaming';
    statusMessage.value = '';
    post('MESSAGE_START', correlation, { input, attachmentCount: files.length });
    await scrollToBottom();
    if (superseded(generation, correlation, '消息发送')) return;
    controller = new AbortController();
    streamController = controller;
    await streamEmbedWidgetMessage(
      currentCredential,
      parentOrigin,
      current.id,
      {
        idempotencyKey: correlation,
        input,
        attachmentIds,
        context: context.value
      },
      event => {
        if (generation === operationGeneration) consumeEvent(event, correlation);
      },
      controller.signal
    );
    if (superseded(generation, correlation, '消息发送')) return;
    await reconcile(generation);
    if (superseded(generation, correlation, '消息发送')) return;
    await settlePendingStopAfterStream(generation);
    if (superseded(generation, correlation, '消息发送')) return;
    status.value = 'ready';
    post('MESSAGE_COMPLETE', correlation, statePayload());
  } catch (error) {
    if (superseded(generation, correlation, '消息发送')) return;
    if (pendingStop?.generation === generation) {
      pendingStop.reject(error);
      pendingStop = null;
    }
    throw error;
  } finally {
    if (controller && streamController === controller) streamController = null;
  }
}

function consumeEvent(event: EmbedSseEvent, correlation: string) {
  if (event.event === 'meta') {
    const turnId = event.data.turnId;
    if (typeof turnId === 'string' || typeof turnId === 'number') activeTurnId.value = String(turnId);
    void flushPendingStop();
  } else {
    const cursor = Number(event.id || 0);
    if (Number.isFinite(cursor)) lastCursor.value = Math.max(lastCursor.value, cursor);
    const eventType = String(event.data.eventType || '');
    const summary = typeof event.data.summary === 'string' ? event.data.summary : '';
    if (eventType === 'custom' && event.data.payload
      && typeof event.data.payload === 'object'
      && (event.data.payload as Record<string, unknown>).retraction === true) {
      const assistant = [...messages.value].reverse().find(message => message.role === 'assistant');
      if (assistant) assistant.content = summary || '此响应因安全策略已撤回';
    } else if (eventType === 'text_delta' && summary) {
      const assistant = [...messages.value].reverse().find(message => message.role === 'assistant');
      if (assistant) assistant.content += summary;
    }
  }
  post('MESSAGE_EVENT', correlation, event);
  void scrollToBottom();
}

async function submitFeedback(
  message: EmbedMessage,
  rating: 'up' | 'down',
  correlation = correlationId('feedback'),
  options: { comment?: string } = {}
) {
  ensureReady(true);
  if (message.role !== 'assistant' || !message.id) return;
  const next = rating;
  const current = requireSession();
  const result = await submitEmbedFeedback(credential.value, parentOrigin, current.id, {
    messageId: Number(message.id),
    rating: next,
    comment: options.comment,
    traceId: message.traceId || undefined
  });
  feedbackByMessageId.set(message.id, next);
  message.feedback = next;
  post('USER_FEEDBACK', correlation, {
    ...result,
    rating: next,
    feedback: next,
    message_id: message.id,
    trace_id: message.traceId || undefined
  });
}

async function stop(correlation: string) {
  ensureReady(true);
  if (status.value === 'initializing') throw commandConflict('Embed 当前正在初始化，无法停止回复');
  const generation = operationGeneration;
  if (pendingStop) {
    await pendingStop.promise;
    if (superseded(generation, correlation, '停止操作')) return;
    post('STATE', correlation, statePayload());
    return;
  }
  if (!activeTurnId.value) {
    if (!['uploading', 'streaming', 'stopping'].includes(status.value)) {
      post('STATE', correlation, statePayload());
      return;
    }
    status.value = 'stopping';
    const queued = pendingStop || createPendingStop();
    pendingStop = queued;
    await queued.promise;
    if (superseded(generation, correlation, '停止操作')) return;
    post('GENERATION_STOPPED', correlation, statePayload());
    post('STATE', correlation, statePayload());
    return;
  }
  status.value = 'stopping';
  const current = requireSession();
  const currentCredential = credential.value;
  try {
    await stopEmbedWidgetTurn(currentCredential, parentOrigin, current.id, activeTurnId.value);
  } catch (error) {
    if (superseded(generation, correlation, '停止操作')) return;
    throw error;
  }
  if (superseded(generation, correlation, '停止操作')) return;
  post('GENERATION_STOPPED', correlation, statePayload());
  post('STATE', correlation, statePayload());
}

function createPendingStop() {
  let resolve!: () => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<void>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { generation: operationGeneration, started: false, promise, resolve, reject };
}

async function flushPendingStop() {
  const queued = pendingStop;
  if (!queued || queued.started || !activeTurnId.value) return;
  if (queued.generation !== operationGeneration) {
    pendingStop = null;
    queued.resolve();
    return;
  }
  queued.started = true;
  status.value = 'stopping';
  try {
    const current = requireSession();
    const currentCredential = credential.value;
    await stopEmbedWidgetTurn(currentCredential, parentOrigin, current.id, activeTurnId.value);
    queued.resolve();
  } catch (error) {
    queued.reject(error);
  } finally {
    if (pendingStop === queued) pendingStop = null;
  }
}

async function settlePendingStopAfterStream(generation: number) {
  const queued = pendingStop;
  if (!queued || queued.generation !== generation) return;
  if (activeTurnId.value) {
    void flushPendingStop();
    await queued.promise;
    return;
  }
  pendingStop = null;
  queued.resolve();
}

async function resume(correlation: string) {
  ensureReady(true);
  if (busy.value) throw commandConflict('Embed 当前正在处理其他操作，无法恢复流');
  const generation = operationGeneration;
  status.value = 'initializing';
  statusMessage.value = '正在恢复回复';
  await reconcile(generation);
  if (superseded(generation, correlation, '流恢复')) return;
  const current = requireSession();
  const currentCredential = credential.value;
  const state = await fetchEmbedWidgetState(currentCredential, parentOrigin, current.id);
  if (superseded(generation, correlation, '流恢复')) return;
  const turn = state.turns.find(item => ['running', 'stopping'].includes(item.status));
  if (!turn) {
    status.value = 'ready';
    activeTurnId.value = null;
    post('STATE', correlation, statePayload());
    return;
  }
  activeTurnId.value = turn.id;
  status.value = turn.status === 'stopping' ? 'stopping' : 'streaming';
  statusMessage.value = '';
  messages.value.push({
    id: correlationId('resume_draft'),
    traceId: '',
    role: 'assistant',
    content: '',
    status: 'streaming',
    createdAt: new Date().toISOString()
  });
  const controller = new AbortController();
  streamController = controller;
  try {
    await resumeEmbedWidgetTurn(
      currentCredential,
      parentOrigin,
      current.id,
      turn.id,
      lastCursor.value,
      event => {
        if (generation === operationGeneration) consumeEvent(event, correlation);
      },
      controller.signal
    );
    if (superseded(generation, correlation, '流恢复')) return;
    await reconcile(generation);
    if (superseded(generation, correlation, '流恢复')) return;
    status.value = 'ready';
    post('STATE', correlation, statePayload());
  } catch (error) {
    if (superseded(generation, correlation, '流恢复')) return;
    throw error;
  } finally {
    if (streamController === controller) streamController = null;
  }
}

async function reconcile(expectedGeneration = operationGeneration) {
  const current = requireSession();
  const state: EmbedState = await fetchEmbedWidgetState(credential.value, parentOrigin, current.id);
  if (expectedGeneration !== operationGeneration) return;
  session.value = state.session;
  messages.value = state.messages.map(message => ({
    ...message,
    feedback: message.feedback ?? feedbackByMessageId.get(message.id) ?? null
  }));
  const active = state.turns.find(turn => ['running', 'stopping'].includes(turn.status));
  activeTurnId.value = active?.id || null;
  await scrollToBottom();
}

function onFiles(event: Event) {
  const input = event.target as HTMLInputElement;
  selectedFiles.value = [...(input.files || [])].slice(0, 5);
  input.value = '';
}

function removeFile(index: number) {
  selectedFiles.value.splice(index, 1);
}

function setTheme(payload: unknown) {
  const source = objectPayload(payload);
  const value = source.theme;
  if (value === 'light' || value === 'dark' || value === 'auto') theme.value = value;
  const rawVars = source.styleVars;
  if (rawVars && typeof rawVars === 'object' && !Array.isArray(rawVars)) {
    const next: Record<string, string> = {};
    Object.entries(rawVars).slice(0, 16).forEach(([key, raw]) => {
      if (!/^--[A-Za-z0-9_-]{1,64}$/.test(key) || typeof raw !== 'string') return;
      if (raw.length > 256 || /[;{}]/.test(raw)) return;
      next[key] = raw;
    });
    styleVars.value = next;
  }
}

function ensureReady(allowBusy = false) {
  if (!credential.value || !session.value) throw new Error('Embed 会话尚未初始化');
  if (!allowBusy && status.value !== 'ready') {
    throw commandConflict('Embed 当前正在处理其他操作');
  }
}

function commandConflict(message: string) {
  const error = new Error(message) as EmbedCommandError;
  error.status = 409;
  error.preserveState = true;
  return error;
}

function superseded(generation: number, correlation: string, operation: string) {
  if (generation === operationGeneration) return false;
  post('ERROR', correlation, {
    message: `${operation}已被会话重置取消`,
    status: 409,
    cancelled: true,
    state: statePayload()
  });
  return true;
}

function requireSession() {
  if (!session.value) throw new Error('Embed 会话尚未初始化');
  return session.value;
}

function objectPayload(payload: unknown): Record<string, any> {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
    throw new Error('Embed 指令载荷无效');
  }
  return payload as Record<string, any>;
}

function recordPayload(payload: unknown, key: string) {
  const value = objectPayload(payload)[key];
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
  return structuredClone(value as Record<string, unknown>);
}

function fail(error: unknown, correlation: string) {
  const message = error instanceof Error ? error.message : 'Embed 操作失败';
  const httpStatus =
    error && typeof error === 'object' && 'status' in error ? Number((error as { status?: unknown }).status) : 0;
  const preserveState = Boolean(
    error && typeof error === 'object' && 'preserveState' in error && (error as EmbedCommandError).preserveState
  );
  const reportedStatus: WidgetStatus = httpStatus === 401
    ? 'expired'
    : httpStatus === 403
      ? 'forbidden'
      : httpStatus === 409
        ? 'conflict'
        : 'error';
  if (!preserveState) {
    status.value = reportedStatus;
  }
  statusMessage.value = message;
  post('CONNECTION_STATUS', correlation, {
    status: preserveState ? status.value : reportedStatus,
    message,
    httpStatus: httpStatus || undefined
  });
  post('ERROR', correlation, { message, status: httpStatus || undefined, state: statePayload() });
}

async function scrollToBottom() {
  await nextTick();
  messagesElement.value?.scrollTo({ top: messagesElement.value.scrollHeight, behavior: 'smooth' });
}

function onMessage(event: MessageEvent) {
  if (
    !parentOrigin ||
    event.origin !== parentOrigin ||
    event.source !== window.parent ||
    !isEmbedEnvelope(event.data, instanceId)
  )
    return;
  void handleCommand(event.data);
}

onMounted(() => {
  if (routeError) {
    status.value = 'error';
    statusMessage.value = routeError;
    return;
  }
  window.addEventListener('message', onMessage);
  resizeObserver = new ResizeObserver(entries => {
    const height = Math.ceil(entries[0]?.contentRect.height || root.value?.scrollHeight || 420);
    post('RESIZE', correlationId('resize'), { height });
  });
  if (root.value) resizeObserver.observe(root.value);
  post('READY', correlationId('ready'), {
    protocolVersion: '1.0',
    capabilities: [
      'INIT_CONFIG', 'OPEN_SAVED_REPORT', 'SYNC_STATE', 'UPDATE_CONTEXT', 'SET_THEME',
      'STOP_GENERATION', 'CLEAR_SESSION', 'RESET_SESSION', 'SEND_COMMAND',
      'attachments', 'resize', 'theme', 'multi_instance'
    ]
  });
});

onBeforeUnmount(() => {
  operationGeneration++;
  streamController?.abort();
  pendingStop?.resolve();
  pendingStop = null;
  resizeObserver?.disconnect();
  window.removeEventListener('message', onMessage);
  credential.value = '';
});
</script>

<template>
  <main ref="root" class="embed-root" :class="`theme-${resolvedTheme}`" :style="themeStyle">
    <header class="embed-header">
      <div class="brand-mark"><SvgIcon icon="lucide:bot" /></div>
      <div class="brand-copy">
        <strong>{{ displayName }}</strong>
        <span v-if="status === 'ready' && !statusMessage">在线</span>
        <span v-else>{{ statusMessage || status }}</span>
      </div>
      <NButton v-if="session" quaternary circle size="small" title="重新开始" :disabled="busy" @click="resetFromUi">
        <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
      </NButton>
    </header>

    <section ref="messagesElement" class="message-list" aria-live="polite">
      <div v-if="!session" class="empty-state">
        <SvgIcon
          :icon="['error', 'forbidden', 'conflict'].includes(status) ? 'lucide:circle-alert' : 'lucide:loader-circle'"
          class="empty-icon"
        />
        <strong>{{ status === 'error' ? '无法启动助手' : statusMessage }}</strong>
      </div>
      <div v-else-if="!messages.length" class="welcome-state">
        <div class="empty-state compact">
          <SvgIcon icon="lucide:message-circle" class="empty-icon" />
          <strong>有什么可以帮你？</strong>
        </div>
        <div v-if="welcomeCards.length" class="welcome-cards" aria-label="快捷提问">
          <button
            v-for="card in welcomeCards"
            :key="`${card.title}-${card.prompt}`"
            type="button"
            class="welcome-card"
            :disabled="busy"
            @click="sendWelcomeCard(card)"
          >
            <span class="welcome-card-icon"><SvgIcon :icon="welcomeCardIcon(card.icon)" /></span>
            <span class="welcome-card-copy">
              <strong>{{ card.title }}</strong>
              <small>{{ card.subtitle }}</small>
            </span>
          </button>
        </div>
      </div>
      <article
        v-for="message in messages"
        :key="message.id"
        class="message"
        :class="message.role === 'user' ? 'message-user' : 'message-assistant'"
      >
        <div class="message-avatar">
          <SvgIcon :icon="message.role === 'user' ? 'lucide:user' : 'lucide:bot'" />
        </div>
        <div class="message-content-wrap">
          <div class="message-content">{{ message.content || '...' }}</div>
          <div v-if="message.role === 'assistant' && message.content" class="message-feedback" aria-label="回复反馈">
            <button
              type="button"
              title="有帮助"
              :class="{ active: message.feedback === 'up' }"
              :disabled="busy"
              @click="submitFeedback(message, 'up')"
            ><SvgIcon icon="lucide:thumbs-up" /></button>
            <button
              type="button"
              title="需要改进"
              :class="{ active: message.feedback === 'down' }"
              :disabled="busy"
              @click="submitFeedback(message, 'down')"
            ><SvgIcon icon="lucide:thumbs-down" /></button>
          </div>
        </div>
      </article>
    </section>

    <footer class="composer">
      <div v-if="selectedFiles.length" class="file-list">
        <button
          v-for="(file, index) in selectedFiles"
          :key="`${file.name}-${index}`"
          type="button"
          @click="removeFile(index)"
        >
          <SvgIcon icon="lucide:paperclip" />
          <span>{{ file.name }}</span>
          <SvgIcon icon="lucide:x" />
        </button>
      </div>
      <div class="composer-row">
        <label class="icon-action" :class="{ disabled: busy || !session }" title="添加附件">
          <SvgIcon icon="lucide:paperclip" />
          <input
            type="file"
            multiple
            accept=".txt,.md,.csv,.json,.pdf,.png,.jpg,.jpeg,.webp"
            :disabled="busy || !session"
            @change="onFiles"
          />
        </label>
        <NInput
          v-model:value="draft"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 5 }"
          :disabled="busy || !session"
          placeholder="输入消息"
          @keydown.enter.exact.prevent="sendFromUi"
        />
        <NButton
          v-if="status === 'streaming' || status === 'stopping'"
          circle
          secondary
          type="warning"
          title="停止"
          :loading="status === 'stopping'"
          @click="stopFromUi"
        >
          <template #icon><SvgIcon icon="lucide:square" /></template>
        </NButton>
        <NButton v-else circle type="primary" title="发送" :disabled="!canSend" @click="sendFromUi">
          <template #icon><SvgIcon icon="lucide:send" /></template>
        </NButton>
      </div>
      <div v-if="application?.watermark" class="watermark">牛火山企业智能体平台</div>
    </footer>
  </main>
</template>

<style scoped>
.embed-root {
  display: grid;
  grid-template-rows: 58px minmax(220px, 1fr) auto;
  width: 100%;
  min-height: 420px;
  max-height: 760px;
  color: #1f2937;
  background: #f8fafc;
  border: 1px solid #d8dee8;
  font-size: 14px;
  overflow: hidden;
}

.embed-root.theme-dark {
  color: #e5e7eb;
  background: #17191d;
  border-color: #343941;
}

.embed-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  color: white;
  background: var(--embed-primary);
}

.brand-mark,
.message-avatar {
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 6px;
}

.brand-mark {
  background: rgb(255 255 255 / 18%);
}
.brand-copy {
  display: grid;
  flex: 1;
  min-width: 0;
}
.brand-copy strong {
  overflow: hidden;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.brand-copy span {
  font-size: 11px;
  opacity: 0.82;
}

.message-list {
  min-height: 220px;
  padding: 16px;
  overflow: auto;
  overscroll-behavior: contain;
}

.empty-state {
  display: grid;
  min-height: 220px;
  place-items: center;
  align-content: center;
  gap: 10px;
  color: #64748b;
  text-align: center;
}

.empty-state.compact {
  min-height: 160px;
}
.empty-icon {
  font-size: 28px;
}

.welcome-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  max-width: 680px;
  margin: -8px auto 12px;
}

.welcome-card {
  display: flex;
  min-width: 0;
  gap: 8px;
  padding: 10px;
  color: inherit;
  text-align: left;
  background: #fff;
  border: 1px solid #dbe2ea;
  border-radius: 6px;
  cursor: pointer;
  transition: border-color 160ms ease, background 160ms ease, transform 160ms ease;
}

.welcome-card:hover:not(:disabled) {
  background: #f8fbff;
  border-color: var(--embed-primary);
  transform: translateY(-1px);
}

.welcome-card:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.welcome-card-icon {
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  width: 28px;
  height: 28px;
  color: var(--embed-primary);
  background: color-mix(in srgb, var(--embed-primary) 12%, white);
  border-radius: 5px;
}

.welcome-card-copy {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.welcome-card-copy strong,
.welcome-card-copy small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.welcome-card-copy strong {
  font-size: 12px;
}

.welcome-card-copy small {
  color: #64748b;
  font-size: 10px;
}

.theme-dark .welcome-card {
  background: #202329;
  border-color: #3b414b;
}

.theme-dark .welcome-card:hover:not(:disabled) {
  background: #282d35;
}

.theme-dark .welcome-card-copy small {
  color: #a7b0be;
}

.message {
  display: flex;
  gap: 9px;
  margin-bottom: 14px;
}

.message-user {
  flex-direction: row-reverse;
}
.message-avatar {
  width: 28px;
  height: 28px;
  color: #475569;
  background: #e2e8f0;
}
.theme-dark .message-avatar {
  color: #cbd5e1;
  background: #30343b;
}

.message-content {
  max-width: min(82%, 620px);
  padding: 9px 11px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: white;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
}

.message-content-wrap {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.message-feedback {
  display: flex;
  gap: 2px;
  padding-left: 2px;
}

.message-feedback button {
  display: inline-grid;
  width: 24px;
  height: 24px;
  padding: 0;
  place-items: center;
  color: #64748b;
  background: transparent;
  border: 0;
  border-radius: 4px;
  cursor: pointer;
}

.message-feedback button:hover:not(:disabled),
.message-feedback button.active {
  color: var(--embed-primary);
  background: color-mix(in srgb, var(--embed-primary) 12%, transparent);
}

.message-feedback button:disabled {
  cursor: not-allowed;
  opacity: .45;
}

.message-user .message-content {
  color: white;
  background: var(--embed-primary);
  border-color: transparent;
}
.theme-dark .message-assistant .message-content {
  background: #25282e;
  border-color: #383d46;
}

.composer {
  padding: 10px 12px 8px;
  background: white;
  border-top: 1px solid #e2e8f0;
}

.theme-dark .composer {
  background: #202329;
  border-color: #343941;
}
.composer-row {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr) 34px;
  align-items: end;
  gap: 8px;
}

.icon-action {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  color: #64748b;
  cursor: pointer;
}

.icon-action.disabled {
  cursor: not-allowed;
  opacity: 0.4;
}
.icon-action input {
  display: none;
}

.file-list {
  display: flex;
  gap: 6px;
  margin-bottom: 8px;
  overflow-x: auto;
}
.file-list button {
  display: inline-flex;
  align-items: center;
  max-width: 220px;
  height: 28px;
  gap: 5px;
  padding: 0 8px;
  color: inherit;
  background: #f1f5f9;
  border: 1px solid #dbe2ea;
  border-radius: 5px;
  cursor: pointer;
}
.file-list span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.theme-dark .file-list button {
  background: #2b2f36;
  border-color: #3b414b;
}

.watermark {
  margin-top: 5px;
  color: #94a3b8;
  font-size: 10px;
  text-align: center;
}

@media (max-width: 480px) {
  .embed-root {
    min-height: 100dvh;
    max-height: 100dvh;
    border: 0;
  }
  .message-list {
    padding: 12px;
  }
  .message-content {
    max-width: 86%;
  }
  .welcome-cards {
    grid-template-columns: 1fr;
    margin-top: -4px;
  }
}
</style>
