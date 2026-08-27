<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
} from "vue";
import dayjs from "dayjs";
import type { FormInst, FormRules, SelectOption } from "naive-ui";
import { NAlert, useDialog } from "naive-ui";
import { onBeforeRouteLeave, useRoute, useRouter } from "vue-router";
import SvgIcon from "@/components/custom/svg-icon.vue";
import CanvasWorkbench from "./canvas-workbench.vue";
import MessageExecutionEvents from "./message-execution-events.vue";
import RichMessageRenderer from "./rich-message-renderer.vue";
import ChatTodoCard from "@/components/chat/ChatTodoCard.vue";
import WorkspaceFileBrowser from "./workspace-file-browser.vue";
import {
  convertConversationToTask,
  cancelConversationGlobally,
  cancelRuntimeConfirmation,
  cancelRuntimeUserQuestion,
  createConversation,
  deleteConversation,
  downloadConversationAttachment,
  downloadConversationExport,
  downloadNhsV1TraceData,
  fetchActiveConversationTurn,
  fetchActiveConversation,
  fetchAllowedAgents,
  fetchChatCodeExecution,
  fetchChatCodeExecutions,
  fetchConversationAttachments,
  fetchConversationEvents,
  fetchConversationMessages,
  fetchConversationResourceScope,
  fetchConversationTurn,
  fetchConversations,
  fetchDataSources,
  fetchDatasets,
  fetchKnowledgeBases,
  fetchProjects,
  fetchPortalPreferences,
  fetchRuntimeConfirmation,
  fetchPendingRuntimeUserQuestions,
  answerRuntimeUserQuestion,
  fetchSkills,
  fetchTasks,
  fetchTools,
  regenerateConversationMessage,
  retryConversationTurn,
  finalizeNhsConversation,
  previewConversationTask,
  resumeChatCodeExecution,
  startChatCodeExecution,
  startConversationTurn,
  confirmRuntimeConfirmation,
  setActiveConversation,
  stopChatCodeExecution,
  streamConversationEvents,
  submitConversationFeedback,
  updateConversationResourceScope,
  updatePortalRoutingPreference,
  uploadConversationAttachment,
} from "@/service/api";
import type {
  AgentOptionView,
  ConversationAttachmentView,
  ConversationExportFormat,
  ConversationFeedbackRating,
  ConversationMessageView,
  ConversationResourceScope,
  ConversationResourceScopeKey,
  ConversationResourceScopeView,
  ConversationTurnView,
  ConversationView,
  ConvertConversationPayload,
  ExecutionEventView,
  ChatCodeStreamEvent,
  ChatCodeExecutionView,
  DataSourceView,
  DatasetView,
  KnowledgeBaseView,
  NhsV1TraceDataExportFormat,
  ProjectView,
  SkillView,
  TaskDraftView,
  TaskResourceRequest,
  TaskView,
  ToolView,
  PortalRoutingMode,
} from "@/service/api";
import {
  runtimeUserQuestionFromView,
  type RuntimeUserQuestionState,
  type RuntimeUserQuestionStatus,
} from "@/utils/runtime-user-question";
import {
  ACTIVE_CODE_EXECUTION_STATUSES,
  chatCodeExecutionKey,
  type ChatCodeExecutionState,
  type ChatCodeExecutionStatus,
} from "./chat-code-execution";
import MessageCodeExecution from "./message-code-execution.vue";
import {
  resolveActiveConversationId,
  shouldPersistActiveConversation,
} from "./active-conversation";
import {
  traceDataExportErrorMessage,
  traceDataExportKey,
  triggerTraceDataDownload,
} from "./trace-data-export";
import {
  parseBusinessConfirmationEvent,
  type BusinessConfirmationField,
  type BusinessConfirmationState,
} from "@/utils/business-confirmation";

interface ChatCitation {
  id: string;
  title: string;
  content: string;
  sourceUrl: string | null;
  similarity: number | null;
}

const props = withDefaults(defineProps<{ clientMode?: boolean }>(), {
  clientMode: false,
});

const AUTO_ROUTE = "__auto__";
const DEFAULT_AGENT = "__default__";
const TERMINAL_TURN_STATUSES = new Set(["succeeded", "failed", "cancelled"]);
const ALLOWED_ATTACHMENT_EXTENSIONS = new Set([
  "txt",
  "md",
  "csv",
  "json",
  "pdf",
  "png",
  "jpg",
  "jpeg",
  "webp",
]);
const CONTEXT_COLLAPSED_STORAGE_KEY = "agent-workspace:context-collapsed";
const CONVERSATION_WIDTH_STORAGE_KEY = "agent-workspace:conversation-width";
const CONTEXT_WIDTH_STORAGE_KEY = "agent-workspace:context-width";
const MIN_CONVERSATION_WIDTH = 232;
const MAX_CONVERSATION_WIDTH = 420;
const MIN_CONTEXT_WIDTH = 300;
const MAX_CONTEXT_WIDTH = 520;
const MIN_CHAT_WIDTH = 640;

const router = useRouter();
const route = useRoute();
const dialog = useDialog();

const loading = ref(false);
const historyLoading = ref(false);
const historySearching = ref(false);
const conversations = ref<ConversationView[]>([]);
const agents = ref<AgentOptionView[]>([]);
const selectedConversationId = ref<string | null>(null);
const persistedActiveConversationId = ref<string | null>(null);
const conversationSearch = ref("");
const conversationSearchExpanded = ref(false);
const conversationMessages = ref<ConversationMessageView[]>([]);
const conversationEvents = ref<ExecutionEventView[]>([]);
const pendingRuntimeQuestions = ref<RuntimeUserQuestionState[]>([]);
const attachments = ref<ConversationAttachmentView[]>([]);
const observedTurn = ref<ConversationTurnView | null>(null);
const streamState = ref<"idle" | "connecting" | "connected" | "reconnecting">(
  "idle",
);
const messageViewport = ref<HTMLElement | null>(null);
const chatShell = ref<HTMLElement | null>(null);
const messageAtBottom = ref(true);
const conversationPanelWidth = ref(storedPanelWidth(
  CONVERSATION_WIDTH_STORAGE_KEY,
  264,
  MIN_CONVERSATION_WIDTH,
  MAX_CONVERSATION_WIDTH,
));
const contextPanelWidth = ref(storedPanelWidth(
  CONTEXT_WIDTH_STORAGE_KEY,
  360,
  MIN_CONTEXT_WIDTH,
  MAX_CONTEXT_WIDTH,
));
const resizingPanel = ref<"conversation" | "context" | null>(null);
const tasks = ref<TaskView[]>([]);
const projects = ref<ProjectView[]>([]);
const taskTools = ref<ToolView[]>([]);
const taskSkills = ref<SkillView[]>([]);
const taskKnowledgeBases = ref<KnowledgeBaseView[]>([]);
const taskDataSources = ref<DataSourceView[]>([]);
const taskDatasets = ref<DatasetView[]>([]);
const selectedContextTaskId = ref<string | null>(null);
const activeProjectFilterId = ref<string | null>(null);
const contextCollapsed = ref(
  window.localStorage.getItem(CONTEXT_COLLAPSED_STORAGE_KEY) === "true",
);
const contextDetailVisible = ref(false);
const contextMobileVisible = ref(false);

const messageInput = ref("");
const sendAgentVersionId = ref(AUTO_ROUTE);
const preferredRoutingMode = ref<PortalRoutingMode>("auto");
const preferredAgentId = ref("");
const portalPreferencesLoaded = ref(false);
const selectedAttachmentIds = ref<string[]>([]);
const sendIdempotencyKey = ref(crypto.randomUUID());
const routePrefillApplied = ref(false);
const sending = ref(false);
const confirmationSubmitting = ref<string | null>(null);
const resolvedRuntimeConfirmations = reactive<Record<string, "confirmed" | "cancelled">>({});
const expiredRuntimeConfirmations = reactive<Record<string, true>>({});
const confirmationIdempotencyKeys = reactive<Record<string, string>>({});
const questionSubmitting = ref<string | null>(null);
const resolvedRuntimeQuestions = reactive<Record<string, RuntimeUserQuestionStatus>>({});
const stopping = ref(false);
const finalizing = ref(false);
const uploading = ref(false);
const attachmentUploadError = ref("");
const fileInput = ref<HTMLInputElement | null>(null);

const eventsVisible = ref(false);
const attachmentsVisible = ref(false);
const workspaceFilesVisible = ref(false);
const attachmentDownloadingId = ref<string | null>(null);
const exportingFormat = ref<ConversationExportFormat | null>(null);
const traceDataExporting = reactive<Record<string, boolean>>({});
const deletingConversationId = ref<string | null>(null);
const feedbackSubmittingMessageId = ref<string | null>(null);
const messageFeedback = reactive<Record<string, ConversationFeedbackRating | undefined>>({});
const citationVisible = ref(false);
const selectedCitation = ref<ChatCitation | null>(null);
const codeExecutions = ref<Record<string, ChatCodeExecutionState>>({});
const canvasWorkbench = ref<InstanceType<typeof CanvasWorkbench> | null>(null);

const resourceScope = ref<ConversationResourceScopeView | null>(null);
const resourceScopeVisible = ref(false);
const resourceScopeSaving = ref(false);
const resourceScopeDraft = reactive<Record<ConversationResourceScopeKey, string[]>>({
  agent_ids: [],
  agent_version_ids: [],
  dataset_ids: [],
  knowledge_base_ids: [],
  tool_ids: [],
  skill_ids: [],
});

const createVisible = ref(false);
const createSubmitting = ref(false);
const createFormRef = ref<FormInst | null>(null);
const createForm = reactive({
  title: "",
  projectId: null as string | null,
  agentVersionId: DEFAULT_AGENT,
});
const createRules: FormRules = {
  title: [
    {
      max: 255,
      message: "会话标题不能超过 255 个字符",
      trigger: ["input", "blur"],
    },
  ],
};

const draftFormRef = ref<FormInst | null>(null);
const draftSubmitting = ref(false);
const converting = ref(false);
const taskDraft = ref<TaskDraftView | null>(null);
const convertedTaskId = ref<string | null>(null);
const taskIdempotencyKey = ref(crypto.randomUUID());
const draftForm = reactive({
  title: "",
  objective: "",
  background: "",
  visibility: "enterprise_shared" as TaskView["visibility"],
  category: "general" as TaskView["category"],
  riskLevel: "R1" as TaskView["riskLevel"],
  importance: false,
  urgency: false,
  tags: [] as string[],
  resourceKeys: [] as string[],
});
const draftRules: FormRules = {
  title: [
    { required: true, message: "请输入任务名称", trigger: ["input", "blur"] },
    {
      max: 255,
      message: "任务名称不能超过 255 个字符",
      trigger: ["input", "blur"],
    },
  ],
  objective: [
    { required: true, message: "请输入任务目标", trigger: ["input", "blur"] },
    {
      max: 12000,
      message: "任务目标不能超过 12000 个字符",
      trigger: ["input", "blur"],
    },
  ],
  background: [
    {
      max: 12000,
      message: "背景说明不能超过 12000 个字符",
      trigger: ["input", "blur"],
    },
  ],
};

let monitorGeneration = 0;
let streamController: AbortController | null = null;
let conversationSearchTimer: number | null = null;
let conversationSearchGeneration = 0;
let resizeAnimationFrame: number | null = null;
let activeConversationSync = Promise.resolve();
let appliedConversationId: string | null = null;
let conversationSelectionGeneration = 0;
let rollingBackConversationSelection = false;

const chatShellStyle = computed(() => ({
  "--conversation-panel-width": `${conversationPanelWidth.value}px`,
  "--context-panel-width": `${contextPanelWidth.value}px`,
}));
const codeStreamControllers = new Map<string, AbortController>();

const selectedConversation = computed(() =>
  conversations.value.find((item) => item.id === selectedConversationId.value),
);
const branchLabel = computed(() => {
  const conversation = selectedConversation.value;
  if (!conversation?.parentConversationId) return "主会话";
  return `当前分支 · 截断至消息 ${conversation.contextCutoffSequence ?? 0}`;
});
const latestAssistantMessageId = computed(() => [...conversationMessages.value].reverse().find(item => item.role === 'assistant')?.id || null);
const visibleConversations = computed(() => {
  if (!activeProjectFilterId.value) return conversations.value;
  if (activeProjectFilterId.value === "unassigned") {
    return conversations.value.filter((conversation) => !conversation.projectId);
  }
  return conversations.value.filter(
    (conversation) => conversation.projectId === activeProjectFilterId.value,
  );
});
const activeContextTasks = computed(() => {
  const activeStatuses = new Set([
    "ready",
    "scheduled",
    "running",
    "waiting",
    "blocked",
    "rework",
  ]);
  const active = tasks.value.filter((task) => activeStatuses.has(task.status));
  return (active.length ? active : tasks.value).slice(0, 6);
});
const selectedContextTask = computed(
  () =>
    tasks.value.find((task) => task.id === selectedContextTaskId.value) ||
    activeContextTasks.value[0] ||
    null,
);
const contextProjects = computed(() => {
  const grouped = new Map<string, number>();
  for (const task of activeContextTasks.value) {
    const key = task.projectId || "unassigned";
    grouped.set(key, (grouped.get(key) || 0) + 1);
  }
  const rows = projects.value
    .filter((project) => project.status === "active")
    .map((project) => ({
      id: project.id,
      name: project.name,
      taskCount: grouped.get(project.id) || 0,
    }));
  if (grouped.has("unassigned")) {
    rows.push({
      id: "unassigned",
      name: "未归属项目",
      taskCount: grouped.get("unassigned") || 0,
    });
  }
  return rows.slice(0, 8);
});
const selectedProject = computed(() => {
  const projectId = selectedConversation.value?.projectId;
  return projects.value.find((project) => project.id === projectId) || null;
});
const dashboardContext = computed(() => {
  for (const event of [...conversationEvents.value].sort((left, right) => right.cursor - left.cursor)) {
    const value = event.projection?.dashboardContext;
    if (!value || typeof value !== "object" || Array.isArray(value)) continue;
    const context = value as Record<string, unknown>;
    const roomName = typeof context.room_name === "string" ? context.room_name : "";
    const metricName = typeof context.metric_name === "string" ? context.metric_name : "";
    const timeRange = typeof context.time_range === "string" ? context.time_range : "";
    if (roomName || metricName || timeRange) return { roomName, metricName, timeRange };
  }
  return null;
});
const resolvedBusinessConfirmations = computed(() => {
  const decisions: Record<string, "confirmed" | "cancelled"> = {
    ...resolvedRuntimeConfirmations,
  };
  for (const message of conversationMessages.value) {
    if (message.role !== "user" || !message.content?.startsWith("【业务确认】")) continue;
    const id = message.content.match(/^confirmation_id:\s*(\S+)\s*$/m)?.[1];
    if (!id) continue;
    decisions[id] = message.content.includes("用户已取消") ? "cancelled" : "confirmed";
  }
  return decisions;
});
const turnActive = computed(() =>
  Boolean(
    observedTurn.value &&
      !TERMINAL_TURN_STATUSES.has(observedTurn.value.status),
  ),
);
const observedTraceEvents = computed(() => {
  const traceId = observedTurn.value?.traceId;
  if (!traceId) return [];
  return conversationEvents.value.filter(event => event.traceId === traceId);
});
const liveAssistantContent = computed(() => {
  const events = observedTraceEvents.value;
  const retraction = [...events]
    .sort((a, b) => b.cursor - a.cursor)
    .find(event => event.eventType === "custom" && event.payload?.retraction === true);
  if (retraction) return retraction.summary || "此响应因安全策略已撤回";
  return events
    .filter(event => event.eventType === "text_delta")
    .sort((a, b) => a.cursor - b.cursor)
    .map(event => event.summary || "")
    .join("");
});
const liveAssistantVisible = computed(() => {
  const turn = observedTurn.value;
  if (!turn) return false;
  const persisted = conversationMessages.value.some(message =>
    message.role === "assistant" && message.traceId === turn.traceId,
  );
  return !persisted && (turnActive.value || observedTraceEvents.value.length > 0);
});
const restoredCodeExecutions = computed(() => Object.values(codeExecutions.value)
  .filter(execution => execution.key.startsWith("execution-")));
const eventCursor = computed(() =>
  conversationEvents.value.reduce(
    (maximum, event) => Math.max(maximum, Number(event.cursor) || 0),
    0,
  ),
);
const publishedAgents = computed(() =>
  agents.value.filter(
    (agent) => agent.status === "active" && agent.publishedVersionId,
  ),
);
const createAgentOptions = computed<SelectOption[]>(() => [
  { label: "默认 Agent", value: DEFAULT_AGENT },
  ...publishedAgents.value.map((agent) => ({
    label: agent.defaultAgent ? `${agent.name} · 默认` : agent.name,
    value: agent.publishedVersionId!,
  })),
]);
const sendAgentOptions = computed<SelectOption[]>(() => [
  { label: "自动路由（会话 / @Agent / 默认）", value: AUTO_ROUTE },
  ...publishedAgents.value.map((agent) => ({
    label: agent.name,
    value: agent.publishedVersionId!,
  })),
]);

function preferredAgentVersionForConversation(conversationId: string | null) {
  const conversation = conversations.value.find(item => item.id === conversationId);
  // A conversation-level binding has higher precedence than a user default.
  if (conversation?.agentVersionId) return AUTO_ROUTE;
  if (preferredRoutingMode.value !== "expert" || !preferredAgentId.value) return AUTO_ROUTE;
  const agent = publishedAgents.value.find(item => String(item.id) === preferredAgentId.value);
  return agent?.publishedVersionId || AUTO_ROUTE;
}

async function handleSendAgentSelection(value: string) {
  const next = value || AUTO_ROUTE;
  const previous = sendAgentVersionId.value;
  sendAgentVersionId.value = next;
  if (props.clientMode || !portalPreferencesLoaded.value) return;
  const agent = publishedAgents.value.find(item => item.publishedVersionId === next);
  const mode: PortalRoutingMode = next === AUTO_ROUTE ? "auto" : "expert";
  const result = await updatePortalRoutingPreference({
    routing_mode: mode,
    expert_agent_id: agent?.id ? String(agent.id) : "",
  });
  if (result.error) {
    sendAgentVersionId.value = previous;
    window.$message?.error(result.error.message || "路由偏好保存失败");
    return;
  }
  preferredRoutingMode.value = mode;
  preferredAgentId.value = mode === "expert" && agent?.id ? String(agent.id) : "";
}
const conversationOptions = computed<SelectOption[]>(() =>
  conversations.value.map((item) => ({
    label: item.title || `会话 #${item.id}`,
    value: item.id,
  })),
);
const projectOptions = computed<SelectOption[]>(() =>
  projects.value
    .filter((project) => project.status === "active")
    .map((project) => ({
      label: project.name,
      value: project.id,
    })),
);
const conversationExportOptions: SelectOption[] = [
  { label: "导出 JSON", value: "json" },
  { label: "导出 Markdown", value: "markdown" },
];
const resourceScopeFields: Array<{
  key: ConversationResourceScopeKey;
  label: string;
}> = [
  {
    key: "agent_ids",
    label: "Agent",
  },
  {
    key: "agent_version_ids",
    label: "Agent 版本",
  },
  {
    key: "dataset_ids",
    label: "数据集",
  },
  {
    key: "knowledge_base_ids",
    label: "知识库",
  },
  {
    key: "tool_ids",
    label: "工具",
  },
  {
    key: "skill_ids",
    label: "Skill",
  },
];
const readyAttachments = computed(() =>
  attachments.value.filter((item) => item.status === "ready"),
);
const attachmentOptions = computed<SelectOption[]>(() =>
  readyAttachments.value.map((item) => ({
    label: `${item.originalName} · ${formatBytes(item.sizeBytes)}`,
    value: item.id,
  })),
);
const selectedAgentName = computed(() => {
  const versionId =
    observedTurn.value?.agentVersionId ||
    selectedConversation.value?.agentVersionId;
  return (
    publishedAgents.value.find(
      (agent) => agent.publishedVersionId === versionId,
    )?.name || "自动路由"
  );
});
const canSend = computed(() =>
  Boolean(
    selectedConversationId.value &&
      messageInput.value.trim() &&
      !turnActive.value &&
      !sending.value,
  ),
);
const resourceScopeCount = computed(() =>
  Object.values(resourceScope.value?.resources || {}).reduce(
    (count, ids) => count + (ids?.length || 0),
    0,
  ),
);
const resourceScopeSummary = computed(() =>
  historyLoading.value && selectedConversationId.value
    ? "加载中"
    : resourceScopeCount.value
    ? `${resourceScopeCount.value} 项资源`
    : "未限制资源",
);

const visibilityOptions: SelectOption[] = [
  { label: "企业共享", value: "enterprise_shared" },
  { label: "受限可见", value: "restricted" },
];
const categoryOptions: SelectOption[] = [
  { label: "通用", value: "general" },
  { label: "研发", value: "development" },
  { label: "数据", value: "data" },
  { label: "知识", value: "knowledge" },
  { label: "运维", value: "operations" },
  { label: "文档", value: "document" },
];
const riskOptions: SelectOption[] = [
  { label: "R0 · 只读低风险", value: "R0" },
  { label: "R1 · 常规操作", value: "R1" },
  { label: "R2 · 敏感操作", value: "R2" },
  { label: "R3 · 高风险审批", value: "R3" },
];
const taskResourceOptions = computed<SelectOption[]>(() => [
  ...publishedAgents.value.map(agent => ({
    label: `Agent · ${agent.name}`,
    value: `agent_version:${agent.publishedVersionId}`
  })),
  ...taskTools.value.filter(item => item.status === 'active' && item.available).map(item => ({
    label: `工具 · ${item.name}`,
    value: `tool:${item.id}`
  })),
  ...taskSkills.value.filter(item => item.status === 'active' && item.publishedVersionId).map(item => ({
    label: `Skill · ${item.name}`,
    value: `skill:${item.id}`
  })),
  ...taskKnowledgeBases.value.filter(item => item.status === 'active' && item.providerType === 'postgres_pgvector').map(item => ({
    label: `知识库 · ${item.name}`,
    value: `knowledge_base:${item.id}`
  })),
  ...taskDataSources.value.filter(item => item.status === 'active').map(item => ({
    label: `数据源 · ${item.name}`,
    value: `data_source:${item.id}`
  })),
  ...taskDatasets.value.filter(item => item.status === 'active').map(item => ({
    label: `数据集 · ${item.name}`,
    value: `dataset:${item.id}`
  }))
]);

async function loadData() {
  loading.value = true;
  const requestedSearch = conversationSearch.value.trim();
  const [conversationResult, agentResult, taskResult, projectResult, preferenceResult] = await Promise.all([
    fetchConversations(requestedSearch ? 100 : 50, requestedSearch || undefined),
    fetchAllowedAgents(),
    fetchTasks(50),
    fetchProjects("active"),
    props.clientMode ? Promise.resolve(null) : fetchPortalPreferences()
  ]);
  let serverActiveConversationId: string | null = null;
  if (!props.clientMode) {
    const activeResult = await fetchActiveConversation();
    if (!activeResult.error) {
      serverActiveConversationId = activeResult.data.conversation_id == null
        ? null
        : String(activeResult.data.conversation_id);
      persistedActiveConversationId.value = serverActiveConversationId;
    }
  }
  if (!conversationResult.error && requestedSearch === conversationSearch.value.trim()) {
    conversations.value = conversationResult.data;
    if (
      !selectedConversationId.value ||
      !conversations.value.some(
        (item) => item.id === selectedConversationId.value,
      )
    ) {
      selectedConversationId.value = resolveActiveConversationId(
        conversations.value,
        serverActiveConversationId,
      );
    }
  }
  if (!agentResult.error) agents.value = agentResult.data;
  if (preferenceResult) {
    portalPreferencesLoaded.value = true;
  }
  if (preferenceResult && !preferenceResult.error) {
    const preference = preferenceResult.data;
    const selected = preference.routing_mode === "expert"
      ? publishedAgents.value.find(item => String(item.id) === String(preference.expert_agent_id || ""))
      : undefined;
    preferredRoutingMode.value = preference.routing_configured && selected ? "expert" : "auto";
    preferredAgentId.value = preferredRoutingMode.value === "expert" ? String(selected?.id || "") : "";
    if (!turnActive.value) {
      sendAgentVersionId.value = preferredAgentVersionForConversation(selectedConversationId.value);
    }
  } else if (preferenceResult?.error) {
    window.$message?.warning("路由偏好暂时不可用，本次选择不会跨设备保存");
  }
  if (!projectResult.error) projects.value = projectResult.data;
  if (!taskResult.error) {
    tasks.value = taskResult.data;
    if (
      !selectedContextTaskId.value ||
      !tasks.value.some((task) => task.id === selectedContextTaskId.value)
    ) {
      selectedContextTaskId.value = activeContextTasks.value[0]?.id || null;
    }
  }
  // The client portal only needs user-facing summaries. Configuration catalogs
  // are loaded by the admin workspace and must not be probed by ordinary users.
  if (!props.clientMode) {
    const [toolResult, skillResult, knowledgeResult, sourceResult, datasetResult] = await Promise.all([
      fetchTools(false),
      fetchSkills(false),
      fetchKnowledgeBases(undefined, false),
      fetchDataSources(),
      fetchDatasets()
    ]);
    if (!toolResult.error) taskTools.value = toolResult.data;
    if (!skillResult.error) taskSkills.value = skillResult.data;
    if (!knowledgeResult.error) taskKnowledgeBases.value = knowledgeResult.data;
    if (!sourceResult.error) taskDataSources.value = sourceResult.data;
    if (!datasetResult.error) taskDatasets.value = datasetResult.data;
  }
  loading.value = false;
  await applyRoutePrefill(!agentResult.error);
}

function routeQueryText(value: unknown) {
  const candidate = Array.isArray(value) ? value[0] : value;
  return typeof candidate === "string" ? candidate.trim() : "";
}

async function applyRoutePrefill(agentCatalogLoaded: boolean) {
  if (props.clientMode || routePrefillApplied.value) return;
  const question = routeQueryText(route.query.question);
  const versionId = routeQueryText(route.query.agent_version_id);
  if (!question && !versionId) return;
  if (versionId && !agentCatalogLoaded) return;

  await nextTick();
  if (question) messageInput.value = question;
  if (versionId) {
    const agent = publishedAgents.value.find(item => item.publishedVersionId === versionId);
    if (agent) sendAgentVersionId.value = versionId;
    else window.$message?.warning("场景实例绑定的 Agent 版本当前不可用，请重新选择");
  }
  routePrefillApplied.value = true;
  await router.replace({
    query: {
      ...route.query,
      question: undefined,
      agent_version_id: undefined,
    },
  });
}

function queueActiveConversationSync(conversationId: string | null) {
  if (props.clientMode || !shouldPersistActiveConversation(
    conversationId,
    persistedActiveConversationId.value,
  )) return;
  activeConversationSync = activeConversationSync.then(async () => {
    if (!shouldPersistActiveConversation(
      conversationId,
      persistedActiveConversationId.value,
    )) return;
    const result = await setActiveConversation(conversationId as string);
    if (!result.error) persistedActiveConversationId.value = conversationId;
  }).catch(() => undefined);
}

async function refreshHistory(conversationId: string) {
  const [messageResult, eventResult, attachmentResult] = await Promise.all([
    fetchConversationMessages(conversationId),
    fetchConversationEvents(conversationId),
    fetchConversationAttachments(conversationId),
  ]);
  if (selectedConversationId.value !== conversationId) return;
  if (!messageResult.error) conversationMessages.value = messageResult.data;
  if (!eventResult.error) {
    conversationEvents.value = [...eventResult.data].sort(
      (a, b) => a.cursor - b.cursor,
    );
    await synchronizeRuntimeConfirmationStates(conversationEvents.value);
  }
  if (!attachmentResult.error) {
    attachments.value = attachmentResult.data;
    const readyIds = new Set(
      attachmentResult.data
        .filter((item) => item.status === "ready")
        .map((item) => item.id),
    );
    selectedAttachmentIds.value = selectedAttachmentIds.value.filter((id) =>
      readyIds.has(id),
    );
  }
  await scrollToBottom();
}

async function synchronizeRuntimeConfirmationStates(events: ExecutionEventView[]) {
  const confirmationKeys = new Set<string>();
  for (const event of events) {
    const confirmation = parseBusinessConfirmationEvent(event);
    if (confirmation) confirmationKeys.add(confirmation.confirmation_id);
  }
  await Promise.all([...confirmationKeys].map(synchronizeRuntimeConfirmationKey));
}

async function synchronizeRuntimeConfirmationKey(confirmationKey: string) {
  if (resolvedRuntimeConfirmations[confirmationKey] || expiredRuntimeConfirmations[confirmationKey]) return;
  const result = await fetchRuntimeConfirmation(confirmationKey);
  if (result.error) return;
  if (result.data.status === "confirmed" || result.data.status === "cancelled") {
    resolvedRuntimeConfirmations[confirmationKey] = result.data.status;
  } else if (result.data.status === "expired") {
    expiredRuntimeConfirmations[confirmationKey] = true;
  }
}

async function loadConversationFacts() {
  stopMonitor();
  for (const key of Object.keys(resolvedRuntimeConfirmations)) {
    delete resolvedRuntimeConfirmations[key];
  }
  for (const key of Object.keys(expiredRuntimeConfirmations)) {
    delete expiredRuntimeConfirmations[key];
  }
  for (const key of Object.keys(confirmationIdempotencyKeys)) {
    delete confirmationIdempotencyKeys[key];
  }
  for (const key of Object.keys(resolvedRuntimeQuestions)) {
    Reflect.deleteProperty(resolvedRuntimeQuestions, key);
  }
  const conversationId = selectedConversationId.value;
  conversationMessages.value = [];
  conversationEvents.value = [];
  pendingRuntimeQuestions.value = [];
  attachments.value = [];
  resourceScope.value = null;
  for (const field of resourceScopeFields) resourceScopeDraft[field.key] = [];
  if (!conversationId) {
    historyLoading.value = false;
    return;
  }
  historyLoading.value = true;
  await refreshHistory(conversationId);
  const [activeResult, scopeResult, codeExecutionResult, pendingQuestionResult] = await Promise.all([
    fetchActiveConversationTurn(conversationId),
    fetchConversationResourceScope(conversationId),
    fetchChatCodeExecutions(conversationId),
    fetchPendingRuntimeUserQuestions(conversationId),
  ]);
  if (selectedConversationId.value === conversationId && !activeResult.error) {
    observedTurn.value = activeResult.data;
    if (
      activeResult.data &&
      !TERMINAL_TURN_STATUSES.has(activeResult.data.status)
    ) {
      startMonitor(conversationId, activeResult.data);
    }
  }
  if (selectedConversationId.value === conversationId && !scopeResult.error) {
    applyResourceScope(scopeResult.data);
  }
  if (selectedConversationId.value === conversationId && !codeExecutionResult.error) {
    restoreCodeExecutions(conversationId, codeExecutionResult.data);
  }
  if (selectedConversationId.value === conversationId && !pendingQuestionResult.error) {
    pendingRuntimeQuestions.value = pendingQuestionResult.data.map(runtimeUserQuestionFromView);
  }
  if (selectedConversationId.value === conversationId)
    historyLoading.value = false;
}

async function refreshAll() {
  await loadData();
  await loadConversationFacts();
}

function applyResourceScope(value: ConversationResourceScopeView) {
  resourceScope.value = value;
  for (const field of resourceScopeFields) {
    resourceScopeDraft[field.key] = (value.resources[field.key] || []).map(String);
  }
}

function parseResourceIds(value: string[]) {
  const tokens = value
    .map(item => item.trim())
    .filter(Boolean);
  if (tokens.some(item => !/^[1-9]\d*$/.test(item))) return null;
  return [...new Set(tokens)];
}

function resourceScopePayload(): ConversationResourceScope | null {
  const resources: ConversationResourceScope = {};
  for (const field of resourceScopeFields) {
    const ids = parseResourceIds(resourceScopeDraft[field.key]);
    if (ids === null) {
      window.$message?.warning(`${field.label} ID 必须为正整数`);
      return null;
    }
    if (ids.length) resources[field.key] = ids;
  }
  return resources;
}

function openResourceScope() {
  const conversationId = selectedConversationId.value;
  if (!conversationId) return;
  if (historyLoading.value || resourceScope.value?.conversationId !== conversationId) {
    window.$message?.warning("会话资源范围尚未加载完成");
    return;
  }
  applyResourceScope(resourceScope.value);
  resourceScopeVisible.value = true;
}

async function saveResourceScope() {
  const conversationId = selectedConversationId.value;
  const currentScope = resourceScope.value;
  if (
    !conversationId ||
    currentScope?.conversationId !== conversationId ||
    turnActive.value
  )
    return;
  const resources = resourceScopePayload();
  if (!resources) return;
  resourceScopeSaving.value = true;
  const result = await updateConversationResourceScope(conversationId, {
    expectedRevision: currentScope.revision,
    resources,
  });
  if (!result.error) {
    applyResourceScope(result.data);
    resourceScopeVisible.value = false;
    window.$message?.success("会话资源范围已保存");
  }
  resourceScopeSaving.value = false;
}

function scheduleConversationSearch() {
  if (conversationSearchTimer !== null) window.clearTimeout(conversationSearchTimer);
  const generation = ++conversationSearchGeneration;
  const requestedSearch = conversationSearch.value.trim();
  historySearching.value = true;
  conversationSearchTimer = window.setTimeout(async () => {
    conversationSearchTimer = null;
    const result = await fetchConversations(100, requestedSearch || undefined);
    if (
      generation === conversationSearchGeneration &&
      !result.error &&
      requestedSearch === conversationSearch.value.trim()
    ) {
      conversations.value = result.data;
      if (
        !selectedConversationId.value ||
        !conversations.value.some(item => item.id === selectedConversationId.value)
      ) {
        selectedConversationId.value = conversations.value[0]?.id || null;
      }
    }
    if (generation === conversationSearchGeneration) historySearching.value = false;
  }, 280);
}

function requestDeleteConversation(conversation: ConversationView) {
  if (conversation.id === selectedConversationId.value && turnActive.value) {
    window.$message?.warning("请先停止当前回复");
    return;
  }
  dialog.warning({
    title: "删除会话",
    content: `确定删除“${conversation.title || `会话 #${conversation.id}`}”及其历史消息吗？`,
    positiveText: "删除",
    negativeText: "取消",
    onPositiveClick: () => removeConversation(conversation),
  });
}

async function removeConversation(conversation: ConversationView) {
  if (deletingConversationId.value) return;
  if (
    conversation.id === selectedConversationId.value
    && canvasWorkbench.value
    && !(await canvasWorkbench.value.guardTransition("删除当前会话"))
  ) return;
  deletingConversationId.value = conversation.id;
  const result = await deleteConversation(conversation.id);
  if (!result.error) {
    if (selectedConversationId.value === conversation.id) stopMonitor();
    conversations.value = conversations.value.filter(item => item.id !== conversation.id);
    if (selectedConversationId.value === conversation.id) {
      selectedConversationId.value = conversations.value[0]?.id || null;
    }
    window.$message?.success("会话已删除");
  }
  deletingConversationId.value = null;
}

async function submitFeedback(message: ConversationMessageView, rating: ConversationFeedbackRating) {
  const conversationId = message.conversationId || selectedConversationId.value;
  if (!conversationId || message.role !== "assistant" || feedbackSubmittingMessageId.value) return;
  if (!/^[1-9]\d*$/.test(message.id)) {
    window.$message?.warning("当前消息缺少可反馈的消息 ID");
    return;
  }
  feedbackSubmittingMessageId.value = message.id;
  const result = await submitConversationFeedback(conversationId, {
    messageId: message.id,
    rating,
    traceId: message.traceId || undefined,
  });
  if (!result.error) {
    messageFeedback[message.id] = result.data.rating;
    window.$message?.success("反馈已记录");
  }
  feedbackSubmittingMessageId.value = null;
}

async function regenerateUserMessage(message: ConversationMessageView) {
  if (message.role !== "user" || !message.content?.trim()) return;
  if (turnActive.value || sending.value) {
    window.$message?.warning("当前回复完成后才能创建分支");
    return;
  }
  const sourceConversationId = message.conversationId || selectedConversationId.value;
  if (!sourceConversationId) return;
  sending.value = true;
  const result = await regenerateConversationMessage(
    sourceConversationId,
    message.id,
    { forkMessageId: message.id, idempotencyKey: crypto.randomUUID() },
  );
  if (!result.error) {
    const branch = result.data.conversation;
    conversations.value = [branch, ...conversations.value.filter(item => item.id !== branch.id)];
    selectedConversationId.value = branch.id;
    await nextTick();
    await loadConversationFacts();
    observedTurn.value = result.data.turn;
    if (!TERMINAL_TURN_STATUSES.has(result.data.turn.status)) {
      startMonitor(branch.id, result.data.turn);
    }
    window.$message?.success(
      result.data.replayed ? "已恢复原分支执行" : "已从此处创建分支并重新生成",
    );
  }
  sending.value = false;
}

async function retryFailedTrace(traceId: string) {
  const conversationId = selectedConversationId.value;
  if (!conversationId || !traceId || turnActive.value || sending.value) return;
  sending.value = true;
  const result = await retryConversationTurn(conversationId, traceId, {
    idempotencyKey: crypto.randomUUID(),
  });
  if (!result.error) {
    observedTurn.value = result.data;
    await refreshHistory(conversationId);
    if (!TERMINAL_TURN_STATUSES.has(result.data.status)) {
      startMonitor(conversationId, result.data);
    }
    window.$message?.success(result.data.replayed ? "已恢复原重试回合" : "已提交重试，正在生成");
  }
  sending.value = false;
}

async function exportConversation(format: ConversationExportFormat) {
  const conversation = selectedConversation.value;
  if (!conversation || exportingFormat.value) return;
  exportingFormat.value = format;
  try {
    const { blob, fileName } = await downloadConversationExport(conversation.id, format);
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = fileName;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 0);
  } catch (error) {
    window.$message?.error(error instanceof Error ? error.message : "会话导出失败");
  } finally {
    exportingFormat.value = null;
  }
}

function isTraceDataExporting(
  traceId: string | null,
  format: NhsV1TraceDataExportFormat,
) {
  const normalizedTraceId = traceId?.trim();
  return Boolean(
    normalizedTraceId &&
      traceDataExporting[traceDataExportKey(normalizedTraceId, format)],
  );
}

async function exportTraceData(
  traceId: string | null,
  format: NhsV1TraceDataExportFormat,
) {
  const normalizedTraceId = traceId?.trim();
  if (!normalizedTraceId) {
    window.$message?.warning("当前回复缺少 Trace ID，无法导出数据");
    return;
  }
  const key = traceDataExportKey(normalizedTraceId, format);
  if (traceDataExporting[key]) return;
  traceDataExporting[key] = true;
  try {
    const { blob, fileName } = await downloadNhsV1TraceData(normalizedTraceId, format);
    triggerTraceDataDownload(blob, fileName);
  } catch (error) {
    window.$message?.error(traceDataExportErrorMessage(error, format));
  } finally {
    delete traceDataExporting[key];
  }
}

function mergeEvent(event: ExecutionEventView) {
  if (
    conversationEvents.value.some(
      (item) => item.eventId === event.eventId || item.cursor === event.cursor,
    )
  )
    return;
  conversationEvents.value = [...conversationEvents.value, event]
    .sort((a, b) => a.cursor - b.cursor)
    .slice(-500);
  streamState.value = "connected";
}

function startMonitor(conversationId: string, turn: ConversationTurnView) {
  stopMonitor(false);
  observedTurn.value = turn;
  const generation = monitorGeneration;
  void streamLoop(generation, conversationId);
  void pollTurnLoop(generation, conversationId, turn.id);
}

async function streamLoop(generation: number, conversationId: string) {
  while (generation === monitorGeneration && turnActive.value) {
    const controller = new AbortController();
    streamController = controller;
    streamState.value =
      streamState.value === "idle" ? "connecting" : "reconnecting";
    try {
      await streamConversationEvents(
        conversationId,
        eventCursor.value,
        (event) => {
          if (generation === monitorGeneration) mergeEvent(event);
        },
        controller.signal,
      );
    } catch (error) {
      if (
        !(error instanceof DOMException && error.name === "AbortError") &&
        generation === monitorGeneration
      ) {
        streamState.value = "reconnecting";
      }
    }
    if (streamController === controller) streamController = null;
    if (generation === monitorGeneration && turnActive.value) await delay(700);
  }
}

async function pollTurnLoop(
  generation: number,
  conversationId: string,
  turnId: string,
) {
  while (generation === monitorGeneration) {
    await delay(800);
    if (generation !== monitorGeneration) return;
    const result = await fetchConversationTurn(conversationId, turnId);
    if (result.error || generation !== monitorGeneration) continue;
    observedTurn.value = result.data;
    if (TERMINAL_TURN_STATUSES.has(result.data.status)) {
      stopMonitor(false);
      observedTurn.value = result.data;
      await Promise.all([refreshHistory(conversationId), loadData()]);
      return;
    }
  }
}

function stopMonitor(clearTurn = true) {
  monitorGeneration += 1;
  streamController?.abort();
  streamController = null;
  streamState.value = "idle";
  if (clearTurn) observedTurn.value = null;
}

async function sendMessage() {
  const conversationId = selectedConversationId.value;
  const input = messageInput.value.trim();
  if (!conversationId || !input || sending.value || turnActive.value) return;
  const explicitAgent = publishedAgents.value.find(
    (agent) => agent.publishedVersionId === sendAgentVersionId.value,
  );
  sending.value = true;
  const result = await startConversationTurn(conversationId, {
    idempotencyKey: sendIdempotencyKey.value,
    input,
    agentId: explicitAgent?.id,
    agentVersionId: explicitAgent?.publishedVersionId || undefined,
    attachmentIds: selectedAttachmentIds.value,
  });
  if (!result.error) {
    messageInput.value = "";
    selectedAttachmentIds.value = [];
    sendIdempotencyKey.value = crypto.randomUUID();
    observedTurn.value = result.data;
    await refreshHistory(conversationId);
    if (!TERMINAL_TURN_STATUSES.has(result.data.status))
      startMonitor(conversationId, result.data);
  } else {
    window.$message?.error(result.error.message || "会话发送失败");
  }
  sending.value = false;
}

/** Sends the editable confirmation decision to the server-owned confirmation state. */
async function submitBusinessConfirmation(payload: {
  confirmation: BusinessConfirmationState;
  confirmed: boolean;
  fields: BusinessConfirmationField[];
}) {
  const conversationId = selectedConversationId.value;
  const turn = observedTurn.value;
  if (
    !conversationId
    || sending.value
    || confirmationSubmitting.value
    || (turnActive.value && turn?.status !== "waiting_confirmation")
  ) return;
  const confirmationKey = payload.confirmation.confirmation_id;
  confirmationSubmitting.value = confirmationKey;
  sending.value = true;
  const idempotencyKey = confirmationIdempotencyKeys[confirmationKey]
    || (confirmationIdempotencyKeys[confirmationKey] = crypto.randomUUID());
  try {
    const fields = payload.fields.map(field => ({ key: field.key, value: field.value }));
    const result = payload.confirmed
      ? await confirmRuntimeConfirmation(confirmationKey, { idempotencyKey, fields })
      : await cancelRuntimeConfirmation(confirmationKey, { idempotencyKey, fields });
    if (!result.error) {
      resolvedRuntimeConfirmations[confirmationKey] = payload.confirmed ? "confirmed" : "cancelled";
      delete expiredRuntimeConfirmations[confirmationKey];
      await refreshHistory(conversationId);
      const turnId = turn?.id;
      if (turnId) {
        const turnResult = await fetchConversationTurn(conversationId, turnId);
        if (!turnResult.error) {
          observedTurn.value = turnResult.data;
          if (!TERMINAL_TURN_STATUSES.has(turnResult.data.status)) {
            startMonitor(conversationId, turnResult.data);
          }
        }
      }
      window.$message?.success(payload.confirmed ? "已确认，正在继续执行" : "已取消，正在结束本次操作");
    } else {
      await synchronizeRuntimeConfirmationKey(confirmationKey);
    }
  } finally {
    sending.value = false;
    confirmationSubmitting.value = null;
  }
}

/** Answers an Agent-originated question through its owner-bound runtime API. */
async function submitRuntimeUserQuestion(payload: {
  question: RuntimeUserQuestionState;
  selectedOptionIds: string[];
  customInput: string;
  cancelled: boolean;
}) {
  const conversationId = selectedConversationId.value;
  const questionId = payload.question.question_id;
  if (!conversationId || !questionId || questionSubmitting.value) return;
  questionSubmitting.value = questionId;
  sending.value = true;
  const requestPayload = {
    idempotencyKey: crypto.randomUUID(),
    selectedOptionIds: payload.cancelled ? [] : payload.selectedOptionIds,
    customInput: payload.cancelled ? '' : payload.customInput.trim(),
  };
  try {
    const result = payload.cancelled
      ? await cancelRuntimeUserQuestion(questionId, { idempotencyKey: requestPayload.idempotencyKey })
      : await answerRuntimeUserQuestion(questionId, requestPayload);
    if (result.error) return;
    const rawStatus = String(result.data.question?.status || (payload.cancelled ? 'cancelled' : 'submitted'));
    const status: RuntimeUserQuestionStatus = rawStatus === 'expired'
      ? 'expired'
      : rawStatus === 'superseded'
        ? 'stale'
        : payload.cancelled ? 'cancelled' : 'submitted';
    resolvedRuntimeQuestions[questionId] = status;
    await refreshHistory(conversationId);
    const turnId = observedTurn.value?.id;
    if (turnId) {
      const turnResult = await fetchConversationTurn(conversationId, turnId);
      if (!turnResult.error) {
        observedTurn.value = turnResult.data;
        if (!TERMINAL_TURN_STATUSES.has(turnResult.data.status)) startMonitor(conversationId, turnResult.data);
      }
    }
    window.$message?.success(payload.cancelled ? '已取消提问' : '回答已提交，正在继续执行');
  } finally {
    sending.value = false;
    questionSubmitting.value = null;
  }
}

async function stopTurn() {
  const conversationId = selectedConversationId.value;
  const turn = observedTurn.value;
  if (!conversationId || !turn || !turnActive.value) return;
  stopping.value = true;
  const result = await cancelConversationGlobally(
    conversationId,
    turn.traceId,
    "用户从工作台停止回复",
  );
  if (!result.error) {
    observedTurn.value = {
      ...turn,
      status: result.data.status === "idle" ? turn.status : result.data.status as ConversationTurnView["status"],
      finishedAt: result.data.lane_released ? new Date().toISOString() : turn.finishedAt,
    };
    const laneCount = result.data.task_runs_cancelled + result.data.canvas_stopped;
    window.$message?.info(
      laneCount > 0 ? `停止请求已提交，同时终止 ${laneCount} 个关联执行` : "停止请求已提交",
    );
  }
  stopping.value = false;
}

async function finalizeConversationMemory() {
  const conversationId = selectedConversationId.value;
  if (!conversationId || turnActive.value) return;
  finalizing.value = true;
  const result = await finalizeNhsConversation(conversationId);
  if (!result.error) {
    if (result.data.finalized) {
      window.$message?.success("会话摘要与个人记忆已更新");
      await loadData();
    } else if (result.data.reason === "no_messages") {
      window.$message?.warning("当前会话还没有可整理的消息");
    } else {
      window.$message?.info("会话暂时不能结束整理");
    }
  }
  finalizing.value = false;
}

function chooseFiles() {
  if (!selectedConversationId.value || turnActive.value) return;
  fileInput.value?.click();
}

async function handleFiles(event: Event) {
  const conversationId = selectedConversationId.value;
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files || []);
  input.value = "";
  if (!conversationId || !files.length) return;
  if (files.length > 5) {
    window.$message?.warning("单次最多选择 5 个附件");
    return;
  }
  attachmentUploadError.value = "";
  uploading.value = true;
  for (const file of files) {
    const extension = file.name.split(".").pop()?.toLowerCase() || "";
    if (
      !file.size ||
      file.size > 10 * 1024 * 1024 ||
      !ALLOWED_ATTACHMENT_EXTENSIONS.has(extension)
    ) {
      window.$message?.warning(`${file.name} 不符合附件类型或 10MB 限制`);
      continue;
    }
    const result = await uploadConversationAttachment(conversationId, file);
    if (result.error) {
      attachmentUploadError.value = result.error.message || `${file.name} 上传失败`;
      continue;
    }
    attachments.value = [
      result.data,
      ...attachments.value.filter((item) => item.id !== result.data.id),
    ];
    if (selectedAttachmentIds.value.length < 5) {
      selectedAttachmentIds.value = [
        ...selectedAttachmentIds.value,
        result.data.id,
      ];
    }
  }
  uploading.value = false;
}

function updateSelectedAttachments(values: string[]) {
  if (values.length > 5) {
    window.$message?.warning("每个回合最多使用 5 个附件");
    return;
  }
  selectedAttachmentIds.value = values;
}

async function downloadAttachment(attachment: ConversationAttachmentView) {
  const conversationId = selectedConversationId.value;
  if (!conversationId) return;
  attachmentDownloadingId.value = attachment.id;
  try {
    const { blob, fileName } = await downloadConversationAttachment(
      conversationId,
      attachment,
    );
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = fileName;
    anchor.click();
    URL.revokeObjectURL(url);
  } catch (error) {
    window.$message?.error(
      error instanceof Error ? error.message : "附件下载失败",
    );
  } finally {
    attachmentDownloadingId.value = null;
  }
}

function openCreate() {
  createForm.title = "";
  createForm.projectId =
    activeProjectFilterId.value && activeProjectFilterId.value !== "unassigned"
      ? activeProjectFilterId.value
      : selectedConversation.value?.projectId || null;
  createForm.agentVersionId = DEFAULT_AGENT;
  createVisible.value = true;
}

async function submitCreate() {
  await createFormRef.value?.validate();
  if (
    canvasWorkbench.value
    && !(await canvasWorkbench.value.guardTransition("创建并切换到新会话"))
  ) return;
  const agent = publishedAgents.value.find(
    (item) => item.publishedVersionId === createForm.agentVersionId,
  );
  createSubmitting.value = true;
  const { data, error } = await createConversation({
    title: createForm.title.trim() || undefined,
    projectId: createForm.projectId || undefined,
    agentId: agent?.id,
    agentVersionId: agent?.publishedVersionId || undefined,
  });
  if (!error) {
    conversations.value = [data, ...conversations.value];
    selectedConversationId.value = data.id;
    createVisible.value = false;
    window.$message?.success("私有会话已创建");
  }
  createSubmitting.value = false;
}

function openCanvasLibrary() {
  void canvasWorkbench.value?.openLibrary();
}

function createCanvasFromMessage(message: ConversationMessageView) {
  void canvasWorkbench.value?.openFromMessage(message);
}

function resetDraft() {
  taskDraft.value = null;
  convertedTaskId.value = null;
  taskIdempotencyKey.value = crypto.randomUUID();
  draftForm.resourceKeys = [];
}

function taskPayload(draftHash?: string): ConvertConversationPayload | null {
  const conversation = selectedConversation.value;
  const agentVersionId =
    conversation?.agentVersionId || observedTurn.value?.agentVersionId;
  if (!conversation || !agentVersionId) return null;
  const resources: TaskResourceRequest[] = draftForm.resourceKeys.flatMap(key => {
    const separator = key.indexOf(":");
    if (separator < 1) return [];
    const resourceType = key.slice(0, separator) as TaskResourceRequest["resourceType"];
    const resourceId = key.slice(separator + 1);
    return [{
      resourceType,
      resourceId,
      permission: resourceType === "knowledge_base" || resourceType === "data_source" || resourceType === "dataset" ? "read" : "use",
      required: true,
      grantSource: "user",
      grantSnapshot: {}
    }];
  });
  return {
    idempotencyKey: taskIdempotencyKey.value,
    title: draftForm.title.trim(),
    objective: draftForm.objective.trim(),
    background: draftForm.background.trim() || undefined,
    agentVersionId,
    visibility: draftForm.visibility,
    category: draftForm.category,
    orchestrationMode: "single_agent",
    lifecycleLevel: "L1_short_task",
    riskLevel: draftForm.riskLevel,
    acceptanceMode: "human",
    importance: draftForm.importance ? 1 : 0,
    urgency: draftForm.urgency ? 1 : 0,
    contextSnapshot: {},
    resources,
    acceptanceSnapshot: {},
    inputSnapshot: {},
    budget: {},
    externalRefs: {},
    tags: draftForm.tags,
    draftHash,
  };
}

async function previewDraft() {
  await draftFormRef.value?.validate();
  const conversation = selectedConversation.value;
  const payload = taskPayload();
  if (!conversation || !payload) {
    window.$message?.warning("请先完成至少一次 Agent 对话，再生成任务草稿");
    return;
  }
  draftSubmitting.value = true;
  const { data, error } = await previewConversationTask(
    conversation.id,
    payload,
  );
  if (!error) {
    taskDraft.value = data;
    convertedTaskId.value = null;
  }
  draftSubmitting.value = false;
}

async function confirmConvert() {
  const conversation = selectedConversation.value;
  const payload = taskPayload(taskDraft.value?.draftHash);
  if (!conversation || !payload || !taskDraft.value) return;
  converting.value = true;
  const { data, error } = await convertConversationToTask(
    conversation.id,
    payload,
  );
  if (!error) {
    convertedTaskId.value = data.taskId;
    window.$message?.success(
      data.replayed ? "重复提交已返回原任务" : "正式任务已创建",
    );
    await loadData();
  }
  converting.value = false;
}

function roleName(role: ConversationMessageView["role"]) {
  return { user: "我", assistant: "Agent", tool: "工具", system: "系统" }[role];
}

function displayedMessageContent(message: ConversationMessageView) {
  const content = message.content || "[无文本内容]";
  if (message.role !== "user" || !content.startsWith("【业务确认】")) return content;
  const id = content.match(/^confirmation_id:\s*(\S+)\s*$/m)?.[1];
  const action = content.includes("用户已取消") ? "已取消业务操作" : "已确认业务操作";
  return id ? `${action} · ${id}` : action;
}

function messageExecutionEvents(message: ConversationMessageView) {
  if (!message.traceId) return [];
  return conversationEvents.value.filter(event => event.traceId === message.traceId);
}

async function runChatCode(payload: { language: string; code: string }) {
  const conversationId = selectedConversationId.value;
  if (!conversationId) return;
  const key = chatCodeExecutionKey(payload.language, payload.code);
  const existing = codeExecutions.value[key];
  if (existing && ACTIVE_CODE_EXECUTION_STATUSES.has(existing.status)) return;

  updateCodeExecution(key, {
    key,
    executionId: null,
    conversationId,
    language: payload.language,
    code: payload.code,
    status: "connecting",
    stdout: "",
    stderr: "",
    outputLoaded: true,
    streamComplete: false,
    cursor: 0,
    exitCode: null,
    elapsedMs: null,
    truncated: false,
    errorCode: null,
    errorMessage: null,
  });

  let initialRequest = true;
  let reconnectAttempt = 0;
  while (
    selectedConversationId.value === conversationId
    && !codeExecutions.value[key]?.streamComplete
  ) {
    const current = codeExecutions.value[key];
    const controller = new AbortController();
    codeStreamControllers.get(key)?.abort();
    codeStreamControllers.set(key, controller);
    try {
      if (initialRequest) {
        await startChatCodeExecution(
          { ...payload, conversationId },
          event => applyCodeStreamEvent(key, event),
          controller.signal,
        );
      } else if (current.executionId) {
        await resumeChatCodeExecution(
          current.executionId,
          conversationId,
          current.cursor,
          event => applyCodeStreamEvent(key, event),
          controller.signal,
        );
      }
    } catch (error) {
      if (controller.signal.aborted) break;
      const latest = codeExecutions.value[key];
      if (!latest.executionId) {
        updateCodeExecution(key, {
          status: "failed",
          errorCode: "code_execution_rejected",
          errorMessage: error instanceof Error ? error.message : "代码执行请求失败",
        });
        break;
      }
    } finally {
      if (codeStreamControllers.get(key) === controller) codeStreamControllers.delete(key);
    }

    const latest = codeExecutions.value[key];
    if (latest.streamComplete) break;
    if (!latest.executionId) {
      updateCodeExecution(key, {
        status: "failed",
        errorCode: "stream_protocol_error",
        errorMessage: "代码执行流未返回执行 ID",
      });
      break;
    }
    await syncCodeExecutionStatus(key, latest.executionId);
    if (codeExecutions.value[key].streamComplete) break;
    updateCodeExecution(key, { status: "reconnecting" });
    reconnectAttempt += 1;
    await delay(Math.min(5000, 500 * 2 ** Math.min(reconnectAttempt, 3)));
    initialRequest = false;
  }
}

function restoreCodeExecutions(conversationId: string, views: ChatCodeExecutionView[]) {
  const restored: Record<string, ChatCodeExecutionState> = {};
  for (const view of views) {
    const key = `execution-${view.executionId}`;
    restored[key] = {
      key,
      executionId: view.executionId,
      conversationId,
      language: view.language,
      code: "",
      status: normalizeCodeStatus(view.status, "failed"),
      stdout: "",
      stderr: "",
      outputLoaded: false,
      streamComplete: false,
      cursor: 0,
      exitCode: view.exitCode,
      elapsedMs: null,
      truncated: view.truncated,
      errorCode: view.failureCode,
      errorMessage: view.failureMessage,
    };
  }
  codeExecutions.value = restored;
  Object.values(restored)
    .filter(execution => ACTIVE_CODE_EXECUTION_STATUSES.has(execution.status))
    .forEach(execution => void replayCodeExecution(execution));
}

async function replayCodeExecution(execution: ChatCodeExecutionState) {
  const executionId = execution.executionId;
  if (!executionId || selectedConversationId.value !== execution.conversationId) return;
  updateCodeExecution(execution.key, { outputLoaded: true, errorMessage: null });
  let reconnectAttempt = 0;
  while (selectedConversationId.value === execution.conversationId) {
    const current = codeExecutions.value[execution.key];
    if (!current) return;
    const controller = new AbortController();
    codeStreamControllers.get(execution.key)?.abort();
    codeStreamControllers.set(execution.key, controller);
    try {
      await resumeChatCodeExecution(
        executionId,
        execution.conversationId,
        current.cursor,
        event => applyCodeStreamEvent(execution.key, event),
        controller.signal,
      );
    } catch (error) {
      if (controller.signal.aborted) return;
      updateCodeExecution(execution.key, {
        errorCode: "stream_reconnect_failed",
        errorMessage: error instanceof Error ? error.message : "代码执行输出恢复失败",
      });
    } finally {
      if (codeStreamControllers.get(execution.key) === controller) {
        codeStreamControllers.delete(execution.key);
      }
    }
    const latest = codeExecutions.value[execution.key];
    if (!latest || latest.streamComplete) return;
    await syncCodeExecutionStatus(execution.key, executionId);
    if (codeExecutions.value[execution.key].streamComplete) return;
    updateCodeExecution(execution.key, { status: "reconnecting" });
    reconnectAttempt += 1;
    await delay(Math.min(5000, 500 * 2 ** Math.min(reconnectAttempt, 3)));
  }
}

async function stopChatCode(execution: ChatCodeExecutionState) {
  if (!execution.executionId || !ACTIVE_CODE_EXECUTION_STATUSES.has(execution.status)) return;
  const result = await stopChatCodeExecution(execution.executionId, execution.conversationId);
  if (result.error) return;
  applyCodeStatusView(execution.key, result.data);
}

function applyCodeStreamEvent(key: string, event: ChatCodeStreamEvent) {
  const current = codeExecutions.value[key];
  if (!current) return;
  const cursor = positiveEventCursor(event.id);
  if (cursor !== null && cursor <= current.cursor) return;
  const base: Partial<ChatCodeExecutionState> = cursor === null ? {} : { cursor };
  if (event.event === "started") {
    updateCodeExecution(key, {
      ...base,
      executionId: eventText(event.data.execution_id) || current.executionId,
      status: normalizeCodeStatus(event.data.status, "running"),
    });
    return;
  }
  if (event.event === "output") {
    const chunk = eventText(event.data.chunk);
    const stream = eventText(event.data.stream);
    updateCodeExecution(key, {
      ...base,
      outputLoaded: true,
      ...(stream === "stderr"
        ? { stderr: current.stderr + chunk }
        : { stdout: current.stdout + chunk }),
    });
    return;
  }

  const terminalBase: Partial<ChatCodeExecutionState> = {
    ...base,
    executionId: eventText(event.data.execution_id) || current.executionId,
    exitCode: nullableEventNumber(event.data.exit_code),
    elapsedMs: nullableEventNumber(event.data.elapsed_ms),
    truncated: event.data.truncated === true,
  };
  if (event.event === "stopped") {
    updateCodeExecution(key, { ...terminalBase, status: "cancelled", streamComplete: true });
  } else if (event.event === "timeout") {
    updateCodeExecution(key, {
      ...terminalBase,
      status: "timed_out",
      streamComplete: true,
      errorCode: "execution_timeout",
      errorMessage: eventText(event.data.message) || "代码执行超时",
    });
  } else if (event.event === "error") {
    updateCodeExecution(key, {
      ...terminalBase,
      status: "failed",
      streamComplete: true,
      errorCode: eventText(event.data.code) || "execution_error",
      errorMessage: eventText(event.data.message) || "代码执行失败",
    });
  } else if (event.event === "finished") {
    updateCodeExecution(key, {
      ...terminalBase,
      status: normalizeCodeStatus(event.data.status, "succeeded"),
      streamComplete: true,
    });
  }
}

async function syncCodeExecutionStatus(key: string, executionId: string) {
  const result = await fetchChatCodeExecution(executionId);
  if (!result.error) applyCodeStatusView(key, result.data);
}

function applyCodeStatusView(
  key: string,
  view: ChatCodeExecutionView,
) {
  updateCodeExecution(key, {
    executionId: view.executionId,
    status: normalizeCodeStatus(view.status, "failed"),
    exitCode: view.exitCode,
    truncated: view.truncated,
    errorCode: view.failureCode,
    errorMessage: view.failureMessage,
  });
}

function updateCodeExecution(
  key: string,
  value: ChatCodeExecutionState | Partial<ChatCodeExecutionState>,
) {
  const current = codeExecutions.value[key];
  codeExecutions.value = {
    ...codeExecutions.value,
    [key]: current ? { ...current, ...value } : value as ChatCodeExecutionState,
  };
}

function normalizeCodeStatus(value: unknown, fallback: ChatCodeExecutionStatus) {
  const status = eventText(value) as ChatCodeExecutionStatus;
  return [
    "connecting", "queued", "leased", "running", "reconnecting",
    "succeeded", "failed", "cancelled", "expired", "timed_out",
  ].includes(status) ? status : fallback;
}

function eventText(value: unknown) {
  return typeof value === "string" ? value : "";
}

function nullableEventNumber(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function positiveEventCursor(value: string | undefined) {
  if (!value || !/^\d+$/.test(value)) return null;
  const cursor = Number(value);
  return Number.isSafeInteger(cursor) && cursor > 0 ? cursor : null;
}

function openCitation(citationId: string, traceId?: string | null) {
  const events = traceId
    ? conversationEvents.value.filter(event => event.traceId === traceId)
    : conversationEvents.value;
  const citation = events
    .flatMap(event => event.eventType === "citation"
      ? extractCitations(event.payload)
      : extractCitations(event.projection))
    .find(item => item.id === citationId);
  if (!citation) {
    window.$message?.warning(`引用 ${citationId} 的详情未随当前事件公开`);
    return;
  }
  selectedCitation.value = citation;
  citationVisible.value = true;
}

function extractCitations(value: unknown, depth = 0): ChatCitation[] {
  if (depth > 8 || value === null || value === undefined) return [];
  if (Array.isArray(value)) return value.flatMap(item => extractCitations(item, depth + 1));
  if (typeof value !== "object") return [];
  const source = value as Record<string, unknown>;
  const id = firstText(source.id, source.citationId, source.citation_id, source.chunkId, source.chunk_id);
  const content = firstText(source.content, source.text, source.snippet, source.quote);
  const direct = id && content ? [{
    id,
    title: firstText(source.title, source.documentName, source.document_name, source.docName, source.doc_name) || `引用 ${id}`,
    content,
    sourceUrl: safeExternalUrl(firstText(source.url, source.link, source.sourceUrl, source.source_url)),
    similarity: finiteNumber(source.similarity, source.score),
  }] : [];
  return [
    ...direct,
    ...Object.values(source).flatMap(item => extractCitations(item, depth + 1)),
  ];
}

function firstText(...values: unknown[]) {
  const value = values.find(item => typeof item === "string" && item.trim());
  return typeof value === "string" ? value.trim() : "";
}

function finiteNumber(...values: unknown[]) {
  const value = values.find(item => typeof item === "number" && Number.isFinite(item));
  return typeof value === "number" ? value : null;
}

function safeExternalUrl(value: string) {
  if (!value) return null;
  try {
    const url = new URL(value);
    return ["http:", "https:"].includes(url.protocol) ? url.toString() : null;
  } catch {
    return null;
  }
}

function turnStatusText(status: ConversationTurnView["status"]) {
  return {
    running: "生成中",
    stopping: "停止中",
    waiting_confirmation: "等待确认",
    waiting_user_question: "等待用户回答",
    succeeded: "已完成",
    failed: "失败",
    cancelled: "已停止",
  }[status];
}

function turnStatusType(status: ConversationTurnView["status"]) {
  if (status === "succeeded") return "success";
  if (status === "failed") return "error";
  if (
    status === "running"
    || status === "stopping"
    || status === "waiting_confirmation"
    || status === "waiting_user_question"
  ) return "warning";
  return "default";
}

function taskStatusText(status: string) {
  return (
    (
      {
        draft: "草稿",
        ready: "就绪",
        scheduled: "已排期",
        running: "运行中",
        waiting: "等待中",
        blocked: "已阻塞",
        rework: "返工中",
        completed: "已完成",
        failed: "失败",
        cancelled: "已取消",
        archived: "已归档",
      } as Record<string, string>
    )[status] || status
  );
}

function taskStatusType(status: string) {
  if (status === "completed") return "success";
  if (status === "failed") return "error";
  if (["waiting", "blocked", "rework"].includes(status)) return "warning";
  if (["ready", "scheduled", "running"].includes(status)) return "info";
  return "default";
}

function openContextTask(task: TaskView) {
  selectedContextTaskId.value = task.id;
  contextDetailVisible.value = true;
  contextMobileVisible.value = false;
}

function toggleContext() {
  contextCollapsed.value = !contextCollapsed.value;
}

function openClientSurface(surface: "tasks" | "projects") {
  void router.push(props.clientMode ? `/app?view=${surface}` : surface === "tasks" ? "/task-center" : "/project-center");
}

function closeConversationSearch() {
  conversationSearchExpanded.value = false;
  if (conversationSearch.value) conversationSearch.value = "";
}

function storedPanelWidth(key: string, fallback: number, minimum: number, maximum: number) {
  const value = Number(window.localStorage.getItem(key));
  return Number.isFinite(value) ? Math.min(maximum, Math.max(minimum, value)) : fallback;
}

function startPanelResize(side: "conversation" | "context", event: PointerEvent) {
  if (!props.clientMode || event.button !== 0 || !chatShell.value || window.innerWidth <= 1500) return;
  const handle = event.currentTarget as HTMLElement;
  const startX = event.clientX;
  const startWidth = side === "conversation" ? conversationPanelWidth.value : contextPanelWidth.value;
  resizingPanel.value = side;
  handle.setPointerCapture?.(event.pointerId);

  const update = (clientX: number) => {
    const shellWidth = chatShell.value?.clientWidth || 0;
    if (side === "conversation") {
      const fixedWidth = contextCollapsed.value ? 56 : contextPanelWidth.value;
      const available = Math.max(0, shellWidth - MIN_CHAT_WIDTH - fixedWidth);
      conversationPanelWidth.value = Math.min(
        MAX_CONVERSATION_WIDTH,
        Math.max(MIN_CONVERSATION_WIDTH, Math.min(available, startWidth + clientX - startX)),
      );
      return;
    }
    const available = Math.max(0, shellWidth - MIN_CHAT_WIDTH - conversationPanelWidth.value);
    contextPanelWidth.value = Math.min(
      MAX_CONTEXT_WIDTH,
      Math.max(MIN_CONTEXT_WIDTH, Math.min(available, startWidth + startX - clientX)),
    );
  };
  const move = (nextEvent: PointerEvent) => {
    if (resizeAnimationFrame !== null) cancelAnimationFrame(resizeAnimationFrame);
    resizeAnimationFrame = requestAnimationFrame(() => {
      resizeAnimationFrame = null;
      update(nextEvent.clientX);
    });
  };
  const finish = (nextEvent: PointerEvent) => {
    handle.releasePointerCapture?.(nextEvent.pointerId);
    handle.removeEventListener("pointermove", move);
    handle.removeEventListener("pointerup", finish);
    handle.removeEventListener("pointercancel", finish);
    resizingPanel.value = null;
    window.localStorage.setItem(CONVERSATION_WIDTH_STORAGE_KEY, String(conversationPanelWidth.value));
    window.localStorage.setItem(CONTEXT_WIDTH_STORAGE_KEY, String(contextPanelWidth.value));
  };
  handle.addEventListener("pointermove", move);
  handle.addEventListener("pointerup", finish);
  handle.addEventListener("pointercancel", finish);
}

function resetPanelWidth(side: "conversation" | "context") {
  if (side === "conversation") {
    conversationPanelWidth.value = 264;
    window.localStorage.setItem(CONVERSATION_WIDTH_STORAGE_KEY, "264");
    return;
  }
  contextPanelWidth.value = 360;
  window.localStorage.setItem(CONTEXT_WIDTH_STORAGE_KEY, "360");
}

function formatTime(value?: string | null) {
  return value ? dayjs(value).format("MM-DD HH:mm") : "尚无消息";
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

function delay(milliseconds: number) {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}

function stopCodeStreams() {
  codeStreamControllers.forEach(controller => controller.abort());
  codeStreamControllers.clear();
}

function handleMessageScroll() {
  const viewport = messageViewport.value;
  if (!viewport) return;
  messageAtBottom.value = viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight < 72;
}

async function scrollToBottom(force = true) {
  await nextTick();
  const viewport = messageViewport.value;
  if (!viewport || (!force && !messageAtBottom.value)) return;
  viewport.scrollTop = viewport.scrollHeight;
  messageAtBottom.value = true;
}

function handleComposerKeydown(event: KeyboardEvent) {
  if (event.key !== "Enter" || event.shiftKey || event.isComposing) return;
  event.preventDefault();
  void sendMessage();
}

function applyConversationSelection(conversationId: string | null) {
  stopCodeStreams();
  codeExecutions.value = {};
  messageAtBottom.value = true;
  messageInput.value = "";
  sendAgentVersionId.value = preferredAgentVersionForConversation(conversationId);
  selectedAttachmentIds.value = [];
  resourceScopeVisible.value = false;
  sendIdempotencyKey.value = crypto.randomUUID();
  resetDraft();
  queueActiveConversationSync(conversationId);
  void loadConversationFacts();
}

function finalizeConversationInBackground(conversationId: string | null) {
  if (!conversationId) return;
  // Nhs treats finalization as an idempotent best-effort flush. Switching
  // conversations must remain responsive when the memory projection is down.
  void finalizeNhsConversation(conversationId);
}

watch(selectedConversationId, async conversationId => {
  if (rollingBackConversationSelection) {
    rollingBackConversationSelection = false;
    return;
  }
  if (conversationId === appliedConversationId) return;
  const generation = ++conversationSelectionGeneration;
  if (
    appliedConversationId
    && canvasWorkbench.value
    && !(await canvasWorkbench.value.guardTransition("切换会话"))
  ) {
    if (generation !== conversationSelectionGeneration) return;
    rollingBackConversationSelection = true;
    selectedConversationId.value = appliedConversationId;
    return;
  }
  if (generation !== conversationSelectionGeneration) return;
  if (appliedConversationId && appliedConversationId !== conversationId) {
    finalizeConversationInBackground(appliedConversationId);
  }
  appliedConversationId = conversationId;
  canvasWorkbench.value?.conversationChanged();
  applyConversationSelection(conversationId);
});

watch(conversationSearch, scheduleConversationSearch);

watch(activeProjectFilterId, () => {
  if (
    selectedConversationId.value &&
    visibleConversations.value.some((conversation) => conversation.id === selectedConversationId.value)
  ) {
    return;
  }
  selectedConversationId.value = visibleConversations.value[0]?.id || null;
});

watch(contextCollapsed, (value) =>
  window.localStorage.setItem(CONTEXT_COLLAPSED_STORAGE_KEY, String(value)),
);

watch([messageInput, sendAgentVersionId, selectedAttachmentIds], () => {
  if (!sending.value) sendIdempotencyKey.value = crypto.randomUUID();
});

watch(
  () => conversationMessages.value.length,
  () => void scrollToBottom(false),
);

watch(eventCursor, () => {
  if (turnActive.value) void scrollToBottom(false);
});

onBeforeRouteLeave(() =>
  canvasWorkbench.value?.guardTransition("离开工作台") ?? true,
);

onMounted(loadData);
onBeforeUnmount(() => {
  finalizeConversationInBackground(appliedConversationId);
  stopMonitor();
  stopCodeStreams();
  conversationSearchGeneration += 1;
  if (conversationSearchTimer !== null) window.clearTimeout(conversationSearchTimer);
  if (resizeAnimationFrame !== null) cancelAnimationFrame(resizeAnimationFrame);
});
</script>

<template>
  <div class="conversation-panel" :class="{ 'client-mode': props.clientMode }">
    <header v-if="!props.clientMode" class="panel-header">
      <div>
        <div class="panel-title">智能工作台</div>
        <div class="panel-subtitle">围绕会话持续推进任务</div>
      </div>
      <NSpace :wrap="false">
        <NTooltip>
          <template #trigger>
            <NButton
              circle
              quaternary
              :loading="loading"
              aria-label="刷新工作台"
              @click="refreshAll"
            >
              <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
            </NButton>
          </template>
          刷新
        </NTooltip>
        <NButton type="primary" @click="openCreate">
          <template #icon><SvgIcon icon="lucide:plus" /></template>
          新建会话
        </NButton>
      </NSpace>
    </header>

    <div v-if="conversations.length || conversationSearch.trim()" class="mobile-conversation-select">
      <NSelect
        v-model:value="selectedConversationId"
        :options="conversationOptions"
        filterable
      />
    </div>

    <div v-if="props.clientMode" class="client-mobile-nav" aria-label="移动端工作区导航">
      <slot name="client-sidebar-header" :open-create="openCreate" />
    </div>

    <div
      v-if="conversations.length || conversationSearch.trim()"
      ref="chatShell"
      class="chat-shell"
      :class="{
        'context-is-collapsed': contextCollapsed,
        'is-resizing': Boolean(resizingPanel),
      }"
      :style="chatShellStyle"
    >
      <aside class="conversation-list" aria-label="私有会话列表">
        <div v-if="props.clientMode" class="client-sidebar-header">
          <slot name="client-sidebar-header" :open-create="openCreate" />
        </div>
        <div v-else class="conversation-list-header">
          <span>工作区</span>
          <NButton
            quaternary
            circle
            size="tiny"
            aria-label="新建会话"
            @click="openCreate"
          >
            <template #icon><SvgIcon icon="lucide:square-pen" /></template>
          </NButton>
        </div>
        <div class="project-switcher">
          <div class="project-switcher-label">
            <span>项目</span>
            <NButton text size="tiny" @click="openClientSurface('projects')">管理</NButton>
          </div>
          <button
            type="button"
            class="project-switcher-item"
            :class="{ active: activeProjectFilterId === null }"
            @click="activeProjectFilterId = null"
          >
            <SvgIcon icon="lucide:layers-3" />
            <span>全部会话</span>
            <small>{{ conversations.length }}</small>
          </button>
          <button
            v-for="project in contextProjects"
            :key="project.id"
            type="button"
            class="project-switcher-item"
            :class="{ active: activeProjectFilterId === project.id }"
            @click="activeProjectFilterId = project.id"
          >
            <SvgIcon icon="lucide:folder" />
            <span>{{ project.name }}</span>
            <small>{{ project.taskCount }}</small>
          </button>
        </div>
        <div class="conversation-section-label">
          <span>会话</span>
          <NButton
            v-if="!conversationSearchExpanded"
            circle
            quaternary
            size="tiny"
            aria-label="搜索会话"
            @click="conversationSearchExpanded = true"
          >
            <template #icon><SvgIcon icon="lucide:search" /></template>
          </NButton>
        </div>
        <div v-if="conversationSearchExpanded" class="conversation-search conversation-search-expanded">
          <NInput
            v-model:value="conversationSearch"
            size="small"
            clearable
            autofocus
            placeholder="搜索会话"
          >
            <template #prefix><SvgIcon icon="lucide:search" /></template>
            <template #suffix>
              <SvgIcon v-if="historySearching" icon="lucide:loader-circle" class="search-spinner" />
            </template>
          </NInput>
          <NButton
            circle
            quaternary
            size="tiny"
            aria-label="关闭会话搜索"
            @click="closeConversationSearch"
          >
            <template #icon><SvgIcon icon="lucide:x" /></template>
          </NButton>
        </div>
        <div class="conversation-scroll">
          <div
            v-for="conversation in visibleConversations"
            :key="conversation.id"
            class="conversation-item" :class="[
              { active: conversation.id === selectedConversationId },
            ]"
          >
            <button
              type="button"
              class="conversation-item-select"
              @click="selectedConversationId = conversation.id"
            >
                  <span class="conversation-item-title">{{
                    conversation.title || `会话 #${conversation.id}`
                  }}</span>
                  <NTag v-if="conversation.parentConversationId" size="tiny" :bordered="false">分支</NTag>
            </button>
            <NTooltip>
              <template #trigger>
                <NButton
                  circle
                  quaternary
                  size="tiny"
                  class="conversation-delete"
                  :loading="deletingConversationId === conversation.id"
                  :disabled="conversation.id === selectedConversationId && turnActive"
                  aria-label="删除会话"
                  @click="requestDeleteConversation(conversation)"
                >
                  <template #icon><SvgIcon icon="lucide:trash-2" /></template>
                </NButton>
              </template>
              删除历史
            </NTooltip>
          </div>
          <div
            v-if="visibleConversations.length === 0"
            class="conversation-search-empty"
          >
            没有匹配的会话
          </div>
        </div>
        <div v-if="props.clientMode" class="client-sidebar-footer">
          <slot name="client-sidebar-footer" />
        </div>
      </aside>

      <div
        v-if="props.clientMode"
        class="panel-resize-handle conversation-resize-handle"
        role="separator"
        aria-label="调整会话栏宽度"
        aria-orientation="vertical"
        title="拖动调整会话栏宽度，双击复位"
        @pointerdown="startPanelResize('conversation', $event)"
        @dblclick="resetPanelWidth('conversation')"
      />

      <section class="chat-main">
        <header class="chat-context">
          <div class="chat-context-copy">
            <strong>{{
              selectedConversation
                ? selectedConversation.title || `会话 #${selectedConversation.id}`
                : "未选择会话"
            }}</strong>
            <span>{{ selectedProject?.name || "未归属项目" }} / {{ selectedAgentName }} / {{ branchLabel }}</span>
          </div>
          <NSpace :wrap="false" size="small">
            <NTag
              v-if="observedTurn"
              size="small"
              :type="turnStatusType(observedTurn.status)"
              :bordered="false"
            >
              {{ turnStatusText(observedTurn.status) }}
            </NTag>
            <NTooltip>
              <template #trigger>
                <NButton
                  circle
                  quaternary
                  :disabled="historyLoading || !resourceScope"
                  aria-label="会话资源范围"
                  @click="openResourceScope"
                >
                  <template #icon><SvgIcon icon="lucide:layers-3" /></template>
                </NButton>
              </template>
              资源范围（{{ resourceScopeSummary }}）
            </NTooltip>
            <NTooltip>
              <template #trigger>
                <NButton
                  circle
                  quaternary
                  :disabled="!selectedConversationId"
                  aria-label="打开 Canvas 工作台"
                  @click="openCanvasLibrary"
                >
                  <template #icon><SvgIcon icon="lucide:panel-top" /></template>
                </NButton>
              </template>
              Canvas 工作台
            </NTooltip>
            <NTooltip>
              <template #trigger>
                <NDropdown
                  :options="conversationExportOptions"
                  trigger="click"
                  @select="exportConversation"
                >
                  <NButton
                    circle
                    quaternary
                    :loading="Boolean(exportingFormat)"
                    aria-label="导出会话"
                  >
                    <template #icon><SvgIcon icon="lucide:download" /></template>
                  </NButton>
                </NDropdown>
              </template>
              导出会话
            </NTooltip>
            <NTooltip>
              <template #trigger>
                <NButton
                  circle
                  quaternary
                  aria-label="查看附件"
                  @click="attachmentsVisible = true"
                >
                  <template #icon><SvgIcon icon="lucide:paperclip" /></template>
                </NButton>
              </template>
              附件（{{ attachments.length }}）
            </NTooltip>
            <NTooltip>
              <template #trigger>
                <NButton
                  circle
                  quaternary
                  aria-label="打开文件工作区"
                  @click="workspaceFilesVisible = true"
                >
                  <template #icon><SvgIcon icon="lucide:folder-open" /></template>
                </NButton>
              </template>
              文件工作区
            </NTooltip>
            <NTooltip>
              <template #trigger>
                <NButton
                  circle
                  quaternary
                  aria-label="查看执行事件"
                  @click="eventsVisible = true"
                >
                  <template #icon><SvgIcon icon="lucide:activity" /></template>
                </NButton>
              </template>
              执行事件（{{ conversationEvents.length }}）
            </NTooltip>
            <NTooltip>
              <template #trigger>
                <NButton
                  circle
                  quaternary
                  :loading="finalizing"
                  :disabled="!selectedConversationId || turnActive"
                  aria-label="结束会话并整理记忆"
                  @click="finalizeConversationMemory"
                >
                  <template #icon><SvgIcon icon="lucide:brain-cog" /></template>
                </NButton>
              </template>
              结束会话并整理个人记忆
            </NTooltip>
            <NTooltip>
              <template #trigger>
                <NButton
                  circle
                  quaternary
                  class="mobile-context-button"
                  aria-label="查看项目上下文"
                  @click="contextMobileVisible = true"
                >
                  <template #icon>
                    <SvgIcon icon="lucide:panel-right-open" />
                  </template>
                </NButton>
              </template>
              项目上下文
            </NTooltip>
          </NSpace>
        </header>

        <div class="message-stage">
          <div
            ref="messageViewport"
            class="message-viewport"
            @scroll.passive="handleMessageScroll"
          >
            <NSpin :show="historyLoading">
              <div v-if="conversationMessages.length || liveAssistantVisible" class="message-list">
              <article
                v-for="message in conversationMessages"
                :key="message.id"
                class="message-row" :class="[`role-${message.role}`]"
              >
                <div class="message-meta">
                  <strong>{{ roleName(message.role) }}</strong>
                  <span>{{ formatTime(message.createdAt) }}</span>
                  <span v-if="message.totalTokens">{{ message.totalTokens }} tokens</span>
                </div>
                <MessageExecutionEvents
                  v-if="message.role === 'assistant'"
                  :events="messageExecutionEvents(message)"
                  :resolved-confirmations="resolvedBusinessConfirmations"
                  :stale-confirmations="expiredRuntimeConfirmations"
                  :submitting-confirmation-id="confirmationSubmitting"
                  :question-states="resolvedRuntimeQuestions"
                  :submitting-question-id="questionSubmitting"
                  :question-snapshots="message.id === latestAssistantMessageId ? pendingRuntimeQuestions : []"
                  @confirmation-submit="submitBusinessConfirmation"
                  @user-question-submit="submitRuntimeUserQuestion"
                  @retry="retryFailedTrace"
                />
                <RichMessageRenderer
                  v-if="message.role !== 'user'"
                  :content="message.content || '[无文本内容]'"
                  :code-executions="codeExecutions"
                  @citation="openCitation($event, message.traceId)"
                  @run-code="runChatCode"
                  @stop-code="stopChatCode"
                />
                <div v-else class="message-content">{{ displayedMessageContent(message) }}</div>
                <div
                  v-if="message.role === 'assistant' || message.role === 'user'"
                  class="message-actions"
                  :class="{ 'message-actions-user': message.role === 'user' }"
                >
                  <NTooltip>
                    <template #trigger>
                      <NButton
                        quaternary
                        size="tiny"
                        aria-label="在 Canvas 中打开"
                        @click="createCanvasFromMessage(message)"
                      >
                        <template #icon><SvgIcon icon="lucide:panel-top" /></template>
                        Canvas
                      </NButton>
                    </template>
                    从这条消息创建 Canvas
                  </NTooltip>
                  <template v-if="message.role === 'assistant' && message.traceId">
                    <NTooltip>
                      <template #trigger>
                        <NButton
                          quaternary
                          size="tiny"
                          class="trace-export-button"
                          :loading="isTraceDataExporting(message.traceId, 'csv')"
                          aria-label="导出 CSV 数据"
                          @click="exportTraceData(message.traceId, 'csv')"
                        >
                          <template #icon><SvgIcon icon="lucide:download" /></template>
                          CSV
                        </NButton>
                      </template>
                      导出当前回复关联的 CSV 数据
                    </NTooltip>
                    <NTooltip>
                      <template #trigger>
                        <NButton
                          quaternary
                          size="tiny"
                          class="trace-export-button"
                          :loading="isTraceDataExporting(message.traceId, 'xlsx')"
                          aria-label="导出 XLSX 数据"
                          @click="exportTraceData(message.traceId, 'xlsx')"
                        >
                          <template #icon><SvgIcon icon="lucide:download" /></template>
                          XLSX
                        </NButton>
                      </template>
                      导出当前回复关联的 Excel 数据
                    </NTooltip>
                  </template>
                  <template v-if="message.role === 'assistant'">
                  <NTooltip>
                    <template #trigger>
                      <NButton
                        circle
                        quaternary
                        size="tiny"
                        :type="messageFeedback[message.id] === 'up' ? 'success' : 'default'"
                        :loading="feedbackSubmittingMessageId === message.id"
                        aria-label="有帮助"
                        @click="submitFeedback(message, 'up')"
                      >
                        <template #icon><SvgIcon icon="lucide:thumbs-up" /></template>
                      </NButton>
                    </template>
                    有帮助
                  </NTooltip>
                  <NTooltip>
                    <template #trigger>
                      <NButton
                        circle
                        quaternary
                        size="tiny"
                        :type="messageFeedback[message.id] === 'down' ? 'error' : 'default'"
                        :loading="feedbackSubmittingMessageId === message.id"
                        aria-label="需要改进"
                        @click="submitFeedback(message, 'down')"
                      >
                        <template #icon><SvgIcon icon="lucide:thumbs-down" /></template>
                      </NButton>
                    </template>
                    需要改进
                  </NTooltip>
                  </template>
                  <NTooltip v-if="message.role === 'user'">
                    <template #trigger>
                      <NButton
                        quaternary
                        size="tiny"
                        :disabled="turnActive || sending"
                        aria-label="从此处重新生成"
                        @click="regenerateUserMessage(message)"
                      >
                          <template #icon><SvgIcon icon="lucide:git-branch" /></template>
                          从此处重新生成
                      </NButton>
                    </template>
                    保留原历史，从此消息创建分支并重新生成
                  </NTooltip>
                </div>
              </article>
              <article v-if="liveAssistantVisible" class="message-row role-assistant live-message">
                <div class="message-meta">
                  <strong>Agent</strong>
                  <NTag
                    v-if="observedTurn"
                    size="tiny"
                    :type="turnStatusType(observedTurn.status)"
                    :bordered="false"
                  >
                    {{ turnStatusText(observedTurn.status) }}
                  </NTag>
                </div>
                <MessageExecutionEvents
                  :events="observedTraceEvents"
                  :active="turnActive"
                  hide-todo
                  :resolved-confirmations="resolvedBusinessConfirmations"
                  :stale-confirmations="expiredRuntimeConfirmations"
                  :submitting-confirmation-id="confirmationSubmitting"
                  :question-states="resolvedRuntimeQuestions"
                  :submitting-question-id="questionSubmitting"
                  :question-snapshots="pendingRuntimeQuestions"
                  @confirmation-submit="submitBusinessConfirmation"
                  @user-question-submit="submitRuntimeUserQuestion"
                  @retry="retryFailedTrace"
                />
                <RichMessageRenderer
                  v-if="liveAssistantContent"
                  :content="liveAssistantContent"
                  :streaming="turnActive"
                  :code-executions="codeExecutions"
                  @citation="openCitation($event, observedTurn?.traceId)"
                  @run-code="runChatCode"
                  @stop-code="stopChatCode"
                />
                <div v-else class="message-content message-pending">正在准备响应</div>
              </article>
              <article v-if="restoredCodeExecutions.length" class="message-row role-tool code-history">
                <div class="message-meta">
                  <strong>代码执行记录</strong>
                  <span>最近 {{ restoredCodeExecutions.length }} 次</span>
                </div>
                <div class="code-history-list">
                  <div v-for="execution in restoredCodeExecutions" :key="execution.key" class="code-history-item">
                    <div class="code-history-language">{{ execution.language }}</div>
                    <MessageCodeExecution
                      :execution="execution"
                      @replay="replayCodeExecution(execution)"
                      @stop="stopChatCode(execution)"
                    />
                  </div>
                </div>
              </article>
              </div>
              <div v-else class="message-empty" role="status" aria-live="polite">
                <div class="message-empty-icon" aria-hidden="true">
                  <SvgIcon icon="lucide:message-square" />
                </div>
                <div class="message-empty-copy">
                  <strong>开始新对话</strong>
                  <span>发送一条消息开始会话</span>
                </div>
              </div>
            </NSpin>
          </div>
          <NTooltip v-if="!messageAtBottom" placement="top">
            <template #trigger>
              <NButton
                class="scroll-to-bottom"
                circle
                secondary
                size="small"
                aria-label="回到底部"
                @click="scrollToBottom()"
              >
                <template #icon><SvgIcon icon="lucide:arrow-down" /></template>
              </NButton>
            </template>
            回到底部
          </NTooltip>
        </div>

        <div v-if="turnActive" class="live-status">
          <SvgIcon icon="lucide:loader-circle" class="live-spinner" />
          <span>{{
            streamState === "reconnecting" ? "事件流重连中" : "Agent 正在处理"
          }}</span>
        </div>

        <div v-if="turnActive && observedTraceEvents.length" class="active-todo-banner">
          <ChatTodoCard :events="observedTraceEvents" />
        </div>

        <div class="composer">
          <div v-if="readyAttachments.length" class="composer-attachments">
            <NSelect
              :value="selectedAttachmentIds"
              :options="attachmentOptions"
              multiple
              size="small"
              :max-tag-count="1"
              placeholder="已上传附件"
              :disabled="turnActive"
              @update:value="updateSelectedAttachments"
            />
          </div>
          <NInput
            v-model:value="messageInput"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 14 }"
            maxlength="131072"
            placeholder="随心输入"
            :disabled="turnActive"
            @keydown="handleComposerKeydown"
          />
          <div class="composer-controls">
            <div class="composer-tools">
              <input
                ref="fileInput"
                class="file-input"
                type="file"
                multiple
                accept=".txt,.md,.csv,.json,.pdf,.png,.jpg,.jpeg,.webp"
                @change="handleFiles"
              />
              <NTooltip>
                <template #trigger>
                  <NButton
                    circle
                    quaternary
                    class="composer-add"
                    :loading="uploading"
                    :disabled="turnActive"
                    aria-label="添加附件"
                    @click="chooseFiles"
                  >
                    <template #icon><SvgIcon icon="lucide:plus" /></template>
                  </NButton>
                </template>
                添加附件
              </NTooltip>
            </div>
            <div class="composer-trailing">
              <NSelect
                :value="sendAgentVersionId"
                :options="sendAgentOptions"
                size="small"
                filterable
                class="composer-agent-select"
                :disabled="turnActive"
                @update:value="handleSendAgentSelection"
              />
              <NButton
                v-if="turnActive"
                circle
                class="composer-primary"
                type="error"
                :loading="stopping"
                aria-label="停止生成"
                @click="stopTurn"
              >
                <template #icon><SvgIcon icon="lucide:square" /></template>
              </NButton>
              <NButton
                v-else
                circle
                class="composer-primary"
                type="primary"
                :loading="sending"
                :disabled="!canSend"
                aria-label="发送消息"
                @click="sendMessage"
              >
                <template #icon><SvgIcon icon="lucide:send" /></template>
              </NButton>
            </div>
          </div>
        </div>

        <NCollapse v-if="!props.clientMode" class="task-conversion">
          <NCollapseItem title="转换为正式任务" name="task-conversion">
            <NForm
              ref="draftFormRef"
              :model="draftForm"
              :rules="draftRules"
              label-placement="top"
            >
              <NGrid :cols="2" :x-gap="16" responsive="screen" item-responsive>
                <NFormItemGi span="2 m:1" label="任务名称" path="title">
                  <NInput
                    v-model:value="draftForm.title"
                    maxlength="255"
                    show-count
                  />
                </NFormItemGi>
                <NFormItemGi span="2 m:1" label="可见范围">
                  <NSelect
                    v-model:value="draftForm.visibility"
                    :options="visibilityOptions"
                  />
                </NFormItemGi>
                <NFormItemGi span="2" label="任务目标" path="objective">
                  <NInput
                    v-model:value="draftForm.objective"
                    type="textarea"
                    :autosize="{ minRows: 3, maxRows: 6 }"
                    maxlength="12000"
                    show-count
                  />
                </NFormItemGi>
                <NFormItemGi span="2" label="背景说明" path="background">
                  <NInput
                    v-model:value="draftForm.background"
                    type="textarea"
                    :autosize="{ minRows: 2, maxRows: 4 }"
                  />
                </NFormItemGi>
                <NFormItemGi span="2 m:1" label="任务分类">
                  <NSelect
                    v-model:value="draftForm.category"
                    :options="categoryOptions"
                  />
                </NFormItemGi>
                <NFormItemGi span="2 m:1" label="风险等级">
                  <NSelect
                    v-model:value="draftForm.riskLevel"
                    :options="riskOptions"
                  />
                </NFormItemGi>
                <NFormItemGi span="2 m:1" label="标签">
                  <NDynamicTags v-model:value="draftForm.tags" :max="32" />
                </NFormItemGi>
                <NFormItemGi span="2 m:1" label="优先属性">
                  <NSpace>
                    <NCheckbox v-model:checked="draftForm.importance">
                      重要
                    </NCheckbox>
                    <NCheckbox v-model:checked="draftForm.urgency">
                      紧急
                    </NCheckbox>
                  </NSpace>
                </NFormItemGi>
                <NFormItemGi span="2" label="任务资源授权">
                  <NSelect
                    v-model:value="draftForm.resourceKeys"
                    multiple
                    filterable
                    clearable
                    :options="taskResourceOptions"
                    placeholder="选择本次任务允许使用的工具、Skill、知识库或数据集"
                  />
                </NFormItemGi>
              </NGrid>
            </NForm>
            <div class="draft-actions">
              <NButton
                type="primary"
                :loading="draftSubmitting"
                @click="previewDraft"
              >
                <template #icon>
                  <SvgIcon icon="lucide:file-check-2" />
                </template>
                生成任务草稿
              </NButton>
            </div>
            <div v-if="taskDraft" class="draft-review">
              <NDescriptions
                :column="2"
                label-placement="left"
                bordered
                responsive="screen"
              >
                <NDescriptionsItem label="标题">
                  {{
                    taskDraft.draft.title
                  }}
                </NDescriptionsItem>
                <NDescriptionsItem label="风险">
                  {{
                    taskDraft.draft.riskLevel
                  }}
                </NDescriptionsItem>
                <NDescriptionsItem label="可见范围">
                  {{
                    taskDraft.draft.visibility
                  }}
                </NDescriptionsItem>
                <NDescriptionsItem label="Agent 版本">
                  #{{ taskDraft.draft.agentVersionId }}
                </NDescriptionsItem>
                <NDescriptionsItem label="目标" :span="2">
                  {{
                    taskDraft.draft.objective
                  }}
                </NDescriptionsItem>
              </NDescriptions>
              <NSpace justify="end" class="draft-confirm-actions">
                <NButton @click="taskDraft = null">返回修改</NButton>
                <NButton
                  type="primary"
                  :loading="converting"
                  :disabled="Boolean(convertedTaskId)"
                  @click="confirmConvert"
                >
                  <template #icon><SvgIcon icon="lucide:check" /></template>
                  {{
                    convertedTaskId
                      ? `已创建任务 #${convertedTaskId}`
                      : "确认创建正式任务"
                  }}
                </NButton>
              </NSpace>
            </div>
          </NCollapseItem>
        </NCollapse>
      </section>

      <div
        v-if="!props.clientMode && !contextCollapsed"
        class="panel-resize-handle context-resize-handle"
        role="separator"
        aria-label="调整上下文栏宽度"
        aria-orientation="vertical"
        title="拖动调整上下文栏宽度，双击复位"
        @pointerdown="startPanelResize('context', $event)"
        @dblclick="resetPanelWidth('context')"
      />

      <aside
        v-if="!props.clientMode && !contextCollapsed"
        class="context-panel"
        aria-label="项目与任务上下文"
      >
        <header class="context-header">
          <div>
            <strong>上下文</strong>
            <span>{{ activeContextTasks.length }} 个进行中任务</span>
          </div>
          <NTooltip>
            <template #trigger>
              <NButton
                circle
                quaternary
                size="small"
                aria-label="收起上下文栏"
                @click="toggleContext"
              >
                <template #icon>
                  <SvgIcon icon="lucide:panel-right-close" />
                </template>
              </NButton>
            </template>
            收起
          </NTooltip>
        </header>

        <div class="context-scroll">
          <section class="context-section">
            <div class="context-section-title">
              <span>项目</span>
              <span>{{ contextProjects.length }}</span>
            </div>
            <div class="project-list">
              <div
                v-for="project in contextProjects"
                :key="project.id"
                class="project-row"
              >
                <SvgIcon icon="lucide:folder-kanban" />
                <span>{{ project.name }}</span>
                <small>{{ project.taskCount }} 项</small>
              </div>
              <div v-if="contextProjects.length === 0" class="context-empty">
                暂无进行中项目
              </div>
            </div>
          </section>

          <section class="context-section">
            <div class="context-section-title">
              <span>进行中的任务</span>
              <NButton text size="tiny" @click="openClientSurface('tasks')">
                全部
              </NButton>
            </div>
            <div class="context-task-list">
              <button
                v-for="task in activeContextTasks"
                :key="task.id"
                type="button"
                class="context-task" :class="[
                  { active: selectedContextTask?.id === task.id },
                ]"
                @click="openContextTask(task)"
              >
                <span>{{ task.title }}</span>
                <span class="context-task-meta">
                  <NTag
                    size="tiny"
                    :type="taskStatusType(task.status)"
                    :bordered="false"
                  >{{ taskStatusText(task.status) }}</NTag>
                  <small v-if="task.importance || task.urgency">{{ task.importance ? "重要" : ""
                  }}{{ task.importance && task.urgency ? " / " : ""
                  }}{{ task.urgency ? "紧急" : "" }}</small>
                </span>
              </button>
              <div v-if="activeContextTasks.length === 0" class="context-empty">
                暂无任务
              </div>
            </div>
          </section>

          <section class="context-section context-facts">
            <div class="context-section-title"><span>当前会话</span></div>
            <dl>
              <div>
                <dt>Agent</dt>
                <dd>{{ selectedAgentName }}</dd>
              </div>
              <div>
                <dt>附件</dt>
                <dd>{{ attachments.length }}</dd>
              </div>
              <div>
                <dt>执行事件</dt>
                <dd>{{ conversationEvents.length }}</dd>
              </div>
              <div>
                <dt>资源范围</dt>
                <dd>{{ resourceScopeSummary }}</dd>
              </div>
            </dl>
          </section>

          <section v-if="dashboardContext" class="context-section context-facts">
            <div class="context-section-title">
              <span>Agent 联动上下文</span>
              <SvgIcon icon="lucide:gauge" />
            </div>
            <dl>
              <div v-if="dashboardContext.roomName">
                <dt>对象</dt>
                <dd>{{ dashboardContext.roomName }}</dd>
              </div>
              <div v-if="dashboardContext.metricName">
                <dt>指标</dt>
                <dd>{{ dashboardContext.metricName }}</dd>
              </div>
              <div v-if="dashboardContext.timeRange">
                <dt>时间范围</dt>
                <dd>{{ dashboardContext.timeRange }}</dd>
              </div>
            </dl>
          </section>
        </div>
      </aside>

      <aside v-else-if="!props.clientMode" class="context-rail" aria-label="展开项目上下文">
        <NTooltip placement="left">
          <template #trigger>
            <NButton
              circle
              quaternary
              aria-label="展开上下文栏"
              @click="toggleContext"
            >
              <template #icon>
                <SvgIcon icon="lucide:panel-right-open" />
              </template>
            </NButton>
          </template>
          展开上下文
        </NTooltip>
        <span>{{ activeContextTasks.length }}</span>
      </aside>
    </div>

    <NEmpty v-else :description="conversationSearch.trim() ? '没有匹配的会话' : '暂无私有会话'">
      <template #extra>
        <NButton type="primary" @click="openCreate">
          <template #icon><SvgIcon icon="lucide:plus" /></template>
          新建会话
        </NButton>
      </template>
    </NEmpty>
  </div>

  <NDrawer
    v-model:show="contextMobileVisible"
    width="min(360px, calc(100vw - 16px))"
    placement="right"
  >
    <NDrawerContent closable title="项目上下文">
      <section class="mobile-context-section">
        <h3>进行中的任务</h3>
        <button
          v-for="task in activeContextTasks"
          :key="task.id"
          type="button"
          class="context-task"
          @click="openContextTask(task)"
        >
          <span>{{ task.title }}</span>
          <span class="context-task-meta">
            <NTag
              size="tiny"
              :type="taskStatusType(task.status)"
              :bordered="false"
            >{{ taskStatusText(task.status) }}</NTag>
            <small>{{ task.taskKey }}</small>
          </span>
        </button>
        <div v-if="activeContextTasks.length === 0" class="context-empty">
          暂无任务
        </div>
      </section>
      <section class="mobile-context-section">
        <h3>会话信息</h3>
        <NDescriptions :column="1" size="small" label-placement="left">
          <NDescriptionsItem label="Agent">
            {{
              selectedAgentName
            }}
          </NDescriptionsItem>
          <NDescriptionsItem label="附件">
            {{
              attachments.length
            }}
          </NDescriptionsItem>
          <NDescriptionsItem label="执行事件">
            {{
              conversationEvents.length
            }}
          </NDescriptionsItem>
          <NDescriptionsItem label="资源范围">
            {{ resourceScopeSummary }}
          </NDescriptionsItem>
        </NDescriptions>
      </section>
      <section v-if="dashboardContext" class="mobile-context-section">
        <h3>Agent 联动上下文</h3>
        <NDescriptions :column="1" size="small" label-placement="left">
          <NDescriptionsItem v-if="dashboardContext.roomName" label="对象">
            {{ dashboardContext.roomName }}
          </NDescriptionsItem>
          <NDescriptionsItem v-if="dashboardContext.metricName" label="指标">
            {{ dashboardContext.metricName }}
          </NDescriptionsItem>
          <NDescriptionsItem v-if="dashboardContext.timeRange" label="时间范围">
            {{ dashboardContext.timeRange }}
          </NDescriptionsItem>
        </NDescriptions>
      </section>
    </NDrawerContent>
  </NDrawer>

  <NDrawer
    v-model:show="contextDetailVisible"
    width="min(720px, calc(100vw - 16px))"
    placement="right"
  >
    <NDrawerContent v-if="selectedContextTask" closable>
      <template #header>
        <div class="context-detail-header">
          <div>
            <strong>{{ selectedContextTask.title }}</strong>
            <span>{{ selectedContextTask.taskKey }}</span>
          </div>
          <NTag
            size="small"
            :type="taskStatusType(selectedContextTask.status)"
            :bordered="false"
          >
            {{ taskStatusText(selectedContextTask.status) }}
          </NTag>
        </div>
      </template>
      <div class="context-detail-body">
        <section>
          <h3>任务目标</h3>
          <p>{{ selectedContextTask.objective }}</p>
        </section>
        <section v-if="selectedContextTask.background">
          <h3>背景与约束</h3>
          <p>{{ selectedContextTask.background }}</p>
        </section>
        <NDescriptions
          :column="2"
          label-placement="top"
          bordered
          responsive="screen"
        >
          <NDescriptionsItem label="所属项目">
            {{
              selectedContextTask.projectId
                ? `项目 #${selectedContextTask.projectId}`
                : "未归属项目"
            }}
          </NDescriptionsItem>
          <NDescriptionsItem label="任务分类">
            {{
              selectedContextTask.category
            }}
          </NDescriptionsItem>
          <NDescriptionsItem label="风险等级">
            {{
              selectedContextTask.riskLevel
            }}
          </NDescriptionsItem>
          <NDescriptionsItem label="编排方式">
            {{
              selectedContextTask.orchestrationMode === "multi_agent_template"
                ? "固定多智能体"
                : "单智能体"
            }}
          </NDescriptionsItem>
          <NDescriptionsItem label="优先属性">
            {{ selectedContextTask.importance ? "重要" : "常规"
            }}{{ selectedContextTask.urgency ? " / 紧急" : "" }}
          </NDescriptionsItem>
          <NDescriptionsItem label="创建时间">
            {{
              formatTime(selectedContextTask.createdAt)
            }}
          </NDescriptionsItem>
        </NDescriptions>
        <div v-if="selectedContextTask.tags.length" class="context-detail-tags">
          <NTag
            v-for="tag in selectedContextTask.tags"
            :key="tag"
            size="small"
            :bordered="false"
          >
            {{ tag }}
          </NTag>
        </div>
      </div>
      <template #footer>
        <NButton type="primary" @click="openClientSurface('tasks')">
          <template #icon><SvgIcon icon="lucide:list-checks" /></template>
          前往任务中心
        </NButton>
      </template>
    </NDrawerContent>
  </NDrawer>

  <NModal
    v-model:show="createVisible"
    preset="card"
    title="新建私有会话"
    class="modal-card"
    style="width: min(560px, calc(100vw - 32px))"
  >
    <NForm
      ref="createFormRef"
      :model="createForm"
      :rules="createRules"
      label-placement="top"
    >
      <NFormItem label="会话标题" path="title">
        <NInput
          v-model:value="createForm.title"
          maxlength="255"
          show-count
          placeholder="可选"
        />
      </NFormItem>
      <NFormItem label="所属项目">
        <NSelect
          v-model:value="createForm.projectId"
          :options="projectOptions"
          filterable
          clearable
          placeholder="不归属项目"
        />
      </NFormItem>
      <NFormItem label="初始 Agent">
        <NSelect
          v-model:value="createForm.agentVersionId"
          :options="createAgentOptions"
          filterable
        />
      </NFormItem>
    </NForm>
    <template #footer>
      <NSpace justify="end">
        <NButton @click="createVisible = false">取消</NButton>
        <NButton
          type="primary"
          :loading="createSubmitting"
          @click="submitCreate"
        >
          创建
        </NButton>
      </NSpace>
    </template>
  </NModal>

  <NModal
    v-model:show="resourceScopeVisible"
    preset="card"
    title="会话资源范围"
    class="modal-card resource-scope-modal"
    style="width: min(680px, calc(100vw - 32px))"
  >
    <NForm label-placement="top">
      <NGrid :cols="2" :x-gap="16" responsive="screen" item-responsive>
        <NFormItemGi
          v-for="field in resourceScopeFields"
          :key="field.key"
          span="2 m:1"
          :label="field.label"
        >
          <NDynamicTags
            v-model:value="resourceScopeDraft[field.key]"
            :disabled="resourceScopeSaving || turnActive || !resourceScope"
          />
        </NFormItemGi>
      </NGrid>
    </NForm>
    <div class="resource-scope-footer-note">
      当前版本：{{ resourceScope?.revision || 0 }} · {{ resourceScopeSummary }}
    </div>
    <template #footer>
      <NSpace justify="end">
        <NButton :disabled="resourceScopeSaving" @click="resourceScopeVisible = false">取消</NButton>
        <NButton
          type="primary"
          :loading="resourceScopeSaving"
          :disabled="turnActive || !resourceScope"
          @click="saveResourceScope"
        >
          保存资源范围
        </NButton>
      </NSpace>
    </template>
  </NModal>

  <NDrawer v-model:show="eventsVisible" width="min(520px, calc(100vw - 16px))" placement="right">
    <NDrawerContent closable title="执行事件">
      <NList v-if="conversationEvents.length" bordered>
        <NListItem
          v-for="event in [...conversationEvents].reverse()"
          :key="event.eventId"
        >
          <NThing
            :title="event.eventType"
            :description="event.summary || '无摘要'"
          >
            <template #header-extra>
              <span class="event-cursor">cursor {{ event.cursor }}</span>
            </template>
            <template #footer>
              {{ formatTime(event.occurredAt) }} ·
              {{ event.eventStatus }}
            </template>
          </NThing>
        </NListItem>
      </NList>
      <NEmpty v-else description="该会话暂无执行事件" />
    </NDrawerContent>
  </NDrawer>

  <NDrawer v-model:show="attachmentsVisible" width="min(520px, calc(100vw - 16px))" placement="right">
    <NDrawerContent closable title="会话附件">
      <NAlert v-if="attachmentUploadError" type="error" :bordered="false" class="attachment-upload-error">
        {{ attachmentUploadError }}
      </NAlert>
      <NList v-if="attachments.length" bordered>
        <NListItem v-for="attachment in attachments" :key="attachment.id">
          <NThing
            :title="attachment.originalName"
            :description="`${attachment.mimeType} · ${formatBytes(attachment.sizeBytes)}`"
          >
            <template #header-extra>
              <NTag size="small" :bordered="false">
                {{
                  attachment.status === "ready" ? "待使用" : attachment.status === "bound" ? "已使用" : "已删除"
                }}
              </NTag>
            </template>
          </NThing>
          <template #suffix>
            <NTooltip>
              <template #trigger>
                <NButton
                  circle
                  quaternary
                  :loading="attachmentDownloadingId === attachment.id"
                  aria-label="下载附件"
                  @click="downloadAttachment(attachment)"
                >
                  <template #icon><SvgIcon icon="lucide:download" /></template>
                </NButton>
              </template>
              下载
            </NTooltip>
          </template>
        </NListItem>
      </NList>
      <NEmpty v-else description="该会话暂无附件" />
    </NDrawerContent>
  </NDrawer>

  <NDrawer v-model:show="citationVisible" width="min(520px, calc(100vw - 16px))" placement="right">
    <NDrawerContent v-if="selectedCitation" closable title="引用详情">
      <NDescriptions :column="1" bordered label-placement="left">
        <NDescriptionsItem label="引用 ID">{{ selectedCitation.id }}</NDescriptionsItem>
        <NDescriptionsItem label="来源">{{ selectedCitation.title }}</NDescriptionsItem>
        <NDescriptionsItem v-if="selectedCitation.similarity !== null" label="匹配度">
          {{ (selectedCitation.similarity * 100).toFixed(1) }}%
        </NDescriptionsItem>
      </NDescriptions>
      <div class="citation-content">{{ selectedCitation.content }}</div>
      <template v-if="selectedCitation.sourceUrl" #footer>
        <NButton
          tag="a"
          :href="selectedCitation.sourceUrl"
          target="_blank"
          rel="noopener noreferrer"
        >
          <template #icon><SvgIcon icon="lucide:external-link" /></template>
          查看来源
        </NButton>
      </template>
    </NDrawerContent>
  </NDrawer>

  <NDrawer
    v-model:show="workspaceFilesVisible"
    width="min(1080px, calc(100vw - 16px))"
    placement="right"
  >
    <NDrawerContent closable title="文件工作区">
      <WorkspaceFileBrowser :visible="workspaceFilesVisible" />
    </NDrawerContent>
  </NDrawer>

  <CanvasWorkbench
    ref="canvasWorkbench"
    :conversation-id="selectedConversationId"
  />
</template>

<style scoped lang="scss">
.conversation-panel {
  display: flex;
  height: calc(100dvh - 112px);
  min-height: 620px;
  overflow: hidden;
  background: var(--n-color);
  flex-direction: column;
}

.conversation-panel.client-mode {
  height: 100%;
  min-height: 0;
  background: var(--n-color);
}

.panel-header,
.chat-context,
.composer-controls,
.live-status,
.draft-actions {
  display: flex;
  align-items: center;
}

.panel-header,
.chat-context,
.composer-controls {
  justify-content: space-between;
}

.panel-header {
  min-height: 58px;
  padding: 8px 12px;
  border: 1px solid var(--n-border-color);
  border-bottom: 0;
  gap: 16px;
}

.panel-title {
  font-size: 16px;
  font-weight: 600;
}

.panel-subtitle,
.chat-context-copy span,
.message-meta,
.event-cursor {
  color: var(--n-text-color-3);
  font-size: 12px;
}

.chat-shell {
  display: grid;
  min-height: 0;
  overflow: hidden;
  position: relative;
  flex: 1;
  grid-template-columns: 220px minmax(0, 1fr) 280px;
  border: 1px solid var(--n-border-color);
}

.chat-shell.context-is-collapsed {
  grid-template-columns: 220px minmax(0, 1fr) 48px;
}

.client-mode .chat-shell {
  grid-template-columns: var(--conversation-panel-width) minmax(0, 1fr);
  border: 0;
}

.client-mode .chat-shell.context-is-collapsed {
  grid-template-columns: var(--conversation-panel-width) minmax(0, 1fr);
}

.panel-resize-handle {
  width: 10px;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: col-resize;
  inset-block: 0;
  position: absolute;
  touch-action: none;
  z-index: 2;

  &::after {
    width: 1px;
    background: transparent;
    content: "";
    inset-block: 0;
    left: 50%;
    position: absolute;
    transition: background-color 0.15s ease;
  }

  &:hover::after {
    background: var(--n-primary-color);
  }
}

.conversation-resize-handle {
  left: var(--conversation-panel-width);
  transform: translateX(-50%);
}

.context-resize-handle {
  right: var(--context-panel-width);
  transform: translateX(50%);
}

.chat-shell.is-resizing,
.chat-shell.is-resizing * {
  cursor: col-resize !important;
  user-select: none;
}

.chat-shell.is-resizing .panel-resize-handle::after {
  background: var(--n-primary-color);
}

.conversation-list {
  display: flex;
  min-height: 0;
  overflow: hidden;
  border-right: 1px solid var(--n-border-color);
  background: rgba(127, 127, 127, 0.035);
  flex-direction: column;
}

.client-sidebar-header {
  padding: 12px 12px 8px;
  border-bottom: 1px solid var(--n-border-color);
}

.client-sidebar-header :deep(.workspace-switcher) {
  margin-bottom: 12px;
}

.client-sidebar-header :deep(.client-nav) {
  gap: 3px;
}

.client-sidebar-header :deep(.nav-item) {
  min-height: 34px;
  padding-inline: 8px;
}

.client-sidebar-footer {
  padding: 8px 12px 10px;
}

.client-sidebar-footer :deep(.sidebar-user) {
  padding-inline: 3px;
}

.project-switcher {
  display: flex;
  max-height: 210px;
  padding: 2px 8px 9px;
  overflow-y: auto;
  flex-direction: column;
  gap: 2px;
}

.project-switcher-label,
.conversation-section-label {
  display: flex;
  min-height: 26px;
  padding: 4px 6px;
  align-items: center;
  justify-content: space-between;
  color: var(--n-text-color-3);
  font-size: 11px;
  font-weight: 600;
}

.conversation-section-label {
  padding: 8px 14px 5px;
}

.project-switcher-item {
  display: grid;
  width: 100%;
  min-height: 34px;
  padding: 6px 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--n-text-color-2);
  cursor: pointer;
  align-items: center;
  grid-template-columns: 18px minmax(0, 1fr) auto;
  gap: 7px;
  text-align: left;
}

.project-switcher-item:hover,
.project-switcher-item.active {
  background: color-mix(in srgb, var(--n-primary-color) 10%, transparent);
  color: var(--n-text-color);
}

.project-switcher-item span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-switcher-item small {
  color: var(--n-text-color-3);
  font-variant-numeric: tabular-nums;
}

.conversation-list-header {
  display: flex;
  min-height: 46px;
  padding: 7px 10px 5px 12px;
  align-items: center;
  justify-content: space-between;
  color: var(--n-text-color-2);
  font-size: 12px;
  font-weight: 600;
}

.conversation-search {
  padding: 0 9px 8px;
}

.conversation-search-expanded {
  display: flex;
  align-items: center;
  gap: 4px;
}

.conversation-search-expanded .n-input {
  min-width: 0;
  flex: 1;
}

.conversation-scroll {
  min-height: 0;
  overflow-y: auto;
  flex: 1;
}

.conversation-search-empty {
  padding: 30px 12px;
  color: var(--n-text-color-3);
  font-size: 12px;
  text-align: center;
}

.conversation-item {
  display: flex;
  width: calc(100% - 16px);
  min-height: 34px;
  margin: 2px 8px;
  padding: 6px 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: inherit;
  position: relative;
  align-items: stretch;
  flex-direction: row;
  gap: 4px;
}

.conversation-item:hover,
.conversation-item.active {
  background: color-mix(in srgb, var(--n-primary-color) 10%, transparent);
  color: var(--n-text-color);
}

.conversation-item-select {
  display: flex;
  min-width: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  flex: 1;
  justify-content: center;
  flex-direction: column;
  gap: 0;
  text-align: left;
}

.conversation-delete {
  align-self: center;
  color: var(--n-text-color-3);
  opacity: 0;
  transition: opacity 0.15s ease;
}

.conversation-item:hover .conversation-delete,
.conversation-item:focus-within .conversation-delete {
  opacity: 1;
}

.search-spinner {
  animation: spin 1s linear infinite;
}

.conversation-item-title {
  overflow: hidden;
  width: 100%;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-main {
  display: flex;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  flex-direction: column;
}

.chat-context {
  min-height: 54px;
  padding: 8px 14px;
  border-bottom: 1px solid var(--n-border-color);
  gap: 12px;
}

.chat-context-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.chat-context-copy strong,
.chat-context-copy span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.message-stage {
  display: flex;
  min-height: 0;
  position: relative;
  flex: 1;
}

.message-viewport {
  width: 100%;
  min-height: 180px;
  padding: 16px;
  overflow-y: auto;
  background: rgba(127, 127, 127, 0.018);
  flex: 1;
}

.scroll-to-bottom {
  right: 20px;
  bottom: 14px;
  position: absolute;
  box-shadow: 0 4px 14px color-mix(in srgb, var(--n-text-color) 12%, transparent);
  z-index: 1;
}

.client-mode .message-viewport {
  padding: 24px 20px 12px;
  background: var(--n-color);
}

.message-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.client-mode .message-list {
  width: min(100%, 780px);
  margin: 0 auto;
  gap: 22px;
}

.message-row {
  width: fit-content;
  max-width: 86%;
  padding: 10px 12px;
  border: 1px solid var(--n-border-color);
  border-radius: 6px;
  background: var(--n-color);
}

.client-mode .message-row {
  width: 100%;
  max-width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
}

.role-user {
  margin-left: auto;
  border-color: rgba(24, 160, 88, 0.48);
  background: rgba(24, 160, 88, 0.07);
}

.client-mode .role-user {
  width: fit-content;
  max-width: min(82%, 620px);
  padding: 10px 13px;
  border: 1px solid color-mix(in srgb, var(--n-primary-color) 28%, var(--n-border-color));
  border-radius: 8px;
  background: color-mix(in srgb, var(--n-primary-color) 7%, var(--n-color));
}

.client-mode .role-assistant .message-meta strong {
  color: var(--n-primary-color);
}

.role-system,
.role-tool {
  max-width: 100%;
  border-style: dashed;
}

.message-meta {
  display: flex;
  margin-bottom: 6px;
  align-items: center;
  gap: 8px;
}

.message-meta strong {
  color: var(--n-text-color-2);
}

.message-content {
  line-height: 1.65;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.message-pending {
  color: var(--n-text-color-3);
}

.live-message {
  border-color: rgba(32, 128, 240, 0.42);
}

.code-history {
  width: 100%;
  max-width: 100%;
}

.code-history-list {
  display: grid;
  gap: 10px;
}

.code-history-item {
  border: 1px solid var(--n-border-color);
  border-radius: 6px;
  overflow: hidden;
}

.code-history-language {
  padding: 6px 10px;
  border-bottom: 1px solid var(--n-border-color);
  color: var(--n-text-color-3);
  background: rgba(128, 128, 128, 0.06);
  font-size: 12px;
}

.message-actions {
  display: flex;
  flex-wrap: wrap;
  margin-top: 6px;
  gap: 2px;
  justify-content: flex-end;
}

.message-actions-user {
  justify-content: flex-start;
}

.trace-export-button {
  min-width: 56px;
}

.citation-content {
  margin-top: 16px;
  padding: 12px;
  border: 1px solid var(--n-border-color);
  border-radius: 6px;
  line-height: 1.7;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.message-empty {
  display: flex;
  width: min(100%, 780px);
  min-height: 132px;
  margin: clamp(54px, 12vh, 112px) auto 0;
  padding: 0 20px;
  align-items: center;
  justify-content: center;
  gap: 12px;
}

.message-empty-icon {
  display: inline-flex;
  width: 38px;
  height: 38px;
  flex: 0 0 38px;
  align-items: center;
  justify-content: center;
  border: 1px solid color-mix(in srgb, var(--n-primary-color) 22%, var(--n-border-color));
  border-radius: 12px;
  background: color-mix(in srgb, var(--n-primary-color) 9%, transparent);
  color: var(--n-primary-color);
  font-size: 19px;
}

.message-empty-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.message-empty-copy strong {
  color: var(--n-text-color-2);
  font-size: 14px;
  font-weight: 600;
}

.message-empty-copy span {
  color: var(--n-text-color-3);
  font-size: 12px;
}

.live-status {
  min-height: 38px;
  padding: 6px 14px;
  border-top: 1px solid var(--n-border-color);
  background: rgba(240, 160, 32, 0.08);
  gap: 8px;
  font-size: 12px;
}

.live-status .n-button {
  margin-left: auto;
}

.live-spinner {
  animation: spin 1s linear infinite;
}

.composer {
  padding: 12px 14px;
  border-top: 1px solid var(--n-border-color);
}

.active-todo-banner {
  min-width: 0;
}

.client-mode .composer {
  display: flex;
  width: min(calc(100% - 24px), 1040px);
  margin: 0 auto 14px;
  padding: 10px 8px 8px;
  border: 1px solid var(--n-border-color);
  border-radius: 22px;
  background: var(--n-color);
  box-shadow: 0 10px 28px color-mix(in srgb, var(--n-text-color) 10%, transparent);
  flex-direction: column;
  gap: 8px;
  transition: border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease;
}

.client-mode .composer:hover {
  border-color: color-mix(in srgb, var(--n-primary-color) 36%, var(--n-border-color));
  box-shadow: 0 12px 32px color-mix(in srgb, var(--n-text-color) 13%, transparent);
}

.client-mode .composer:focus-within {
  border-color: color-mix(in srgb, var(--n-primary-color) 62%, var(--n-border-color));
  box-shadow:
    0 0 0 2px color-mix(in srgb, var(--n-primary-color) 12%, transparent),
    0 12px 32px color-mix(in srgb, var(--n-text-color) 13%, transparent);
}

.client-mode .composer :deep(.n-input) {
  --n-border: 0 !important;
  --n-border-hover: 0 !important;
  --n-border-focus: 0 !important;
  --n-box-shadow-focus: none !important;
  background: transparent;
}

.client-mode .composer :deep(.n-input-wrapper) {
  padding: 0 !important;
}

.client-mode .composer :deep(.n-input__textarea-el),
.client-mode .composer :deep(.n-input__textarea-mirror),
.client-mode .composer :deep(.n-input__placeholder) {
  box-sizing: border-box;
  min-height: 48px;
  padding: 4px 8px 4px 12px;
  color: var(--n-text-color);
  font-size: 16px;
  line-height: 24px;
  overflow-wrap: anywhere;
}

.client-mode .composer :deep(.n-input__placeholder) {
  color: var(--n-text-color-3);
}

.composer-controls {
  margin-top: 0;
  gap: 12px;
}

.composer-tools,
.composer-trailing {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}

.composer-tools {
  flex: 1;
}

.composer-trailing {
  flex: none;
}

.composer-agent-select {
  min-width: 180px;
  max-width: 260px;
}

.composer-add {
  width: 28px;
  height: 28px;
  min-width: 28px;
  border-radius: 999px;
}

.composer-primary {
  width: 34px;
  height: 34px;
  min-width: 34px;
  padding: 0;
  border-radius: 999px;
  transition: transform 100ms ease, opacity 100ms ease;
}

.composer-primary:active:not(:disabled),
.composer-add:active:not(:disabled) {
  transform: scale(0.96);
}

.composer-attachments {
  min-width: 0;
  margin: 0 8px -2px;
}

.composer-attachments :deep(.n-base-selection) {
  min-height: 28px;
  border: 0 !important;
  box-shadow: none !important;
  background: transparent;
}

.composer-attachments :deep(.n-base-selection-tag) {
  max-width: 220px;
  border-radius: 8px;
  background: color-mix(in srgb, var(--n-primary-color) 10%, transparent);
}

.client-mode .composer-controls {
  padding: 2px 8px 4px;
}

.client-mode .composer-agent-select :deep(.n-base-selection) {
  min-height: 30px;
  border: 0 !important;
  border-radius: 8px;
  box-shadow: none !important;
  background: transparent;
  transition: background-color 140ms ease;
}

.client-mode .composer-agent-select:hover :deep(.n-base-selection),
.client-mode .composer-agent-select :deep(.n-base-selection--active) {
  background: color-mix(in srgb, var(--n-text-color) 6%, transparent);
}

.client-mode .composer-agent-select :deep(.n-base-selection-label),
.client-mode .composer-agent-select :deep(.n-base-selection-overlay) {
  border: 0 !important;
  box-shadow: none !important;
  background: transparent !important;
}

.client-mode .composer-agent-select :deep(.n-base-selection__border),
.client-mode .composer-agent-select :deep(.n-base-selection__state-border) {
  border: 0 !important;
  opacity: 0;
}

.client-mode .composer-agent-select :deep(.n-base-selection-label) {
  border-radius: 8px;
}

.file-input {
  display: none;
}

.task-conversion {
  max-height: 44%;
  padding: 0 14px 10px;
  border-top: 1px solid var(--n-border-color);
  overflow-y: auto;
}

.client-mode .task-conversion {
  width: min(calc(100% - 32px), 840px);
  max-height: 210px;
  margin: 0 auto;
  padding: 0 4px 8px;
  border-top: 0;
}

.draft-actions {
  justify-content: flex-end;
}

.draft-review {
  margin-top: 14px;
}

.draft-confirm-actions {
  margin-top: 12px;
}

.mobile-conversation-select {
  display: none;
  margin-bottom: 10px;
}

.client-mobile-nav {
  display: none;
}

.mobile-context-button {
  display: none;
}

.context-panel,
.context-rail {
  min-height: 0;
  border-left: 1px solid var(--n-border-color);
  background: color-mix(in srgb, var(--n-color) 97%, var(--n-text-color) 3%);
}

.context-panel {
  display: flex;
  flex-direction: column;
}

.context-header {
  display: flex;
  min-height: 54px;
  padding: 8px 8px 8px 12px;
  border-bottom: 1px solid var(--n-border-color);
  align-items: center;
  justify-content: space-between;
  gap: 8px;

  > div {
    display: flex;
    min-width: 0;
    flex-direction: column;
  }

  strong {
    font-size: 13px;
    font-weight: 600;
  }

  span {
    color: var(--n-text-color-3);
    font-size: 11px;
  }
}

.context-scroll {
  min-height: 0;
  overflow-y: auto;
  flex: 1;
}

.context-section {
  padding: 14px 12px;
  border-bottom: 1px solid var(--n-border-color);
}

.context-section-title {
  display: flex;
  min-height: 24px;
  margin-bottom: 7px;
  align-items: center;
  justify-content: space-between;
  color: var(--n-text-color-3);
  font-size: 11px;
  font-weight: 600;
}

.project-list,
.context-task-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.project-row {
  display: grid;
  min-height: 34px;
  padding: 5px 6px;
  align-items: center;
  grid-template-columns: 18px minmax(0, 1fr) auto;
  gap: 6px;

  > .svg-icon {
    color: #18a058;
    font-size: 15px;
  }

  span {
    overflow: hidden;
    font-size: 12px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    color: var(--n-text-color-3);
    font-size: 10px;
  }
}

.context-task {
  display: flex;
  width: 100%;
  min-width: 0;
  padding: 8px;
  border: 1px solid transparent;
  border-radius: 5px;
  background: transparent;
  color: var(--n-text-color);
  cursor: pointer;
  flex-direction: column;
  gap: 7px;
  text-align: left;

  &:hover,
  &:focus-visible,
  &.active {
    border-color: var(--n-border-color);
    background: var(--n-color);
    outline: 0;
  }

  > span:first-child {
    overflow: hidden;
    font-size: 12px;
    font-weight: 500;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.context-task-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;

  small {
    overflow: hidden;
    color: var(--n-text-color-3);
    font-size: 10px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.context-empty {
  padding: 22px 8px;
  color: var(--n-text-color-3);
  font-size: 11px;
  text-align: center;
}

.context-facts dl {
  display: grid;
  margin: 0;
  gap: 8px;
}

.context-facts dl > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.context-facts dt,
.context-facts dd {
  margin: 0;
  font-size: 11px;
}

.context-facts dt {
  color: var(--n-text-color-3);
}

.context-facts dd {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.context-rail {
  display: flex;
  padding: 9px 5px;
  align-items: center;
  flex-direction: column;
  gap: 8px;

  > span {
    color: var(--n-text-color-3);
    font-size: 10px;
    font-variant-numeric: tabular-nums;
  }
}

.mobile-context-section + .mobile-context-section {
  margin-top: 24px;
}

.mobile-context-section h3,
.context-detail-body h3 {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
}

.context-detail-header {
  display: flex;
  width: 100%;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  > div {
    display: flex;
    min-width: 0;
    flex-direction: column;
  }

  strong,
  span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    color: var(--n-text-color-3);
    font-size: 11px;
  }
}

.context-detail-body {
  display: flex;
  flex-direction: column;
  gap: 20px;

  section p {
    margin: 0;
    color: var(--n-text-color-2);
    line-height: 1.7;
    white-space: pre-wrap;
  }
}

.context-detail-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.resource-scope-footer-note {
  color: var(--n-text-color-3);
  font-size: 12px;
  line-height: 1.6;
}

.resource-scope-footer-note {
  margin-top: 4px;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 1500px) {
  .panel-resize-handle {
    display: none;
  }

  .client-mode .chat-shell {
    grid-template-columns: 240px minmax(0, 1fr);
  }

  .client-mode .chat-shell.context-is-collapsed {
    grid-template-columns: 240px minmax(0, 1fr);
  }
}

@media (max-width: 1280px) {
  .client-mode .chat-shell,
  .client-mode .chat-shell.context-is-collapsed {
    grid-template-columns: 232px minmax(0, 1fr);
  }

  .client-mode .mobile-context-button {
    display: inline-flex;
  }
}

.client-mode .mobile-context-button {
  display: inline-flex;
}

@media (max-width: 1100px) {
  .chat-shell,
  .chat-shell.context-is-collapsed {
    grid-template-columns: 210px minmax(0, 1fr);
  }

  .context-panel,
  .context-rail {
    display: none;
  }

  .mobile-context-button {
    display: inline-flex;
  }
}

@media (max-width: 900px) {
  .client-mobile-nav {
    display: block;
    padding: 8px 10px 0;
    border-bottom: 1px solid var(--n-border-color);
    background: var(--n-color);
  }

  .client-mobile-nav :deep(.workspace-switcher) {
    min-height: 38px;
    margin-bottom: 5px;
  }

  .client-mobile-nav :deep(.client-nav) {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 4px;
  }

  .client-mobile-nav :deep(.nav-item) {
    min-height: 34px;
    padding: 6px 5px;
    justify-content: center;
    gap: 6px;
    font-size: 12px;
  }

  .conversation-list {
    display: none;
  }

  .mobile-conversation-select {
    display: block;
  }

  .chat-shell {
    grid-template-columns: minmax(0, 1fr);
  }

  // The client layout has a more specific two-column rule for its desktop
  // conversation rail. Once that rail is hidden on narrow screens, make the
  // chat surface occupy the full grid explicitly.
  .client-mode .chat-shell,
  .client-mode .chat-shell.context-is-collapsed {
    grid-template-columns: minmax(0, 1fr);
  }

  .client-mode .chat-main {
    grid-column: 1;
  }
}

@media (max-width: 640px) {
  .conversation-panel {
    height: calc(100dvh - 80px);
    min-height: 540px;
  }

  .panel-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .message-viewport {
    padding: 12px;
  }

  .message-row {
    max-width: 94%;
  }

  .composer-controls {
    align-items: stretch;
    flex-direction: column;
  }

  .client-mode .composer {
    width: calc(100% - 16px);
    margin-bottom: 8px;
    border-radius: 18px;
  }

  .client-mode .composer-controls {
    align-items: center;
    flex-direction: row;
  }

  .client-mode .composer-agent-select {
    width: min(190px, calc(100vw - 120px));
    min-width: 0;
  }

  .composer-trailing {
    gap: 6px;
  }
}
</style>
