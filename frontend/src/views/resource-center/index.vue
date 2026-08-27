<script setup lang="ts">
import { computed, h, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import dayjs from 'dayjs';
import type { DataTableColumns, SelectOption, UploadFileInfo } from 'naive-ui';
import { NButton, NSpace, NTag, useDialog } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import SearchProviderConsole from './search-provider-console.vue';
import { useAuth } from '@/hooks/business/auth';
import { useAuthStore } from '@/store/modules/auth';
import {
  archiveSkillVersion,
  approveSkillPublication,
  cloneSkillVersion,
  createConnector,
  createMemory,
  createModel,
  createSkill,
  createSkillFileEntry,
  createSkillVersion,
  createTool,
  createToolVersion,
  deleteConnector,
  deleteMemory,
  deleteModel,
  deleteSkill,
  deleteSkillFile,
  deleteSkillVersion,
  deleteTool,
  discoverConnector,
  discoverModels,
  fetchAvailableTools,
  fetchBuiltinTools,
  fetchConnectors,
  fetchConnectorDiscoveries,
  fetchConnectorRuntime,
  fetchConnectorTools,
  fetchDatasets,
  fetchKnowledgeBases,
  fetchMemories,
  fetchMcpConnectorUsage,
  fetchModelReferences,
  fetchModels,
  fetchSkillVersions,
  fetchSkillFiles,
  fetchSkillFile,
  fetchPendingSkillPublications,
  fetchSkillPublicationDetail,
  fetchSkillPublicationStatus,
  fetchSkills,
  fetchSkillDependencyInstall,
  fetchTools,
  importMcpServer,
  installSkillDependencies,
  previewMcpServersImport,
  publishMcpWizard,
  publishSkillVersion,
  putSkillFile,
  rejectSkillPublication,
  submitSkillPublication,
  downloadSkillArchive,
  uploadSkillArchive,
  uploadSkillFile,
  withdrawSkillPublication,
  reviewMemory,
  testConnector,
  testConnectorDraft,
  validateMcpWizard,
  testModel,
  testModelConfig,
  testTool,
  updateConnector,
  updateMemory,
  updateModel,
  updateSkill,
  updateSkillStatus,
  updateToolStatus
} from '@/service/api';
import type {
  ConnectorView,
  BuiltinToolDescriptor,
  DatasetView,
  KnowledgeBaseView,
  McpConnectionTestView,
  McpConnectorUsageView,
  McpDiscoveryView,
  McpImportEntryView,
  McpRuntimeMountView,
  McpRuntimeOverviewView,
  McpUsageDetailView,
  MemoryView,
  ModelOptionView,
  ModelReferenceView,
  ModelView,
  SaveConnectorPayload,
  SaveMemoryPayload,
  SaveModelPayload,
  SaveSkillPayload,
  SaveToolPayload,
  SkillDependencyInstallView,
  SkillVersionView,
  SkillFileView,
  SkillPublicationFileNode,
  SkillPublicationView,
  SkillView,
  ToolOnlineTestView,
  ToolView
} from '@/service/api';
import {
  defaultSkillScope,
  getAuthorizedResourceTabs,
  hasResourcePermission,
  normalizeMemoryScope,
  normalizeSkillScope,
  resolveResourceTab,
  type ResourceAction,
  type ResourceTab
} from './permissions';
import { buildSafeMcpImportDocument } from './mcp-import';
import {
  circuitStateText,
  formatMcpBytes,
  mountStatusText,
  mountStatusType,
  runtimeHealthText,
  runtimeHealthType,
  usageStatusText,
  usageStatusType
} from './mcp-runtime';
import { buildSqlToolPolicy, parseSqlToolPolicy, toSqlDiagnosticTable, validateSqlToolDraft } from './sql-tool';

type ConnectorAuthType = 'none' | 'bearer' | 'header';
type ToolParameterType = 'string' | 'number' | 'integer' | 'boolean' | 'array';
type EditableToolType = SaveToolPayload['toolType'];
type SkillInputMode = 'form' | 'zip';

interface ToolParameterDraft {
  name: string;
  label: string;
  type: ToolParameterType;
  required: boolean;
  description: string;
}

interface ToolTestResultRow {
  path: string;
  value: string;
}

interface SkillParameterDraft {
  name: string;
  label: string;
  type: ToolParameterType;
  required: boolean;
  description: string;
}

interface SkillDocumentDraft {
  content: string;
  summary: string;
  tags: string[];
  compatibleAgentTypes: string[];
  requiredToolKeys: string[];
  parameters: SkillParameterDraft[];
  requiredToolIds: string[];
  requiredKnowledgeBaseIds: string[];
  maxContextBytes: number | null;
  workspaceAccess: 'none' | 'read_only' | 'read_write';
  pythonDependencies: string[];
  nodeDependencies: string[];
}

interface ZipEntry {
  compressionMethod: number;
  compressedSize: number;
  uncompressedSize: number;
  localHeaderOffset: number;
}

const authStore = useAuthStore();
const { hasAuth } = useAuth();
const dialog = useDialog();
const route = useRoute();
const activeTab = ref<ResourceTab>('model');
const pageLoading = ref(false);

const authorizedTabs = computed(() => getAuthorizedResourceTabs(hasAuth));
const hasResourceTabs = computed(() => authorizedTabs.value.length > 0);
const isPlatformAdmin = computed(() =>
  authStore.userInfo.roles.some(role => ['platform_admin', 'superadmin'].includes(role))
);
const canManageSharedSkillScopes = computed(
  () => isPlatformAdmin.value && hasResourcePermission(hasAuth, 'skill', 'create')
);
const canManageSharedMemory = computed(
  () =>
    authStore.userInfo.roles.some(role => ['approval_user', 'platform_admin', 'superadmin'].includes(role)) &&
    hasResourcePermission(hasAuth, 'memory', 'operate')
);

function canResource(tab: ResourceTab, action: ResourceAction) {
  return hasResourcePermission(hasAuth, tab, action);
}

function ensureResourcePermission(tab: ResourceTab, action: ResourceAction) {
  if (canResource(tab, action)) return true;
  window.$message?.warning('当前账号没有此资源操作权限');
  return false;
}

function ensureConnectorManageable(row: ConnectorView, action: ResourceAction) {
  if (!ensureResourcePermission('connector', action)) return false;
  if (row.manageable) return true;
  window.$message?.warning(
    row.scope === 'global' ? '只有平台管理员可以维护企业共享连接器' : '只能维护自己的个人连接器'
  );
  return false;
}

function canAccessMemoryScope(row: Pick<MemoryView, 'scopeType' | 'scopeId'>) {
  return canManageSharedMemory.value || (row.scopeType === 'user' && row.scopeId === authStore.userInfo.userId);
}

const models = ref<ModelView[]>([]);
const modelVisible = ref(false);
const modelSubmitting = ref(false);
const editingModel = ref<ModelView | null>(null);
const modelOptions = ref<ModelOptionView[]>([]);
const modelReferences = ref<ModelReferenceView[]>([]);
const modelReferenceVisible = ref(false);
const modelForm = reactive({
  modelKey: '',
  displayName: '',
  providerPreset: 'custom',
  providerType: 'openai-compatible' as SaveModelPayload['providerType'],
  modelName: '',
  modelType: 'chat' as ModelView['modelType'],
  endpointUrl: '',
  apiKey: '',
  contextSize: 128000,
  maxOutputTokens: 8192,
  temperature: 0.7 as number | null,
  topP: 1 as number | null,
  frequencyPenalty: 0 as number | null,
  presencePenalty: 0 as number | null,
  thinkingBudget: null as number | null,
  reasoningEffort: '' as '' | 'low' | 'medium' | 'high',
  parallelToolCalls: true,
  nativeStructuredOutput: false,
  nativeStructuredOutputWithTools: false,
  endpointPath: '',
  streaming: true,
  toolCalling: true,
  vision: false,
  jsonSchema: true,
  reasoning: false,
  inputModalities: ['text'] as string[],
  outputModalities: ['text'] as string[],
  status: 'active' as SaveModelPayload['status']
});

const connectors = ref<ConnectorView[]>([]);
const connectorLoadError = ref<string | null>(null);
const connectorScopeFilter = ref<'all' | ConnectorView['scope']>('all');
const connectorVisible = ref(false);
const connectorSubmitting = ref(false);
const connectorDraftTesting = ref(false);
const connectorDraftTestResult = ref<McpConnectionTestView | null>(null);
const connectorDraftTestError = ref<string | null>(null);
const mcpWizardStep = ref(1);
const mcpWizardMessage = ref<string | null>(null);
const editingConnector = ref<ConnectorView | null>(null);
const lastDiscovery = ref<McpDiscoveryView | null>(null);
const connectorDiscoveringId = ref<string | null>(null);
const discoveryVisible = ref(false);
const discoveryLoading = ref(false);
const discoveryError = ref<string | null>(null);
const discoveryConnector = ref<ConnectorView | null>(null);
const discoveries = ref<McpDiscoveryView[]>([]);
const connectorTestVisible = ref(false);
const connectorTestingId = ref<string | null>(null);
const connectorTestTarget = ref<ConnectorView | null>(null);
const connectorTestResult = ref<McpConnectionTestView | null>(null);
const connectorTestError = ref<string | null>(null);
const runtimeVisible = ref(false);
const runtimeLoading = ref(false);
const runtimeError = ref<string | null>(null);
const runtimeConnector = ref<ConnectorView | null>(null);
const runtimeOverview = ref<McpRuntimeOverviewView | null>(null);
const runtimeUsage = ref<McpConnectorUsageView | null>(null);
const runtimeUsageError = ref<string | null>(null);
const mcpImportVisible = ref(false);
const mcpImportParsing = ref(false);
const mcpImportSubmitting = ref(false);
const mcpImportText = ref('');
const mcpImportEntries = ref<McpImportEntryView[]>([]);
const mcpImportSourceKey = ref<string | null>(null);
const mcpImportConnectorKey = ref('');
const mcpImportName = ref('');
const mcpImportScope = ref<ConnectorView['scope']>('personal');
const mcpImportCredentialName = ref('');
const mcpImportStatus = ref<ConnectorView['status']>('active');
const connectorForm = reactive({
  connectorKey: '',
  name: '',
  providerType: 'mcp' as ConnectorView['providerType'],
  scope: 'personal' as ConnectorView['scope'],
  endpointUrl: '',
  authType: 'none' as ConnectorAuthType,
  authHeader: 'X-API-Key',
  credentialName: '',
  transport: 'streamable_http' as 'streamable_http' | 'sse',
  namespace: 'default',
  connectTimeoutMs: 5000,
  requestTimeoutMs: 15000,
  searchEngine: 'custom' as 'brave' | 'bing' | 'serper' | 'searxng' | 'custom',
  searchRequestMethod: 'GET' as 'GET' | 'POST',
  searchQueryParam: 'q',
  searchCountParam: 'count',
  searchMaxResults: 10,
  searchRateLimitPerMinute: 60,
  searchFailureThreshold: 3,
  searchCooldownSeconds: 60,
  status: 'active' as ConnectorView['status']
});

const tools = ref<ToolView[]>([]);
const toolLoadError = ref<string | null>(null);
const builtinTools = ref<BuiltinToolDescriptor[]>([]);
const sqlDatasets = ref<DatasetView[]>([]);
const toolVisible = ref(false);
const toolSubmitting = ref(false);
const versioningTool = ref<ToolView | null>(null);
const toolStatusUpdatingId = ref<string | null>(null);
const toolTestVisible = ref(false);
const toolTestSubmitting = ref(false);
const testingTool = ref<ToolView | null>(null);
const toolTestArguments = ref('{}');
const toolTestJsonError = ref<string | null>(null);
const toolTestConfirmRisk = ref(false);
const toolTestResult = ref<ToolOnlineTestView | null>(null);
const toolTestRequestError = ref<string | null>(null);
const sqlToolTestArguments = ref<Record<string, string | number | boolean | string[]>>({});
const mcpToolsVisible = ref(false);
const mcpToolsLoading = ref(false);
const mcpToolsError = ref<string | null>(null);
const mcpToolsConnector = ref<ConnectorView | null>(null);
const mcpConnectorTools = ref<ToolView[]>([]);
const mcpSelectedToolIds = ref<string[]>([]);
const mcpBatchSubmitting = ref(false);
const mcpWorkbenchUsage = ref<McpConnectorUsageView | null>(null);
const mcpWorkbenchUsageError = ref<string | null>(null);
const toolForm = reactive({
  toolKey: '',
  name: '',
  description: '',
  connectorId: null as string | null,
  toolType: 'api' as EditableToolType,
  riskLevel: 'R1' as ToolView['riskLevel'],
  externalName: '',
  status: 'active' as ToolView['status'],
  parameters: [] as ToolParameterDraft[],
  handlerKey: '',
  datasetId: null as string | null,
  queryPurpose: '',
  sqlTemplate: '',
  method: 'GET' as 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  path: '/',
  readOnly: true,
  timeoutMs: 15000,
  retryCount: 0,
  maxOutputBytes: 1048576,
  templateKey: 'python',
  workspaceAccess: 'read_write' as 'read_only' | 'read_write',
  workspacePath: '.',
  memoryMb: 512,
  cpuMillis: 1000,
  pidsLimit: 128,
  priority: 0
});

const skills = ref<SkillView[]>([]);
const skillDependencyTools = ref<ToolView[]>([]);
const knowledgeBases = ref<KnowledgeBaseView[]>([]);
const skillVisible = ref(false);
const skillSubmitting = ref(false);
const skillImporting = ref(false);
const skillImportFile = ref<File | null>(null);
const skillUploadKey = ref(0);
const editingSkill = ref<SkillView | null>(null);
const skillForm = reactive({
  skillKey: '',
  name: '',
  description: '',
  scopeType: 'user' as SkillView['scopeType'],
  scopeId: authStore.userInfo.userId,
  inputMode: 'form' as SkillInputMode,
  packageName: '',
  content: '# Skill\n',
  summary: '',
  tags: [] as string[],
  compatibleAgentTypes: [] as string[],
  requiredToolKeys: [] as string[],
  parameters: [] as SkillParameterDraft[],
  requiredToolIds: [] as string[],
  requiredKnowledgeBaseIds: [] as string[],
  maxContextBytes: 32768 as number | null,
  workspaceAccess: 'none' as SkillDocumentDraft['workspaceAccess'],
  pythonDependencies: [] as string[],
  nodeDependencies: [] as string[]
});
const activeSkill = ref<SkillView | null>(null);
const skillVersions = ref<SkillVersionView[]>([]);
const skillFiles = ref<SkillFileView[]>([]);
const skillFilesLoading = ref(false);
const skillFilesError = ref('');
const skillDependencyInstall = ref<SkillDependencyInstallView | null>(null);
const skillDependencyLoading = ref(false);
const skillDependencyInstalling = ref(false);
const activeSkillVersionId = ref<string | null>(null);
const activeSkillVersion = computed(
  () => skillVersions.value.find(item => item.id === activeSkillVersionId.value) || null
);
const selectedSkillFile = ref<SkillFileView | null>(null);
const skillFileContent = ref('');
const skillFileBaseline = ref('');
const skillFileSaving = ref(false);
const skillFileCreating = ref(false);
const skillFileCreateVisible = ref(false);
const skillFileUploadKey = ref(0);
const skillFileUploading = ref(false);
const skillFileEntryForm = reactive({ path: '', kind: 'file' as 'file' | 'directory' });
const skillFileDirty = computed(() =>
  Boolean(
    selectedSkillFile.value &&
    !selectedSkillFile.value.binary &&
    selectedSkillFile.value.fileKind === 'file' &&
    skillFileContent.value !== skillFileBaseline.value
  )
);
const canEditActiveSkillFiles = computed(() =>
  Boolean(activeSkillVersion.value?.status === 'draft' && canResource('skill', 'edit'))
);
const skillBundleUploading = ref(false);
const versionVisible = ref(false);
const versionSubmitting = ref(false);
const skillVersionForm = reactive<SkillDocumentDraft>(newSkillDocument());
const skillPublicationStatuses = ref<Record<string, SkillPublicationView>>({});
const skillPublicationUpdatingId = ref<string | null>(null);
const skillLifecycleUpdatingId = ref<string | null>(null);
const skillVersionUpdatingId = ref<string | null>(null);
const pendingSkillPublications = ref<SkillPublicationView[]>([]);
const skillReviewVisible = ref(false);
const skillReviewLoading = ref(false);
const skillReviewDetail = ref<SkillPublicationView | null>(null);
const skillReviewDetailLoading = ref(false);
const skillReviewSubmitting = ref(false);
const skillRejectVisible = ref(false);
const skillRejectComment = ref('');
const canReviewSkillPublications = computed(() => isPlatformAdmin.value);
const skillReviewFiles = computed(() => flattenPublicationFiles(skillReviewDetail.value?.file_tree || []));

const memories = ref<MemoryView[]>([]);
const memoryLoading = ref(false);
const memoryScopeType = ref<MemoryView['scopeType']>('user');
const memoryScopeId = ref(authStore.userInfo.userId);
const memorySearch = ref('');
const memoryVisible = ref(false);
const memorySubmitting = ref(false);
const editingMemory = ref<MemoryView | null>(null);
const memoryForm = reactive({
  memoryKey: '',
  memoryType: 'fact',
  content: '',
  sourceType: 'manual',
  sourceId: null as number | null,
  confidence: 1,
  sensitiveLevel: 'internal',
  expiresAt: null as string | null,
  title: '',
  category: '',
  tags: [] as string[],
  language: 'zh-CN',
  sourceLabel: '',
  sourceUrl: '',
  retentionNote: '',
  verified: false
});

const modelProviderPresets = [
  {
    label: 'OpenAI',
    value: 'openai',
    providerType: 'openai',
    endpoint: 'https://api.openai.com/v1',
    credential: 'OPENAI_API_KEY'
  },
  {
    label: 'DeepSeek',
    value: 'deepseek',
    providerType: 'openai-compatible',
    endpoint: 'https://api.deepseek.com/v1',
    credential: 'DEEPSEEK_API_KEY'
  },
  {
    label: '阿里云百炼 / Qwen',
    value: 'qwen',
    providerType: 'openai-compatible',
    endpoint: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    credential: 'DASHSCOPE_API_KEY'
  },
  {
    label: 'Moonshot / Kimi',
    value: 'moonshot',
    providerType: 'openai-compatible',
    endpoint: 'https://api.moonshot.cn/v1',
    credential: 'MOONSHOT_API_KEY'
  },
  {
    label: '硅基流动',
    value: 'siliconflow',
    providerType: 'openai-compatible',
    endpoint: 'https://api.siliconflow.cn/v1',
    credential: 'SILICONFLOW_API_KEY'
  },
  {
    label: '火山引擎方舟',
    value: 'volcengine',
    providerType: 'openai-compatible',
    endpoint: 'https://ark.cn-beijing.volces.com/api/v3',
    credential: 'ARK_API_KEY'
  },
  {
    label: '其他 OpenAI 兼容服务',
    value: 'custom',
    providerType: 'openai-compatible',
    endpoint: '',
    credential: 'NHS_MODEL_API_KEY'
  }
] as const;
const providerOptions: SelectOption[] = modelProviderPresets.map(item => ({ label: item.label, value: item.value }));

function detectProviderPreset(providerType?: string, endpointUrl?: string | null) {
  if (providerType === 'openai') return 'openai';
  const endpoint = (endpointUrl || '').replace(/\/$/, '');
  return (
    modelProviderPresets.find(item => item.value !== 'openai' && item.value !== 'custom' && item.endpoint === endpoint)
      ?.value || 'custom'
  );
}
const modelTypeOptions: SelectOption[] = [
  { label: '对话模型', value: 'chat' },
  { label: '向量模型', value: 'embedding' },
  { label: '多模态模型', value: 'multimodal' },
  { label: '重排模型', value: 'rerank' }
];
const supportsGenerationOptions = computed(
  () => modelForm.modelType === 'chat' || modelForm.modelType === 'multimodal'
);
const modalityOptions: SelectOption[] = [
  { label: '文本', value: 'text' },
  { label: '图片', value: 'image' },
  { label: '音频', value: 'audio' },
  { label: '视频', value: 'video' }
];
const connectorTypeOptions: SelectOption[] = [
  { label: 'MCP 服务', value: 'mcp' },
  { label: 'API 服务', value: 'api' },
  { label: '搜索服务', value: 'search' }
];
const authTypeOptions: SelectOption[] = [
  { label: '无需鉴权', value: 'none' },
  { label: 'Bearer Token', value: 'bearer' },
  { label: '自定义请求头', value: 'header' }
];
const searchEngineOptions: SelectOption[] = [
  { label: 'Brave Search API', value: 'brave' },
  { label: 'Bing Web Search API', value: 'bing' },
  { label: 'Google Serper API', value: 'serper' },
  { label: 'SearXNG（私有部署）', value: 'searxng' },
  { label: '自定义 JSON 搜索 API', value: 'custom' }
];
const connectorScopeOptions = computed<SelectOption[]>(() => [
  { label: '个人私有', value: 'personal' },
  { label: '企业共享', value: 'global', disabled: !isPlatformAdmin.value }
]);
const mcpImportEntryOptions = computed<SelectOption[]>(() =>
  mcpImportEntries.value.map(entry => ({
    label: entry.sourceKey + (entry.importable ? '' : '（不可导入）'),
    value: entry.sourceKey,
    disabled: !entry.importable
  }))
);
const selectedMcpImportEntry = computed(
  () => mcpImportEntries.value.find(entry => entry.sourceKey === mcpImportSourceKey.value) || null
);
const toolTypeOptions: SelectOption[] = [
  { label: '内置处理器', value: 'builtin' },
  { label: 'API', value: 'api' },
  { label: 'Search', value: 'search' },
  { label: 'SQL', value: 'sql' },
  { label: 'Sandbox', value: 'sandbox' }
];
const builtinToolOptions = computed<SelectOption[]>(() =>
  builtinTools.value.map(item => ({
    label: `${item.name} · ${item.description}${item.execution === 'local' ? '' : '（尚未实现）'}`,
    value: item.name,
    disabled: item.execution !== 'local'
  }))
);
const parameterTypeOptions: SelectOption[] = [
  { label: '文本', value: 'string' },
  { label: '数字', value: 'number' },
  { label: '整数', value: 'integer' },
  { label: '开关', value: 'boolean' },
  { label: '文本列表', value: 'array' }
];
const agentTypeOptions: SelectOption[] = [
  { label: '通用助手', value: 'assistant' },
  { label: '知识问答', value: 'knowledge' },
  { label: '数据分析', value: 'data' },
  { label: '编码开发', value: 'coding' },
  { label: '主管智能体', value: 'supervisor' }
];
const scopeOptions = computed<SelectOption[]>(() =>
  canManageSharedMemory.value
    ? [
        { label: '个人记忆', value: 'user' },
        { label: '项目记忆', value: 'project' },
        { label: '任务记忆', value: 'task' }
      ]
    : [{ label: '个人记忆', value: 'user' }]
);
const skillScopeOptions = computed<SelectOption[]>(() => {
  if (canManageSharedSkillScopes.value) {
    return [
      { label: '系统共享', value: 'system' },
      { label: '指定项目', value: 'project' },
      { label: '当前用户', value: 'user' }
    ];
  }
  return [{ label: '当前用户', value: 'user' }];
});
const memoryTypeOptions: SelectOption[] = [
  { label: '事实', value: 'fact' },
  { label: '偏好', value: 'preference' },
  { label: '摘要', value: 'summary' },
  { label: '反馈', value: 'feedback' },
  { label: '候选记忆', value: 'candidate' }
];
const memorySourceOptions: SelectOption[] = [
  { label: '人工录入', value: 'manual' },
  { label: '对话', value: 'conversation' },
  { label: '任务', value: 'task' },
  { label: '产物', value: 'artifact' }
];

const discoveredModelOptions = computed<SelectOption[]>(() =>
  modelOptions.value.map(item => ({ label: item.displayName + ' (' + item.modelName + ')', value: item.modelName }))
);
const connectorOptions = computed<SelectOption[]>(() =>
  connectors.value
    .filter(
      item =>
        (toolForm.toolType === 'api' || toolForm.toolType === 'search') &&
        item.providerType === toolForm.toolType &&
        item.status === 'active'
    )
    .map(item => ({ label: item.name + ' (' + item.endpointUrl + ')', value: item.id }))
);
const sqlDatasetOptions = computed<SelectOption[]>(() =>
  sqlDatasets.value
    .filter(item => item.status === 'active')
    .map(item => ({ label: `${item.name} (${item.datasetKey})`, value: item.id }))
);
const toolTestIsHighRisk = computed(() =>
  Boolean(testingTool.value && ['R2', 'R3'].includes(testingTool.value.riskLevel))
);
const toolTestUsesStructuredForm = computed(() =>
  Boolean(
    testingTool.value &&
    (testingTool.value.toolType === 'sql' ||
      (testingTool.value.toolType === 'mcp' && supportsStructuredToolSchema(testingTool.value.parameterSchema)))
  )
);
const testingStructuredParameters = computed(() =>
  toolTestUsesStructuredForm.value && testingTool.value
    ? schemaParameters(objectValue(testingTool.value.parameterSchema))
    : []
);
const toolTestResultRows = computed(() => flattenToolTestData(toolTestResult.value?.data));
const toolTestResultJson = computed(() => JSON.stringify(toolTestResult.value?.data ?? null, null, 2));
const sqlToolTestTable = computed(() => toSqlDiagnosticTable(toolTestResult.value?.data));
const sqlToolTestColumns = computed<DataTableColumns<Record<string, unknown>>>(() =>
  (sqlToolTestTable.value?.columns || []).map(column => ({
    title: column.title,
    key: column.key,
    minWidth: 140,
    ellipsis: { tooltip: true },
    render: row => formatDiagnosticCell(row[column.key])
  }))
);
const skillToolKeyOptions = computed<SelectOption[]>(() =>
  skillDependencyTools.value.map(item => ({ label: item.name + ' (' + item.toolKey + ')', value: item.toolKey }))
);
const skillToolIdOptions = computed<SelectOption[]>(() =>
  skillDependencyTools.value
    .filter(item => item.status === 'active')
    .map(item => ({ label: item.name + ' v' + item.versionNo, value: item.id }))
);
const knowledgeBaseOptions = computed<SelectOption[]>(() =>
  knowledgeBases.value
    .filter(item => item.status === 'active')
    .map(item => ({ label: item.name + ' (' + item.knowledgeKey + ')', value: item.id }))
);
const mcpSelectedTools = computed(() => {
  const selected = new Set(mcpSelectedToolIds.value);
  return mcpConnectorTools.value.filter(tool => selected.has(tool.id));
});
const mcpPublishedCount = computed(
  () => mcpConnectorTools.value.filter(tool => tool.status === 'active' && tool.available).length
);
const mcpUnavailableCount = computed(
  () => mcpConnectorTools.value.filter(tool => !tool.available || tool.status === 'deprecated').length
);
const mcpUnpublishedCount = computed(
  () => mcpConnectorTools.value.filter(tool => tool.available && tool.status === 'disabled').length
);

function newSkillDocument(): SkillDocumentDraft {
  return {
    content: '# Skill\n',
    summary: '',
    tags: [],
    compatibleAgentTypes: [],
    requiredToolKeys: [],
    parameters: [],
    requiredToolIds: [],
    requiredKnowledgeBaseIds: [],
    maxContextBytes: 32768,
    workspaceAccess: 'none',
    pythonDependencies: [],
    nodeDependencies: []
  };
}

function objectValue(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
}

function requestErrorMessage(error: unknown) {
  const responseData = objectValue(objectValue(error).response).data;
  const backendData = objectValue(responseData);
  const backendMessage = textValue(backendData, 'message') || textValue(backendData, 'msg');
  if (backendMessage) return backendMessage;
  return error instanceof Error ? error.message : String(error || '请求失败');
}

function schemaExampleValue(schema: Record<string, unknown>, depth = 0): unknown {
  if ('default' in schema) return schema.default;
  if ('example' in schema) return schema.example;
  if (Array.isArray(schema.enum) && schema.enum.length > 0) return schema.enum[0];
  if (depth >= 4) return null;
  const type = textValue(schema, 'type');
  if (type === 'object' || schema.properties) {
    return Object.fromEntries(
      Object.entries(objectValue(schema.properties)).map(([key, value]) => [
        key,
        schemaExampleValue(objectValue(value), depth + 1)
      ])
    );
  }
  if (type === 'array') return [];
  if (type === 'boolean') return false;
  if (type === 'number' || type === 'integer') return 0;
  return '';
}

function supportsStructuredToolSchema(value: unknown) {
  const schema = objectValue(value);
  const properties = Object.values(objectValue(schema.properties));
  if (properties.length > 64) return false;
  return properties.every(raw => {
    const property = objectValue(raw);
    const type = textValue(property, 'type', 'string');
    if (!['string', 'number', 'integer', 'boolean', 'array'].includes(type)) return false;
    if (type !== 'array') return true;
    const itemType = textValue(objectValue(property.items), 'type', 'string');
    return itemType === 'string';
  });
}

function toolTestEnumOptions(name: string): SelectOption[] {
  const schema = objectValue(testingTool.value?.parameterSchema);
  const property = objectValue(objectValue(schema.properties)[name]);
  if (!Array.isArray(property.enum)) return [];
  return property.enum
    .filter(value => typeof value === 'string' || typeof value === 'number')
    .map(value => ({ label: String(value), value }));
}

function flattenToolTestData(
  value: unknown,
  path = '结果',
  rows: ToolTestResultRow[] = [],
  depth = 0
): ToolTestResultRow[] {
  if (rows.length >= 200) return rows;
  if (value === null || value === undefined) {
    rows.push({ path, value: '空' });
    return rows;
  }
  if (typeof value !== 'object') {
    const display = typeof value === 'boolean' ? (value ? '是' : '否') : String(value);
    rows.push({ path, value: display.length > 4000 ? `${display.slice(0, 4000)}…` : display });
    return rows;
  }
  if (depth >= 6) {
    rows.push({ path, value: Array.isArray(value) ? `列表（${value.length} 项）` : '嵌套对象' });
    return rows;
  }
  const entries = Array.isArray(value)
    ? value.map((item, index) => [String(index + 1), item] as const)
    : Object.entries(value);
  if (entries.length === 0) {
    rows.push({ path, value: Array.isArray(value) ? '空列表' : '空对象' });
    return rows;
  }
  for (const [key, item] of entries) {
    flattenToolTestData(item, path === '结果' ? key : `${path}.${key}`, rows, depth + 1);
    if (rows.length >= 200) break;
  }
  return rows;
}

function textValue(source: Record<string, unknown>, key: string, fallback = '') {
  return typeof source[key] === 'string' ? String(source[key]) : fallback;
}

function numberValue(source: Record<string, unknown>, key: string, fallback: number | null) {
  return typeof source[key] === 'number' && Number.isFinite(source[key]) ? Number(source[key]) : fallback;
}

function booleanValue(source: Record<string, unknown>, key: string, fallback: boolean) {
  return typeof source[key] === 'boolean' ? Boolean(source[key]) : fallback;
}

function textList(value: unknown) {
  return Array.isArray(value) ? value.filter(item => typeof item === 'string').map(String) : [];
}

function credentialName(value: string | null | undefined) {
  return value?.startsWith('env:') ? value.slice(4) : '';
}

function idList(value: unknown) {
  return Array.isArray(value)
    ? value.filter(item => typeof item === 'string' || typeof item === 'number').map(String)
    : [];
}

function formatTime(value: string | null) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-';
}

function statusType(status: string) {
  if (['active', 'published', 'approved', 'success', 'succeeded'].includes(status)) return 'success';
  if (['pending', 'draft'].includes(status)) return 'warning';
  if (['rejected', 'failed', 'deprecated'].includes(status)) return 'error';
  return 'default';
}

function skillStatusText(row: SkillView) {
  return row.metadata?.materializationRequired === true
    ? '待物化'
    : (
        {
          active: '启用',
          draft: '草稿',
          archived: '已归档',
          disabled: '停用'
        } as Record<string, string>
      )[row.status] || row.status;
}

function skillStatusType(row: SkillView) {
  return row.metadata?.materializationRequired === true ? 'warning' : statusType(row.status);
}

function skillPublicationStatusText(status?: SkillPublicationView['publication_status']) {
  return (
    (
      {
        UNPUBLISHED: '未提交',
        PENDING: '待审核',
        PUBLISHED: '已公开',
        REJECTED: '未通过',
        WITHDRAWN: '已撤回'
      } as Record<string, string>
    )[status || 'UNPUBLISHED'] ||
    status ||
    '未提交'
  );
}

function skillPublicationStatusType(status?: SkillPublicationView['publication_status']) {
  if (status === 'PUBLISHED') return 'success';
  if (status === 'PENDING') return 'warning';
  if (status === 'REJECTED') return 'error';
  return 'default';
}

function flattenPublicationFiles(
  nodes: SkillPublicationFileNode[],
  depth = 0
): Array<SkillPublicationFileNode & { depth: number }> {
  return nodes.flatMap(node => [{ ...node, depth }, ...flattenPublicationFiles(node.children || [], depth + 1)]);
}

function modelStatusText(status: string) {
  return status === 'active' ? '启用' : '停用';
}

function connectorStatusText(status: string) {
  return { active: '启用', disabled: '停用' }[status] || status;
}

function toolStatusText(status: string) {
  return { active: '启用', disabled: '停用', deprecated: '已废弃' }[status] || status;
}

function mcpToolConnectorActive(tool: ToolView) {
  if (tool.toolType !== 'mcp') return true;
  return connectors.value.some(connector => connector.id === tool.connectorId && connector.status === 'active');
}

const RESOURCE_PAGE_SIZE = 10;

function resourcePagination(items: readonly unknown[]) {
  if (items.length <= RESOURCE_PAGE_SIZE) return false;
  return {
    pageSize: RESOURCE_PAGE_SIZE,
    showSizePicker: true,
    pageSizes: [10, 20, 50, 100]
  };
}

async function loadModels() {
  const { data, error } = await fetchModels(undefined, true);
  if (!error) models.value = data;
}

async function loadConnectors(useScopeFilter = activeTab.value === 'connector') {
  const scope = useScopeFilter && connectorScopeFilter.value !== 'all' ? connectorScopeFilter.value : undefined;
  connectorLoadError.value = null;
  const { data, error } = await fetchConnectors(true, scope);
  if (error) connectorLoadError.value = requestErrorMessage(error);
  else connectors.value = data;
}

async function loadTools() {
  toolLoadError.value = null;
  const [toolResult, builtinResult] = await Promise.all([fetchTools(true), fetchBuiltinTools()]);
  if (!toolResult.error) tools.value = toolResult.data;
  if (!builtinResult.error) builtinTools.value = builtinResult.data;
  const error = toolResult.error || builtinResult.error;
  if (error) toolLoadError.value = requestErrorMessage(error);
}

async function loadSqlDatasets() {
  const { data, error } = await fetchDatasets();
  if (!error) sqlDatasets.value = data;
}

async function loadSkills() {
  const { data, error } = await fetchSkills(true);
  if (error) return;
  skills.value = data;
  await Promise.all([loadSkillPublicationStatuses(), loadPendingSkillPublications()]);
}

async function loadSkillPublicationStatuses() {
  const owned = skills.value.filter(row => row.scopeType === 'user' && row.ownerId === authStore.userInfo.userId);
  const results = await Promise.all(
    owned.map(async row => ({
      id: row.id,
      result: await fetchSkillPublicationStatus(row.id)
    }))
  );
  const statuses: Record<string, SkillPublicationView> = {};
  results.forEach(({ id, result }) => {
    if (!result.error) statuses[id] = result.data;
  });
  skillPublicationStatuses.value = statuses;
}

async function loadPendingSkillPublications() {
  if (!canReviewSkillPublications.value) {
    pendingSkillPublications.value = [];
    return;
  }
  const { data, error } = await fetchPendingSkillPublications();
  if (!error) pendingSkillPublications.value = data;
}

async function loadSkillDependencyTools() {
  const { data, error } = await fetchAvailableTools();
  if (!error) skillDependencyTools.value = data;
}

async function loadKnowledgeBases() {
  const { data, error } = await fetchKnowledgeBases(undefined, true);
  if (!error) knowledgeBases.value = data;
}

async function loadAuthorizedTab(tab: ResourceTab = activeTab.value) {
  if (!authorizedTabs.value.includes(tab)) return;
  pageLoading.value = true;
  try {
    if (tab === 'model') await loadModels();
    if (tab === 'connector') await loadConnectors();
    if (tab === 'tool') {
      const dependencies = authorizedTabs.value.includes('connector')
        ? [loadTools(), loadConnectors(false), loadSqlDatasets()]
        : [loadTools(), loadSqlDatasets()];
      await Promise.all(dependencies);
    }
    if (tab === 'skill') await Promise.all([loadSkills(), loadSkillDependencyTools(), loadKnowledgeBases()]);
    if (tab === 'memory') await loadMemories();
  } finally {
    pageLoading.value = false;
  }
}

async function loadAll() {
  await loadAuthorizedTab(activeTab.value);
}

function openModel(row?: ModelView) {
  if (!ensureResourcePermission('model', row ? 'edit' : 'create')) return;
  editingModel.value = row || null;
  modelOptions.value = [];
  const reasoning = objectValue(row?.reasoningConfig);
  const capabilities = objectValue(row?.capabilities);
  const provider = row?.providerType === 'openai' ? 'openai' : 'openai-compatible';
  const effort = textValue(reasoning, 'reasoningEffort');
  Object.assign(modelForm, {
    modelKey: row?.modelKey || '',
    displayName: row?.displayName || '',
    providerPreset: detectProviderPreset(row?.providerType, row?.endpointUrl),
    providerType: provider,
    modelName: row?.modelName || '',
    modelType: row?.modelType || 'chat',
    endpointUrl: row?.endpointUrl || (provider === 'openai' ? 'https://api.openai.com/v1' : ''),
    apiKey: '',
    contextSize: row?.contextSize || 128000,
    maxOutputTokens: row?.maxOutputTokens || 8192,
    temperature: numberValue(reasoning, 'temperature', 0.7),
    topP: numberValue(reasoning, 'topP', 1),
    frequencyPenalty: numberValue(reasoning, 'frequencyPenalty', 0),
    presencePenalty: numberValue(reasoning, 'presencePenalty', 0),
    thinkingBudget: numberValue(reasoning, 'thinkingBudget', null),
    reasoningEffort: ['low', 'medium', 'high'].includes(effort) ? effort : '',
    parallelToolCalls: booleanValue(reasoning, 'parallelToolCalls', true),
    nativeStructuredOutput: booleanValue(reasoning, 'nativeStructuredOutput', false),
    nativeStructuredOutputWithTools: booleanValue(reasoning, 'nativeStructuredOutputWithTools', false),
    endpointPath: textValue(reasoning, 'endpointPath'),
    streaming: booleanValue(capabilities, 'streaming', true),
    toolCalling: booleanValue(capabilities, 'toolCalling', true),
    vision: booleanValue(capabilities, 'vision', false),
    jsonSchema: booleanValue(capabilities, 'jsonSchema', true),
    reasoning: booleanValue(capabilities, 'reasoning', false),
    inputModalities: textList(capabilities.inputModalities).length ? textList(capabilities.inputModalities) : ['text'],
    outputModalities: textList(capabilities.outputModalities).length
      ? textList(capabilities.outputModalities)
      : ['text'],
    status: row?.status === 'disabled' ? 'disabled' : 'active'
  });
  modelVisible.value = true;
}

function modelPayload() {
  if (
    !modelForm.displayName.trim() ||
    !modelForm.modelName.trim() ||
    (!editingModel.value && !modelForm.apiKey.trim())
  ) {
    window.$message?.warning(
      editingModel.value ? '请完整填写模型名称和模型 ID' : '请完整填写模型名称、模型 ID 和 API Key'
    );
    return null;
  }
  const reasoningConfig: Record<string, unknown> = supportsGenerationOptions.value
    ? {
        parallelToolCalls: modelForm.parallelToolCalls,
        nativeStructuredOutput: modelForm.nativeStructuredOutput,
        nativeStructuredOutputWithTools: modelForm.nativeStructuredOutputWithTools
      }
    : {};
  if (supportsGenerationOptions.value) {
    if (modelForm.temperature !== null) reasoningConfig.temperature = modelForm.temperature;
    if (modelForm.topP !== null) reasoningConfig.topP = modelForm.topP;
    if (modelForm.frequencyPenalty !== null) reasoningConfig.frequencyPenalty = modelForm.frequencyPenalty;
    if (modelForm.presencePenalty !== null) reasoningConfig.presencePenalty = modelForm.presencePenalty;
    if (modelForm.thinkingBudget !== null) reasoningConfig.thinkingBudget = modelForm.thinkingBudget;
    if (modelForm.reasoningEffort) reasoningConfig.reasoningEffort = modelForm.reasoningEffort;
    if (modelForm.endpointPath.trim()) reasoningConfig.endpointPath = modelForm.endpointPath.trim();
  }
  const payload = {
    displayName: modelForm.displayName.trim(),
    providerType: modelForm.providerType,
    modelName: modelForm.modelName.trim(),
    modelType: modelForm.modelType,
    endpointUrl: modelForm.endpointUrl.trim() || undefined,
    contextSize: modelForm.contextSize,
    maxOutputTokens: supportsGenerationOptions.value ? modelForm.maxOutputTokens : undefined,
    reasoningConfig,
    capabilities: supportsGenerationOptions.value
      ? {
          streaming: modelForm.streaming,
          toolCalling: modelForm.toolCalling,
          vision: modelForm.vision,
          jsonSchema: modelForm.jsonSchema,
          reasoning: modelForm.reasoning,
          inputModalities: modelForm.inputModalities,
          outputModalities: modelForm.outputModalities
        }
      : {},
    status: modelForm.status
  } as SaveModelPayload;
  if (modelForm.apiKey.trim()) payload.apiKey = modelForm.apiKey;
  return payload;
}

async function submitModel() {
  if (!ensureResourcePermission('model', editingModel.value ? 'edit' : 'create')) return;
  const payload = modelPayload();
  if (!payload || (!editingModel.value && !/^[a-z][a-z0-9._-]{0,127}$/.test(modelForm.modelKey))) {
    window.$message?.warning('模型标识需要以小写字母开头');
    return;
  }
  modelSubmitting.value = true;
  const result = editingModel.value
    ? await updateModel(editingModel.value.id, payload)
    : await createModel({ ...payload, modelKey: modelForm.modelKey });
  if (!result.error) {
    modelVisible.value = false;
    window.$message?.success('模型配置已保存');
    await loadModels();
  }
  modelSubmitting.value = false;
}

async function testCurrentModel() {
  if (!ensureResourcePermission('model', 'operate')) return;
  const payload = modelPayload();
  if (!payload) return;
  modelSubmitting.value = true;
  const result = await testModelConfig({
    ...payload,
    existingModelId: editingModel.value?.id
  });
  if (!result.error) {
    window.$message?.[result.data.success ? 'success' : 'error'](
      result.data.message + '，耗时 ' + result.data.latencyMs + ' ms'
    );
  }
  modelSubmitting.value = false;
}

async function discoverCurrentModels() {
  if (!ensureResourcePermission('model', 'operate')) return;
  if (!modelForm.endpointUrl.trim() && modelForm.providerType === 'openai-compatible') {
    window.$message?.warning('请先填写服务地址');
    return;
  }
  modelSubmitting.value = true;
  try {
    const { data, error } = await discoverModels({
      existingModelId: editingModel.value?.id,
      providerType: modelForm.providerType,
      endpointUrl: modelForm.endpointUrl.trim() || undefined,
      apiKey: modelForm.apiKey.trim() || undefined
    });
    if (error) {
      window.$message?.error(requestErrorMessage(error));
      return;
    }
    modelOptions.value = data;
    window.$message?.success(data.length ? '已发现 ' + data.length + ' 个模型' : '服务未返回模型列表');
  } finally {
    modelSubmitting.value = false;
  }
}

async function showModelReferences(row: ModelView) {
  if (!ensureResourcePermission('model', 'list')) return;
  const { data, error } = await fetchModelReferences(row.id);
  if (!error) {
    modelReferences.value = data;
    modelReferenceVisible.value = true;
  }
}

function confirmDeleteModel(row: ModelView) {
  if (!ensureResourcePermission('model', 'delete')) return;
  dialog.warning({
    title: '删除模型',
    content: '确认删除“' + row.displayName + '”？存在版本引用时后台会拒绝。',
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      const { error } = await deleteModel(row.id);
      if (!error) {
        window.$message?.success('模型已删除');
        await loadModels();
      }
    }
  });
}

function openConnector(row?: ConnectorView, defaultType: ConnectorView['providerType'] = 'mcp') {
  if (row ? !ensureConnectorManageable(row, 'edit') : !ensureResourcePermission('connector', 'create')) return;
  editingConnector.value = row || null;
  mcpWizardStep.value = row?.providerType === 'mcp' ? 3 : 1;
  mcpWizardMessage.value = null;
  connectorDraftTestResult.value = null;
  connectorDraftTestError.value = null;
  const config = objectValue(row?.config);
  const authType = textValue(config, 'authType', 'none');
  Object.assign(connectorForm, {
    connectorKey: row?.connectorKey || '',
    name: row?.name || '',
    providerType: row?.providerType || defaultType,
    scope: row?.scope || 'personal',
    endpointUrl: row?.endpointUrl || '',
    authType: ['none', 'bearer', 'header'].includes(authType) ? authType : 'none',
    authHeader: textValue(config, 'authHeader', 'X-API-Key'),
    credentialName: credentialName(row?.credentialRef),
    transport: textValue(config, 'transport', 'streamable_http') === 'sse' ? 'sse' : 'streamable_http',
    namespace: textValue(config, 'namespace', 'default'),
    connectTimeoutMs: numberValue(config, 'connectTimeoutMs', 5000),
    requestTimeoutMs: numberValue(config, 'requestTimeoutMs', 15000),
    searchEngine: ['brave', 'bing', 'serper', 'searxng', 'custom'].includes(textValue(config, 'engine', 'custom'))
      ? textValue(config, 'engine', 'custom')
      : 'custom',
    searchRequestMethod: textValue(config, 'requestMethod', 'GET') === 'POST' ? 'POST' : 'GET',
    searchQueryParam: textValue(config, 'queryParam', 'q'),
    searchCountParam: textValue(config, 'countParam', 'count'),
    searchMaxResults: numberValue(config, 'maxResults', 10),
    searchRateLimitPerMinute: numberValue(config, 'rateLimitPerMinute', 60),
    searchFailureThreshold: numberValue(config, 'failureThreshold', 3),
    searchCooldownSeconds: numberValue(config, 'cooldownSeconds', 60),
    status: row?.status || 'active'
  });
  connectorVisible.value = true;
}

function connectorPayload() {
  if (
    !connectorForm.name.trim() ||
    !connectorForm.endpointUrl.trim() ||
    (connectorForm.authType !== 'none' && !/^[A-Z][A-Z0-9_]{0,127}$/.test(connectorForm.credentialName))
  ) {
    window.$message?.warning('请完整填写连接名称、服务地址和鉴权信息');
    return null;
  }
  const config: Record<string, unknown> = {
    authType: connectorForm.authType,
    connectTimeoutMs: connectorForm.connectTimeoutMs,
    requestTimeoutMs: connectorForm.requestTimeoutMs
  };
  if (connectorForm.authType === 'header') config.authHeader = connectorForm.authHeader.trim();
  if (connectorForm.providerType === 'mcp') {
    config.transport = connectorForm.transport;
    config.namespace = connectorForm.namespace.trim().toLowerCase();
  }
  if (connectorForm.providerType === 'search') {
    if (
      !/^[A-Za-z][A-Za-z0-9_.-]{0,63}$/.test(connectorForm.searchQueryParam) ||
      !/^[A-Za-z][A-Za-z0-9_.-]{0,63}$/.test(connectorForm.searchCountParam)
    ) {
      window.$message?.warning('搜索请求参数名格式无效');
      return null;
    }
    Object.assign(config, {
      engine: connectorForm.searchEngine,
      requestMethod: connectorForm.searchRequestMethod,
      queryParam: connectorForm.searchQueryParam.trim(),
      countParam: connectorForm.searchCountParam.trim(),
      maxResults: connectorForm.searchMaxResults,
      rateLimitPerMinute: connectorForm.searchRateLimitPerMinute,
      failureThreshold: connectorForm.searchFailureThreshold,
      cooldownSeconds: connectorForm.searchCooldownSeconds
    });
  }
  return {
    name: connectorForm.name.trim(),
    providerType: connectorForm.providerType,
    scope: connectorForm.scope,
    endpointUrl: connectorForm.endpointUrl.trim(),
    credentialRef: connectorForm.authType === 'none' ? undefined : 'env:' + connectorForm.credentialName.trim(),
    config,
    status: connectorForm.status
  } satisfies SaveConnectorPayload;
}

function applySearchEnginePreset(value: string) {
  const presets: Record<
    string,
    {
      endpointUrl: string;
      authHeader: string;
      credentialName: string;
      method: 'GET' | 'POST';
      countParam: string;
    }
  > = {
    brave: {
      endpointUrl: 'https://api.search.brave.com/res/v1/web/search',
      authHeader: 'X-Subscription-Token',
      credentialName: 'BRAVE_SEARCH_API_KEY',
      method: 'GET',
      countParam: 'count'
    },
    bing: {
      endpointUrl: 'https://api.bing.microsoft.com/v7.0/search',
      authHeader: 'Ocp-Apim-Subscription-Key',
      credentialName: 'BING_SEARCH_API_KEY',
      method: 'GET',
      countParam: 'count'
    },
    serper: {
      endpointUrl: 'https://google.serper.dev/search',
      authHeader: 'X-API-KEY',
      credentialName: 'SERPER_API_KEY',
      method: 'POST',
      countParam: 'num'
    },
    searxng: {
      endpointUrl: '',
      authHeader: 'X-API-Key',
      credentialName: '',
      method: 'GET',
      countParam: 'count'
    }
  };
  const preset = presets[value];
  if (!preset) return;
  connectorForm.endpointUrl = preset.endpointUrl;
  connectorForm.authType = value === 'searxng' ? 'none' : 'header';
  connectorForm.authHeader = preset.authHeader;
  connectorForm.credentialName = preset.credentialName;
  connectorForm.searchRequestMethod = preset.method;
  connectorForm.searchQueryParam = 'q';
  connectorForm.searchCountParam = preset.countParam;
}

function clearMcpImport() {
  mcpImportText.value = '';
  mcpImportEntries.value = [];
  mcpImportSourceKey.value = null;
  mcpImportConnectorKey.value = '';
  mcpImportName.value = '';
  mcpImportScope.value = 'personal';
  mcpImportCredentialName.value = '';
  mcpImportStatus.value = 'active';
}

function openMcpImport() {
  if (!ensureResourcePermission('connector', 'create')) return;
  clearMcpImport();
  mcpImportVisible.value = true;
}

function closeMcpImport() {
  if (mcpImportParsing.value || mcpImportSubmitting.value) return;
  mcpImportVisible.value = false;
  clearMcpImport();
}

function applyMcpImportEntry(entry: McpImportEntryView | null) {
  if (!entry) return;
  mcpImportConnectorKey.value = entry.suggestedConnectorKey;
  mcpImportName.value = entry.suggestedName;
  mcpImportCredentialName.value = credentialName(entry.credentialRef);
}

async function parseMcpImport() {
  let parsed: unknown;
  try {
    parsed = JSON.parse(mcpImportText.value);
  } catch {
    window.$message?.error('JSON 格式无效，请检查粘贴内容');
    return;
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    window.$message?.error('mcpServers 配置必须是 JSON 对象');
    return;
  }
  mcpImportParsing.value = true;
  try {
    const document = parsed as Record<string, unknown>;
    const { data, error } = await previewMcpServersImport(document);
    if (error) return;
    mcpImportEntries.value = data.entries;
    const first = data.entries.find(entry => entry.importable) || data.entries[0] || null;
    mcpImportSourceKey.value = first?.sourceKey || null;
    applyMcpImportEntry(first);
    if (data.entries.some(entry => entry.importable)) {
      mcpImportText.value = '';
    } else {
      window.$message?.warning('配置中没有可安全导入的远程 MCP 服务');
    }
  } finally {
    mcpImportParsing.value = false;
  }
}

async function submitMcpImport() {
  const entry = selectedMcpImportEntry.value;
  if (!entry?.importable || !entry.endpointUrl) {
    window.$message?.warning('请先解析并选择可导入的 MCP 服务');
    return;
  }
  if (!/^[a-z][a-z0-9._-]{0,127}$/.test(mcpImportConnectorKey.value)) {
    window.$message?.warning('连接器标识需要以小写字母开头');
    return;
  }
  if (!mcpImportName.value.trim()) {
    window.$message?.warning('请填写连接器名称');
    return;
  }
  const requiresCredential = entry.authType !== 'none';
  if (requiresCredential && !/^[A-Z][A-Z0-9_]{0,127}$/.test(mcpImportCredentialName.value)) {
    window.$message?.warning('内联凭据不会导入，请填写部署环境变量名称');
    return;
  }
  mcpImportSubmitting.value = true;
  try {
    const { data, error } = await importMcpServer({
      document: buildSafeMcpImportDocument(entry, mcpImportCredentialName.value),
      sourceKey: entry.sourceKey,
      connectorKey: mcpImportConnectorKey.value,
      name: mcpImportName.value.trim(),
      scope: mcpImportScope.value,
      credentialRef: requiresCredential ? 'env:' + mcpImportCredentialName.value : undefined,
      status: mcpImportStatus.value
    });
    if (!error) {
      const connector = data;
      mcpImportVisible.value = false;
      clearMcpImport();
      await synchronizeMcpConnector(connector, 'MCP 服务已安全导入');
      await loadConnectors();
    }
  } finally {
    mcpImportSubmitting.value = false;
  }
}

async function submitConnector() {
  if (!ensureResourcePermission('connector', editingConnector.value ? 'edit' : 'create')) return;
  const payload = connectorPayload();
  if (!payload || (!editingConnector.value && !/^[a-z][a-z0-9._-]{0,127}$/.test(connectorForm.connectorKey))) {
    window.$message?.warning('连接器标识需要以小写字母开头');
    return;
  }
  if (connectorForm.providerType === 'mcp' && mcpWizardStep.value < 3) {
    const validation = await validateMcpWizard({
      step: mcpWizardStep.value,
      connectorKey: connectorForm.connectorKey.trim(),
      name: payload.name,
      endpointUrl: payload.endpointUrl,
      namespace: connectorForm.namespace.trim(),
      transport: connectorForm.transport,
      authType: connectorForm.authType,
      credentialRef: payload.credentialRef,
      config: payload.config
    });
    if (validation.error) {
      mcpWizardMessage.value = requestErrorMessage(validation.error);
      return;
    }
    if (!validation.data?.valid) {
      mcpWizardMessage.value = validation.data?.diagnostics.join('；') || '当前步骤未通过';
      return;
    }
    mcpWizardStep.value = validation.data.nextStep || 3;
    mcpWizardMessage.value = `第 ${mcpWizardStep.value - 1} 步已完成，请继续验证并发布`;
    return;
  }
  if (
    editingConnector.value?.providerType === 'mcp' &&
    editingConnector.value.status === 'active' &&
    payload.status === 'disabled'
  ) {
    const usageResult = await fetchMcpConnectorUsage(editingConnector.value.id);
    if (usageResult.error) {
      window.$message?.error(`无法读取受影响 Agent：${requestErrorMessage(usageResult.error)}`);
      return;
    }
    const usage = usageResult.data;
    const impact =
      usage.boundAgentCount > 0
        ? `停用后关联工具会立即不可用，将影响 ${usage.boundAgentCount} 个 Agent，其中当前生效 ${usage.activeAgentCount} 个。`
        : '停用后该服务下的 MCP 工具会立即不可用。';
    dialog.warning({
      title: '停用 MCP 服务',
      content: impact,
      positiveText: '确认停用',
      negativeText: '取消',
      onPositiveClick: () => persistConnector(payload)
    });
    return;
  }
  await persistConnector(payload);
}

async function persistConnector(payload: SaveConnectorPayload) {
  connectorSubmitting.value = true;
  try {
    const result = editingConnector.value
      ? await updateConnector(editingConnector.value.id, editingConnector.value.revision, payload)
      : await createConnector({ ...payload, connectorKey: connectorForm.connectorKey.trim() });
    if (!result.error) {
      connectorVisible.value = false;
      if (result.data.providerType === 'mcp') {
        await synchronizeMcpConnector(result.data, 'MCP 服务已保存');
      } else {
        window.$message?.success('连接器已保存');
      }
      await loadConnectors();
    }
  } finally {
    connectorSubmitting.value = false;
  }
}

async function synchronizeMcpConnector(row: ConnectorView, successPrefix?: string) {
  if (row.providerType !== 'mcp') return true;
  if (row.status !== 'active') {
    window.$message?.success(successPrefix || 'MCP 服务已保存');
    return true;
  }
  connectorDiscoveringId.value = row.id;
  try {
    const { data, error } = await discoverConnector(row.id);
    if (error) {
      const prefix = successPrefix ? `${successPrefix}，但` : '';
      window.$message?.warning(`${prefix}工具自动同步失败：${requestErrorMessage(error)}`);
      return false;
    }
    lastDiscovery.value = data;
    window.$message?.success(`${successPrefix ? `${successPrefix}，` : ''}已同步 ${data.toolCount} 个工具`);
    if (authorizedTabs.value.includes('tool')) await loadTools();
    if (mcpToolsConnector.value?.id === row.id) await loadMcpConnectorTools();
    return true;
  } finally {
    connectorDiscoveringId.value = null;
  }
}

async function runDiscovery(row: ConnectorView) {
  if (!ensureConnectorManageable(row, 'operate')) return;
  await synchronizeMcpConnector(row);
  await loadConnectors();
}

async function runConnectorDraftTest() {
  if (connectorForm.providerType !== 'mcp') return;
  const payload = connectorPayload();
  if (!payload) return;
  connectorDraftTesting.value = true;
  connectorDraftTestResult.value = null;
  connectorDraftTestError.value = null;
  try {
    const { data, error } = await testConnectorDraft({
      connectorId: editingConnector.value?.id,
      name: payload.name,
      endpointUrl: payload.endpointUrl,
      credentialRef: payload.credentialRef,
      config: payload.config
    });
    if (error) connectorDraftTestError.value = requestErrorMessage(error);
    else connectorDraftTestResult.value = data;
  } finally {
    connectorDraftTesting.value = false;
  }
}

async function runConnectorTest(row: ConnectorView) {
  if (!ensureConnectorManageable(row, 'operate')) return;
  connectorTestTarget.value = row;
  connectorTestResult.value = null;
  connectorTestError.value = null;
  connectorTestVisible.value = true;
  connectorTestingId.value = row.id;
  try {
    const { data, error } = await testConnector(row.id);
    if (error) connectorTestError.value = requestErrorMessage(error);
    else connectorTestResult.value = data;
    await loadConnectors();
  } finally {
    connectorTestingId.value = null;
  }
}

async function loadDiscoveries() {
  if (!discoveryConnector.value) return;
  if (!ensureConnectorManageable(discoveryConnector.value, 'operate')) return;
  discoveryLoading.value = true;
  discoveryError.value = null;
  const { data, error } = await fetchConnectorDiscoveries(discoveryConnector.value.id);
  if (error) {
    discoveryError.value = error instanceof Error ? error.message : String(error);
    discoveries.value = [];
  } else {
    discoveries.value = data;
  }
  discoveryLoading.value = false;
}

function openDiscoveries(row: ConnectorView) {
  if (!ensureConnectorManageable(row, 'operate')) return;
  discoveryConnector.value = row;
  discoveries.value = [];
  discoveryError.value = null;
  discoveryVisible.value = true;
  void loadDiscoveries();
}

async function loadRuntimeOverview() {
  if (!runtimeConnector.value) return;
  runtimeLoading.value = true;
  runtimeError.value = null;
  runtimeUsageError.value = null;
  const [runtimeResult, usageResult] = await Promise.all([
    fetchConnectorRuntime(runtimeConnector.value.id),
    fetchMcpConnectorUsage(runtimeConnector.value.id)
  ]);
  if (runtimeResult.error) {
    runtimeOverview.value = null;
    runtimeError.value = requestErrorMessage(runtimeResult.error);
  } else {
    runtimeOverview.value = runtimeResult.data;
  }
  if (usageResult.error) {
    runtimeUsage.value = null;
    runtimeUsageError.value = requestErrorMessage(usageResult.error);
  } else {
    runtimeUsage.value = usageResult.data;
  }
  runtimeLoading.value = false;
}

function openRuntimeOverview(row: ConnectorView) {
  if (!ensureConnectorManageable(row, 'operate')) return;
  runtimeConnector.value = row;
  runtimeOverview.value = null;
  runtimeUsage.value = null;
  runtimeError.value = null;
  runtimeUsageError.value = null;
  runtimeVisible.value = true;
  void loadRuntimeOverview();
}

async function loadMcpConnectorTools() {
  if (!mcpToolsConnector.value) return;
  mcpToolsLoading.value = true;
  mcpToolsError.value = null;
  mcpWorkbenchUsageError.value = null;
  try {
    const [toolResult, usageResult] = await Promise.all([
      fetchConnectorTools(mcpToolsConnector.value.id, true),
      fetchMcpConnectorUsage(mcpToolsConnector.value.id)
    ]);
    if (toolResult.error) {
      mcpToolsError.value = requestErrorMessage(toolResult.error);
    } else {
      mcpConnectorTools.value = toolResult.data;
      const selectable = new Set(
        toolResult.data.filter(tool => tool.available && tool.status !== 'deprecated').map(tool => tool.id)
      );
      mcpSelectedToolIds.value = mcpSelectedToolIds.value.filter(id => selectable.has(id));
    }
    if (usageResult.error) {
      mcpWorkbenchUsage.value = null;
      mcpWorkbenchUsageError.value = requestErrorMessage(usageResult.error);
    } else {
      mcpWorkbenchUsage.value = usageResult.data;
    }
  } finally {
    mcpToolsLoading.value = false;
  }
}

function openMcpToolWorkbench(row: ConnectorView) {
  if (!ensureConnectorManageable(row, 'operate') || !ensureResourcePermission('tool', 'list')) return;
  mcpToolsConnector.value = row;
  mcpConnectorTools.value = [];
  mcpSelectedToolIds.value = [];
  mcpToolsError.value = null;
  mcpWorkbenchUsage.value = null;
  mcpWorkbenchUsageError.value = null;
  mcpToolsVisible.value = true;
  void loadMcpConnectorTools();
}

function updateMcpCheckedRows(keys: Array<string | number>) {
  mcpSelectedToolIds.value = keys.map(String);
}

async function setMcpToolStatus(row: ToolView, status: 'active' | 'disabled') {
  if (!ensureResourcePermission('tool', 'operate')) return false;
  toolStatusUpdatingId.value = row.id;
  try {
    const { error } = await updateToolStatus(row.id, row.status, status);
    if (error) {
      window.$message?.error(requestErrorMessage(error));
      return false;
    }
    window.$message?.success(status === 'active' ? 'MCP 工具已发布' : 'MCP 工具已撤回');
    await Promise.all([loadMcpConnectorTools(), loadTools()]);
    return true;
  } finally {
    toolStatusUpdatingId.value = null;
  }
}

async function batchSetMcpToolStatus(status: 'active' | 'disabled') {
  if (!ensureResourcePermission('tool', 'operate') || !mcpToolsConnector.value) return;
  if (status === 'active' && mcpToolsConnector.value.status !== 'active') {
    window.$message?.warning('请先启用 MCP 服务再发布工具');
    return;
  }
  const targets = mcpSelectedTools.value.filter(
    tool => tool.available && tool.status !== 'deprecated' && tool.status !== status
  );
  if (!targets.length) {
    window.$message?.warning(status === 'active' ? '所选工具无需发布' : '所选工具无需撤回');
    return;
  }
  mcpBatchSubmitting.value = true;
  try {
    const outcomes = await Promise.all(
      targets.map(async tool => {
        const result = await updateToolStatus(tool.id, tool.status, status);
        return { tool, error: result.error };
      })
    );
    const failed = outcomes.filter(item => item.error);
    const succeeded = outcomes.length - failed.length;
    if (failed.length) {
      const names = failed
        .slice(0, 3)
        .map(item => item.tool.name)
        .join('、');
      window.$message?.warning(`已处理 ${succeeded} 个，${failed.length} 个失败：${names}`);
    } else {
      window.$message?.success(`${status === 'active' ? '发布' : '撤回'} ${succeeded} 个 MCP 工具`);
    }
    await Promise.all([loadMcpConnectorTools(), loadTools()]);
  } finally {
    mcpBatchSubmitting.value = false;
  }
}

async function publishAllMcpTools() {
  const connector = mcpToolsConnector.value;
  if (!connector || !ensureResourcePermission('connector', 'operate')) return;
  if (connector.status !== 'active') {
    window.$message?.warning('请先启用 MCP 服务再发布工具');
    return;
  }
  const pending = mcpUnpublishedCount.value;
  if (!pending) {
    window.$message?.warning('当前没有待发布的可用 MCP 工具');
    return;
  }
  const namespace = typeof connector.config.namespace === 'string' ? connector.config.namespace : 'default';
  mcpBatchSubmitting.value = true;
  try {
    const result = await publishMcpWizard(connector.id, connector.revision, namespace);
    if (result.error) {
      window.$message?.error(requestErrorMessage(result.error));
      return;
    }
    mcpToolsConnector.value = result.data;
    mcpSelectedToolIds.value = [];
    window.$message?.success(`已发布 ${pending} 个 MCP 工具`);
    await Promise.all([loadMcpConnectorTools(), loadConnectors(), loadTools()]);
  } finally {
    mcpBatchSubmitting.value = false;
  }
}

function discoveryStatusText(status: string) {
  return (
    {
      succeeded: '成功',
      failed: '失败',
      running: '执行中',
      pending: '排队中'
    }[status] || status
  );
}

function discoveryStatusType(status: string) {
  if (status === 'succeeded') return 'success';
  if (status === 'failed') return 'error';
  if (status === 'running' || status === 'pending') return 'warning';
  return 'default';
}

async function confirmDeleteConnector(row: ConnectorView) {
  if (!ensureConnectorManageable(row, 'delete')) return;
  let usage: McpConnectorUsageView | null = null;
  if (row.providerType === 'mcp') {
    const result = await fetchMcpConnectorUsage(row.id);
    if (result.error) {
      window.$message?.error(`无法读取受影响 Agent：${requestErrorMessage(result.error)}`);
      return;
    }
    usage = result.data;
  }
  const impact =
    usage && usage.boundAgentCount > 0
      ? `删除后关联工具会立即不可用，将影响 ${usage.boundAgentCount} 个 Agent、${usage.boundVersionCount} 个版本，其中当前生效 ${usage.activeAgentCount} 个。`
      : row.providerType === 'mcp'
        ? '删除后该服务及其已发现工具会立即下线。'
        : '删除后该连接器将不再可用。';
  dialog.warning({
    title: '删除连接器',
    content: `确认删除“${row.name}”？${impact}`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      const { error } = await deleteConnector(row.id, row.revision);
      if (!error) {
        window.$message?.success('连接器已删除');
        await Promise.all([loadConnectors(), loadTools()]);
      }
    }
  });
}

function newToolParameter(): ToolParameterDraft {
  return { name: '', label: '', type: 'string', required: toolForm.toolType === 'sql', description: '' };
}

function schemaParameters(schema: Record<string, unknown>): ToolParameterDraft[] {
  const properties = objectValue(schema.properties);
  const required = new Set(textList(schema.required));
  return Object.entries(properties).map(([name, raw]) => {
    const property = objectValue(raw);
    const rawType = textValue(property, 'type', 'string');
    const type = parameterTypeOptions.some(item => item.value === rawType) ? (rawType as ToolParameterType) : 'string';
    return {
      name,
      label: textValue(property, 'title', name),
      type,
      required: required.has(name),
      description: textValue(property, 'description')
    };
  });
}

function openTool(row?: ToolView) {
  if (!ensureResourcePermission('tool', row ? 'edit' : 'create')) return;
  versioningTool.value = row || null;
  const policy = objectValue(row?.executionPolicy);
  const sqlPolicy = parseSqlToolPolicy(policy);
  const rowType = row && row.toolType !== 'mcp' ? row.toolType : 'api';
  Object.assign(toolForm, {
    toolKey: row?.toolKey || '',
    name: row?.name || '',
    description: row?.description || '',
    connectorId: row?.connectorId || null,
    toolType: rowType,
    riskLevel: row?.riskLevel || 'R1',
    externalName: row?.externalName || '',
    status: row?.status || 'active',
    parameters: row ? schemaParameters(objectValue(row.parameterSchema)) : [],
    handlerKey: textValue(policy, 'handlerKey'),
    datasetId: sqlPolicy.datasetId,
    queryPurpose: sqlPolicy.queryPurpose,
    sqlTemplate: sqlPolicy.sqlTemplate,
    method: textValue(policy, 'method', 'GET').toUpperCase(),
    path: textValue(policy, 'path', '/'),
    readOnly: booleanValue(policy, 'readOnly', true),
    timeoutMs: numberValue(policy, 'timeoutMs', 15000),
    retryCount: numberValue(policy, 'retryCount', 0),
    maxOutputBytes: numberValue(policy, 'maxOutputBytes', 1048576),
    templateKey: textValue(policy, 'templateKey', 'python'),
    workspaceAccess: textValue(policy, 'workspaceAccess', 'read_write') === 'read_only' ? 'read_only' : 'read_write',
    workspacePath: textValue(policy, 'workspacePath', '.'),
    memoryMb: numberValue(policy, 'memoryMb', 512),
    cpuMillis: numberValue(policy, 'cpuMillis', 1000),
    pidsLimit: numberValue(policy, 'pidsLimit', 128),
    priority: numberValue(policy, 'priority', 0)
  });
  toolVisible.value = true;
}

function selectBuiltin(handlerKey: string) {
  const descriptor = builtinTools.value.find(item => item.name === handlerKey);
  if (!descriptor || descriptor.execution !== 'local') return;
  toolForm.handlerKey = handlerKey;
  if (!versioningTool.value) {
    toolForm.toolKey = handlerKey;
    toolForm.name = handlerKey;
  }
  toolForm.description = descriptor.description;
  toolForm.riskLevel = descriptor.riskLevel;
  toolForm.readOnly = descriptor.readOnly;
  toolForm.parameters = schemaParameters(descriptor.parameterSchema);
}

function addToolParameter() {
  toolForm.parameters.push(newToolParameter());
}

function removeToolParameter(index: number) {
  toolForm.parameters.splice(index, 1);
}

function buildParameterSchema(parameters: ToolParameterDraft[]) {
  const names = new Set<string>();
  const properties: Record<string, unknown> = {};
  const required: string[] = [];
  for (const item of parameters) {
    const name = item.name.trim();
    if (!/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(name) || names.has(name)) {
      window.$message?.warning('参数名称需唯一，并使用字母、数字或下划线');
      return null;
    }
    names.add(name);
    const property: Record<string, unknown> = {
      type: item.type,
      title: item.label.trim() || name
    };
    if (item.type === 'array') property.items = { type: 'string' };
    if (item.description.trim()) property.description = item.description.trim();
    properties[name] = property;
    if (item.required) required.push(name);
  }
  return { type: 'object', properties, required, additionalProperties: false };
}

function buildToolPolicy() {
  if (toolForm.toolType === 'api' || toolForm.toolType === 'search') {
    const policy: Record<string, unknown> = {
      method: toolForm.method,
      path: toolForm.path.trim(),
      contentType: 'application/json',
      timeoutMs: toolForm.timeoutMs,
      maxOutputBytes: toolForm.maxOutputBytes,
      readOnly: toolForm.readOnly,
      networkPolicy: 'connector_origin'
    };
    if (toolForm.method === 'GET' && toolForm.readOnly) policy.retryCount = toolForm.retryCount;
    return policy;
  }
  if (toolForm.toolType === 'sandbox') {
    return {
      templateKey: toolForm.templateKey.trim(),
      workspaceAccess: toolForm.workspaceAccess,
      workspacePath: toolForm.workspacePath.trim() || '.',
      networkPolicy: 'none',
      timeoutMs: toolForm.timeoutMs,
      maxOutputBytes: toolForm.maxOutputBytes,
      memoryMb: toolForm.memoryMb,
      cpuMillis: toolForm.cpuMillis,
      pidsLimit: toolForm.pidsLimit,
      priority: toolForm.priority
    };
  }
  if (toolForm.toolType === 'sql') {
    return buildSqlToolPolicy({
      datasetId: toolForm.datasetId,
      queryPurpose: toolForm.queryPurpose,
      sqlTemplate: toolForm.sqlTemplate
    });
  }
  return {
    handlerKey: toolForm.handlerKey.trim(),
    readOnly: toolForm.readOnly,
    timeoutMs: toolForm.timeoutMs,
    maxOutputBytes: toolForm.maxOutputBytes
  };
}

async function submitTool() {
  if (!ensureResourcePermission('tool', versioningTool.value ? 'edit' : 'create')) return;
  const parameterSchema = buildParameterSchema(toolForm.parameters);
  const external = toolForm.toolType === 'api' || toolForm.toolType === 'search';
  const sqlError =
    toolForm.toolType === 'sql'
      ? validateSqlToolDraft(
          {
            datasetId: toolForm.datasetId,
            queryPurpose: toolForm.queryPurpose,
            sqlTemplate: toolForm.sqlTemplate
          },
          toolForm.parameters
        )
      : null;
  const invalidRisk =
    (external && toolForm.riskLevel === 'R0') ||
    (external && !toolForm.readOnly && ['R0', 'R1'].includes(toolForm.riskLevel)) ||
    (external && toolForm.method === 'DELETE' && toolForm.riskLevel !== 'R3') ||
    (toolForm.toolType === 'sql' && toolForm.riskLevel !== 'R1') ||
    (toolForm.toolType === 'sandbox' && !['R2', 'R3'].includes(toolForm.riskLevel));
  if (
    !parameterSchema ||
    !toolForm.name.trim() ||
    (!versioningTool.value && !/^[a-z][a-z0-9._-]{0,127}$/.test(toolForm.toolKey)) ||
    (external && (!toolForm.connectorId || !toolForm.externalName.trim() || !toolForm.path.trim().startsWith('/'))) ||
    (toolForm.toolType === 'sandbox' && !toolForm.templateKey.trim()) ||
    (toolForm.toolType === 'builtin' && !toolForm.handlerKey.trim()) ||
    Boolean(sqlError) ||
    invalidRisk
  ) {
    window.$message?.warning(sqlError || '请补全当前工具类型要求的配置');
    return;
  }
  const payload: SaveToolPayload = {
    name: toolForm.name.trim(),
    description: toolForm.description.trim() || undefined,
    connectorId: external ? toolForm.connectorId || undefined : undefined,
    toolType: toolForm.toolType,
    riskLevel: toolForm.riskLevel,
    parameterSchema,
    executionPolicy: buildToolPolicy(),
    externalName: external ? toolForm.externalName.trim() : undefined,
    status: toolForm.status
  };
  toolSubmitting.value = true;
  const result = versioningTool.value
    ? await createToolVersion(versioningTool.value.toolKey, payload)
    : await createTool({ ...payload, toolKey: toolForm.toolKey.trim() });
  if (!result.error) {
    toolVisible.value = false;
    window.$message?.success(versioningTool.value ? '工具新版本已创建' : '工具已创建');
    await loadTools();
  }
  toolSubmitting.value = false;
}

async function toggleTool(row: ToolView) {
  if (!ensureResourcePermission('tool', 'operate')) return;
  const target = row.status === 'active' ? 'disabled' : 'active';
  if (row.toolType === 'mcp' && (!row.available || row.status === 'deprecated')) {
    window.$message?.warning('远端不可用或已失效的 MCP 工具不能发布，请先重新发现工具');
    return;
  }
  if (row.toolType === 'mcp' && target === 'active' && !mcpToolConnectorActive(row)) {
    window.$message?.warning('请先启用 MCP 服务再发布工具');
    return;
  }
  toolStatusUpdatingId.value = row.id;
  try {
    const { error } = await updateToolStatus(row.id, row.status, target);
    if (error) {
      window.$message?.error(requestErrorMessage(error));
      return;
    }
    const activeMessage = row.toolType === 'mcp' ? 'MCP 工具已发布' : '工具已启用';
    const disabledMessage = row.toolType === 'mcp' ? 'MCP 工具已撤回' : '工具已停用';
    window.$message?.success(target === 'active' ? activeMessage : disabledMessage);
    await loadTools();
  } finally {
    toolStatusUpdatingId.value = null;
  }
}

function openToolTest(row: ToolView) {
  if (!ensureResourcePermission('tool', 'operate')) return;
  if (!['mcp', 'api', 'search', 'sql'].includes(row.toolType)) {
    window.$message?.warning('当前仅支持 MCP、API、Search 和 SQL 工具在线测试');
    return;
  }
  testingTool.value = row;
  const example = objectValue(schemaExampleValue(objectValue(row.parameterSchema)));
  sqlToolTestArguments.value = Object.fromEntries(
    Object.entries(example).map(([key, value]) => [key, normalizeSqlTestValue(value)])
  );
  toolTestArguments.value = JSON.stringify(example, null, 2) || '{}';
  toolTestJsonError.value = null;
  toolTestConfirmRisk.value = false;
  toolTestResult.value = null;
  toolTestRequestError.value = null;
  toolTestVisible.value = true;
}

function parseToolTestArguments() {
  toolTestJsonError.value = null;
  if (toolTestUsesStructuredForm.value) return { ...sqlToolTestArguments.value };
  try {
    const value: unknown = JSON.parse(toolTestArguments.value);
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      throw new TypeError('参数必须是 JSON 对象');
    }
    return value as Record<string, unknown>;
  } catch (error) {
    toolTestJsonError.value = error instanceof Error ? error.message : 'JSON 参数格式不正确';
    return null;
  }
}

function normalizeSqlTestValue(value: unknown): string | number | boolean | string[] {
  if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'string') return value;
  if (Array.isArray(value)) return value.filter(item => typeof item === 'string') as string[];
  return '';
}

function sqlTestTextValue(name: string) {
  const value = sqlToolTestArguments.value[name];
  return typeof value === 'string' ? value : '';
}

function structuredToolTestValue(name: string) {
  const value = sqlToolTestArguments.value[name];
  return typeof value === 'string' || typeof value === 'number' ? value : null;
}

function sqlTestNumberValue(name: string) {
  const value = sqlToolTestArguments.value[name];
  return typeof value === 'number' ? value : 0;
}

function sqlTestBooleanValue(name: string) {
  return sqlToolTestArguments.value[name] === true;
}

function sqlTestArrayValue(name: string) {
  const value = sqlToolTestArguments.value[name];
  return Array.isArray(value) ? value : [];
}

function setSqlTestArgument(name: string, value: string | number | boolean | string[] | null) {
  sqlToolTestArguments.value = { ...sqlToolTestArguments.value, [name]: value ?? 0 };
}

function formatDiagnosticCell(value: unknown) {
  if (value === null || value === undefined) return '空';
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (typeof value === 'object') return Array.isArray(value) ? value.join(', ') : String(value);
  return String(value);
}

async function runToolTest() {
  if (!ensureResourcePermission('tool', 'operate') || !testingTool.value) return;
  if (toolTestIsHighRisk.value && !toolTestConfirmRisk.value) {
    window.$message?.warning('请确认高风险工具将发起真实外部调用');
    return;
  }
  const argumentsValue = parseToolTestArguments();
  if (!argumentsValue) return;
  toolTestSubmitting.value = true;
  toolTestResult.value = null;
  toolTestRequestError.value = null;
  try {
    const { data, error } = await testTool(testingTool.value.id, {
      arguments: argumentsValue,
      confirmRisk: toolTestIsHighRisk.value && toolTestConfirmRisk.value
    });
    if (error) toolTestRequestError.value = requestErrorMessage(error);
    else toolTestResult.value = data;
  } finally {
    toolTestSubmitting.value = false;
  }
}

function toolTestStatusText(result: ToolOnlineTestView) {
  if (result.ok && result.status === 'succeeded') return '调用成功';
  return {
    succeeded: '调用失败',
    provider_error: '服务方返回失败',
    transport_error: '连接或传输失败',
    query_error: '查询校验或执行失败',
    tool_unavailable: '工具未配置或执行器不可用',
    authorization_error: '当前账号无权调用',
    conflict: '调用被策略或状态冲突阻止',
    invalid_arguments: '调用参数无效'
  }[result.status];
}

function confirmDeleteTool(row: ToolView) {
  if (!ensureResourcePermission('tool', 'delete')) return;
  if (row.toolType === 'mcp') {
    window.$message?.warning('MCP 工具由连接器发现目录管理，不能手工删除');
    return;
  }
  dialog.warning({
    title: '删除工具定义',
    content: `确认删除“${row.name}”的全部版本？存在 Agent 版本引用时后台会拒绝，本操作保留审计记录。`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      const { error } = await deleteTool(row.id);
      if (!error) {
        window.$message?.success('工具定义已删除');
        await loadTools();
      }
    }
  });
}

function newSkillParameter(): SkillParameterDraft {
  return { name: '', label: '', type: 'string', required: false, description: '' };
}

function addSkillParameter(target: SkillDocumentDraft) {
  target.parameters.push(newSkillParameter());
}

function removeSkillParameter(target: SkillDocumentDraft, index: number) {
  target.parameters.splice(index, 1);
}

function buildSkillManifest(source: SkillDocumentDraft) {
  const parameterSchema = buildParameterSchema(source.parameters);
  if (!parameterSchema) return null;
  const manifest: Record<string, unknown> = {};
  if (source.summary.trim()) manifest.summary = source.summary.trim();
  if (source.tags.length) manifest.tags = source.tags;
  if (source.parameters.length) manifest.parameters = parameterSchema;
  if (source.compatibleAgentTypes.length) manifest.compatibleAgentTypes = source.compatibleAgentTypes;
  if (source.requiredToolKeys.length) manifest.requiredToolKeys = source.requiredToolKeys;
  return manifest;
}

function buildSkillRuntime(source: SkillDocumentDraft) {
  const runtime: Record<string, unknown> = { workspaceAccess: source.workspaceAccess };
  if (source.requiredToolIds.length) runtime.requiredToolIds = source.requiredToolIds;
  if (source.requiredKnowledgeBaseIds.length) runtime.requiredKnowledgeBaseIds = source.requiredKnowledgeBaseIds;
  if (source.maxContextBytes !== null) runtime.maxContextBytes = source.maxContextBytes;
  const dependencies: Record<string, string[]> = {};
  if (source.pythonDependencies.length) dependencies.python = source.pythonDependencies;
  if (source.nodeDependencies.length) dependencies.node = source.nodeDependencies;
  if (Object.keys(dependencies).length) runtime.dependencies = dependencies;
  return runtime;
}

function skillParameters(value: unknown): SkillParameterDraft[] {
  const schema = objectValue(value);
  return schemaParameters(schema);
}

function applySkillDocument(
  target: SkillDocumentDraft,
  content: string,
  manifestValue: Record<string, unknown>,
  runtimeValue: Record<string, unknown>
) {
  Object.assign(target, {
    content,
    summary: textValue(manifestValue, 'summary'),
    tags: textList(manifestValue.tags),
    compatibleAgentTypes: textList(manifestValue.compatibleAgentTypes),
    requiredToolKeys: textList(manifestValue.requiredToolKeys),
    parameters: skillParameters(manifestValue.parameters),
    requiredToolIds: idList(runtimeValue.requiredToolIds),
    requiredKnowledgeBaseIds: idList(runtimeValue.requiredKnowledgeBaseIds),
    maxContextBytes: numberValue(runtimeValue, 'maxContextBytes', 32768),
    workspaceAccess: ['read_only', 'read_write'].includes(textValue(runtimeValue, 'workspaceAccess'))
      ? textValue(runtimeValue, 'workspaceAccess')
      : 'none',
    pythonDependencies: textList(objectValue(runtimeValue.dependencies).python),
    nodeDependencies: textList(objectValue(runtimeValue.dependencies).node)
  });
}

function resetSkillForm(mode: SkillInputMode) {
  const document = newSkillDocument();
  const scope = defaultSkillScope(canManageSharedSkillScopes.value, authStore.userInfo.userId);
  Object.assign(skillForm, {
    skillKey: '',
    name: '',
    description: '',
    scopeType: scope.scopeType,
    scopeId: scope.scopeId,
    inputMode: mode,
    packageName: '',
    ...document
  });
  skillImportFile.value = null;
}

function canManageSkill(row: SkillView) {
  return isPlatformAdmin.value || row.ownerId === authStore.userInfo.userId;
}

function canPublishPersonalSkill(row: SkillView) {
  return row.scopeType === 'user' && row.ownerId === authStore.userInfo.userId && canResource('skill', 'publish');
}

function canToggleSkill(row: SkillView) {
  return (
    canManageSkill(row) &&
    canResource('skill', 'operate') &&
    Boolean(row.publishedVersionId) &&
    ['active', 'disabled'].includes(row.status)
  );
}

async function changeSkillStatus(row: SkillView) {
  if (!canToggleSkill(row)) return;
  const expectedStatus = row.status as 'active' | 'disabled';
  const status = expectedStatus === 'active' ? 'disabled' : 'active';
  skillLifecycleUpdatingId.value = row.id;
  const result = await updateSkillStatus(row.id, expectedStatus, status, row.revision);
  skillLifecycleUpdatingId.value = null;
  if (result.error) {
    window.$message?.error(requestErrorMessage(result.error));
    return;
  }
  window.$message?.success(status === 'active' ? 'Skill 已启用' : 'Skill 已停用');
  await loadSkills();
}

function confirmSkillStatus(row: SkillView) {
  const enabling = row.status === 'disabled';
  dialog.warning({
    title: enabling ? '启用 Skill' : '停用 Skill',
    content: enabling
      ? `启用后，${row.name} 将重新出现在可用 Skill 列表中。`
      : `停用后，${row.name} 不再供新任务选择，已冻结的历史运行快照不受影响。`,
    positiveText: enabling ? '启用' : '停用',
    negativeText: '取消',
    onPositiveClick: () => changeSkillStatus(row)
  });
}

async function removeSkill(row: SkillView) {
  skillLifecycleUpdatingId.value = row.id;
  const result = await deleteSkill(row.id, row.revision);
  skillLifecycleUpdatingId.value = null;
  if (result.error) {
    window.$message?.error(requestErrorMessage(result.error));
    return;
  }
  if (activeSkill.value?.id === row.id) {
    versionVisible.value = false;
    activeSkill.value = null;
    clearSelectedSkillFile();
  }
  window.$message?.success('未发布 Skill 已删除');
  await loadSkills();
}

function confirmDeleteSkill(row: SkillView) {
  if (!canManageSkill(row) || !ensureResourcePermission('skill', 'delete')) return;
  dialog.warning({
    title: '删除 Skill',
    content: `确认删除 ${row.name}？仅从未发布、未被 Agent 引用且没有待审或已公开申请的 Skill 可以删除。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: () => removeSkill(row)
  });
}

async function updateSkillPublication(row: SkillView, action: 'submit' | 'withdraw') {
  if (!canPublishPersonalSkill(row)) return;
  skillPublicationUpdatingId.value = row.id;
  const result = action === 'submit' ? await submitSkillPublication(row.id) : await withdrawSkillPublication(row.id);
  skillPublicationUpdatingId.value = null;
  if (result.error) {
    window.$message?.error(requestErrorMessage(result.error));
    return;
  }
  skillPublicationStatuses.value = { ...skillPublicationStatuses.value, [row.id]: result.data };
  window.$message?.success(action === 'submit' ? '已提交系统 Skill 审核' : '已撤回发布申请');
  await loadPendingSkillPublications();
}

function confirmSkillPublication(row: SkillView, action: 'submit' | 'withdraw') {
  dialog.warning({
    title: action === 'submit' ? '提交系统 Skill 审核' : '撤回发布申请',
    content:
      action === 'submit'
        ? '将冻结当前最新版本及完整文件包供审核，后续编辑不会改变本次快照。'
        : '撤回后本次审核记录仍会保留，可以修改后重新提交。',
    positiveText: action === 'submit' ? '提交审核' : '确认撤回',
    negativeText: '取消',
    onPositiveClick: () => updateSkillPublication(row, action)
  });
}

async function openSkillReview() {
  skillReviewVisible.value = true;
  skillReviewDetail.value = null;
  skillReviewLoading.value = true;
  await loadPendingSkillPublications();
  skillReviewLoading.value = false;
}

async function inspectSkillPublication(row: SkillPublicationView) {
  if (!row.version_id) return;
  skillReviewDetailLoading.value = true;
  const result = await fetchSkillPublicationDetail(row.version_id);
  skillReviewDetailLoading.value = false;
  if (result.error) window.$message?.error(requestErrorMessage(result.error));
  else skillReviewDetail.value = result.data;
}

async function approvePublication() {
  const versionId = skillReviewDetail.value?.version_id;
  if (!versionId) return;
  skillReviewSubmitting.value = true;
  const result = await approveSkillPublication(versionId);
  skillReviewSubmitting.value = false;
  if (result.error) {
    window.$message?.error(requestErrorMessage(result.error));
    return;
  }
  window.$message?.success('技能快照已审核通过并生成独立系统 Skill');
  skillReviewDetail.value = null;
  await Promise.all([loadSkills(), loadPendingSkillPublications()]);
}

function confirmApprovePublication() {
  if (!skillReviewDetail.value) return;
  dialog.warning({
    title: '通过技能发布申请',
    content: '系统会再次校验快照哈希，并把该快照物化为独立系统 Skill 版本。',
    positiveText: '通过并发布',
    negativeText: '取消',
    onPositiveClick: approvePublication
  });
}

function openRejectPublication() {
  skillRejectComment.value = '';
  skillRejectVisible.value = true;
}

async function rejectPublication() {
  const versionId = skillReviewDetail.value?.version_id;
  const comment = skillRejectComment.value.trim();
  if (!versionId || !comment) {
    window.$message?.warning('请填写驳回原因');
    return;
  }
  skillReviewSubmitting.value = true;
  const result = await rejectSkillPublication(versionId, comment);
  skillReviewSubmitting.value = false;
  if (result.error) {
    window.$message?.error(requestErrorMessage(result.error));
    return;
  }
  skillRejectVisible.value = false;
  skillReviewDetail.value = null;
  window.$message?.success('技能发布申请已驳回');
  await Promise.all([loadSkills(), loadPendingSkillPublications()]);
}

function openSkill(row?: SkillView, mode: SkillInputMode = 'form') {
  if (row && !canManageSkill(row)) {
    window.$message?.warning('只能修改自己创建的 Skill');
    return;
  }
  if (!ensureResourcePermission('skill', row ? 'edit' : 'create')) return;
  editingSkill.value = row || null;
  skillUploadKey.value += 1;
  if (row) {
    resetSkillForm('form');
    Object.assign(skillForm, {
      skillKey: row.skillKey,
      name: row.name,
      description: row.description || '',
      scopeType: row.scopeType,
      scopeId: row.scopeId || ''
    });
  } else {
    resetSkillForm(mode);
  }
  skillVisible.value = true;
}

function findEndOfCentralDirectory(view: DataView) {
  const minimum = Math.max(0, view.byteLength - 65557);
  for (let offset = view.byteLength - 22; offset >= minimum; offset -= 1) {
    if (view.getUint32(offset, true) === 0x06054b50) return offset;
  }
  return -1;
}

async function inflateZipEntry(bytes: Uint8Array) {
  if (typeof DecompressionStream === 'undefined') throw new Error('当前浏览器不支持 ZIP 解压');
  const copy = bytes.slice().buffer;
  const stream = new Blob([copy]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

async function readSkillZip(file: File) {
  if (file.size > 32 * 1024 * 1024) throw new Error('ZIP 包不能超过 32 MB');
  const buffer = await file.arrayBuffer();
  const view = new DataView(buffer);
  const endOffset = findEndOfCentralDirectory(view);
  if (endOffset < 0) throw new Error('没有找到有效的 ZIP 目录');
  const entryCount = view.getUint16(endOffset + 10, true);
  const centralSize = view.getUint32(endOffset + 12, true);
  let offset = view.getUint32(endOffset + 16, true);
  const centralEnd = offset + centralSize;
  const decoder = new TextDecoder();
  const targets = new Map<string, ZipEntry>();
  for (let index = 0; index < entryCount && offset < centralEnd; index += 1) {
    if (view.getUint32(offset, true) !== 0x02014b50) throw new Error('ZIP 目录结构无效');
    const compressionMethod = view.getUint16(offset + 10, true);
    const compressedSize = view.getUint32(offset + 20, true);
    const uncompressedSize = view.getUint32(offset + 24, true);
    const nameLength = view.getUint16(offset + 28, true);
    const extraLength = view.getUint16(offset + 30, true);
    const commentLength = view.getUint16(offset + 32, true);
    const localHeaderOffset = view.getUint32(offset + 42, true);
    const name = decoder.decode(new Uint8Array(buffer, offset + 46, nameLength));
    const normalized = name.toLowerCase();
    if (
      normalized.endsWith('/skill.md') ||
      normalized === 'skill.md' ||
      normalized.endsWith('/manifest.json') ||
      normalized === 'manifest.json' ||
      normalized.endsWith('/runtime.json') ||
      normalized === 'runtime.json' ||
      normalized.endsWith('/runtime-requirements.json') ||
      normalized === 'runtime-requirements.json'
    ) {
      if (uncompressedSize > 2 * 1024 * 1024) throw new Error('包内配置文件过大');
      targets.set(name, { compressionMethod, compressedSize, uncompressedSize, localHeaderOffset });
    }
    offset += 46 + nameLength + extraLength + commentLength;
  }
  const files = new Map<string, string>();
  for (const [name, entry] of targets) {
    const local = entry.localHeaderOffset;
    if (view.getUint32(local, true) !== 0x04034b50) throw new Error('ZIP 文件头无效');
    const nameLength = view.getUint16(local + 26, true);
    const extraLength = view.getUint16(local + 28, true);
    const dataOffset = local + 30 + nameLength + extraLength;
    const compressed = new Uint8Array(buffer, dataOffset, entry.compressedSize);
    const content =
      entry.compressionMethod === 0
        ? compressed
        : entry.compressionMethod === 8
          ? await inflateZipEntry(compressed)
          : null;
    if (!content) throw new Error('ZIP 使用了暂不支持的压缩方式');
    if (content.byteLength !== entry.uncompressedSize) throw new Error('ZIP 文件大小校验失败');
    files.set(name, decoder.decode(content));
  }
  return files;
}

function matchingFile(files: Map<string, string>, names: string[]) {
  const entry = [...files.entries()].find(([name]) =>
    names.some(suffix => name.toLowerCase() === suffix || name.toLowerCase().endsWith('/' + suffix))
  );
  return entry?.[1] || '';
}

function parsePackageDocument(content: string, label: string) {
  if (!content.trim()) return {};
  try {
    return objectValue(JSON.parse(content));
  } catch {
    throw new Error(label + '格式无效');
  }
}

async function handleSkillZipChange(options: { file: UploadFileInfo }) {
  if (!ensureResourcePermission('skill', 'create')) return;
  const rawFile = options.file.file;
  if (!rawFile) return;
  skillImportFile.value = rawFile;
  skillImporting.value = true;
  try {
    const files = await readSkillZip(rawFile);
    const content = matchingFile(files, ['skill.md']);
    if (!content.trim()) throw new Error('ZIP 包内缺少 SKILL.md');
    const packageDocument = parsePackageDocument(matchingFile(files, ['manifest.json']), '包清单');
    const runtimeDocument = parsePackageDocument(
      matchingFile(files, ['runtime-requirements.json', 'runtime.json']),
      '运行要求'
    );
    const identity = objectValue(packageDocument.skill);
    const manifest = objectValue(packageDocument.manifest);
    const normalizedManifest = Object.keys(manifest).length ? manifest : packageDocument;
    const embeddedRuntime = objectValue(packageDocument.runtimeRequirements);
    const runtime = Object.keys(runtimeDocument).length ? runtimeDocument : embeddedRuntime;
    const fallbackKey = rawFile.name
      .replace(/\.zip$/i, '')
      .toLowerCase()
      .replace(/[^a-z0-9._-]+/g, '-')
      .replace(/^[^a-z]+/, '');
    skillForm.skillKey = textValue(
      identity,
      'skillKey',
      textValue(packageDocument, 'skillKey', fallbackKey || 'imported-skill')
    );
    skillForm.name = textValue(
      identity,
      'name',
      textValue(packageDocument, 'name', rawFile.name.replace(/\.zip$/i, ''))
    );
    skillForm.description = textValue(identity, 'description', textValue(packageDocument, 'description'));
    skillForm.packageName = rawFile.name;
    applySkillDocument(skillForm, content, normalizedManifest, runtime);
    window.$message?.success('Skill 包已读取，请确认清单信息后保存');
  } catch (error) {
    window.$message?.error(error instanceof Error ? error.message : 'Skill 包读取失败');
  } finally {
    skillImporting.value = false;
  }
}

async function submitSkill() {
  if (!ensureResourcePermission('skill', editingSkill.value ? 'edit' : 'create')) return;
  if (!skillForm.name.trim() || (!editingSkill.value && (!skillForm.skillKey.trim() || !skillForm.content.trim()))) {
    window.$message?.warning('请填写 Skill 标识、名称和指令内容');
    return;
  }
  skillSubmitting.value = true;
  if (editingSkill.value) {
    const { error } = await updateSkill(
      editingSkill.value.id,
      skillForm.name.trim(),
      skillForm.description.trim() || undefined,
      editingSkill.value.revision
    );
    if (!error) {
      skillVisible.value = false;
      window.$message?.success('Skill 基本信息已更新');
      await loadSkills();
    }
  } else {
    const manifest = buildSkillManifest(skillForm);
    if (manifest) {
      const scope = normalizeSkillScope(
        skillForm.scopeType,
        skillForm.scopeId,
        canManageSharedSkillScopes.value,
        authStore.userInfo.userId
      );
      const payload: SaveSkillPayload & { skillKey: string } = {
        skillKey: skillForm.skillKey.trim(),
        name: skillForm.name.trim(),
        description: skillForm.description.trim() || undefined,
        scopeType: scope.scopeType,
        scopeId: scope.scopeId || undefined,
        content: skillForm.content,
        manifest,
        runtimeRequirements: buildSkillRuntime(skillForm)
      };
      const { error } = await createSkill(payload);
      if (!error) {
        skillVisible.value = false;
        window.$message?.success('Skill 草稿已创建');
        await loadSkills();
        if (skillImportFile.value) {
          const created = skills.value.find(item => item.skillKey === payload.skillKey);
          if (created) {
            const versions = await fetchSkillVersions(created.id);
            const draft = versions.data?.find(item => item.status === 'draft');
            if (!versions.error && draft) {
              const bundle = await uploadSkillArchive(created.id, draft.id, skillImportFile.value);
              if (bundle.error) window.$message?.error(requestErrorMessage(bundle.error));
              else window.$message?.success('Skill 完整文件包已保存');
            }
          }
        }
      }
    }
  }
  skillSubmitting.value = false;
}

async function manageSkill(row: SkillView) {
  if (!canManageSkill(row)) {
    window.$message?.warning('只能管理自己创建的 Skill 版本');
    return;
  }
  if (!ensureResourcePermission('skill', 'operate')) return;
  activeSkill.value = row;
  const { data, error } = await fetchSkillVersions(row.id);
  if (!error) {
    skillVersions.value = data;
    const seed =
      data.find(item => item.status === 'draft') || data.find(item => item.status === 'published') || data[0];
    Object.assign(skillVersionForm, newSkillDocument());
    activeSkillVersionId.value = seed?.id || null;
    clearSelectedSkillFile();
    if (seed) {
      applySkillDocument(skillVersionForm, seed.content, seed.manifest, seed.runtimeRequirements);
      await Promise.all([loadSkillFiles(row.id, seed.id), loadSkillDependencyInstall(row.id, seed.id)]);
    } else {
      skillFiles.value = [];
      skillDependencyInstall.value = null;
      clearSelectedSkillFile();
    }
    versionVisible.value = true;
  }
}

async function loadSkillFiles(skillId: string, versionId: string) {
  skillFilesLoading.value = true;
  skillFilesError.value = '';
  const result = await fetchSkillFiles(skillId, versionId);
  if (result.error) {
    skillFilesError.value = requestErrorMessage(result.error);
    skillFiles.value = [];
  } else {
    skillFiles.value = result.data;
  }
  skillFilesLoading.value = false;
}

async function loadSkillDependencyInstall(skillId: string, versionId: string) {
  skillDependencyLoading.value = true;
  const result = await fetchSkillDependencyInstall(skillId, versionId);
  skillDependencyInstall.value = result.error ? null : result.data;
  skillDependencyLoading.value = false;
}

async function installSelectedSkillDependencies() {
  if (!activeSkill.value || !activeSkillVersionId.value) return;
  skillDependencyInstalling.value = true;
  const result = await installSkillDependencies(activeSkill.value.id, activeSkillVersionId.value);
  if (result.error) {
    window.$message?.error(requestErrorMessage(result.error));
  } else {
    skillDependencyInstall.value = result.data;
    window.$message?.success(
      result.data.status === 'succeeded' ? 'Skill 依赖安装完成' : result.data.message || '依赖安装状态已更新'
    );
  }
  skillDependencyInstalling.value = false;
}

async function selectSkillVersion(item: SkillVersionView) {
  if (!activeSkill.value) return;
  if (!(await confirmDiscardSkillFile())) return;
  activeSkillVersionId.value = item.id;
  clearSelectedSkillFile();
  Object.assign(skillVersionForm, newSkillDocument());
  applySkillDocument(skillVersionForm, item.content, item.manifest, item.runtimeRequirements);
  await Promise.all([
    loadSkillFiles(activeSkill.value.id, item.id),
    loadSkillDependencyInstall(activeSkill.value.id, item.id)
  ]);
}

async function inspectSkillFile(file: SkillFileView) {
  if (!activeSkill.value || !activeSkillVersionId.value || selectedSkillFile.value?.path === file.path) return;
  if (!(await confirmDiscardSkillFile())) return;
  if (file.fileKind === 'directory') {
    setSelectedSkillFile(file);
    return;
  }
  const result = await fetchSkillFile(activeSkill.value.id, activeSkillVersionId.value, file.path);
  if (!result.error) setSelectedSkillFile(result.data);
  else window.$message?.error(requestErrorMessage(result.error));
}

function setSelectedSkillFile(file: SkillFileView) {
  selectedSkillFile.value = file;
  skillFileContent.value = file.content || '';
  skillFileBaseline.value = file.content || '';
}

function clearSelectedSkillFile() {
  selectedSkillFile.value = null;
  skillFileContent.value = '';
  skillFileBaseline.value = '';
}

function confirmDiscardSkillFile() {
  if (!skillFileDirty.value) return Promise.resolve(true);
  return new Promise<boolean>(resolve => {
    let settled = false;
    const finish = (value: boolean) => {
      if (settled) return;
      settled = true;
      resolve(value);
    };
    dialog.warning({
      title: '放弃未保存修改',
      content: `${selectedSkillFile.value?.path || '当前文件'} 的修改尚未保存。`,
      positiveText: '放弃修改',
      negativeText: '继续编辑',
      onPositiveClick: () => finish(true),
      onNegativeClick: () => finish(false),
      onClose: () => finish(false)
    });
  });
}

async function saveSkillFile() {
  if (!activeSkill.value || !activeSkillVersionId.value || !selectedSkillFile.value || !skillFileDirty.value) return;
  skillFileSaving.value = true;
  const result = await putSkillFile(
    activeSkill.value.id,
    activeSkillVersionId.value,
    selectedSkillFile.value.path,
    skillFileContent.value
  );
  skillFileSaving.value = false;
  if (result.error) {
    window.$message?.error(requestErrorMessage(result.error));
    return;
  }
  setSelectedSkillFile(result.data);
  skillFiles.value = skillFiles.value.map(file =>
    file.path === result.data.path ? { ...file, ...result.data, content: null } : file
  );
  if (result.data.path === 'SKILL.md') {
    const versions = await fetchSkillVersions(activeSkill.value.id);
    if (!versions.error) {
      skillVersions.value = versions.data;
      const current = versions.data.find(item => item.id === activeSkillVersionId.value);
      if (current) applySkillDocument(skillVersionForm, current.content, current.manifest, current.runtimeRequirements);
    }
  }
  window.$message?.success('文件已保存');
}

function openSkillFileCreate(kind: 'file' | 'directory' = 'file') {
  const folder = selectedSkillFile.value?.fileKind === 'directory' ? `${selectedSkillFile.value.path}/` : '';
  Object.assign(skillFileEntryForm, { path: folder, kind });
  skillFileCreateVisible.value = true;
}

async function createSkillFile() {
  if (!activeSkill.value || !activeSkillVersionId.value || !skillFileEntryForm.path.trim()) {
    window.$message?.warning('请填写相对文件路径');
    return;
  }
  skillFileCreating.value = true;
  const result = await createSkillFileEntry(
    activeSkill.value.id,
    activeSkillVersionId.value,
    skillFileEntryForm.path.trim(),
    skillFileEntryForm.kind
  );
  skillFileCreating.value = false;
  if (result.error) {
    window.$message?.error(requestErrorMessage(result.error));
    return;
  }
  skillFileCreateVisible.value = false;
  await loadSkillFiles(activeSkill.value.id, activeSkillVersionId.value);
  const created = skillFiles.value.find(file => file.path === result.data.path);
  if (created) await inspectSkillFile(created);
  window.$message?.success(skillFileEntryForm.kind === 'directory' ? '目录已创建' : '文件已创建');
}

async function handleSkillFileUpload(options: { file: UploadFileInfo }) {
  const file = options.file.file;
  if (!file || !activeSkill.value || !activeSkillVersionId.value) return;
  if (!(await confirmDiscardSkillFile())) {
    skillFileUploadKey.value += 1;
    return;
  }
  const folder = selectedSkillFile.value?.fileKind === 'directory' ? `${selectedSkillFile.value.path}/` : '';
  skillFileUploading.value = true;
  const result = await uploadSkillFile(activeSkill.value.id, activeSkillVersionId.value, `${folder}${file.name}`, file);
  skillFileUploading.value = false;
  skillFileUploadKey.value += 1;
  if (result.error) {
    window.$message?.error(requestErrorMessage(result.error));
    return;
  }
  clearSelectedSkillFile();
  await loadSkillFiles(activeSkill.value.id, activeSkillVersionId.value);
  const uploaded = skillFiles.value.find(item => item.path === result.data.path);
  if (uploaded) await inspectSkillFile(uploaded);
  window.$message?.success('文件已上传');
}

function confirmDeleteSkillFile(file: SkillFileView) {
  if (!activeSkill.value || !activeSkillVersionId.value || file.path === 'SKILL.md') return;
  dialog.warning({
    title: file.fileKind === 'directory' ? '删除目录' : '删除文件',
    content: file.fileKind === 'directory' ? `将删除 ${file.path} 及其全部子文件。` : `确认删除 ${file.path}？`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: () => removeSkillFile(file)
  });
}

async function removeSkillFile(file: SkillFileView) {
  if (!activeSkill.value || !activeSkillVersionId.value) return;
  const result = await deleteSkillFile(activeSkill.value.id, activeSkillVersionId.value, file.path);
  if (result.error) {
    window.$message?.error(requestErrorMessage(result.error));
    return;
  }
  if (selectedSkillFile.value?.path === file.path || selectedSkillFile.value?.path.startsWith(`${file.path}/`))
    clearSelectedSkillFile();
  await loadSkillFiles(activeSkill.value.id, activeSkillVersionId.value);
  window.$message?.success('文件已删除');
}

async function handleSkillBundleUpload(options: { file: UploadFileInfo }) {
  const file = options.file.file;
  if (!file || !activeSkill.value || !activeSkillVersionId.value) return;
  if (!(await confirmDiscardSkillFile())) return;
  skillBundleUploading.value = true;
  const result = await uploadSkillArchive(activeSkill.value.id, activeSkillVersionId.value, file);
  if (result.error) {
    window.$message?.error(requestErrorMessage(result.error));
  } else {
    skillFiles.value = result.data;
    clearSelectedSkillFile();
    window.$message?.success('Skill 完整文件包已导入');
  }
  skillBundleUploading.value = false;
}

async function exportSkillBundle() {
  if (!activeSkill.value || !activeSkillVersionId.value) return;
  try {
    const result = await downloadSkillArchive(activeSkill.value.id, activeSkillVersionId.value);
    const url = URL.createObjectURL(result.blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = result.fileName;
    anchor.click();
    URL.revokeObjectURL(url);
    window.$message?.success('Skill 完整文件包已导出');
  } catch (error) {
    window.$message?.error(error instanceof Error ? error.message : 'Skill 文件包导出失败');
  }
}

async function addSkillVersion() {
  if (!ensureResourcePermission('skill', 'edit')) return;
  if (!activeSkill.value || !skillVersionForm.content.trim()) {
    window.$message?.warning('请填写 Skill 指令内容');
    return;
  }
  const manifest = buildSkillManifest(skillVersionForm);
  if (!manifest) return;
  versionSubmitting.value = true;
  const { error } = await createSkillVersion(activeSkill.value.id, {
    content: skillVersionForm.content,
    manifest,
    runtimeRequirements: buildSkillRuntime(skillVersionForm),
    expectedRevision: activeSkill.value.revision
  });
  if (!error) {
    window.$message?.success('Skill 新草稿版本已创建');
    await loadSkills();
    const refreshed = skills.value.find(item => item.id === activeSkill.value?.id);
    if (refreshed) await manageSkill(refreshed);
  }
  versionSubmitting.value = false;
}

async function cloneSkillDraft(row: SkillVersionView) {
  if (!ensureResourcePermission('skill', 'edit') || !activeSkill.value) return;
  if (!(await confirmDiscardSkillFile())) return;
  skillVersionUpdatingId.value = row.id;
  const result = await cloneSkillVersion(activeSkill.value.id, row.id, activeSkill.value.revision);
  skillVersionUpdatingId.value = null;
  if (result.error) {
    window.$message?.error(requestErrorMessage(result.error));
    return;
  }
  window.$message?.success(`已从 v${row.versionNo} 复制完整文件包并创建新草稿`);
  await loadSkills();
  const refreshed = skills.value.find(item => item.id === activeSkill.value?.id);
  if (refreshed) await manageSkill(refreshed);
}

async function removeSkillDraft(row: SkillVersionView) {
  if (!ensureResourcePermission('skill', 'delete') || !activeSkill.value) return;
  skillVersionUpdatingId.value = row.id;
  const result = await deleteSkillVersion(activeSkill.value.id, row.id, activeSkill.value.revision);
  skillVersionUpdatingId.value = null;
  if (result.error) {
    window.$message?.error(requestErrorMessage(result.error));
    return;
  }
  window.$message?.success(`Skill 草稿 v${row.versionNo} 已删除`);
  await loadSkills();
  const refreshed = skills.value.find(item => item.id === activeSkill.value?.id);
  if (refreshed) await manageSkill(refreshed);
}

function confirmDeleteSkillDraft(row: SkillVersionView) {
  dialog.warning({
    title: '删除草稿版本',
    content: `确认删除 v${row.versionNo} 及其完整文件包？已发布和已归档版本不会提供此操作。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: () => removeSkillDraft(row)
  });
}

async function changeSkillVersion(row: SkillVersionView, action: 'publish' | 'archive') {
  if (!ensureResourcePermission('skill', action)) return;
  if (!activeSkill.value) return;
  if (!(await confirmDiscardSkillFile())) return;
  const result =
    action === 'publish'
      ? await publishSkillVersion(activeSkill.value.id, row.id, activeSkill.value.revision)
      : await archiveSkillVersion(activeSkill.value.id, row.id, activeSkill.value.revision);
  if (!result.error) {
    window.$message?.success(action === 'publish' ? 'Skill 版本已发布' : 'Skill 版本已归档');
    await loadSkills();
    const refreshed = skills.value.find(item => item.id === activeSkill.value?.id);
    if (refreshed) await manageSkill(refreshed);
  }
}

async function handleVersionDrawerVisibility(show: boolean) {
  if (show) {
    versionVisible.value = true;
    return;
  }
  if (!(await confirmDiscardSkillFile())) return;
  versionVisible.value = false;
  clearSelectedSkillFile();
}

function warnUnsavedSkillFile(event: BeforeUnloadEvent) {
  if (!skillFileDirty.value) return;
  event.preventDefault();
  event.returnValue = '';
}

async function loadMemories() {
  if (!ensureResourcePermission('memory', 'list')) return;
  const scope = normalizeMemoryScope(
    memoryScopeType.value,
    memoryScopeId.value,
    canManageSharedMemory.value,
    authStore.userInfo.userId
  );
  memoryScopeType.value = scope.scopeType;
  memoryScopeId.value = scope.scopeId;
  if (!/^\d+$/.test(scope.scopeId)) {
    window.$message?.warning('范围 ID 必须是正整数');
    return;
  }
  memoryLoading.value = true;
  const { data, error } = await fetchMemories(scope.scopeType, scope.scopeId, memorySearch.value.trim() || undefined);
  if (!error) {
    memories.value = canManageSharedMemory.value
      ? data
      : data.filter(row => row.scopeType === 'user' && row.scopeId === authStore.userInfo.userId);
  }
  memoryLoading.value = false;
}

function openMemory(row: MemoryView) {
  if (!ensureResourcePermission('memory', 'edit')) return;
  if (!canAccessMemoryScope(row)) return;
  editingMemory.value = row;
  const metadata = objectValue(row.metadata);
  Object.assign(memoryForm, {
    memoryKey: row.memoryKey,
    memoryType: row.memoryType,
    content: row.content,
    sourceType: row.sourceType,
    sourceId: row.sourceId ? Number(row.sourceId) : null,
    confidence: row.confidence ?? 1,
    sensitiveLevel: row.sensitiveLevel,
    expiresAt: row.expiresAt || null,
    title: textValue(metadata, 'title'),
    category: textValue(metadata, 'category'),
    tags: textList(metadata.tags),
    language: textValue(metadata, 'language', 'zh-CN'),
    sourceLabel: textValue(metadata, 'sourceLabel'),
    sourceUrl: textValue(metadata, 'sourceUrl'),
    retentionNote: textValue(metadata, 'retentionNote'),
    verified: booleanValue(metadata, 'verified', false)
  });
  memoryVisible.value = true;
}

function resetMemoryForm() {
  Object.assign(memoryForm, {
    memoryKey: '',
    memoryType: 'fact',
    content: '',
    sourceType: 'manual',
    sourceId: null,
    confidence: 1,
    sensitiveLevel: 'internal',
    expiresAt: null,
    title: '',
    category: '',
    tags: [],
    language: 'zh-CN',
    sourceLabel: '',
    sourceUrl: '',
    retentionNote: '',
    verified: false
  });
}

function openNewMemory() {
  if (!ensureResourcePermission('memory', 'create')) return;
  editingMemory.value = null;
  resetMemoryForm();
  memoryVisible.value = true;
}

async function submitMemory() {
  if (!ensureResourcePermission('memory', editingMemory.value ? 'edit' : 'create')) return;
  if (editingMemory.value && !canAccessMemoryScope(editingMemory.value)) return;
  if (!/^[a-z][a-z0-9._-]{0,127}$/.test(memoryForm.memoryKey.trim().toLowerCase())) {
    window.$message?.warning('记忆标识需以小写字母开头，只能包含小写字母、数字、点、下划线或短横线');
    return;
  }
  if (!memoryForm.content.trim()) {
    window.$message?.warning('请填写记忆内容');
    return;
  }
  if (memoryForm.sourceType !== 'manual' && !memoryForm.sourceId) {
    window.$message?.warning('非人工记忆需要选择来源记录');
    return;
  }
  const metadata: Record<string, unknown> = {
    language: memoryForm.language,
    verified: memoryForm.verified
  };
  if (memoryForm.title.trim()) metadata.title = memoryForm.title.trim();
  if (memoryForm.category.trim()) metadata.category = memoryForm.category.trim();
  if (memoryForm.tags.length) metadata.tags = memoryForm.tags;
  if (memoryForm.sourceLabel.trim()) metadata.sourceLabel = memoryForm.sourceLabel.trim();
  if (memoryForm.sourceUrl.trim()) metadata.sourceUrl = memoryForm.sourceUrl.trim();
  if (memoryForm.retentionNote.trim()) metadata.retentionNote = memoryForm.retentionNote.trim();
  const payload: SaveMemoryPayload = {
    memoryType: memoryForm.memoryType,
    content: memoryForm.content.trim(),
    sourceType: memoryForm.sourceType,
    sourceId: memoryForm.sourceType === 'manual' ? undefined : String(memoryForm.sourceId),
    confidence: memoryForm.confidence,
    sensitiveLevel: memoryForm.sensitiveLevel,
    expiresAt: memoryForm.expiresAt || undefined,
    metadata
  };
  const scope = normalizeMemoryScope(
    memoryScopeType.value,
    memoryScopeId.value,
    canManageSharedMemory.value,
    authStore.userInfo.userId
  );
  memorySubmitting.value = true;
  const result = editingMemory.value
    ? await updateMemory(editingMemory.value.id, editingMemory.value.revisionNo, payload)
    : await createMemory(scope.scopeType, scope.scopeId, {
        ...payload,
        memoryKey: memoryForm.memoryKey.trim()
      });
  if (!result.error) {
    memoryVisible.value = false;
    window.$message?.success(editingMemory.value ? '记忆已保存并重新进入审核' : '记忆已创建，等待审核');
    await loadMemories();
  }
  memorySubmitting.value = false;
}

async function decideMemory(row: MemoryView, decision: 'approved' | 'rejected') {
  if (!ensureResourcePermission('memory', 'operate')) return;
  const { error } = await reviewMemory(row.id, row.revisionNo, decision);
  if (!error) {
    window.$message?.success(decision === 'approved' ? '记忆已批准' : '记忆已拒绝');
    await loadMemories();
  }
}

function confirmDeleteMemory(row: MemoryView) {
  if (!ensureResourcePermission('memory', 'delete')) return;
  if (!canAccessMemoryScope(row)) return;
  dialog.warning({
    title: '删除记忆',
    content: '确认删除“' + row.memoryKey + '”？该操作使用软删除并保留审计。',
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      const { error } = await deleteMemory(row.id, row.revisionNo);
      if (!error) {
        window.$message?.success('记忆已删除');
        await loadMemories();
      }
    }
  });
}

const modelColumns: DataTableColumns<ModelView> = [
  {
    title: '模型',
    key: 'displayName',
    width: 420,
    render: row =>
      h('div', { class: 'primary-cell' }, [
        h('strong', row.displayName),
        h('span', row.modelKey + ' · ' + row.modelName)
      ])
  },
  { title: 'Provider', key: 'providerType', width: 180 },
  { title: '类型', key: 'modelType', width: 120 },
  {
    title: '状态',
    key: 'status',
    width: 110,
    render: row => h(NTag, { size: 'small', type: statusType(row.status) }, () => modelStatusText(row.status))
  },
  {
    title: 'API Key',
    key: 'apiKeyConfigured',
    width: 140,
    render: row =>
      h(NTag, { size: 'small', type: row.apiKeyConfigured ? 'success' : 'warning' }, () =>
        row.apiKeyConfigured ? '已配置' : '未配置'
      )
  },
  {
    title: '操作',
    key: 'actions',
    width: 230,
    fixed: 'right',
    render: row =>
      h(NSpace, { size: 8 }, () => [
        canResource('model', 'edit')
          ? h(NButton, { size: 'tiny', text: true, type: 'primary', onClick: () => openModel(row) }, () => '编辑')
          : null,
        canResource('model', 'operate')
          ? h(
              NButton,
              {
                size: 'tiny',
                text: true,
                onClick: () => {
                  if (!ensureResourcePermission('model', 'operate')) return;
                  void testModel(row.id).then(result => {
                    if (!result.error)
                      window.$message?.[result.data.success ? 'success' : 'error'](result.data.message);
                  });
                }
              },
              () => '测试'
            )
          : null,
        h(NButton, { size: 'tiny', text: true, onClick: () => showModelReferences(row) }, () => '引用'),
        canResource('model', 'delete')
          ? h(
              NButton,
              { size: 'tiny', text: true, type: 'error', onClick: () => confirmDeleteModel(row) },
              () => '删除'
            )
          : null
      ])
  }
];

const connectorColumns: DataTableColumns<ConnectorView> = [
  {
    title: '连接器',
    key: 'name',
    minWidth: 250,
    render: row =>
      h('div', { class: 'primary-cell' }, [
        h('strong', row.name),
        h('span', row.connectorKey + ' · ' + row.endpointUrl)
      ])
  },
  { title: '类型', key: 'providerType', width: 90 },
  {
    title: '可见范围',
    key: 'scope',
    width: 110,
    render: row =>
      h(NTag, { size: 'small', type: row.scope === 'global' ? 'info' : 'default' }, () =>
        row.scope === 'global' ? '企业共享' : '个人私有'
      )
  },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: row => h(NTag, { size: 'small', type: statusType(row.status) }, () => connectorStatusText(row.status))
  },
  { title: '最近检查', key: 'lastCheckAt', width: 170, render: row => formatTime(row.lastCheckAt) },
  { title: '错误', key: 'lastError', minWidth: 180, ellipsis: { tooltip: true }, render: row => row.lastError || '-' },
  {
    title: '操作',
    key: 'actions',
    width: 500,
    fixed: 'right',
    render: row =>
      h(NSpace, { size: 8 }, () => [
        row.manageable && canResource('connector', 'edit')
          ? h(NButton, { size: 'tiny', text: true, type: 'primary', onClick: () => openConnector(row) }, () => '编辑')
          : null,
        row.manageable && row.providerType === 'mcp' && canResource('connector', 'operate')
          ? h(
              NButton,
              {
                size: 'tiny',
                text: true,
                loading: connectorTestingId.value === row.id,
                disabled: row.status !== 'active',
                title: row.status === 'active' ? '执行真实 MCP 协议握手' : '请先启用连接器',
                onClick: () => runConnectorTest(row)
              },
              () => '测试连接'
            )
          : null,
        row.manageable && row.providerType === 'mcp' && canResource('connector', 'operate')
          ? h(
              NButton,
              {
                size: 'tiny',
                text: true,
                loading: connectorDiscoveringId.value === row.id,
                disabled: row.status !== 'active',
                onClick: () => runDiscovery(row)
              },
              () => '同步工具'
            )
          : null,
        row.manageable &&
        row.providerType === 'mcp' &&
        canResource('connector', 'operate') &&
        canResource('tool', 'list')
          ? h(NButton, { size: 'tiny', text: true, onClick: () => openMcpToolWorkbench(row) }, () => '工具管理')
          : null,
        row.manageable && row.providerType === 'mcp' && canResource('connector', 'operate')
          ? h(NButton, { size: 'tiny', text: true, onClick: () => openDiscoveries(row) }, () => '发现历史')
          : null,
        row.manageable && row.providerType === 'mcp' && canResource('connector', 'operate')
          ? h(NButton, { size: 'tiny', text: true, onClick: () => openRuntimeOverview(row) }, () => '运行观测')
          : null,
        row.manageable && canResource('connector', 'delete')
          ? h(
              NButton,
              { size: 'tiny', text: true, type: 'error', onClick: () => confirmDeleteConnector(row) },
              () => '删除'
            )
          : null
      ])
  }
];

const mcpToolColumns: DataTableColumns<ToolView> = [
  {
    type: 'selection',
    disabled: row => !row.available || row.status === 'deprecated'
  },
  {
    title: '工具',
    key: 'name',
    minWidth: 240,
    render: row =>
      h('div', { class: 'primary-cell' }, [h('strong', row.name), h('span', row.externalName || row.toolKey)])
  },
  { title: '风险', key: 'riskLevel', width: 80 },
  {
    title: '远端状态',
    key: 'available',
    width: 110,
    render: row =>
      h(NTag, { size: 'small', type: row.available && row.status !== 'deprecated' ? 'success' : 'error' }, () =>
        row.available && row.status !== 'deprecated' ? '可用' : '远端已失效'
      )
  },
  {
    title: '发布状态',
    key: 'status',
    width: 110,
    render: row =>
      h(NTag, { size: 'small', type: row.status === 'active' ? 'success' : 'default' }, () =>
        row.status === 'active' ? '已发布' : '未发布'
      )
  },
  { title: 'Agent 引用', key: 'usageCount', width: 105 },
  {
    title: '操作',
    key: 'actions',
    width: 190,
    fixed: 'right',
    render: row =>
      h(NSpace, { size: 8 }, () => [
        canResource('tool', 'operate')
          ? h(
              NButton,
              {
                size: 'tiny',
                text: true,
                disabled: row.status !== 'active' || !row.available,
                onClick: () => openToolTest(row)
              },
              () => '在线测试'
            )
          : null,
        canResource('tool', 'operate')
          ? h(
              NButton,
              {
                size: 'tiny',
                text: true,
                loading: toolStatusUpdatingId.value === row.id,
                disabled:
                  !row.available ||
                  row.status === 'deprecated' ||
                  (row.status !== 'active' && mcpToolsConnector.value?.status !== 'active'),
                onClick: () => setMcpToolStatus(row, row.status === 'active' ? 'disabled' : 'active')
              },
              () => (row.status === 'active' ? '撤回' : '发布')
            )
          : null
      ])
  }
];

const discoveryColumns: DataTableColumns<McpDiscoveryView> = [
  {
    title: '状态',
    key: 'status',
    width: 105,
    render: row =>
      h(NTag, { size: 'small', type: discoveryStatusType(row.status) }, () => discoveryStatusText(row.status))
  },
  { title: '协议', key: 'protocolVersion', width: 110, render: row => row.protocolVersion || '-' },
  { title: '工具数', key: 'toolCount', width: 90 },
  { title: '开始时间', key: 'startedAt', width: 175, render: row => formatTime(row.startedAt) },
  { title: '完成时间', key: 'completedAt', width: 175, render: row => formatTime(row.completedAt) },
  {
    title: '摘要',
    key: 'summary',
    minWidth: 220,
    ellipsis: { tooltip: true },
    render: row => row.errorSummary || (row.contentHash ? `内容指纹 ${row.contentHash}` : '-')
  }
];

const runtimeMountColumns: DataTableColumns<McpRuntimeMountView> = [
  {
    title: '挂载范围',
    key: 'scopeType',
    width: 110,
    render: row =>
      h(NTag, { size: 'small', type: row.scopeType === 'run' ? 'info' : 'default' }, () =>
        row.scopeType === 'run' ? '运行级' : '会话级'
      )
  },
  {
    title: '状态',
    key: 'status',
    width: 110,
    render: row => h(NTag, { size: 'small', type: mountStatusType(row.status) }, () => mountStatusText(row.status))
  },
  {
    title: '关联对象',
    key: 'identity',
    minWidth: 165,
    render: row =>
      row.runId ? `运行 ${row.runId}${row.stepId ? ` / 步骤 ${row.stepId}` : ''}` : `会话 ${row.conversationId || '-'}`
  },
  {
    title: '连接 / 重连',
    key: 'connections',
    width: 125,
    render: row => `${row.connectionAttempts} / ${row.reconnectCount}`
  },
  {
    title: '调用 / 失败',
    key: 'invocations',
    width: 120,
    render: row => `${row.invocationCount} / ${row.failureCount}`
  },
  { title: '最近使用', key: 'lastUsedAt', width: 175, render: row => formatTime(row.lastUsedAt) },
  {
    title: '错误摘要',
    key: 'lastErrorSummary',
    minWidth: 220,
    ellipsis: { tooltip: true },
    render: row => row.lastErrorSummary || '-'
  }
];

const runtimeUsageColumns: DataTableColumns<McpUsageDetailView> = [
  { title: '远端工具', key: 'externalToolName', minWidth: 160, ellipsis: { tooltip: true } },
  {
    title: '结果',
    key: 'status',
    width: 120,
    render: row => h(NTag, { size: 'small', type: usageStatusType(row.status) }, () => usageStatusText(row.status))
  },
  {
    title: '运行上下文',
    key: 'runtime',
    minWidth: 160,
    render: row => (row.runId ? `运行 ${row.runId}` : `会话 ${row.conversationId || '-'}`)
  },
  { title: '用户', key: 'userId', width: 105 },
  { title: '尝试', key: 'attemptCount', width: 75 },
  { title: '耗时', key: 'latencyMs', width: 95, render: row => `${row.latencyMs} ms` },
  {
    title: '请求 / 响应',
    key: 'traffic',
    width: 145,
    render: row => `${formatMcpBytes(row.requestBytes)} / ${formatMcpBytes(row.responseBytes)}`
  },
  { title: '开始时间', key: 'startedAt', width: 175, render: row => formatTime(row.startedAt) },
  {
    title: '错误摘要',
    key: 'errorSummary',
    minWidth: 220,
    ellipsis: { tooltip: true },
    render: row => row.errorSummary || '-'
  }
];

const mcpAgentUsageColumns: DataTableColumns<McpConnectorUsageView['agents'][number]> = [
  {
    title: 'Agent',
    key: 'name',
    minWidth: 190,
    render: row =>
      h('div', { class: 'primary-cell' }, [h('strong', row.displayName || row.name), h('span', `#${row.id}`)])
  },
  {
    title: '状态',
    key: 'active',
    width: 115,
    render: row =>
      h(
        NTag,
        {
          size: 'small',
          type: row.active ? 'success' : row.isEnabled ? 'warning' : 'default'
        },
        () => (row.active ? '当前生效' : row.isEnabled ? 'Agent 已启用' : 'Agent 未启用')
      )
  },
  { title: '绑定版本', key: 'versionCount', width: 100, render: row => `${row.versionCount} 个` }
];

const toolColumns: DataTableColumns<ToolView> = [
  {
    title: '工具',
    key: 'name',
    minWidth: 240,
    render: row =>
      h('div', { class: 'primary-cell' }, [h('strong', row.name), h('span', row.toolKey + ' · v' + row.versionNo)])
  },
  { title: '类型', key: 'toolType', width: 90 },
  { title: '风险', key: 'riskLevel', width: 75 },
  {
    title: '执行器',
    key: 'runtimeExecution',
    width: 105,
    render: row =>
      h(NTag, { size: 'small', type: row.runtimeExecution === 'local' ? 'success' : 'warning' }, () =>
        row.runtimeExecution === 'local' ? '本地已实现' : row.runtimeExecution === 'unavailable' ? '未实现' : '已配置'
      )
  },
  {
    title: '状态',
    key: 'status',
    width: 120,
    render: row =>
      h(NTag, { size: 'small', type: statusType(row.status) }, () => {
        const status =
          row.toolType === 'mcp'
            ? row.status === 'active'
              ? '已发布'
              : row.status === 'disabled'
                ? '未发布'
                : '已失效'
            : toolStatusText(row.status);
        if (!row.available) return `${status} / 不可用`;
        return row.toolType === 'mcp' && !mcpToolConnectorActive(row) ? `${status} / 服务停用` : status;
      })
  },
  { title: 'Agent 引用', key: 'usageCount', width: 105 },
  {
    title: '依赖资源',
    key: 'connectorId',
    minWidth: 160,
    render: row => {
      if (row.toolType === 'sql') {
        const datasetId = parseSqlToolPolicy(objectValue(row.executionPolicy)).datasetId;
        if (!datasetId) return '数据集配置缺失';
        return sqlDatasets.value.find(item => item.id === datasetId)?.name || `数据集 #${datasetId}`;
      }
      return connectors.value.find(item => item.id === row.connectorId)?.name || '-';
    }
  },
  {
    title: '操作',
    key: 'actions',
    width: 290,
    fixed: 'right',
    render: row =>
      h(NSpace, { size: 8 }, () => [
        row.toolType !== 'mcp' && canResource('tool', 'edit')
          ? h(NButton, { size: 'tiny', text: true, type: 'primary', onClick: () => openTool(row) }, () => '新版本')
          : null,
        ['builtin', 'mcp', 'api', 'search', 'sql'].includes(row.toolType) && canResource('tool', 'operate')
          ? h(
              NButton,
              {
                size: 'tiny',
                text: true,
                disabled:
                  row.status !== 'active' || !row.available || (row.toolType === 'mcp' && !mcpToolConnectorActive(row)),
                title:
                  row.status === 'active' && row.available && mcpToolConnectorActive(row)
                    ? row.toolType === 'sql'
                      ? '使用当前账号权限执行受治理只读查询'
                      : row.toolType === 'builtin'
                        ? '复用生产内置工具执行器进行真实测试'
                        : '使用当前参数真实调用工具'
                    : '工具未启用或依赖资源不可用',
                onClick: () => openToolTest(row)
              },
              () => '在线测试'
            )
          : null,
        canResource('tool', 'operate')
          ? h(
              NButton,
              {
                size: 'tiny',
                text: true,
                loading: toolStatusUpdatingId.value === row.id,
                disabled:
                  row.toolType === 'mcp' &&
                  (!row.available ||
                    row.status === 'deprecated' ||
                    (row.status !== 'active' && !mcpToolConnectorActive(row))),
                title:
                  row.toolType === 'mcp'
                    ? row.available &&
                      row.status !== 'deprecated' &&
                      (row.status === 'active' || mcpToolConnectorActive(row))
                      ? row.status === 'active'
                        ? '撤回 MCP 工具发布状态'
                        : '发布 MCP 工具供 Agent 使用'
                      : '远端工具不可用，请先重新发现'
                    : undefined,
                onClick: () => toggleTool(row)
              },
              () =>
                row.toolType === 'mcp'
                  ? row.status === 'active'
                    ? '撤回'
                    : '发布'
                  : row.status === 'active'
                    ? '停用'
                    : '启用'
            )
          : null,
        canResource('tool', 'delete')
          ? h(
              NButton,
              {
                size: 'tiny',
                text: true,
                type: 'error',
                disabled: row.toolType === 'mcp',
                title:
                  row.toolType === 'mcp' ? 'MCP 工具随连接器发现目录管理' : '删除全部版本（有 Agent 引用时会拒绝）',
                onClick: () => confirmDeleteTool(row)
              },
              () => '删除'
            )
          : null
      ])
  }
];

const skillColumns: DataTableColumns<SkillView> = [
  {
    title: 'Skill',
    key: 'name',
    minWidth: 250,
    render: row =>
      h('div', { class: 'primary-cell' }, [
        h('strong', row.name),
        h('span', row.skillKey + ' · ' + row.scopeType + (row.scopeId ? ' #' + row.scopeId : ''))
      ])
  },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: row => h(NTag, { size: 'small', type: skillStatusType(row) }, () => skillStatusText(row))
  },
  {
    title: '发布版本',
    key: 'publishedVersionNo',
    width: 110,
    render: row => (row.publishedVersionNo ? 'v' + row.publishedVersionNo : '-')
  },
  {
    title: '系统发布',
    key: 'publicationStatus',
    width: 110,
    render: row => {
      const publication = skillPublicationStatuses.value[row.id];
      if (row.scopeType !== 'user' || row.ownerId !== authStore.userInfo.userId) return '-';
      return h(
        NTag,
        {
          size: 'small',
          type: skillPublicationStatusType(publication?.publication_status)
        },
        () => skillPublicationStatusText(publication?.publication_status)
      );
    }
  },
  { title: '更新时间', key: 'updatedAt', width: 170, render: row => formatTime(row.updatedAt || row.createdAt) },
  {
    title: '操作',
    key: 'actions',
    width: 430,
    fixed: 'right',
    render: row =>
      h(NSpace, { size: 8 }, () => [
        canManageSkill(row) && canResource('skill', 'edit')
          ? h(NButton, { size: 'tiny', text: true, type: 'primary', onClick: () => openSkill(row) }, () => '编辑')
          : null,
        canManageSkill(row) && canResource('skill', 'operate')
          ? h(NButton, { size: 'tiny', text: true, onClick: () => manageSkill(row) }, () => '版本')
          : null,
        canToggleSkill(row)
          ? h(
              NButton,
              {
                size: 'tiny',
                text: true,
                type: row.status === 'active' ? 'warning' : 'success',
                loading: skillLifecycleUpdatingId.value === row.id,
                onClick: () => confirmSkillStatus(row)
              },
              () => (row.status === 'active' ? '停用' : '启用')
            )
          : null,
        canPublishPersonalSkill(row) && skillPublicationStatuses.value[row.id]?.publication_status !== 'PENDING'
          ? h(
              NButton,
              {
                size: 'tiny',
                text: true,
                type: 'primary',
                loading: skillPublicationUpdatingId.value === row.id,
                onClick: () => confirmSkillPublication(row, 'submit')
              },
              () => (skillPublicationStatuses.value[row.id]?.current_public_version ? '提交新版' : '提交审核')
            )
          : null,
        canPublishPersonalSkill(row) && skillPublicationStatuses.value[row.id]?.publication_status === 'PENDING'
          ? h(
              NButton,
              {
                size: 'tiny',
                text: true,
                type: 'warning',
                loading: skillPublicationUpdatingId.value === row.id,
                onClick: () => confirmSkillPublication(row, 'withdraw')
              },
              () => '撤回'
            )
          : null,
        canManageSkill(row) && canResource('skill', 'delete')
          ? h(
              NButton,
              {
                size: 'tiny',
                text: true,
                type: 'error',
                loading: skillLifecycleUpdatingId.value === row.id,
                title: '仅允许删除从未发布且无引用的 Skill',
                onClick: () => confirmDeleteSkill(row)
              },
              () => '删除'
            )
          : null
      ])
  }
];

const memoryColumns: DataTableColumns<MemoryView> = [
  {
    title: '记忆',
    key: 'memoryKey',
    minWidth: 280,
    render: row => h('div', { class: 'primary-cell' }, [h('strong', row.memoryKey), h('span', row.content)])
  },
  { title: '类型', key: 'memoryType', width: 100 },
  { title: '敏感级别', key: 'sensitiveLevel', width: 105 },
  {
    title: '审核',
    key: 'reviewStatus',
    width: 100,
    render: row => h(NTag, { size: 'small', type: statusType(row.reviewStatus) }, () => row.reviewStatus)
  },
  { title: '更新时间', key: 'updatedAt', width: 170, render: row => formatTime(row.updatedAt || row.createdAt) },
  {
    title: '操作',
    key: 'actions',
    width: 230,
    fixed: 'right',
    render: row =>
      h(NSpace, { size: 8 }, () => [
        canResource('memory', 'edit')
          ? h(NButton, { size: 'tiny', text: true, type: 'primary', onClick: () => openMemory(row) }, () => '编辑')
          : null,
        row.reviewStatus === 'pending' && row.scopeType !== 'user' && canResource('memory', 'operate')
          ? h(
              NButton,
              { size: 'tiny', text: true, type: 'success', onClick: () => decideMemory(row, 'approved') },
              () => '批准'
            )
          : null,
        row.reviewStatus === 'pending' && row.scopeType !== 'user' && canResource('memory', 'operate')
          ? h(
              NButton,
              { size: 'tiny', text: true, type: 'warning', onClick: () => decideMemory(row, 'rejected') },
              () => '拒绝'
            )
          : null,
        canResource('memory', 'delete')
          ? h(
              NButton,
              { size: 'tiny', text: true, type: 'error', onClick: () => confirmDeleteMemory(row) },
              () => '删除'
            )
          : null
      ])
  }
];

let resourceCenterMounted = false;

function routeQueryValue(value: unknown) {
  return Array.isArray(value) ? String(value[0] || '') : typeof value === 'string' ? value : '';
}

function applyResourceCenterDeepLink() {
  const tab = routeQueryValue(route.query.tab).toLowerCase();
  const type = routeQueryValue(route.query.type).toLowerCase();
  if (
    (['mcp', 'connector', 'connectors'].includes(tab) || type === 'mcp') &&
    authorizedTabs.value.includes('connector')
  ) {
    activeTab.value = 'connector';
  }
  const scope = routeQueryValue(route.query.scope).toLowerCase();
  if (scope === 'global' || scope === 'personal') connectorScopeFilter.value = scope;
}

watch(memoryScopeType, value => {
  if (!canManageSharedMemory.value || value === 'user') memoryScopeId.value = authStore.userInfo.userId;
  memories.value = [];
});
watch(connectorScopeFilter, () => {
  if (resourceCenterMounted && activeTab.value === 'connector') void loadConnectors(true);
});
watch(mcpImportSourceKey, sourceKey => {
  applyMcpImportEntry(mcpImportEntries.value.find(entry => entry.sourceKey === sourceKey) || null);
});
watch(activeTab, value => {
  if (resourceCenterMounted) void loadAuthorizedTab(value);
});
watch(
  () => [route.query.tab, route.query.type, route.query.scope],
  () => applyResourceCenterDeepLink()
);
watch(
  authorizedTabs,
  tabs => {
    const next = resolveResourceTab(activeTab.value, tabs);
    if (next && next !== activeTab.value) {
      activeTab.value = next;
    } else if (next && resourceCenterMounted) {
      void loadAuthorizedTab(next);
    }
  },
  { immediate: true }
);
watch(canManageSharedMemory, allowed => {
  if (!allowed) {
    memoryScopeType.value = 'user';
    memoryScopeId.value = authStore.userInfo.userId;
  }
});
watch(isPlatformAdmin, allowed => {
  if (allowed) return;
  if (!editingConnector.value) connectorForm.scope = 'personal';
  mcpImportScope.value = 'personal';
});
watch(
  () => [
    connectorForm.name,
    connectorForm.endpointUrl,
    connectorForm.transport,
    connectorForm.authType,
    connectorForm.authHeader,
    connectorForm.credentialName,
    connectorForm.connectTimeoutMs,
    connectorForm.requestTimeoutMs
  ],
  () => {
    if (connectorDraftTesting.value) return;
    connectorDraftTestResult.value = null;
    connectorDraftTestError.value = null;
  }
);
watch(
  () => modelForm.providerPreset,
  value => {
    const preset = modelProviderPresets.find(item => item.value === value);
    if (!preset) return;
    modelForm.providerType = preset.providerType;
    if (preset.value !== 'custom') modelForm.endpointUrl = preset.endpoint;
    // The provider preset only changes the endpoint; API keys are always entered by the operator.
  }
);
watch(
  () => [toolForm.toolType, toolForm.method, toolForm.readOnly] as const,
  ([type, method, readOnly]) => {
    if (type !== 'api' && type !== 'search') toolForm.connectorId = null;
    if (
      (type === 'api' || type === 'search') &&
      toolForm.connectorId &&
      connectors.value.find(item => item.id === toolForm.connectorId)?.providerType !== type
    ) {
      toolForm.connectorId = null;
    }
    if (type === 'sql') {
      toolForm.readOnly = true;
      toolForm.riskLevel = 'R1';
      toolForm.parameters.forEach(parameter => {
        parameter.required = true;
      });
    }
    if (type === 'sandbox' && !['R2', 'R3'].includes(toolForm.riskLevel)) toolForm.riskLevel = 'R2';
    if ((type === 'api' || type === 'search') && !readOnly && ['R0', 'R1'].includes(toolForm.riskLevel)) {
      toolForm.riskLevel = 'R2';
    }
    if (method === 'DELETE') {
      toolForm.readOnly = false;
      toolForm.riskLevel = 'R3';
    }
  }
);
watch(
  () => skillForm.scopeType,
  value => {
    if (!editingSkill.value && !canManageSharedSkillScopes.value && value !== 'user') {
      skillForm.scopeType = 'user';
      skillForm.scopeId = authStore.userInfo.userId;
      return;
    }
    if (!editingSkill.value && value === 'user') skillForm.scopeId = authStore.userInfo.userId;
    if (!editingSkill.value && value === 'system') skillForm.scopeId = '';
  }
);
watch(
  () => authStore.userInfo.userId,
  userId => {
    if (!canManageSharedMemory.value || memoryScopeType.value === 'user') memoryScopeId.value = userId;
    if (!editingSkill.value && !canManageSharedSkillScopes.value) {
      skillForm.scopeType = 'user';
      skillForm.scopeId = userId;
    }
  }
);
onMounted(() => {
  applyResourceCenterDeepLink();
  resourceCenterMounted = true;
  window.addEventListener('beforeunload', warnUnsavedSkillFile);
  void loadAuthorizedTab(activeTab.value);
});
onBeforeUnmount(() => window.removeEventListener('beforeunload', warnUnsavedSkillFile));
</script>

<template>
  <div class="resource-center-container">
    <header class="page-header">
      <div class="page-header-inner">
        <div class="page-header-copy">
          <h2>资源配置</h2>
          <p>统一管理模型、连接器、工具、Skill 版本和记忆。</p>
        </div>
        <NButton v-if="hasResourceTabs" secondary :loading="pageLoading" @click="loadAll">
          <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
          刷新当前目录
        </NButton>
      </div>
    </header>

    <div class="resource-center-content">
      <NEmpty v-if="!hasResourceTabs" description="当前账号没有可查看的资源目录" />

      <NTabs v-else v-model:value="activeTab" type="line" animated>
        <NTabPane v-if="canResource('model', 'list')" name="model" tab="模型">
          <div class="toolbar">
            <NButton v-if="canResource('model', 'create')" type="primary" @click="openModel()">
              <template #icon><SvgIcon icon="lucide:plus" /></template>
              新增模型
            </NButton>
            <span>{{ models.length }} 个模型配置</span>
          </div>
          <NDataTable
            :columns="modelColumns"
            :data="models"
            :loading="pageLoading"
            :row-key="row => row.id"
            :scroll-x="1200"
            :max-height="590"
            :pagination="resourcePagination(models)"
          />
        </NTabPane>

        <NTabPane v-if="canResource('connector', 'list')" name="connector" tab="连接器">
          <div class="toolbar">
            <NButton v-if="canResource('connector', 'create')" type="primary" @click="openConnector()">
              <template #icon><SvgIcon icon="lucide:plus" /></template>
              新增连接器
            </NButton>
            <NButton v-if="canResource('connector', 'create')" secondary @click="openMcpImport">
              <template #icon><SvgIcon icon="lucide:file-input" /></template>
              导入 MCP
            </NButton>
            <NRadioGroup v-model:value="connectorScopeFilter" size="small">
              <NRadioButton value="all">全部可见</NRadioButton>
              <NRadioButton value="global">企业共享</NRadioButton>
              <NRadioButton value="personal">我的私有</NRadioButton>
            </NRadioGroup>
            <NAlert v-if="lastDiscovery" type="info" :show-icon="false">
              最近一次 MCP 发现得到 {{ lastDiscovery.toolCount }} 个工具，状态
              {{ discoveryStatusText(lastDiscovery.status) }}
            </NAlert>
          </div>
          <NAlert v-if="connectorLoadError" type="error" title="连接器加载失败" :show-icon="true" class="section-alert">
            {{ connectorLoadError }}
            <NButton class="alert-retry" size="small" secondary @click="loadConnectors()">
              <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
              重试
            </NButton>
          </NAlert>
          <NDataTable
            :columns="connectorColumns"
            :data="connectors"
            :loading="pageLoading"
            :row-key="row => row.id"
            :scroll-x="1200"
            :max-height="590"
            :pagination="resourcePagination(connectors)"
          />
          <SearchProviderConsole />
        </NTabPane>

        <NTabPane v-if="canResource('tool', 'list')" name="tool" tab="工具">
          <div class="toolbar">
            <NButton v-if="canResource('tool', 'create')" type="primary" @click="openTool()">
              <template #icon><SvgIcon icon="lucide:plus" /></template>
              新增工具
            </NButton>
            <span>MCP 工具由连接器自动发现，API、Search、SQL 和 Sandbox 在此配置</span>
          </div>
          <NAlert v-if="toolLoadError" type="error" title="工具目录加载失败" :show-icon="true" class="section-alert">
            {{ toolLoadError }}
            <NButton class="alert-retry" size="small" secondary @click="loadTools">
              <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
              重试
            </NButton>
          </NAlert>
          <NDataTable
            :columns="toolColumns"
            :data="tools"
            :loading="pageLoading"
            :row-key="row => row.id"
            :scroll-x="1100"
            :max-height="590"
            :pagination="resourcePagination(tools)"
          />
        </NTabPane>

        <NTabPane v-if="canResource('skill', 'list')" name="skill" tab="Skill">
          <div class="toolbar">
            <NSpace>
              <template v-if="canResource('skill', 'create')">
                <NButton type="primary" @click="openSkill()">
                  <template #icon><SvgIcon icon="lucide:plus" /></template>
                  新建 Skill
                </NButton>
                <NButton secondary @click="openSkill(undefined, 'zip')">
                  <template #icon><SvgIcon icon="lucide:package-open" /></template>
                  导入 ZIP
                </NButton>
              </template>
              <NButton v-if="canReviewSkillPublications" secondary @click="openSkillReview">
                <template #icon><SvgIcon icon="lucide:clipboard-check" /></template>
                待审核 {{ pendingSkillPublications.length }}
              </NButton>
            </NSpace>
            <span>{{ skills.length }} 个技能定义</span>
          </div>
          <NDataTable
            :columns="skillColumns"
            :data="skills"
            :loading="pageLoading"
            :row-key="row => row.id"
            :scroll-x="1270"
            :max-height="590"
            :pagination="resourcePagination(skills)"
          />
        </NTabPane>

        <NTabPane v-if="canResource('memory', 'list')" name="memory" tab="记忆">
          <div class="toolbar memory-toolbar">
            <NSelect v-model:value="memoryScopeType" :options="scopeOptions" :disabled="!canManageSharedMemory" />
            <NInput v-model:value="memoryScopeId" placeholder="范围 ID" :disabled="!canManageSharedMemory" />
            <NInput v-model:value="memorySearch" clearable placeholder="搜索记忆内容" @keyup.enter="loadMemories" />
            <NButton v-if="canResource('memory', 'create')" type="primary" secondary @click="openNewMemory">
              <template #icon><SvgIcon icon="lucide:plus" /></template>
              新建记忆
            </NButton>
            <NButton type="primary" :loading="memoryLoading" @click="loadMemories">
              <template #icon><SvgIcon icon="lucide:search" /></template>
              查询
            </NButton>
          </div>
          <NDataTable
            :columns="memoryColumns"
            :data="memories"
            :loading="memoryLoading"
            :row-key="row => row.id"
            :scroll-x="1200"
            :max-height="560"
            :pagination="resourcePagination(memories)"
          />
        </NTabPane>
      </NTabs>
    </div>

    <NModal
      v-model:show="modelVisible"
      preset="card"
      :title="editingModel ? '编辑模型' : '新增模型'"
      class="wide-modal"
      style="width: min(980px, calc(100vw - 32px))"
      :mask-closable="!modelSubmitting"
    >
      <section class="config-section first">
        <h3>服务与凭据</h3>
        <div class="form-grid three">
          <NFormItem label="模型标识" required>
            <NInput
              v-model:value="modelForm.modelKey"
              :disabled="Boolean(editingModel)"
              placeholder="例如 data-analyst"
            />
          </NFormItem>
          <NFormItem label="显示名称" required>
            <NInput v-model:value="modelForm.displayName" placeholder="用户看到的模型名称" />
          </NFormItem>
          <NFormItem label="模型服务商" required>
            <NSelect v-model:value="modelForm.providerPreset" :options="providerOptions" filterable />
          </NFormItem>
          <NFormItem label="服务地址" :required="modelForm.providerType === 'openai-compatible'">
            <NInput v-model:value="modelForm.endpointUrl" placeholder="https://api.example.com/v1" />
          </NFormItem>
          <NFormItem label="API Key" :required="!editingModel || !editingModel.apiKeyConfigured">
            <NInput
              v-model:value="modelForm.apiKey"
              type="password"
              show-password-on="click"
              :placeholder="editingModel?.apiKeyConfigured ? '留空沿用已保存的 API Key' : '手动填写 Provider API Key'"
            />
          </NFormItem>
          <NFormItem label="状态">
            <NSelect
              v-model:value="modelForm.status"
              :options="[
                { label: '启用', value: 'active' },
                { label: '停用', value: 'disabled' }
              ]"
            />
          </NFormItem>
        </div>
      </section>

      <section class="config-section">
        <div class="section-heading">
          <h3>模型与容量</h3>
          <NButton
            v-if="canResource('model', 'operate')"
            text
            type="primary"
            :loading="modelSubmitting"
            @click="discoverCurrentModels"
          >
            <template #icon><SvgIcon icon="lucide:list-filter" /></template>
            从 Provider 发现模型
          </NButton>
        </div>
        <div class="form-grid">
          <NFormItem label="模型" required>
            <NSelect
              v-model:value="modelForm.modelName"
              :options="discoveredModelOptions"
              filterable
              tag
              placeholder="先发现模型，也可输入明确的模型 ID"
            />
          </NFormItem>
          <NFormItem label="模型类型">
            <NSelect v-model:value="modelForm.modelType" :options="modelTypeOptions" />
          </NFormItem>
          <NFormItem label="上下文长度">
            <NInputNumber v-model:value="modelForm.contextSize" :min="1" :max="10000000" />
          </NFormItem>
          <NFormItem v-if="supportsGenerationOptions" label="最大输出长度">
            <NInputNumber v-model:value="modelForm.maxOutputTokens" :min="1" :max="1000000" />
          </NFormItem>
        </div>
      </section>

      <section v-if="supportsGenerationOptions" class="config-section">
        <h3>能力开关</h3>
        <div class="switch-grid">
          <label class="switch-item">
            <span>流式输出</span>
            <NSwitch v-model:value="modelForm.streaming" />
          </label>
          <label class="switch-item">
            <span>工具调用</span>
            <NSwitch v-model:value="modelForm.toolCalling" />
          </label>
          <label class="switch-item">
            <span>视觉理解</span>
            <NSwitch v-model:value="modelForm.vision" />
          </label>
          <label class="switch-item">
            <span>结构化输出</span>
            <NSwitch v-model:value="modelForm.jsonSchema" />
          </label>
          <label class="switch-item">
            <span>推理模型</span>
            <NSwitch v-model:value="modelForm.reasoning" />
          </label>
          <label class="switch-item">
            <span>并行调用工具</span>
            <NSwitch v-model:value="modelForm.parallelToolCalls" />
          </label>
        </div>
        <div class="form-grid capability-selects">
          <NFormItem label="输入模态">
            <NSelect v-model:value="modelForm.inputModalities" multiple :options="modalityOptions" />
          </NFormItem>
          <NFormItem label="输出模态">
            <NSelect v-model:value="modelForm.outputModalities" multiple :options="modalityOptions" />
          </NFormItem>
        </div>
      </section>

      <section v-if="supportsGenerationOptions" class="config-section">
        <h3>生成与推理</h3>
        <div class="form-grid three">
          <NFormItem label="Temperature">
            <NInputNumber v-model:value="modelForm.temperature" clearable :min="0" :max="2" :step="0.1" />
          </NFormItem>
          <NFormItem label="Top P">
            <NInputNumber v-model:value="modelForm.topP" clearable :min="0" :max="1" :step="0.05" />
          </NFormItem>
          <NFormItem label="推理等级">
            <NSelect
              v-model:value="modelForm.reasoningEffort"
              clearable
              :options="[
                { label: '低', value: 'low' },
                { label: '中', value: 'medium' },
                { label: '高', value: 'high' }
              ]"
            />
          </NFormItem>
          <NFormItem label="思考预算">
            <NInputNumber v-model:value="modelForm.thinkingBudget" clearable :min="0" :max="1000000" />
          </NFormItem>
          <NFormItem label="频率惩罚">
            <NInputNumber v-model:value="modelForm.frequencyPenalty" clearable :min="-2" :max="2" :step="0.1" />
          </NFormItem>
          <NFormItem label="存在惩罚">
            <NInputNumber v-model:value="modelForm.presencePenalty" clearable :min="-2" :max="2" :step="0.1" />
          </NFormItem>
          <NFormItem label="请求路径">
            <NInput v-model:value="modelForm.endpointPath" placeholder="留空使用 Provider 默认路径" />
          </NFormItem>
          <NFormItem label="原生结构化输出">
            <NSwitch v-model:value="modelForm.nativeStructuredOutput" />
          </NFormItem>
          <NFormItem label="工具调用中启用结构化输出">
            <NSwitch v-model:value="modelForm.nativeStructuredOutputWithTools" />
          </NFormItem>
        </div>
      </section>

      <template #footer>
        <div class="modal-actions">
          <NButton
            v-if="canResource('model', 'operate')"
            :loading="modelSubmitting"
            secondary
            @click="testCurrentModel"
          >
            <template #icon><SvgIcon icon="lucide:plug-zap" /></template>
            测试连接
          </NButton>
          <NSpace>
            <NButton @click="modelVisible = false">取消</NButton>
            <NButton
              v-if="canResource('model', editingModel ? 'edit' : 'create')"
              type="primary"
              :loading="modelSubmitting"
              @click="submitModel"
            >
              保存
            </NButton>
          </NSpace>
        </div>
      </template>
    </NModal>

    <NModal
      v-model:show="modelReferenceVisible"
      preset="card"
      title="模型引用"
      class="medium-modal"
      style="width: min(700px, calc(100vw - 32px))"
    >
      <NList bordered>
        <NListItem v-for="item in modelReferences" :key="item.versionId">
          <strong>{{ item.agentName }} · v{{ item.versionNo }}</strong>
          <span class="muted">{{ item.slots.join(', ') }} · {{ item.versionStatus }}</span>
        </NListItem>
      </NList>
      <NEmpty v-if="modelReferences.length === 0" description="当前没有 Agent 版本引用" />
    </NModal>

    <NModal
      v-model:show="connectorVisible"
      preset="card"
      :title="editingConnector ? '编辑连接器' : '新增连接器'"
      class="medium-modal"
      style="width: min(700px, calc(100vw - 32px))"
      :mask-closable="!connectorSubmitting && !connectorDraftTesting"
    >
      <div class="form-grid">
        <NFormItem label="连接器标识" required>
          <NInput
            v-model:value="connectorForm.connectorKey"
            :disabled="Boolean(editingConnector)"
            placeholder="例如 enterprise-search"
          />
        </NFormItem>
        <NFormItem label="名称" required>
          <NInput v-model:value="connectorForm.name" />
        </NFormItem>
        <NFormItem label="类型" required>
          <NSelect v-model:value="connectorForm.providerType" :options="connectorTypeOptions" />
        </NFormItem>
        <NFormItem label="可见范围" required>
          <NSelect
            v-model:value="connectorForm.scope"
            :options="connectorScopeOptions"
            :disabled="Boolean(editingConnector)"
          />
        </NFormItem>
        <NFormItem label="状态">
          <NSelect
            v-model:value="connectorForm.status"
            :options="[
              { label: '启用', value: 'active' },
              { label: '停用', value: 'disabled' }
            ]"
          />
        </NFormItem>
      </div>
      <NAlert v-if="connectorForm.providerType === 'mcp'" type="info" :show-icon="true" class="section-alert">
        MCP 三步向导：{{
          mcpWizardStep === 1 ? '填写地址和命名空间' : mcpWizardStep === 2 ? '验证连接与凭据' : '保存并发布工具'
        }}
        <span v-if="mcpWizardMessage">· {{ mcpWizardMessage }}</span>
      </NAlert>
      <NFormItem
        :label="
          connectorForm.providerType === 'mcp'
            ? 'MCP 服务地址'
            : connectorForm.providerType === 'search'
              ? '搜索服务地址'
              : 'API 基础地址'
        "
        required
      >
        <NInput v-model:value="connectorForm.endpointUrl" placeholder="https://service.example.com" />
      </NFormItem>
      <template v-if="connectorForm.providerType === 'search'">
        <div class="form-grid">
          <NFormItem label="搜索 Provider" required>
            <NSelect
              v-model:value="connectorForm.searchEngine"
              :options="searchEngineOptions"
              @update:value="applySearchEnginePreset"
            />
          </NFormItem>
          <NFormItem label="请求方法" required>
            <NRadioGroup v-model:value="connectorForm.searchRequestMethod" size="small">
              <NRadioButton value="GET">GET</NRadioButton>
              <NRadioButton value="POST">POST JSON</NRadioButton>
            </NRadioGroup>
          </NFormItem>
          <NFormItem label="查询参数名" required>
            <NInput v-model:value="connectorForm.searchQueryParam" placeholder="q" />
          </NFormItem>
          <NFormItem label="数量参数名" required>
            <NInput v-model:value="connectorForm.searchCountParam" placeholder="count" />
          </NFormItem>
          <NFormItem label="单次最多结果">
            <NInputNumber v-model:value="connectorForm.searchMaxResults" :min="1" :max="20" />
          </NFormItem>
          <NFormItem label="每分钟限额">
            <NInputNumber v-model:value="connectorForm.searchRateLimitPerMinute" :min="1" :max="10000" />
          </NFormItem>
          <NFormItem label="连续失败熔断">
            <NInputNumber v-model:value="connectorForm.searchFailureThreshold" :min="1" :max="20">
              <template #suffix>次</template>
            </NInputNumber>
          </NFormItem>
          <NFormItem label="熔断冷却">
            <NInputNumber v-model:value="connectorForm.searchCooldownSeconds" :min="5" :max="3600">
              <template #suffix>秒</template>
            </NInputNumber>
          </NFormItem>
        </div>
      </template>
      <div class="form-grid">
        <NFormItem v-if="connectorForm.providerType === 'mcp'" label="MCP 传输方式">
          <NSelect
            v-model:value="connectorForm.transport"
            :options="[
              { label: 'Streamable HTTP', value: 'streamable_http' },
              { label: 'Server-Sent Events', value: 'sse' }
            ]"
          />
        </NFormItem>
        <NFormItem v-if="connectorForm.providerType === 'mcp'" label="命名空间" required>
          <NInput
            v-model:value="connectorForm.namespace"
            placeholder="default，同地址可注册不同命名空间"
            maxlength="128"
          />
        </NFormItem>
        <NFormItem label="鉴权方式">
          <NSelect
            v-model:value="connectorForm.authType"
            :options="authTypeOptions"
          />
        </NFormItem>
        <NFormItem v-if="connectorForm.authType === 'header'" label="鉴权请求头" required>
          <NInput v-model:value="connectorForm.authHeader" placeholder="X-API-Key" />
        </NFormItem>
        <NFormItem v-if="connectorForm.authType !== 'none'" label="凭据环境变量" required>
          <NInput v-model:value="connectorForm.credentialName" placeholder="CONNECTOR_TOKEN">
            <template #prefix>env:</template>
          </NInput>
        </NFormItem>
        <NFormItem label="连接超时">
          <NInputNumber v-model:value="connectorForm.connectTimeoutMs" :min="250" :max="30000">
            <template #suffix>ms</template>
          </NInputNumber>
        </NFormItem>
        <NFormItem label="请求超时">
          <NInputNumber v-model:value="connectorForm.requestTimeoutMs" :min="1000" :max="120000">
            <template #suffix>ms</template>
          </NInputNumber>
        </NFormItem>
      </div>
      <NAlert v-if="connectorDraftTestError" type="error" title="连接验证失败" :show-icon="true" class="section-alert">
        {{ connectorDraftTestError }}
      </NAlert>
      <NAlert
        v-else-if="connectorDraftTestResult"
        type="success"
        title="MCP 协议握手成功"
        :show-icon="true"
        class="section-alert"
      >
        {{ connectorDraftTestResult.serverName }} · {{ connectorDraftTestResult.protocolVersion }} ·
        {{ connectorDraftTestResult.toolCount }} 个工具 · {{ connectorDraftTestResult.latencyMs }} ms
      </NAlert>
      <NScrollbar v-if="connectorDraftTestResult?.tools.length" class="mcp-preview-scroll">
        <NList bordered size="small">
          <NListItem v-for="tool in connectorDraftTestResult.tools" :key="tool.externalName">
            <div class="primary-cell">
              <strong>{{ tool.name }}</strong>
              <span>{{ tool.description || tool.externalName }}</span>
            </div>
          </NListItem>
        </NList>
      </NScrollbar>
      <template #footer>
        <div class="modal-actions">
          <span />
          <NSpace>
            <NButton :disabled="connectorDraftTesting || connectorSubmitting" @click="connectorVisible = false">
              取消
            </NButton>
            <NButton
              v-if="connectorForm.providerType === 'mcp'"
              secondary
              :loading="connectorDraftTesting"
              :disabled="connectorSubmitting"
              @click="runConnectorDraftTest"
            >
              <template #icon><SvgIcon icon="lucide:plug-zap" /></template>
              验证连接
            </NButton>
            <NButton
              v-if="canResource('connector', editingConnector ? 'edit' : 'create')"
              type="primary"
              :loading="connectorSubmitting"
              :disabled="connectorDraftTesting"
              @click="submitConnector"
            >
              {{
                connectorForm.providerType === 'mcp' && mcpWizardStep < 3
                  ? `下一步（${mcpWizardStep}/3）`
                  : '保存并同步'
              }}
            </NButton>
          </NSpace>
        </div>
      </template>
    </NModal>

    <NModal
      v-model:show="mcpImportVisible"
      preset="card"
      title="导入 MCP 服务"
      class="medium-modal"
      style="width: min(760px, calc(100vw - 32px))"
      :mask-closable="!mcpImportParsing && !mcpImportSubmitting"
      @after-leave="clearMcpImport"
    >
      <NFormItem label="mcpServers JSON" required>
        <NInput
          v-model:value="mcpImportText"
          type="textarea"
          :autosize="{ minRows: 7, maxRows: 14 }"
          placeholder='{ "mcpServers": { "reports": { "url": "https://mcp.example/rpc" } } }'
        />
      </NFormItem>
      <div class="modal-actions section-alert">
        <span />
        <NButton :loading="mcpImportParsing" @click="parseMcpImport">
          <template #icon><SvgIcon icon="lucide:scan-search" /></template>
          解析配置
        </NButton>
      </div>
      <template v-if="mcpImportEntries.length">
        <NFormItem label="服务" required>
          <NSelect v-model:value="mcpImportSourceKey" :options="mcpImportEntryOptions" />
        </NFormItem>
        <NAlert
          v-for="diagnostic in selectedMcpImportEntry?.diagnostics || []"
          :key="diagnostic"
          :type="selectedMcpImportEntry?.importable ? 'warning' : 'error'"
          :show-icon="true"
          class="section-alert"
        >
          {{ diagnostic }}
        </NAlert>
        <NDescriptions v-if="selectedMcpImportEntry" :column="2" bordered size="small" class="section-alert">
          <NDescriptionsItem label="地址">{{ selectedMcpImportEntry.endpointUrl || '-' }}</NDescriptionsItem>
          <NDescriptionsItem label="传输">{{ selectedMcpImportEntry.transport }}</NDescriptionsItem>
          <NDescriptionsItem label="鉴权">{{ selectedMcpImportEntry.authType }}</NDescriptionsItem>
          <NDescriptionsItem label="请求头">{{ selectedMcpImportEntry.authHeader || '-' }}</NDescriptionsItem>
        </NDescriptions>
        <div class="form-grid">
          <NFormItem label="连接器标识" required>
            <NInput v-model:value="mcpImportConnectorKey" />
          </NFormItem>
          <NFormItem label="名称" required>
            <NInput v-model:value="mcpImportName" />
          </NFormItem>
          <NFormItem label="可见范围" required>
            <NSelect v-model:value="mcpImportScope" :options="connectorScopeOptions" />
          </NFormItem>
          <NFormItem label="状态">
            <NSelect
              v-model:value="mcpImportStatus"
              :options="[
                { label: '启用', value: 'active' },
                { label: '停用', value: 'disabled' }
              ]"
            />
          </NFormItem>
          <NFormItem v-if="selectedMcpImportEntry?.authType !== 'none'" label="凭据环境变量" required>
            <NInput v-model:value="mcpImportCredentialName" placeholder="MCP_SERVER_TOKEN">
              <template #prefix>env:</template>
            </NInput>
          </NFormItem>
        </div>
      </template>
      <template #footer>
        <div class="modal-actions">
          <span />
          <NSpace>
            <NButton @click="closeMcpImport">取消</NButton>
            <NButton
              type="primary"
              :loading="mcpImportSubmitting"
              :disabled="!selectedMcpImportEntry?.importable"
              @click="submitMcpImport"
            >
              导入
            </NButton>
          </NSpace>
        </div>
      </template>
    </NModal>

    <NModal
      v-model:show="connectorTestVisible"
      preset="card"
      :title="`${connectorTestTarget?.name || 'MCP 连接器'} · 连接测试`"
      style="width: min(620px, calc(100vw - 32px))"
      :mask-closable="!connectorTestingId"
    >
      <NSpin :show="Boolean(connectorTestingId)">
        <NAlert v-if="connectorTestError" type="error" title="连接失败" :show-icon="true">
          {{ connectorTestError }}
        </NAlert>
        <template v-else-if="connectorTestResult">
          <NAlert
            :type="connectorTestResult.success ? 'success' : 'error'"
            :title="connectorTestResult.success ? 'MCP 协议握手成功' : 'MCP 协议握手失败'"
            :show-icon="true"
            class="test-result-alert"
          >
            本次测试只验证连接与工具清单读取，不会修改已发现的工具目录。
          </NAlert>
          <NDescriptions bordered :column="2" size="small" responsive="screen">
            <NDescriptionsItem label="服务名称">{{ connectorTestResult.serverName }}</NDescriptionsItem>
            <NDescriptionsItem label="协议版本">{{ connectorTestResult.protocolVersion }}</NDescriptionsItem>
            <NDescriptionsItem label="可用工具">{{ connectorTestResult.toolCount }} 个</NDescriptionsItem>
            <NDescriptionsItem label="连接延迟">{{ connectorTestResult.latencyMs }} ms</NDescriptionsItem>
            <NDescriptionsItem label="检查时间">{{ formatTime(connectorTestResult.checkedAt) }}</NDescriptionsItem>
          </NDescriptions>
          <NCollapse v-if="connectorTestResult.tools.length" class="section-alert">
            <NCollapseItem :title="`工具预览（${connectorTestResult.tools.length}）`" name="tools">
              <NList bordered size="small">
                <NListItem v-for="tool in connectorTestResult.tools" :key="tool.externalName">
                  <div class="primary-cell">
                    <strong>{{ tool.name }}</strong>
                    <span>{{ tool.description || tool.externalName }}</span>
                  </div>
                </NListItem>
              </NList>
            </NCollapseItem>
          </NCollapse>
        </template>
        <NEmpty v-else-if="!connectorTestingId" description="尚未得到连接测试结果" />
      </NSpin>
      <template #footer>
        <div class="modal-actions">
          <span />
          <NButton :disabled="Boolean(connectorTestingId)" @click="connectorTestVisible = false">关闭</NButton>
        </div>
      </template>
    </NModal>

    <NDrawer v-model:show="discoveryVisible" width="min(900px, calc(100vw - 16px))">
      <NDrawerContent closable :title="`${discoveryConnector?.name || 'MCP 连接器'} · 发现历史`">
        <div class="discovery-drawer-toolbar">
          <span v-if="discoveryConnector" class="muted-text">
            {{ discoveryConnector.endpointUrl }} · 最近 20 次记录
          </span>
          <NButton
            v-if="canResource('connector', 'operate')"
            secondary
            :loading="discoveryLoading"
            @click="loadDiscoveries"
          >
            <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
            刷新
          </NButton>
        </div>
        <NAlert v-if="discoveryError" type="error" :show-icon="false" class="discovery-error">
          {{ discoveryError }}
        </NAlert>
        <NSpin v-if="discoveryLoading" size="small" />
        <NEmpty v-else-if="!discoveryError && !discoveries.length" description="暂无 MCP 发现记录" />
        <NDataTable
          v-else
          :columns="discoveryColumns"
          :data="discoveries"
          :loading="discoveryLoading"
          :row-key="row => row.id"
          :scroll-x="900"
          size="small"
        />
      </NDrawerContent>
    </NDrawer>

    <NDrawer v-model:show="mcpToolsVisible" width="min(1120px, calc(100vw - 16px))">
      <NDrawerContent closable :title="`${mcpToolsConnector?.name || 'MCP 服务'} · 工具管理`">
        <div class="mcp-workbench-toolbar">
          <NSpace>
            <NButton
              v-if="mcpToolsConnector && canResource('connector', 'operate')"
              secondary
              :loading="connectorDiscoveringId === mcpToolsConnector.id"
              :disabled="mcpToolsConnector.status !== 'active' || mcpBatchSubmitting"
              @click="runDiscovery(mcpToolsConnector)"
            >
              <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
              同步远端工具
            </NButton>
            <NButton
              v-if="canResource('tool', 'operate')"
              type="primary"
              :loading="mcpBatchSubmitting"
              :disabled="
                !mcpSelectedTools.some(
                  tool => tool.status !== 'active' && tool.available && tool.status !== 'deprecated'
                ) || mcpToolsConnector?.status !== 'active'
              "
              @click="batchSetMcpToolStatus('active')"
            >
              <template #icon><SvgIcon icon="lucide:upload" /></template>
              批量发布
            </NButton>
            <NButton
              v-if="canResource('tool', 'operate')"
              secondary
              :loading="mcpBatchSubmitting"
              :disabled="!mcpSelectedTools.some(tool => tool.status === 'active')"
              @click="batchSetMcpToolStatus('disabled')"
            >
              <template #icon><SvgIcon icon="lucide:download" /></template>
              批量撤回
            </NButton>
          </NSpace>
          <NButton secondary :loading="mcpToolsLoading" @click="loadMcpConnectorTools">
            <template #icon><SvgIcon icon="lucide:rotate-cw" /></template>
            刷新
          </NButton>
        </div>

        <NAlert
          v-if="mcpToolsConnector?.status !== 'active'"
          type="warning"
          title="MCP 服务已停用"
          :show-icon="true"
          class="section-alert"
        >
          已发布工具当前不可执行，重新启用服务后才能同步或发布。
        </NAlert>
        <NAlert
          v-else-if="mcpUnpublishedCount > 0"
          type="warning"
          :title="`发现 ${mcpUnpublishedCount} 个可用但未发布的 MCP 工具`"
          :show-icon="true"
          class="section-alert"
        >
          请选择工具后批量发布；未发布工具不会进入 Agent 运行时。
          <NButton
            v-if="canResource('connector', 'operate')"
            size="small"
            type="primary"
            :loading="mcpBatchSubmitting"
            @click="publishAllMcpTools"
          >
            一键全部发布
          </NButton>
        </NAlert>

        <NDescriptions bordered :column="4" size="small" responsive="screen" class="mcp-workbench-summary">
          <NDescriptionsItem label="工具总数">{{ mcpConnectorTools.length }}</NDescriptionsItem>
          <NDescriptionsItem label="已发布">{{ mcpPublishedCount }}</NDescriptionsItem>
          <NDescriptionsItem label="远端失效">{{ mcpUnavailableCount }}</NDescriptionsItem>
          <NDescriptionsItem label="待发布">{{ mcpUnpublishedCount }}</NDescriptionsItem>
          <NDescriptionsItem label="已选择">{{ mcpSelectedToolIds.length }}</NDescriptionsItem>
        </NDescriptions>

        <NAlert v-if="mcpToolsError" type="error" title="工具目录加载失败" :show-icon="true" class="section-alert">
          {{ mcpToolsError }}
          <NButton class="alert-retry" size="small" secondary @click="loadMcpConnectorTools">重试</NButton>
        </NAlert>
        <NAlert
          v-if="mcpWorkbenchUsageError"
          type="warning"
          title="Agent 使用情况加载失败"
          :show-icon="true"
          class="section-alert"
        >
          {{ mcpWorkbenchUsageError }}
        </NAlert>
        <NAlert
          v-else-if="mcpWorkbenchUsage && mcpWorkbenchUsage.boundAgentCount > 0"
          type="info"
          :title="`已关联 ${mcpWorkbenchUsage.boundAgentCount} 个 Agent · 当前生效 ${mcpWorkbenchUsage.activeAgentCount} 个`"
          :show-icon="true"
          class="section-alert"
        >
          <NSpace size="small">
            <NTag v-for="agent in mcpWorkbenchUsage.agents.slice(0, 8)" :key="agent.id" size="small">
              {{ agent.displayName }}
            </NTag>
          </NSpace>
        </NAlert>

        <NDataTable
          v-if="mcpToolsLoading || mcpConnectorTools.length"
          :columns="mcpToolColumns"
          :data="mcpConnectorTools"
          :loading="mcpToolsLoading"
          :row-key="row => row.id"
          :checked-row-keys="mcpSelectedToolIds"
          :scroll-x="940"
          :max-height="620"
          @update:checked-row-keys="updateMcpCheckedRows"
        />
        <NEmpty
          v-if="!mcpToolsLoading && !mcpToolsError && !mcpConnectorTools.length"
          description="尚未同步到远端工具"
        />
      </NDrawerContent>
    </NDrawer>

    <NDrawer v-model:show="runtimeVisible" width="min(1180px, calc(100vw - 16px))">
      <NDrawerContent closable :title="`${runtimeConnector?.name || 'MCP 连接器'} · 运行观测`">
        <div class="discovery-drawer-toolbar">
          <span v-if="runtimeConnector" class="muted-text">
            {{ runtimeConnector.endpointUrl }}
          </span>
          <NButton secondary :loading="runtimeLoading" @click="loadRuntimeOverview">
            <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
            刷新
          </NButton>
        </div>

        <NAlert v-if="runtimeError" type="error" title="运行数据加载失败" :show-icon="true" class="discovery-error">
          {{ runtimeError }}
        </NAlert>
        <NSpin v-if="runtimeLoading && !runtimeOverview" size="small" />
        <template v-else-if="runtimeOverview">
          <NAlert
            v-if="runtimeOverview.health.circuitState !== 'closed' || runtimeOverview.health.lastErrorSummary"
            :type="runtimeOverview.health.circuitState === 'open' ? 'error' : 'warning'"
            :title="runtimeOverview.health.circuitState === 'open' ? '连接器已熔断' : '连接器运行降级'"
            :show-icon="true"
            class="runtime-health-alert"
          >
            {{ runtimeOverview.health.lastErrorSummary || '服务端正在执行半开连接探测' }}
          </NAlert>

          <NDescriptions bordered :column="4" size="small" responsive="screen" class="runtime-summary">
            <NDescriptionsItem label="健康状态">
              <NTag :type="runtimeHealthType(runtimeOverview.health.healthStatus)" size="small">
                {{ runtimeHealthText(runtimeOverview.health.healthStatus) }}
              </NTag>
            </NDescriptionsItem>
            <NDescriptionsItem label="熔断状态">
              <NTag
                :type="
                  runtimeOverview.health.circuitState === 'open'
                    ? 'error'
                    : runtimeOverview.health.circuitState === 'half_open'
                      ? 'warning'
                      : 'success'
                "
                size="small"
              >
                {{ circuitStateText(runtimeOverview.health.circuitState) }}
              </NTag>
            </NDescriptionsItem>
            <NDescriptionsItem label="活动挂载">{{ runtimeOverview.health.activeMountCount }}</NDescriptionsItem>
            <NDescriptionsItem label="最近耗时">
              {{ runtimeOverview.health.lastLatencyMs === null ? '-' : `${runtimeOverview.health.lastLatencyMs} ms` }}
            </NDescriptionsItem>
            <NDescriptionsItem label="累计调用">{{ runtimeOverview.health.totalInvocations }}</NDescriptionsItem>
            <NDescriptionsItem label="成功 / 失败">
              {{ runtimeOverview.health.totalSuccesses }} / {{ runtimeOverview.health.totalFailures }}
            </NDescriptionsItem>
            <NDescriptionsItem label="连接 / 重连">
              {{ runtimeOverview.health.totalConnections }} / {{ runtimeOverview.health.totalReconnections }}
            </NDescriptionsItem>
            <NDescriptionsItem label="连续传输失败">{{ runtimeOverview.health.consecutiveFailures }}</NDescriptionsItem>
            <NDescriptionsItem label="最近成功">
              {{ formatTime(runtimeOverview.health.lastSuccessAt) }}
            </NDescriptionsItem>
            <NDescriptionsItem label="最近失败">
              {{ formatTime(runtimeOverview.health.lastFailureAt) }}
            </NDescriptionsItem>
            <NDescriptionsItem label="最近重连">
              {{ formatTime(runtimeOverview.health.lastReconnectAt) }}
            </NDescriptionsItem>
            <NDescriptionsItem label="熔断恢复时间">
              {{ formatTime(runtimeOverview.health.circuitOpenUntil) }}
            </NDescriptionsItem>
          </NDescriptions>

          <NAlert
            v-if="runtimeUsageError"
            type="error"
            title="绑定使用情况加载失败"
            :show-icon="true"
            class="discovery-error"
          >
            {{ runtimeUsageError }}
          </NAlert>
          <NDescriptions
            v-else-if="runtimeUsage"
            bordered
            :column="3"
            size="small"
            responsive="screen"
            class="runtime-summary"
          >
            <NDescriptionsItem label="绑定 Agent">{{ runtimeUsage.boundAgentCount }}</NDescriptionsItem>
            <NDescriptionsItem label="当前生效 Agent">{{ runtimeUsage.activeAgentCount }}</NDescriptionsItem>
            <NDescriptionsItem label="绑定 Agent 版本">{{ runtimeUsage.boundVersionCount }}</NDescriptionsItem>
          </NDescriptions>

          <NTabs type="line" animated class="runtime-tabs">
            <NTabPane name="agent-usage" :tab="`绑定使用情况 (${runtimeUsage?.agents.length || 0})`">
              <NEmpty v-if="runtimeUsageError" description="绑定使用情况暂不可用" />
              <NEmpty v-else-if="!runtimeUsage?.agents.length" description="暂无 Agent 绑定此 MCP 服务" />
              <NDataTable
                v-else
                :columns="mcpAgentUsageColumns"
                :data="runtimeUsage.agents"
                :row-key="row => row.id"
                :scroll-x="620"
                size="small"
              />
            </NTabPane>
            <NTabPane name="mounts" :tab="`挂载生命周期 (${runtimeOverview.mounts.length})`">
              <NEmpty v-if="!runtimeOverview.mounts.length" description="暂无 MCP 运行挂载" />
              <NDataTable
                v-else
                :columns="runtimeMountColumns"
                :data="runtimeOverview.mounts"
                :row-key="row => row.id"
                :scroll-x="1080"
                size="small"
              />
            </NTabPane>
            <NTabPane name="usage" :tab="`服务使用明细 (${runtimeOverview.usage.length})`">
              <NEmpty v-if="!runtimeOverview.usage.length" description="暂无 MCP 工具调用明细" />
              <NDataTable
                v-else
                :columns="runtimeUsageColumns"
                :data="runtimeOverview.usage"
                :row-key="row => row.id"
                :scroll-x="1280"
                size="small"
              />
            </NTabPane>
          </NTabs>
        </template>
        <NEmpty v-else-if="!runtimeLoading && !runtimeError" description="暂无 MCP 运行数据" />
      </NDrawerContent>
    </NDrawer>

    <NModal
      v-model:show="skillFileCreateVisible"
      preset="card"
      :title="skillFileEntryForm.kind === 'directory' ? '新建目录' : '新建文件'"
      style="width: min(520px, calc(100vw - 32px))"
      :mask-closable="!skillFileCreating"
    >
      <NFormItem label="条目类型">
        <NRadioGroup v-model:value="skillFileEntryForm.kind">
          <NRadioButton value="file">文件</NRadioButton>
          <NRadioButton value="directory">目录</NRadioButton>
        </NRadioGroup>
      </NFormItem>
      <NFormItem label="相对路径" required>
        <NInput
          v-model:value="skillFileEntryForm.path"
          placeholder="例如 scripts/validate.py"
          @keyup.enter="createSkillFile"
        />
      </NFormItem>
      <template #footer>
        <div class="modal-actions">
          <span />
          <NSpace>
            <NButton @click="skillFileCreateVisible = false">取消</NButton>
            <NButton type="primary" :loading="skillFileCreating" @click="createSkillFile">创建</NButton>
          </NSpace>
        </div>
      </template>
    </NModal>

    <NDrawer v-model:show="skillReviewVisible" width="min(1080px, calc(100vw - 16px))">
      <NDrawerContent closable title="系统 Skill 发布审核">
        <NSpin :show="skillReviewLoading">
          <div class="skill-review-layout">
            <section class="skill-review-queue">
              <div class="section-heading compact">
                <div>
                  <h3>待审核</h3>
                  <span>{{ pendingSkillPublications.length }} 个不可变快照</span>
                </div>
                <NButton quaternary circle title="刷新" @click="openSkillReview">
                  <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
                </NButton>
              </div>
              <NEmpty v-if="!pendingSkillPublications.length" description="当前没有待审核技能" />
              <NList v-else bordered>
                <NListItem
                  v-for="item in pendingSkillPublications"
                  :key="item.version_id || item.publication_id || item.skill_id"
                  :class="{ 'is-selected': skillReviewDetail?.version_id === item.version_id }"
                  @click="inspectSkillPublication(item)"
                >
                  <div class="review-queue-row">
                    <strong>{{ item.name }}</strong>
                    <span>v{{ item.version_number }} · {{ formatTime(item.submitted_at) }}</span>
                    <span>{{ item.file_count }} 个文件 · {{ item.total_size }} B</span>
                  </div>
                </NListItem>
              </NList>
            </section>

            <section class="skill-review-detail">
              <NSpin :show="skillReviewDetailLoading">
                <NEmpty v-if="!skillReviewDetail" description="选择一条申请查看快照" />
                <template v-else>
                  <div class="section-heading">
                    <div>
                      <h3>{{ skillReviewDetail.name }} · v{{ skillReviewDetail.version_number }}</h3>
                      <p>{{ skillReviewDetail.description || '未填写描述' }}</p>
                    </div>
                    <NTag type="warning">{{ skillPublicationStatusText(skillReviewDetail.publication_status) }}</NTag>
                  </div>
                  <div class="review-metadata">
                    <span>提交人 #{{ skillReviewDetail.submitted_by }}</span>
                    <span>{{ formatTime(skillReviewDetail.submitted_at) }}</span>
                    <span>{{ skillReviewDetail.file_count }} 个文件</span>
                    <span>{{ skillReviewDetail.total_size }} B</span>
                    <span>SHA-256 {{ skillReviewDetail.content_sha256?.slice(0, 16) }}</span>
                  </div>
                  <div class="review-actions">
                    <NButton type="error" secondary :disabled="skillReviewSubmitting" @click="openRejectPublication">
                      <template #icon><SvgIcon icon="lucide:x" /></template>
                      驳回
                    </NButton>
                    <NButton type="primary" :loading="skillReviewSubmitting" @click="confirmApprovePublication">
                      <template #icon><SvgIcon icon="lucide:check" /></template>
                      通过并发布
                    </NButton>
                  </div>
                  <section class="review-content-section">
                    <h3>SKILL.md</h3>
                    <NCode :code="skillReviewDetail.skill_md_content || ''" language="markdown" word-wrap />
                  </section>
                  <section class="review-content-section">
                    <h3>文件快照</h3>
                    <NList bordered>
                      <NListItem v-for="file in skillReviewFiles" :key="file.path">
                        <div class="skill-file-row">
                          <SvgIcon :icon="file.is_dir ? 'lucide:folder' : 'lucide:file-text'" />
                          <span class="skill-file-path" :style="{ paddingLeft: `${file.depth * 14}px` }">
                            {{ file.path }}
                          </span>
                          <NTag size="tiny" :bordered="false">{{ file.is_dir ? '目录' : `${file.size} B` }}</NTag>
                        </div>
                      </NListItem>
                    </NList>
                  </section>
                </template>
              </NSpin>
            </section>
          </div>
        </NSpin>
      </NDrawerContent>
    </NDrawer>

    <NModal
      v-model:show="skillRejectVisible"
      preset="card"
      title="驳回技能发布申请"
      style="width: min(560px, calc(100vw - 32px))"
      :mask-closable="!skillReviewSubmitting"
    >
      <NFormItem label="驳回原因" required>
        <NInput
          v-model:value="skillRejectComment"
          type="textarea"
          maxlength="2000"
          show-count
          :autosize="{ minRows: 4, maxRows: 10 }"
        />
      </NFormItem>
      <template #footer>
        <div class="modal-actions">
          <span />
          <NSpace>
            <NButton @click="skillRejectVisible = false">取消</NButton>
            <NButton type="error" :loading="skillReviewSubmitting" @click="rejectPublication">确认驳回</NButton>
          </NSpace>
        </div>
      </template>
    </NModal>

    <NModal
      v-model:show="toolVisible"
      preset="card"
      :title="versioningTool ? '创建 ' + versioningTool.toolKey + ' 新版本' : '新增工具'"
      class="wide-modal"
      style="width: min(980px, calc(100vw - 32px))"
      :mask-closable="!toolSubmitting"
    >
      <section class="config-section first">
        <h3>基本信息</h3>
        <div class="form-grid three">
          <NFormItem label="工具标识" required>
            <NInput
              v-model:value="toolForm.toolKey"
              :disabled="Boolean(versioningTool)"
              placeholder="例如 order-query"
            />
          </NFormItem>
          <NFormItem label="名称" required><NInput v-model:value="toolForm.name" /></NFormItem>
          <NFormItem label="类型" required>
            <NSelect v-model:value="toolForm.toolType" :options="toolTypeOptions" />
          </NFormItem>
          <NFormItem label="风险等级">
            <NSelect
              v-model:value="toolForm.riskLevel"
              :options="['R0', 'R1', 'R2', 'R3'].map(value => ({ label: value, value }))"
              :disabled="toolForm.toolType === 'sql'"
            />
          </NFormItem>
          <NFormItem label="状态">
            <NSelect
              v-model:value="toolForm.status"
              :options="[
                { label: '启用', value: 'active' },
                { label: '停用', value: 'disabled' },
                { label: '已废弃', value: 'deprecated' }
              ]"
            />
          </NFormItem>
          <NFormItem v-if="toolForm.toolType === 'api' || toolForm.toolType === 'search'" label="连接器" required>
            <NSelect
              v-model:value="toolForm.connectorId"
              :options="connectorOptions"
              placeholder="选择同类型的已启用连接器"
            />
          </NFormItem>
        </div>
        <NFormItem label="说明">
          <NInput v-model:value="toolForm.description" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }" />
        </NFormItem>
      </section>

      <section v-if="toolForm.toolType === 'api' || toolForm.toolType === 'search'" class="config-section">
        <h3>{{ toolForm.toolType === 'search' ? '搜索请求' : 'API 请求' }}</h3>
        <div class="form-grid three">
          <NFormItem label="外部动作名称" required>
            <NInput v-model:value="toolForm.externalName" placeholder="服务端动作或接口名称" />
          </NFormItem>
          <NFormItem label="请求方法" required>
            <NSelect
              v-model:value="toolForm.method"
              :options="['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map(value => ({ label: value, value }))"
            />
          </NFormItem>
          <NFormItem label="接口路径" required>
            <NInput v-model:value="toolForm.path" placeholder="/v1/search" />
          </NFormItem>
          <NFormItem label="只读调用">
            <NSwitch v-model:value="toolForm.readOnly" :disabled="toolForm.method === 'DELETE'" />
          </NFormItem>
          <NFormItem label="超时时间">
            <NInputNumber v-model:value="toolForm.timeoutMs" :min="1000" :max="120000">
              <template #suffix>ms</template>
            </NInputNumber>
          </NFormItem>
          <NFormItem v-if="toolForm.method === 'GET' && toolForm.readOnly" label="失败重试">
            <NInputNumber v-model:value="toolForm.retryCount" :min="0" :max="2" />
          </NFormItem>
          <NFormItem label="最大响应">
            <NInputNumber v-model:value="toolForm.maxOutputBytes" :min="1024" :max="10485760">
              <template #suffix>bytes</template>
            </NInputNumber>
          </NFormItem>
        </div>
      </section>

      <section v-else-if="toolForm.toolType === 'sandbox'" class="config-section">
        <h3>Sandbox 运行限制</h3>
        <NAlert type="info" :show-icon="false" class="section-alert">一期 Sandbox 固定禁用外网访问。</NAlert>
        <div class="form-grid three">
          <NFormItem label="运行模板" required>
            <NInput v-model:value="toolForm.templateKey" placeholder="python" />
          </NFormItem>
          <NFormItem label="工作区权限">
            <NSelect
              v-model:value="toolForm.workspaceAccess"
              :options="[
                { label: '只读', value: 'read_only' },
                { label: '可读写', value: 'read_write' }
              ]"
            />
          </NFormItem>
          <NFormItem label="工作目录"><NInput v-model:value="toolForm.workspacePath" placeholder="." /></NFormItem>
          <NFormItem label="超时时间">
            <NInputNumber v-model:value="toolForm.timeoutMs" :min="1000" :max="3600000">
              <template #suffix>ms</template>
            </NInputNumber>
          </NFormItem>
          <NFormItem label="内存上限">
            <NInputNumber v-model:value="toolForm.memoryMb" :min="64" :max="32768">
              <template #suffix>MB</template>
            </NInputNumber>
          </NFormItem>
          <NFormItem label="CPU 配额">
            <NInputNumber v-model:value="toolForm.cpuMillis" :min="100" :max="16000">
              <template #suffix>millicores</template>
            </NInputNumber>
          </NFormItem>
          <NFormItem label="进程上限">
            <NInputNumber v-model:value="toolForm.pidsLimit" :min="16" :max="2048" />
          </NFormItem>
          <NFormItem label="调度优先级">
            <NInputNumber v-model:value="toolForm.priority" :min="-100" :max="100" />
          </NFormItem>
          <NFormItem label="最大输出">
            <NInputNumber v-model:value="toolForm.maxOutputBytes" :min="1024" :max="10485760">
              <template #suffix>bytes</template>
            </NInputNumber>
          </NFormItem>
        </div>
      </section>

      <section v-else-if="toolForm.toolType === 'sql'" class="config-section">
        <h3>受治理 SQL 查询</h3>
        <div class="form-grid">
          <NFormItem label="数据集" required>
            <NSelect
              v-model:value="toolForm.datasetId"
              :options="sqlDatasetOptions"
              filterable
              placeholder="选择已同步且可查询的数据集"
            />
          </NFormItem>
          <NFormItem label="查询用途" required>
            <NInput
              v-model:value="toolForm.queryPurpose"
              maxlength="1000"
              placeholder="例如：按客户和年份查询订单金额"
            />
          </NFormItem>
        </div>
        <NFormItem label="SQL 模板" required>
          <NInput
            v-model:value="toolForm.sqlTemplate"
            type="textarea"
            class="sql-template-input"
            :autosize="{ minRows: 8, maxRows: 18 }"
            placeholder="SELECT o.customer_id, o.total_amount FROM public.orders o WHERE o.customer_id = {{customer_id}}"
          />
        </NFormItem>
        <div class="form-grid">
          <NFormItem label="只读执行"><NSwitch :value="true" disabled /></NFormItem>
          <NFormItem label="风险等级"><NTag size="small">R1</NTag></NFormItem>
        </div>
      </section>

      <section v-else class="config-section">
        <h3>内置处理器</h3>
        <div class="form-grid">
          <NFormItem label="处理器标识" required>
            <NSelect
              v-model:value="toolForm.handlerKey"
              :options="builtinToolOptions"
              filterable
              placeholder="选择平台已实现的内置处理器"
              @update:value="selectBuiltin"
            />
          </NFormItem>
          <NFormItem label="只读执行">
            <NSwitch v-model:value="toolForm.readOnly" />
          </NFormItem>
          <NFormItem label="超时时间">
            <NInputNumber v-model:value="toolForm.timeoutMs" :min="1000" :max="120000">
              <template #suffix>ms</template>
            </NInputNumber>
          </NFormItem>
          <NFormItem label="最大输出">
            <NInputNumber v-model:value="toolForm.maxOutputBytes" :min="1024" :max="10485760">
              <template #suffix>bytes</template>
            </NInputNumber>
          </NFormItem>
        </div>
      </section>

      <section class="config-section">
        <div class="section-heading">
          <div>
            <h3>输入参数</h3>
            <p>
              {{
                toolForm.toolType === 'sql'
                  ? '参数名与 SQL 模板占位符保持一致。'
                  : '定义智能体调用工具时可以填写的字段。'
              }}
            </p>
          </div>
          <NButton secondary size="small" @click="addToolParameter">
            <template #icon><SvgIcon icon="lucide:plus" /></template>
            添加参数
          </NButton>
        </div>
        <NEmpty v-if="toolForm.parameters.length === 0" size="small" description="当前工具没有输入参数" />
        <div v-else class="parameter-list">
          <div v-for="(parameter, index) in toolForm.parameters" :key="index" class="parameter-row">
            <NInput v-model:value="parameter.name" placeholder="参数名" />
            <NInput v-model:value="parameter.label" placeholder="显示名称" />
            <NSelect v-model:value="parameter.type" :options="parameterTypeOptions" />
            <NCheckbox v-model:checked="parameter.required" :disabled="toolForm.toolType === 'sql'">必填</NCheckbox>
            <NInput v-model:value="parameter.description" placeholder="用途说明" />
            <NButton quaternary circle title="删除参数" @click="removeToolParameter(index)">
              <template #icon><SvgIcon icon="lucide:trash-2" /></template>
            </NButton>
          </div>
        </div>
      </section>

      <template #footer>
        <div class="modal-actions">
          <span />
          <NSpace>
            <NButton @click="toolVisible = false">取消</NButton>
            <NButton
              v-if="canResource('tool', versioningTool ? 'edit' : 'create')"
              type="primary"
              :loading="toolSubmitting"
              @click="submitTool"
            >
              保存版本
            </NButton>
          </NSpace>
        </div>
      </template>
    </NModal>

    <NModal
      v-model:show="toolTestVisible"
      preset="card"
      :title="`${testingTool?.name || '工具'} · 在线测试`"
      class="medium-modal"
      style="width: min(760px, calc(100vw - 32px))"
      :mask-closable="!toolTestSubmitting"
    >
      <NAlert type="info" :show-icon="false" class="section-alert">
        {{
          testingTool?.toolType === 'sql'
            ? '在线测试会使用当前账号权限执行受治理只读查询，并写入数据查询与工具调用审计。'
            : testingTool?.toolType === 'builtin'
              ? '在线测试会复用生产内置工具执行器和当前账号权限，结果会写入工具调用审计。'
              : '在线测试会使用当前账号权限和当前版本配置发起真实调用，结果会写入工具调用审计。'
        }}
      </NAlert>
      <NDescriptions v-if="testingTool" bordered :column="3" size="small" responsive="screen" class="tool-test-meta">
        <NDescriptionsItem label="工具标识">{{ testingTool.toolKey }}</NDescriptionsItem>
        <NDescriptionsItem label="类型">{{ testingTool.toolType.toUpperCase() }}</NDescriptionsItem>
        <NDescriptionsItem label="风险等级">
          <NTag :type="toolTestIsHighRisk ? 'warning' : 'default'" size="small">{{ testingTool.riskLevel }}</NTag>
        </NDescriptionsItem>
      </NDescriptions>

      <section v-if="toolTestUsesStructuredForm" class="sql-test-arguments">
        <NEmpty v-if="testingStructuredParameters.length === 0" size="small" description="当前工具不需要输入参数" />
        <div v-else class="form-grid">
          <NFormItem
            v-for="parameter in testingStructuredParameters"
            :key="parameter.name"
            :label="parameter.label || parameter.name"
            :required="parameter.required"
          >
            <NSelect
              v-if="toolTestEnumOptions(parameter.name).length"
              :value="structuredToolTestValue(parameter.name)"
              :options="toolTestEnumOptions(parameter.name)"
              @update:value="value => setSqlTestArgument(parameter.name, value)"
            />
            <NSwitch
              v-else-if="parameter.type === 'boolean'"
              :value="sqlTestBooleanValue(parameter.name)"
              @update:value="value => setSqlTestArgument(parameter.name, value)"
            />
            <NInputNumber
              v-else-if="parameter.type === 'number' || parameter.type === 'integer'"
              :value="sqlTestNumberValue(parameter.name)"
              :precision="parameter.type === 'integer' ? 0 : undefined"
              @update:value="value => setSqlTestArgument(parameter.name, value)"
            />
            <NDynamicTags
              v-else-if="parameter.type === 'array'"
              :value="sqlTestArrayValue(parameter.name)"
              @update:value="(value: string[]) => setSqlTestArgument(parameter.name, value)"
            />
            <NInput
              v-else
              :value="sqlTestTextValue(parameter.name)"
              @update:value="value => setSqlTestArgument(parameter.name, value)"
            />
          </NFormItem>
        </div>
      </section>

      <NFormItem
        v-else
        label="调用参数（JSON 对象）"
        :validation-status="toolTestJsonError ? 'error' : undefined"
        :feedback="toolTestJsonError || undefined"
      >
        <NInput
          v-model:value="toolTestArguments"
          type="textarea"
          class="json-argument-input"
          :autosize="{ minRows: 8, maxRows: 18 }"
          placeholder="{}"
          :disabled="toolTestSubmitting"
          @update:value="toolTestJsonError = null"
        />
      </NFormItem>

      <NAlert v-if="toolTestIsHighRisk" type="warning" title="高风险真实调用" :show-icon="true" class="section-alert">
        R2/R3 工具可能写入或影响外部系统。在线测试不绕过审批策略；需要审批的调用会由后台拒绝并提示改走任务审批流程。
        <div class="risk-confirmation">
          <NCheckbox v-model:checked="toolTestConfirmRisk" :disabled="toolTestSubmitting">
            我确认本次测试会按上述参数真实调用外部服务
          </NCheckbox>
        </div>
      </NAlert>

      <NAlert v-if="toolTestRequestError" type="error" title="请求未执行" :show-icon="true" class="section-alert">
        {{ toolTestRequestError }}
      </NAlert>

      <section v-if="toolTestResult" class="tool-test-result">
        <NAlert
          :type="toolTestResult.ok && toolTestResult.status === 'succeeded' ? 'success' : 'error'"
          :title="toolTestStatusText(toolTestResult)"
          :show-icon="true"
          class="test-result-alert"
        >
          {{
            toolTestResult.ok
              ? testingTool?.toolType === 'sql'
                ? '只读查询已返回有效结果。'
                : testingTool?.toolType === 'builtin'
                  ? '内置执行器已返回有效结果。'
                  : '远端服务已返回有效结果。'
              : toolTestResult.error || '服务没有返回可用结果。'
          }}
        </NAlert>
        <NDescriptions bordered :column="3" size="small" responsive="screen">
          <NDescriptionsItem label="状态">{{ toolTestStatusText(toolTestResult) }}</NDescriptionsItem>
          <NDescriptionsItem label="耗时">{{ toolTestResult.latencyMs }} ms</NDescriptionsItem>
          <NDescriptionsItem label="可重试">{{ toolTestResult.retryable ? '是' : '否' }}</NDescriptionsItem>
          <NDescriptionsItem label="检查时间">{{ formatTime(toolTestResult.checkedAt) }}</NDescriptionsItem>
          <template v-if="testingTool?.toolType === 'sql' && sqlToolTestTable">
            <NDescriptionsItem label="查询记录">{{ sqlToolTestTable.queryId }}</NDescriptionsItem>
            <NDescriptionsItem label="结果行数">{{ sqlToolTestTable.rowCount }}</NDescriptionsItem>
            <NDescriptionsItem label="结果大小">{{ sqlToolTestTable.resultBytes }} bytes</NDescriptionsItem>
          </template>
        </NDescriptions>
        <NAlert
          v-if="testingTool?.toolType === 'sql' && sqlToolTestTable?.truncated"
          type="warning"
          :show-icon="false"
          class="section-alert"
        >
          查询结果已达到数据源返回上限。
        </NAlert>
        <NDataTable
          v-if="testingTool?.toolType === 'sql' && sqlToolTestTable"
          :columns="sqlToolTestColumns"
          :data="sqlToolTestTable.rows"
          :row-key="row => String(row.__rowKey)"
          :scroll-x="Math.max(640, sqlToolTestColumns.length * 160)"
          :max-height="360"
          :pagination="{ pageSize: 50 }"
          size="small"
          striped
          class="sql-result-table"
        />
        <div v-else-if="toolTestResult.ok" class="result-fields">
          <div class="result-fields-header">
            <span>返回字段</span>
            <span>值</span>
          </div>
          <div v-for="row in toolTestResultRows" :key="row.path" class="result-fields-row">
            <span>{{ row.path }}</span>
            <span>{{ row.value }}</span>
          </div>
        </div>
        <NCollapse class="section-alert">
          <NCollapseItem name="raw-json" title="原始 JSON">
            <NCode :code="toolTestResultJson" language="json" word-wrap />
          </NCollapseItem>
        </NCollapse>
      </section>

      <template #footer>
        <div class="modal-actions">
          <span />
          <NSpace>
            <NButton :disabled="toolTestSubmitting" @click="toolTestVisible = false">关闭</NButton>
            <NButton
              v-if="canResource('tool', 'operate')"
              type="primary"
              :loading="toolTestSubmitting"
              :disabled="toolTestIsHighRisk && !toolTestConfirmRisk"
              @click="runToolTest"
            >
              <template #icon><SvgIcon icon="lucide:play" /></template>
              执行测试
            </NButton>
          </NSpace>
        </div>
      </template>
    </NModal>

    <NModal
      v-model:show="skillVisible"
      preset="card"
      :title="editingSkill ? '编辑 Skill' : skillForm.inputMode === 'zip' ? '导入 Skill ZIP' : '新建 Skill'"
      class="wide-modal"
      style="width: min(980px, calc(100vw - 32px))"
      :mask-closable="!skillSubmitting"
    >
      <section v-if="!editingSkill && skillForm.inputMode === 'zip'" class="config-section first">
        <h3>选择 Skill 包</h3>
        <NUpload
          :key="skillUploadKey"
          accept=".zip,application/zip"
          :default-upload="false"
          :max="1"
          :show-file-list="true"
          @change="handleSkillZipChange"
        >
          <NUploadDragger class="package-drop">
            <SvgIcon icon="lucide:package-open" class="package-icon" />
            <strong>{{ skillImporting ? '正在读取 Skill 包' : '点击或拖入 ZIP 包' }}</strong>
            <span>包内需要包含 SKILL.md，可选包含 manifest.json 与 runtime.json</span>
          </NUploadDragger>
        </NUpload>
        <NAlert v-if="skillForm.packageName" type="success" :show-icon="false" class="section-alert">
          已读取 {{ skillForm.packageName }}，下方为解析后的清单预览。
        </NAlert>
      </section>

      <section class="config-section" :class="{ first: editingSkill || skillForm.inputMode !== 'zip' }">
        <h3>基本信息</h3>
        <div class="form-grid three">
          <NFormItem label="Skill 标识" required>
            <NInput
              v-model:value="skillForm.skillKey"
              :disabled="Boolean(editingSkill)"
              placeholder="例如 report-review"
            />
          </NFormItem>
          <NFormItem label="名称" required><NInput v-model:value="skillForm.name" /></NFormItem>
          <NFormItem label="范围">
            <NSelect
              v-model:value="skillForm.scopeType"
              :disabled="Boolean(editingSkill)"
              :options="skillScopeOptions"
            />
          </NFormItem>
        </div>
        <NFormItem v-if="skillForm.scopeType !== 'system'" label="范围 ID" required>
          <NInput v-model:value="skillForm.scopeId" :disabled="Boolean(editingSkill) || !canManageSharedSkillScopes" />
        </NFormItem>
        <NFormItem label="说明">
          <NInput v-model:value="skillForm.description" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }" />
        </NFormItem>
      </section>

      <template v-if="!editingSkill">
        <section class="config-section">
          <h3>清单信息</h3>
          <NFormItem label="能力摘要">
            <NInput v-model:value="skillForm.summary" placeholder="一句话说明这个 Skill 解决什么问题" />
          </NFormItem>
          <div class="form-grid">
            <NFormItem label="标签">
              <NDynamicTags v-model:value="skillForm.tags" />
            </NFormItem>
            <NFormItem label="适用智能体">
              <NSelect v-model:value="skillForm.compatibleAgentTypes" multiple :options="agentTypeOptions" />
            </NFormItem>
            <NFormItem label="依赖工具标识">
              <NSelect v-model:value="skillForm.requiredToolKeys" multiple filterable :options="skillToolKeyOptions" />
            </NFormItem>
            <NFormItem label="工作区权限">
              <NSelect
                v-model:value="skillForm.workspaceAccess"
                :options="[
                  { label: '不访问工作区', value: 'none' },
                  { label: '只读', value: 'read_only' },
                  { label: '可读写', value: 'read_write' }
                ]"
              />
            </NFormItem>
            <NFormItem label="运行时工具">
              <NSelect v-model:value="skillForm.requiredToolIds" multiple filterable :options="skillToolIdOptions" />
            </NFormItem>
            <NFormItem label="知识库">
              <NSelect
                v-model:value="skillForm.requiredKnowledgeBaseIds"
                multiple
                filterable
                :options="knowledgeBaseOptions"
              />
            </NFormItem>
            <NFormItem label="最大上下文">
              <NInputNumber v-model:value="skillForm.maxContextBytes" clearable :min="1024" :max="10485760">
                <template #suffix>bytes</template>
              </NInputNumber>
            </NFormItem>
            <NFormItem label="Python 依赖">
              <NDynamicTags v-model:value="skillForm.pythonDependencies" :max="32" />
              <small class="form-help">仅填写固定版本，例如 requests==2.32.3</small>
            </NFormItem>
            <NFormItem label="Node 依赖">
              <NDynamicTags v-model:value="skillForm.nodeDependencies" :max="32" />
              <small class="form-help">仅填写固定版本，例如 lodash@4.17.21</small>
            </NFormItem>
          </div>
        </section>

        <section class="config-section">
          <div class="section-heading">
            <div>
              <h3>Skill 参数</h3>
              <p>这些字段会在智能体使用 Skill 前收集。</p>
            </div>
            <NButton secondary size="small" @click="addSkillParameter(skillForm)">
              <template #icon><SvgIcon icon="lucide:plus" /></template>
              添加参数
            </NButton>
          </div>
          <NEmpty v-if="skillForm.parameters.length === 0" size="small" description="当前 Skill 不需要额外参数" />
          <div v-else class="parameter-list">
            <div v-for="(parameter, index) in skillForm.parameters" :key="index" class="parameter-row">
              <NInput v-model:value="parameter.name" placeholder="参数名" />
              <NInput v-model:value="parameter.label" placeholder="显示名称" />
              <NSelect v-model:value="parameter.type" :options="parameterTypeOptions" />
              <NCheckbox v-model:checked="parameter.required">必填</NCheckbox>
              <NInput v-model:value="parameter.description" placeholder="用途说明" />
              <NButton quaternary circle title="删除参数" @click="removeSkillParameter(skillForm, index)">
                <template #icon><SvgIcon icon="lucide:trash-2" /></template>
              </NButton>
            </div>
          </div>
        </section>

        <section class="config-section">
          <h3>Skill 指令</h3>
          <NFormItem label="指令内容" required>
            <NInput v-model:value="skillForm.content" type="textarea" :autosize="{ minRows: 10, maxRows: 20 }" />
          </NFormItem>
        </section>
      </template>

      <template #footer>
        <div class="modal-actions">
          <span />
          <NSpace>
            <NButton @click="skillVisible = false">取消</NButton>
            <NButton
              v-if="canResource('skill', editingSkill ? 'edit' : 'create')"
              type="primary"
              :loading="skillSubmitting"
              @click="submitSkill"
            >
              保存
            </NButton>
          </NSpace>
        </div>
      </template>
    </NModal>

    <NDrawer :show="versionVisible" width="min(920px, calc(100vw - 16px))" @update:show="handleVersionDrawerVisibility">
      <NDrawerContent v-if="activeSkill" closable :title="activeSkill.name + ' · 版本管理'">
        <section v-if="canResource('skill', 'edit')" class="drawer-section first">
          <h3>创建草稿版本</h3>
          <NFormItem label="Skill 指令" required>
            <NInput v-model:value="skillVersionForm.content" type="textarea" :autosize="{ minRows: 8, maxRows: 18 }" />
          </NFormItem>
          <NFormItem label="能力摘要">
            <NInput v-model:value="skillVersionForm.summary" />
          </NFormItem>
          <div class="form-grid">
            <NFormItem label="标签"><NDynamicTags v-model:value="skillVersionForm.tags" /></NFormItem>
            <NFormItem label="适用智能体">
              <NSelect v-model:value="skillVersionForm.compatibleAgentTypes" multiple :options="agentTypeOptions" />
            </NFormItem>
            <NFormItem label="依赖工具标识">
              <NSelect v-model:value="skillVersionForm.requiredToolKeys" multiple :options="skillToolKeyOptions" />
            </NFormItem>
            <NFormItem label="运行时工具">
              <NSelect v-model:value="skillVersionForm.requiredToolIds" multiple :options="skillToolIdOptions" />
            </NFormItem>
            <NFormItem label="知识库">
              <NSelect
                v-model:value="skillVersionForm.requiredKnowledgeBaseIds"
                multiple
                :options="knowledgeBaseOptions"
              />
            </NFormItem>
            <NFormItem label="工作区权限">
              <NSelect
                v-model:value="skillVersionForm.workspaceAccess"
                :options="[
                  { label: '不访问工作区', value: 'none' },
                  { label: '只读', value: 'read_only' },
                  { label: '可读写', value: 'read_write' }
                ]"
              />
            </NFormItem>
            <NFormItem label="最大上下文">
              <NInputNumber v-model:value="skillVersionForm.maxContextBytes" clearable :min="1024" :max="10485760">
                <template #suffix>bytes</template>
              </NInputNumber>
            </NFormItem>
            <NFormItem label="Python 依赖">
              <NDynamicTags v-model:value="skillVersionForm.pythonDependencies" :max="32" />
              <small class="form-help">仅填写固定版本，例如 requests==2.32.3</small>
            </NFormItem>
            <NFormItem label="Node 依赖">
              <NDynamicTags v-model:value="skillVersionForm.nodeDependencies" :max="32" />
              <small class="form-help">仅填写固定版本，例如 lodash@4.17.21</small>
            </NFormItem>
          </div>
          <div class="section-heading compact">
            <h3>Skill 参数</h3>
            <NButton secondary size="small" @click="addSkillParameter(skillVersionForm)">
              <template #icon><SvgIcon icon="lucide:plus" /></template>
              添加参数
            </NButton>
          </div>
          <div class="parameter-list">
            <div
              v-for="(parameter, index) in skillVersionForm.parameters"
              :key="index"
              class="parameter-row drawer-parameter"
            >
              <NInput v-model:value="parameter.name" placeholder="参数名" />
              <NInput v-model:value="parameter.label" placeholder="显示名称" />
              <NSelect v-model:value="parameter.type" :options="parameterTypeOptions" />
              <NCheckbox v-model:checked="parameter.required">必填</NCheckbox>
              <NButton quaternary circle title="删除参数" @click="removeSkillParameter(skillVersionForm, index)">
                <template #icon><SvgIcon icon="lucide:trash-2" /></template>
              </NButton>
            </div>
          </div>
          <NButton type="primary" :loading="versionSubmitting" @click="addSkillVersion">
            <template #icon><SvgIcon icon="lucide:git-branch-plus" /></template>
            创建草稿版本
          </NButton>
        </section>

        <section class="drawer-section">
          <h3>版本历史</h3>
          <NList bordered>
            <NListItem
              v-for="item in skillVersions"
              :key="item.id"
              :class="{ 'is-selected': item.id === activeSkillVersionId }"
              @click="selectSkillVersion(item)"
            >
              <div class="version-row">
                <div>
                  <strong>v{{ item.versionNo }}</strong>
                  <span>{{ item.contentHash.slice(0, 12) }} · {{ formatTime(item.createdAt) }}</span>
                </div>
                <NSpace>
                  <NTag :type="statusType(item.status)" size="small">{{ item.status }}</NTag>
                  <NButton
                    v-if="item.status === 'draft' && canResource('skill', 'publish')"
                    size="tiny"
                    type="primary"
                    secondary
                    @click="changeSkillVersion(item, 'publish')"
                  >
                    发布版本
                  </NButton>
                  <NButton
                    v-if="canResource('skill', 'edit')"
                    size="tiny"
                    secondary
                    :loading="skillVersionUpdatingId === item.id"
                    @click.stop="cloneSkillDraft(item)"
                  >
                    复制为草稿
                  </NButton>
                  <NButton
                    v-if="item.status === 'draft' && canResource('skill', 'delete')"
                    size="tiny"
                    type="error"
                    secondary
                    :loading="skillVersionUpdatingId === item.id"
                    @click.stop="confirmDeleteSkillDraft(item)"
                  >
                    删除草稿
                  </NButton>
                  <NButton
                    v-if="item.status !== 'archived' && canResource('skill', 'archive')"
                    size="tiny"
                    secondary
                    @click="changeSkillVersion(item, 'archive')"
                  >
                    归档
                  </NButton>
                </NSpace>
              </div>
            </NListItem>
          </NList>
        </section>

        <section class="drawer-section">
          <div class="section-heading compact">
            <div>
              <h3>运行时依赖</h3>
              <span v-if="skillDependencyInstall">状态：{{ skillDependencyInstall.status }}</span>
            </div>
            <NSpace>
              <NButton
                size="small"
                secondary
                :loading="skillDependencyLoading"
                :disabled="!activeSkillVersionId"
                @click="
                  activeSkillVersionId &&
                  activeSkill &&
                  loadSkillDependencyInstall(activeSkill.id, activeSkillVersionId)
                "
              >
                <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
                刷新状态
              </NButton>
              <NButton
                v-if="
                  skillDependencyInstall &&
                  Object.values(skillDependencyInstall.dependencies).some(items => items.length) &&
                  canResource('skill', 'operate')
                "
                size="small"
                type="primary"
                :loading="skillDependencyInstalling"
                :disabled="activeSkillVersion?.status !== 'published'"
                @click="installSelectedSkillDependencies"
              >
                <template #icon><SvgIcon icon="lucide:download-cloud" /></template>
                安装依赖
              </NButton>
            </NSpace>
          </div>
          <NAlert
            v-if="skillDependencyInstall && !skillDependencyInstall.installerEnabled"
            type="warning"
            :show-icon="false"
          >
            依赖安装器未启用；声明仍会随版本冻结，运行时不会自动下载依赖。
          </NAlert>
          <NEmpty
            v-if="
              skillDependencyInstall && !Object.values(skillDependencyInstall.dependencies).some(items => items.length)
            "
            size="small"
            description="当前版本未声明第三方依赖"
          />
          <div v-else-if="skillDependencyInstall" class="dependency-summary">
            <div v-for="(items, type) in skillDependencyInstall.dependencies" :key="type" class="dependency-row">
              <strong>{{ type }}</strong>
              <span>{{ items.join('、') }}</span>
            </div>
            <NAlert
              v-if="skillDependencyInstall.message"
              :type="skillDependencyInstall.status === 'failed' ? 'error' : 'info'"
              :show-icon="false"
            >
              {{ skillDependencyInstall.message }}
            </NAlert>
          </div>
          <NSpin v-else :show="skillDependencyLoading" />
        </section>

        <section class="drawer-section skill-file-workbench">
          <div class="section-heading compact">
            <div>
              <h3>完整文件包</h3>
              <span v-if="activeSkillVersionId">
                当前版本 v{{ skillVersions.find(item => item.id === activeSkillVersionId)?.versionNo || '-' }} ·
                {{ skillFiles.length }} 个文件
              </span>
            </div>
            <NSpace>
              <NButton size="small" secondary :disabled="!canEditActiveSkillFiles" @click="openSkillFileCreate('file')">
                <template #icon><SvgIcon icon="lucide:file-plus-2" /></template>
                新建文件
              </NButton>
              <NButton
                size="small"
                secondary
                :disabled="!canEditActiveSkillFiles"
                @click="openSkillFileCreate('directory')"
              >
                <template #icon><SvgIcon icon="lucide:folder-plus" /></template>
                新建目录
              </NButton>
              <NUpload
                :key="skillFileUploadKey"
                :default-upload="false"
                :show-file-list="false"
                :disabled="skillFileUploading || !canEditActiveSkillFiles"
                @change="handleSkillFileUpload"
              >
                <NButton size="small" secondary :loading="skillFileUploading" :disabled="!canEditActiveSkillFiles">
                  <template #icon><SvgIcon icon="lucide:upload" /></template>
                  上传文件
                </NButton>
              </NUpload>
              <NUpload
                :default-upload="false"
                :show-file-list="false"
                accept=".zip,.tar,.tar.gz,.tgz,application/zip,application/x-tar,application/gzip"
                :disabled="
                  skillBundleUploading || activeSkillVersion?.status !== 'draft' || !canResource('skill', 'edit')
                "
                @change="handleSkillBundleUpload"
              >
                <NButton size="small" secondary :loading="skillBundleUploading">
                  <template #icon><SvgIcon icon="lucide:package-open" /></template>
                  导入完整包（ZIP / TAR）
                </NButton>
              </NUpload>
              <NButton
                size="small"
                secondary
                :disabled="!activeSkillVersionId || !skillFiles.length"
                @click="exportSkillBundle"
              >
                <template #icon><SvgIcon icon="lucide:download" /></template>
                导出完整 ZIP
              </NButton>
            </NSpace>
          </div>
          <NAlert v-if="skillFilesError" type="error" :show-icon="true">{{ skillFilesError }}</NAlert>
          <NSpin :show="skillFilesLoading">
            <NEmpty
              v-if="!skillFilesLoading && !skillFilesError && !skillFiles.length"
              description="当前版本暂无文件"
            />
            <div v-else class="skill-file-layout">
              <NList bordered class="skill-file-list">
                <NListItem
                  v-for="file in skillFiles"
                  :key="file.path"
                  :class="{ 'is-selected': selectedSkillFile?.path === file.path }"
                  @click="inspectSkillFile(file)"
                >
                  <div class="skill-file-row">
                    <SvgIcon
                      :icon="
                        file.fileKind === 'directory'
                          ? 'lucide:folder'
                          : file.binary
                            ? 'lucide:file-box'
                            : 'lucide:file-text'
                      "
                    />
                    <span
                      class="skill-file-path"
                      :style="{ paddingLeft: `${Math.max(0, file.path.split('/').length - 1) * 14}px` }"
                    >
                      {{ file.path }}
                    </span>
                    <NTag size="tiny" :bordered="false">
                      {{ file.fileKind === 'directory' ? '目录' : file.binary ? '二进制' : `${file.sizeBytes} B` }}
                    </NTag>
                    <NButton
                      v-if="canEditActiveSkillFiles"
                      quaternary
                      circle
                      size="tiny"
                      type="error"
                      :disabled="file.path === 'SKILL.md'"
                      :title="file.path === 'SKILL.md' ? '核心规范文件不能删除' : '删除'"
                      @click.stop="confirmDeleteSkillFile(file)"
                    >
                      <template #icon><SvgIcon icon="lucide:trash-2" /></template>
                    </NButton>
                  </div>
                </NListItem>
              </NList>
              <div v-if="selectedSkillFile" class="skill-file-preview">
                <div class="section-heading compact">
                  <div>
                    <strong>{{ selectedSkillFile.path }}</strong>
                    <NTag v-if="skillFileDirty" size="tiny" type="warning" :bordered="false">未保存</NTag>
                  </div>
                  <NSpace>
                    <NTag size="tiny" :bordered="false">{{ selectedSkillFile.contentHash.slice(0, 12) }}</NTag>
                    <NButton
                      v-if="
                        selectedSkillFile.fileKind === 'file' && !selectedSkillFile.binary && canEditActiveSkillFiles
                      "
                      size="small"
                      type="primary"
                      :disabled="!skillFileDirty"
                      :loading="skillFileSaving"
                      @click="saveSkillFile"
                    >
                      <template #icon><SvgIcon icon="lucide:save" /></template>
                      保存
                    </NButton>
                  </NSpace>
                </div>
                <NAlert v-if="selectedSkillFile.fileKind === 'directory'" type="info" :show-icon="false">
                  上传文件或新建条目时会以该目录作为默认位置。
                </NAlert>
                <NAlert v-else-if="selectedSkillFile.binary" type="info" :show-icon="false">
                  二进制文件已完整保留，可通过导出 ZIP 下载原始字节。
                </NAlert>
                <NInput
                  v-else
                  v-model:value="skillFileContent"
                  type="textarea"
                  :readonly="!canEditActiveSkillFiles"
                  :autosize="{ minRows: 16, maxRows: 30 }"
                  class="skill-file-editor"
                />
              </div>
            </div>
          </NSpin>
        </section>
      </NDrawerContent>
    </NDrawer>

    <NModal
      v-model:show="memoryVisible"
      preset="card"
      :title="editingMemory ? '编辑记忆' : '新建记忆'"
      class="medium-modal"
      style="width: min(700px, calc(100vw - 32px))"
      :mask-closable="!memorySubmitting"
    >
      <NAlert type="info" :show-icon="false" class="section-alert">
        {{ canManageSharedMemory ? '编辑后的共享记忆会重新进入待审核状态。' : '个人记忆仅保存到当前用户作用域。' }}
      </NAlert>
      <div class="form-grid">
        <NFormItem label="记忆标识">
          <NInput
            v-model:value="memoryForm.memoryKey"
            :disabled="Boolean(editingMemory)"
            placeholder="user.preference.language"
          />
        </NFormItem>
        <NFormItem label="记忆类型">
          <NSelect v-model:value="memoryForm.memoryType" :options="memoryTypeOptions" />
        </NFormItem>
        <NFormItem label="来源类型">
          <NSelect v-model:value="memoryForm.sourceType" :options="memorySourceOptions" />
        </NFormItem>
        <NFormItem v-if="memoryForm.sourceType !== 'manual'" label="来源记录" required>
          <NInputNumber v-model:value="memoryForm.sourceId" :min="1" />
        </NFormItem>
        <NFormItem label="敏感级别">
          <NSelect
            v-model:value="memoryForm.sensitiveLevel"
            :options="[
              { label: '公开', value: 'public' },
              { label: '内部', value: 'internal' },
              { label: '敏感', value: 'sensitive' },
              { label: '机密', value: 'secret' }
            ]"
          />
        </NFormItem>
        <NFormItem label="到期时间">
          <NDatePicker
            v-model:formatted-value="memoryForm.expiresAt"
            type="datetime"
            value-format="yyyy-MM-dd'T'HH:mm:ss"
            clearable
          />
        </NFormItem>
      </div>
      <NFormItem label="内容" required>
        <NInput
          v-model:value="memoryForm.content"
          type="textarea"
          maxlength="4000"
          show-count
          :autosize="{ minRows: 5, maxRows: 12 }"
        />
      </NFormItem>
      <NFormItem label="置信度">
        <div class="confidence-control">
          <NSlider v-model:value="memoryForm.confidence" :min="0" :max="1" :step="0.05" />
          <NInputNumber v-model:value="memoryForm.confidence" :min="0" :max="1" :step="0.05" />
        </div>
      </NFormItem>
      <section class="config-section">
        <h3>元数据信息</h3>
        <div class="form-grid">
          <NFormItem label="标题"><NInput v-model:value="memoryForm.title" /></NFormItem>
          <NFormItem label="分类"><NInput v-model:value="memoryForm.category" /></NFormItem>
          <NFormItem label="语言">
            <NSelect
              v-model:value="memoryForm.language"
              :options="[
                { label: '简体中文', value: 'zh-CN' },
                { label: '繁体中文', value: 'zh-TW' },
                { label: 'English', value: 'en' }
              ]"
            />
          </NFormItem>
          <NFormItem label="已核验"><NSwitch v-model:value="memoryForm.verified" /></NFormItem>
          <NFormItem label="来源名称"><NInput v-model:value="memoryForm.sourceLabel" /></NFormItem>
          <NFormItem label="来源链接">
            <NInput v-model:value="memoryForm.sourceUrl" placeholder="https://..." />
          </NFormItem>
        </div>
        <NFormItem label="标签"><NDynamicTags v-model:value="memoryForm.tags" /></NFormItem>
        <NFormItem label="保留说明">
          <NInput v-model:value="memoryForm.retentionNote" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" />
        </NFormItem>
      </section>
      <template #footer>
        <div class="modal-actions">
          <span />
          <NSpace>
            <NButton @click="memoryVisible = false">取消</NButton>
            <NButton
              v-if="canResource('memory', editingMemory ? 'edit' : 'create')"
              type="primary"
              :loading="memorySubmitting"
              @click="submitMemory"
            >
              保存
            </NButton>
          </NSpace>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style scoped lang="scss">
.resource-center-container {
  min-height: 100%;
  padding: 0 0 44px;
}

.page-header,
.toolbar,
.modal-actions,
.version-row,
.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}

.page-header {
  margin-bottom: 0;
  padding: 30px 32px 28px;
  background: color-mix(in srgb, var(--n-color) 95%, var(--n-primary-color));
  border-bottom: 1px solid var(--n-border-color);
}

.page-header-inner,
.resource-center-content {
  box-sizing: border-box;
  margin: 0 auto;
}

.page-header-inner {
  width: min(100%, 1680px);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
}

.page-header-copy {
  min-width: 0;
}

.page-header h2 {
  margin: 0;
  font-size: 28px;
  font-weight: 700;
  line-height: 1.2;
  letter-spacing: 0;
}

.page-header p,
.section-heading p {
  margin: 8px 0 0;
  color: var(--n-text-color-3);
  font-size: 14px;
  line-height: 1.5;
}

.resource-center-content {
  width: calc(100% - 64px);
  max-width: 1680px;
  padding-top: 22px;
}

.resource-center-content :deep(.n-tabs-nav) {
  margin-bottom: 2px;
}

.resource-center-content :deep(.n-tabs-tab) {
  padding: 12px 4px 13px;
  margin-right: 24px;
  font-size: 14px;
}

.resource-center-content :deep(.n-tabs-pane-wrapper) {
  padding-top: 4px;
}

.toolbar {
  justify-content: flex-start;
  min-height: 42px;
  margin-bottom: 16px;
}

.discovery-drawer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.mcp-workbench-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.mcp-workbench-summary {
  margin-bottom: 14px;
}

.mcp-preview-scroll {
  max-height: 240px;
  margin-bottom: 14px;
}

.muted-text {
  min-width: 0;
  overflow: hidden;
  color: var(--n-text-color-3);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.discovery-error {
  margin-bottom: 14px;
}

.runtime-health-alert,
.runtime-summary {
  margin-bottom: 16px;
}

.runtime-tabs {
  margin-top: 4px;
}

.test-result-alert,
.tool-test-meta {
  margin-bottom: 14px;
}

.json-argument-input :deep(textarea),
.sql-template-input :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.sql-test-arguments {
  margin-bottom: 14px;
}

.sql-result-table {
  margin-top: 14px;
}

.risk-confirmation {
  margin-top: 10px;
}

.tool-test-result {
  padding-top: 4px;
}

.result-fields {
  max-height: 280px;
  margin-top: 14px;
  overflow: auto;
  border: 1px solid var(--n-border-color);
  border-radius: 6px;
}

.result-fields-header,
.result-fields-row {
  display: grid;
  grid-template-columns: minmax(140px, 0.75fr) minmax(220px, 1.5fr);
}

.result-fields-header {
  position: sticky;
  z-index: 1;
  top: 0;
  color: var(--n-text-color-2);
  background: var(--n-color-modal);
  font-size: 12px;
  font-weight: 650;
}

.result-fields-header span,
.result-fields-row span {
  min-width: 0;
  padding: 8px 10px;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.result-fields-header span + span,
.result-fields-row span + span {
  border-left: 1px solid var(--n-border-color);
}

.result-fields-row {
  border-top: 1px solid var(--n-border-color);
  color: var(--n-text-color-2);
  font-size: 12px;
}

.toolbar > span {
  margin-left: auto;
  color: var(--n-text-color-3);
  font-size: 12px;
}

.memory-toolbar > :nth-child(1) {
  width: 150px;
}

.memory-toolbar > :nth-child(2) {
  width: 160px;
}

.memory-toolbar > :nth-child(3) {
  width: min(360px, 32vw);
}

:deep(.primary-cell) {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

:deep(.primary-cell strong),
:deep(.primary-cell span) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:deep(.primary-cell span),
.muted {
  color: var(--n-text-color-3);
  font-size: 12px;
}

:deep(.primary-cell strong) {
  font-size: 14px;
  font-weight: 650;
  line-height: 1.4;
}

.resource-center-content :deep(.n-data-table) {
  overflow: hidden;
  border: 1px solid var(--n-border-color);
  border-radius: 10px;
  background: var(--n-color);
}

.resource-center-content :deep(.n-data-table-th) {
  height: 48px;
  font-size: 12px;
  font-weight: 650;
}

.resource-center-content :deep(.n-data-table-td) {
  min-height: 68px;
  padding-top: 14px;
  padding-bottom: 14px;
}

.resource-center-content :deep(.n-data-table-tr:hover .n-data-table-td) {
  background: color-mix(in srgb, var(--n-color) 94%, var(--n-primary-color));
}

.resource-center-content :deep(.n-data-table .n-button) {
  min-height: 28px;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}

.form-grid.three {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.config-section {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--n-border-color);
}

.config-section.first {
  margin-top: 0;
  padding-top: 0;
  border-top: 0;
}

.config-section h3,
.drawer-section h3,
.section-heading h3 {
  margin: 0 0 14px;
  font-size: 15px;
  font-weight: 650;
  letter-spacing: 0;
}

.section-heading {
  align-items: flex-start;
  margin-bottom: 14px;
}

.section-heading h3 {
  margin-bottom: 0;
}

.section-heading.compact {
  margin-top: 10px;
}

.switch-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.switch-item {
  display: flex;
  min-height: 38px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 10px;
  border: 1px solid var(--n-border-color);
  border-radius: 6px;
}

.capability-selects {
  margin-top: 14px;
}

.section-alert {
  margin-bottom: 14px;
}

.alert-retry {
  margin-left: 12px;
}

.parameter-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 14px;
}

.parameter-row {
  display: grid;
  grid-template-columns: 1fr 1fr 130px 70px 1.3fr 34px;
  align-items: center;
  gap: 8px;
  padding: 10px;
  border: 1px solid var(--n-border-color);
  border-radius: 6px;
}

.drawer-parameter {
  grid-template-columns: 1fr 1fr 120px 70px 34px;
}

.package-drop {
  min-height: 140px;
}

.package-drop :deep(.n-upload-dragger) {
  display: flex;
  min-height: 140px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-radius: 6px;
}

.package-drop span {
  color: var(--n-text-color-3);
  font-size: 12px;
}

.package-icon {
  font-size: 30px;
}

.drawer-section {
  padding: 18px 0;
  border-bottom: 1px solid var(--n-border-color);
}

.drawer-section.first {
  padding-top: 0;
}

.version-row {
  width: 100%;
}

.version-row > div:first-child {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.version-row span {
  color: var(--n-text-color-3);
  font-size: 12px;
}

.skill-file-layout {
  display: grid;
  min-height: 360px;
  grid-template-columns: minmax(230px, 0.7fr) minmax(360px, 1.3fr);
  gap: 14px;
}

.skill-file-list {
  max-height: 520px;
  overflow: auto;
}

.skill-file-list :deep(.n-list-item),
.skill-review-queue :deep(.n-list-item) {
  cursor: pointer;
}

.skill-file-list :deep(.n-list-item.is-selected),
.skill-review-queue :deep(.n-list-item.is-selected) {
  background: var(--n-color-hover);
}

.skill-file-row {
  display: flex;
  width: 100%;
  min-width: 0;
  align-items: center;
  gap: 8px;
}

.skill-file-path {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-file-preview {
  min-width: 0;
  padding-left: 14px;
  border-left: 1px solid var(--n-border-color);
}

.skill-file-preview .section-heading > div,
.review-queue-row {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.skill-file-editor :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  line-height: 1.55;
}

.skill-review-layout {
  display: grid;
  min-height: calc(100vh - 110px);
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 20px;
}

.skill-review-queue {
  padding-right: 20px;
  border-right: 1px solid var(--n-border-color);
}

.skill-review-queue :deep(.n-list) {
  max-height: calc(100vh - 200px);
  overflow: auto;
}

.review-queue-row span,
.skill-review-queue .section-heading span {
  color: var(--n-text-color-3);
  font-size: 12px;
}

.skill-review-detail {
  min-width: 0;
}

.review-metadata {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  color: var(--n-text-color-3);
  font-size: 12px;
}

.review-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin: 16px 0;
  padding: 14px 0;
  border-top: 1px solid var(--n-border-color);
  border-bottom: 1px solid var(--n-border-color);
}

.review-content-section {
  margin-top: 18px;
}

.review-content-section h3 {
  margin: 0 0 10px;
  font-size: 14px;
  letter-spacing: 0;
}

.review-content-section :deep(pre) {
  max-height: 420px;
  overflow: auto;
}

.confidence-control {
  display: grid;
  width: 100%;
  grid-template-columns: 1fr 110px;
  align-items: center;
  gap: 14px;
}

@media (max-width: 900px) {
  .form-grid.three,
  .switch-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .parameter-row,
  .drawer-parameter {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 1120px) and (min-width: 761px) {
  .memory-toolbar {
    flex-wrap: wrap;
  }

  .memory-toolbar > :nth-child(3) {
    width: auto;
    min-width: 220px;
    flex: 1 1 260px;
  }
}

@media (max-width: 760px) {
  .resource-center-container {
    padding-bottom: 24px;
  }

  .page-header {
    padding: 22px 16px;
  }

  .page-header-inner {
    align-items: stretch;
    flex-direction: column;
    gap: 16px;
  }

  .page-header h2 {
    font-size: 22px;
  }

  .page-header-inner > .n-button {
    align-self: flex-start;
  }

  .resource-center-content {
    width: calc(100% - 24px);
    padding-top: 14px;
  }

  .page-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .toolbar {
    align-items: stretch;
    flex-wrap: wrap;
  }

  .mcp-workbench-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .toolbar > span {
    width: 100%;
    margin-left: 0;
  }

  .memory-toolbar > * {
    width: 100% !important;
  }

  .form-grid,
  .form-grid.three,
  .switch-grid,
  .parameter-row,
  .drawer-parameter {
    grid-template-columns: 1fr;
  }

  .modal-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .modal-actions > * {
    width: 100%;
  }

  .section-heading {
    align-items: stretch;
    flex-direction: column;
  }

  .confidence-control {
    grid-template-columns: 1fr;
  }

  .skill-file-layout,
  .skill-review-layout {
    grid-template-columns: 1fr;
  }

  .skill-file-preview,
  .skill-review-queue {
    padding: 14px 0 0;
    border-top: 1px solid var(--n-border-color);
    border-left: 0;
    border-right: 0;
  }

  .result-fields-header,
  .result-fields-row {
    grid-template-columns: minmax(100px, 0.65fr) minmax(180px, 1.35fr);
  }
}
</style>
