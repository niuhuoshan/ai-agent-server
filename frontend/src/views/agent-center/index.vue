<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import dayjs from 'dayjs';
import type { DataTableColumns, FormInst, FormRules, SelectGroupOption, SelectOption } from 'naive-ui';
import { NButton, NSpace, NTag, useDialog } from 'naive-ui';
import { useRoute } from 'vue-router';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  archiveAgentVersion,
  cloneAgentVersion,
  createAgentVersion,
  deleteAgent,
  deleteAgentVersion,
  fetchAgents,
  fetchAgentVersions,
  fetchAgentExecutionHistory,
  fetchNhsV1TraceLogs,
  fetchKnowledgeBases,
  fetchModels,
  fetchSkills,
  fetchTools,
  onboardAgent,
  optimizePortalPrompt,
  publishAgentVersion,
  reorderAgents,
  testModel,
  updateAgent,
  updateAgentStatus,
  updateAgentVersion
} from '@/service/api';
import type {
  AgentResourceBindingPayload,
  AgentEngineType,
  AgentExecutionHistoryView,
  AgentVersionView,
  AgentView,
  KnowledgeBaseView,
  ModelView,
  NhsV1TraceLogView,
  NhsV1TraceStepView,
  PortalPromptOptimizeResult,
  SaveAgentPayload,
  SaveAgentVersionPayload,
  SkillView,
  ToolView
} from '@/service/api';
import { buildAgentEngineConfig, editableRuntimeConfig } from './engine-config';

const dialog = useDialog();
const route = useRoute();
const loading = ref(false);
const reorderLoading = ref(false);
const agents = ref<AgentView[]>([]);
const models = ref<ModelView[]>([]);
const knowledgeBases = ref<KnowledgeBaseView[]>([]);
const tools = ref<ToolView[]>([]);
const skills = ref<SkillView[]>([]);
const search = ref('');
const routeAgentApplied = ref(false);

const wizardVisible = ref(false);
const onboardingKey = ref(crypto.randomUUID());
const wizardLoading = ref(false);
const wizardSubmitting = ref(false);
const modelTesting = ref(false);
const wizardStep = ref(1);
const wizardFormRef = ref<FormInst | null>(null);
const editingAgent = ref<AgentView | null>(null);
const editingVersion = ref<AgentVersionView | null>(null);
const sourceVersion = ref<AgentVersionView | null>(null);
const publishAfterSave = ref(false);
const engineTypeLocked = ref(false);
type WelcomeMode = 'manual' | 'ai';
type WelcomeCardDraft = { icon: string; title: string; subtitle: string; prompt: string };

function emptyWelcomeCards(): WelcomeCardDraft[] {
  return [
    { icon: 'chat', title: '', subtitle: '', prompt: '' },
    { icon: 'knowledge', title: '', subtitle: '', prompt: '' },
    { icon: 'chart', title: '', subtitle: '', prompt: '' }
  ];
}

const wizardForm = reactive({
  agentKey: '',
  name: '',
  description: '',
  avatarUrl: '',
  agentType: 'assistant' as SaveAgentPayload['agentType'],
  defaultAgent: false,
  sortOrder: 0,
  engineType: 'agentscope_java' as AgentEngineType,
  systemPrompt: '',
  modelId: null as string | null,
  synthesisModelId: null as string | null,
  temperature: 0.7,
  topP: 1,
  maxIterations: 12,
  timeoutSeconds: 300,
  workspaceAccess: 'none' as 'none' | 'read_only' | 'read_write',
  responseFormat: 'text' as 'text' | 'json',
  toolIds: [] as string[],
  skillIds: [] as string[],
  knowledgeBaseIds: [] as string[],
  routingTags: [] as string[],
  welcomeMessage: '',
  welcomeSuggestions: [] as string[],
  showSuggestions: true,
  welcomeEnabled: false,
  welcomeMode: 'manual' as WelcomeMode,
  welcomeRequirement: '',
  welcomeCards: emptyWelcomeCards()
});
const wizardRules: FormRules = {
  agentKey: [
    { required: true, message: '请输入稳定标识', trigger: ['input', 'blur'] },
    { pattern: /^[a-z][a-z0-9._-]{0,127}$/, message: '仅支持小写字母开头及数字、点、下划线、短横线', trigger: ['input', 'blur'] }
  ],
  name: [{ required: true, message: '请输入智能体名称', trigger: ['input', 'blur'] }],
  systemPrompt: [{ required: true, message: '请输入系统提示词', trigger: ['input', 'blur'] }],
  modelId: [{ required: true, message: '请选择对话模型', trigger: ['change', 'blur'] }]
};

const versionVisible = ref(false);
const versionLoading = ref(false);
const versionActionKey = ref('');
const activeAgent = ref<AgentView | null>(null);
const versions = ref<AgentVersionView[]>([]);
const historyVisible = ref(false);
const historyLoading = ref(false);
const historyError = ref('');
const historyAgent = ref<AgentView | null>(null);
const executionHistory = ref<AgentExecutionHistoryView[]>([]);
const traceVisible = ref(false);
const traceLoading = ref(false);
const traceError = ref('');
const traceLog = ref<NhsV1TraceLogView | null>(null);
const traceStepDetailVisible = ref(false);
const traceStepDetail = ref<NhsV1TraceStepView | null>(null);
const welcomeSnapshotVisible = ref(false);
const welcomeSnapshotVersion = ref<AgentVersionView | null>(null);
const promptOptimizeVisible = ref(false);
const promptOptimizeLoading = ref(false);
const promptOptimizeError = ref('');
const promptOptimizeOriginal = ref('');
const promptOptimizeResult = ref<PortalPromptOptimizeResult | null>(null);

const filteredAgents = computed(() => {
  const keyword = search.value.trim().toLocaleLowerCase();
  return agents.value.filter(agent =>
    !keyword || [agent.name, agent.agentKey, agent.description || ''].some(value => value.toLocaleLowerCase().includes(keyword))
  );
});
const modelOptions = computed<SelectOption[]>(() =>
  models.value.map(model => ({
    label: `${model.displayName} / ${model.providerType} / ${model.modelName}${model.status === 'active' ? '' : '（已停用）'}`,
    value: model.id,
    disabled: model.status !== 'active'
  }))
);
const knowledgeOptions = computed<SelectOption[]>(() =>
  knowledgeBases.value.map(base => ({
    label: `${base.name}${base.status === 'active' ? '' : '（已停用）'}`,
    value: base.id,
    disabled: base.status !== 'active'
  }))
);
const toolOptions = computed<(SelectOption | SelectGroupOption)[]>(() => {
  type ToolGroup = { type: 'group'; label: string; key: string; children: SelectOption[] };
  const groups: Record<string, ToolGroup> = {
    chatbi: { type: 'group', label: '数据智能', key: 'chatbi', children: [] },
    knowledge: { type: 'group', label: '知识检索', key: 'knowledge', children: [] },
    web: { type: 'group', label: 'Web 与研发', key: 'web', children: [] },
    office: { type: 'group', label: '办公文件', key: 'office', children: [] },
    notification: { type: 'group', label: '消息通知', key: 'notification', children: [] },
    memory: { type: 'group', label: '记忆与个人资源', key: 'memory', children: [] },
    delegation: { type: 'group', label: '多智能体协同与调度', key: 'delegation', children: [] },
    other: { type: 'group', label: '其他扩展工具', key: 'other', children: [] }
  };
  tools.value.forEach(tool => {
    const name = `${tool.name} ${tool.toolKey}`.toLowerCase();
    const group = name.includes('sub_agent') ? 'delegation'
      : name.includes('sql') || name.includes('dataset') || name.includes('bi_') || name.includes('olap') ? 'chatbi'
        : name.includes('knowledge') || name.includes('document_search') ? 'knowledge'
          : name.startsWith('excel_document') || name.startsWith('word_document') ? 'office'
            : name.includes('email') || name.includes('dingtalk') || name.includes('wechat') || name.includes('notification') ? 'notification'
              : name.includes('memory') || name.includes('preference') || name.includes('myinfo') ? 'memory'
                : name.includes('web_') || name.includes('http') || name.includes('search') || name.includes('browser') || name.includes('lint') ? 'web'
                  : 'other';
    groups[group].children.push({
      label: `${tool.name} / ${tool.toolType}${tool.available && tool.status === 'active' ? '' : '（不可用）'}`,
      value: tool.id,
      disabled: !tool.available || tool.status !== 'active'
    });
  });
  return Object.values(groups).filter(group => group.children.length > 0);
});
const skillOptions = computed<SelectOption[]>(() =>
  skills.value.map(skill => ({
    label: `${skill.name}${skill.publishedVersionId && skill.status === 'active' ? '' : '（未发布或已停用）'}`,
    value: skill.id,
    disabled: !skill.publishedVersionId || skill.status !== 'active'
  }))
);
const agentTypeOptions: SelectOption[] = [
  { label: '通用智能体', value: 'general' },
  { label: '业务助手', value: 'assistant' },
  { label: '知识问答', value: 'knowledge' },
  { label: '数据分析', value: 'data' },
  { label: '研发编码', value: 'coding' },
  { label: '任务主管', value: 'supervisor' }
];
const workspaceOptions: SelectOption[] = [
  { label: '不访问工作区', value: 'none' },
  { label: '只读访问', value: 'read_only' },
  { label: '读写访问', value: 'read_write' }
];
const responseFormatOptions: SelectOption[] = [
  { label: '自然语言', value: 'text' },
  { label: '结构化数据', value: 'json' }
];
const welcomeModeOptions: SelectOption[] = [
  { label: '手工配置', value: 'manual' },
  { label: 'AI 动态推荐', value: 'ai' }
];
const welcomeIconOptions: SelectOption[] = [
  { label: '对话', value: 'chat' },
  { label: '数据图表', value: 'chart' },
  { label: '知识库', value: 'knowledge' },
  { label: '工作区', value: 'workspace' },
  { label: '报告', value: 'report' },
  { label: '提醒', value: 'alert' }
];
const engineTypeOptions: Array<{ label: string; value: AgentEngineType }> = [
  { label: 'AgentScope Java', value: 'agentscope_java' }
];
const stepTitles = ['基本信息', '角色与指令', '引擎与运行', '能力装配', '体验与发布'];
const currentModel = computed(() => models.value.find(model => model.id === wizardForm.modelId));

function numberValue(value: unknown, fallback: number) {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function stringValue<T extends string>(value: unknown, allowed: readonly T[], fallback: T): T {
  return typeof value === 'string' && allowed.includes(value as T) ? value as T : fallback;
}

function statusType(status: string) {
  if (status === 'active' || status === 'published') return 'success';
  if (status === 'draft') return 'warning';
  return 'default';
}

function statusText(status: string) {
  return ({ active: '启用', disabled: '停用', archived: '已归档', draft: '草稿', published: '已发布' } as Record<string, string>)[status] || status;
}

function welcomeConfigSummary(config: Record<string, unknown> | undefined) {
  if (!config || config.enabled !== true) return '未启用';
  if (config.mode === 'ai') return 'AI 推荐';
  const cards = Array.isArray(config.cards) ? config.cards : [];
  return cards.length ? `手工 ${Math.min(cards.length, 3)} 卡片` : '手工配置';
}

function welcomeCards(config: Record<string, unknown> | undefined): Array<Record<string, unknown>> {
  if (!config || !Array.isArray(config.cards)) return [];
  return config.cards.filter(
    (card): card is Record<string, unknown> => Boolean(card && typeof card === 'object' && !Array.isArray(card))
  );
}

function welcomeRequirement(config: Record<string, unknown> | undefined) {
  if (!config) return '未指定';
  const value = config.generation_requirement ?? config.generationRequirement;
  return typeof value === 'string' && value.trim() ? value : '未指定';
}

function welcomeIcon(icon: unknown) {
  const icons: Record<string, string> = {
    chart: 'bar-chart-3',
    knowledge: 'book-open',
    workspace: 'folder-open',
    report: 'file-text',
    alert: 'triangle-alert',
    chat: 'message-circle'
  };
  return `lucide:${icons[String(icon)] || icons.chat}`;
}

function openWelcomeSnapshot(version: AgentVersionView) {
  welcomeSnapshotVersion.value = version;
  welcomeSnapshotVisible.value = true;
}

function modelName(modelId: string | null) {
  if (!modelId) return '未配置';
  return models.value.find(model => model.id === modelId)?.displayName || '模型不可用';
}

function engineTypeText(engineType: string) {
  return engineType === 'agentscope_java' ? 'AgentScope Java' : '未知引擎';
}

function selectEngineType(value: AgentEngineType) {
  if (engineTypeLocked.value) return;
  wizardForm.engineType = value;
  if (!wizardForm.modelId) {
    wizardForm.modelId = models.value.find(model => model.status === 'active')?.id || null;
  }
}

async function loadData() {
  loading.value = true;
  const [agentResult, modelResult, knowledgeResult, toolResult, skillResult] = await Promise.all([
    fetchAgents(undefined, true),
    fetchModels('chat', true),
    fetchKnowledgeBases(undefined, true),
    fetchTools(true),
    fetchSkills(true)
  ]);
  if (!agentResult.error) agents.value = agentResult.data;
  if (!modelResult.error) models.value = modelResult.data;
  if (!knowledgeResult.error) knowledgeBases.value = knowledgeResult.data;
  if (!toolResult.error) tools.value = toolResult.data;
  if (!skillResult.error) skills.value = skillResult.data;
  loading.value = false;
  if (!agentResult.error) applyRouteAgent();
}

function applyRouteAgent() {
  if (routeAgentApplied.value) return;
  const raw = Array.isArray(route.query.agent_id) ? route.query.agent_id[0] : route.query.agent_id;
  if (typeof raw !== 'string' || !raw.trim()) return;
  routeAgentApplied.value = true;
  const agent = agents.value.find(item => item.id === raw.trim());
  if (agent) void openVersions(agent);
  else window.$message?.warning('场景实例绑定的 Agent 当前不可用');
}

function bindingsOf(version: AgentVersionView | null, type: string): AgentResourceBindingPayload[] {
  return (version?.bindings || [])
    .filter(binding => binding.resourceType === type)
    .map(binding => ({
      resourceId: binding.resourceId,
      permission: binding.permission as AgentResourceBindingPayload['permission'],
      config: objectConfig(binding.config.binding) || binding.config
    }));
}

function objectConfig(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function welcomeCardsFromConfig(value: unknown, suggestions: string[]): WelcomeCardDraft[] {
  const source = Array.isArray(value) ? value : [];
  const configured = source
    .filter((item): item is Record<string, unknown> => Boolean(item && typeof item === 'object' && !Array.isArray(item)))
    .map(item => ({
      icon: typeof item.icon === 'string' ? item.icon : 'chat',
      title: typeof item.title === 'string' ? item.title : '',
      subtitle: typeof item.subtitle === 'string' ? item.subtitle : '',
      prompt: typeof item.prompt === 'string' ? item.prompt : ''
    }))
    .slice(0, 3);
  const legacy = suggestions.slice(0, 3).map(prompt => ({
    icon: 'chat',
    title: prompt.length > 16 ? `${prompt.slice(0, 16)}…` : prompt,
    subtitle: '点击开始提问',
    prompt
  }));
  return [...configured, ...legacy, ...emptyWelcomeCards()].slice(0, 3);
}

function resetWizard(agent: AgentView | null, version: AgentVersionView | null) {
  const runtime = version?.runtimeConfig || {};
  const welcome = version?.welcomeConfig || {};
  wizardForm.agentKey = agent?.agentKey || '';
  wizardForm.name = agent?.name || '';
  wizardForm.description = agent?.description || '';
  wizardForm.avatarUrl = agent?.avatarUrl || '';
  wizardForm.agentType = (agent?.agentType as SaveAgentPayload['agentType']) || 'assistant';
  wizardForm.defaultAgent = agent?.defaultAgent || false;
  wizardForm.sortOrder = agent?.sortOrder || 0;
  wizardForm.engineType = stringValue(
    agent?.engineType,
    ['agentscope_java'] as const,
    'agentscope_java'
  );
  wizardForm.systemPrompt = version?.systemPrompt || '';
  wizardForm.modelId = version?.modelId || models.value.find(model => model.status === 'active')?.id || null;
  wizardForm.synthesisModelId = version?.synthesisModelId || null;
  wizardForm.temperature = numberValue(runtime.temperature, 0.7);
  wizardForm.topP = numberValue(runtime.topP, 1);
  wizardForm.maxIterations = numberValue(runtime.maxIterations, 12);
  wizardForm.timeoutSeconds = numberValue(runtime.timeoutSeconds, 300);
  wizardForm.workspaceAccess = stringValue(runtime.workspaceAccess, ['none', 'read_only', 'read_write'] as const, 'none');
  wizardForm.responseFormat = stringValue(runtime.responseFormat, ['text', 'json'] as const, 'text');
  wizardForm.toolIds = bindingsOf(version, 'tool').map(binding => binding.resourceId);
  wizardForm.skillIds = bindingsOf(version, 'skill').map(binding => binding.resourceId);
  wizardForm.knowledgeBaseIds = bindingsOf(version, 'knowledge_base').map(binding => binding.resourceId);
  wizardForm.routingTags = [...(version?.routingTags || [])];
  wizardForm.welcomeMessage = typeof welcome.message === 'string' ? welcome.message : '';
  wizardForm.welcomeSuggestions = Array.isArray(welcome.suggestions)
    ? welcome.suggestions.filter((item): item is string => typeof item === 'string')
    : [];
  wizardForm.showSuggestions = welcome.showSuggestions !== false;
  const legacyWelcome = Object.prototype.hasOwnProperty.call(welcome, 'suggestions')
    || Object.prototype.hasOwnProperty.call(welcome, 'showSuggestions');
  wizardForm.welcomeEnabled = typeof welcome.enabled === 'boolean'
    ? welcome.enabled
    : legacyWelcome && wizardForm.showSuggestions;
  wizardForm.welcomeMode = welcome.mode === 'ai' ? 'ai' : 'manual';
  wizardForm.welcomeRequirement = typeof welcome.generation_requirement === 'string'
    ? welcome.generation_requirement
    : typeof welcome.generationRequirement === 'string' ? welcome.generationRequirement : '';
  wizardForm.welcomeCards = welcomeCardsFromConfig(welcome.cards, wizardForm.welcomeSuggestions);
  publishAfterSave.value = false;
}

async function fetchVersions(agent: AgentView) {
  const { data, error } = await fetchAgentVersions(agent.id);
  if (error) {
    versions.value = [];
    return null;
  }
  versions.value = data;
  return data;
}

async function openWizard(
  agent?: AgentView,
  preferredVersion?: AgentVersionView,
  startStep = 1,
  forceNewVersion = false
) {
  if (!agent) onboardingKey.value = crypto.randomUUID();
  wizardVisible.value = true;
  wizardLoading.value = true;
  wizardStep.value = startStep;
  editingAgent.value = agent || null;
  activeAgent.value = agent || null;
  let availableVersions: AgentVersionView[] = [];
  if (agent) {
    const loadedVersions = await fetchVersions(agent);
    if (loadedVersions === null) {
      engineTypeLocked.value = true;
      wizardLoading.value = false;
      wizardVisible.value = false;
      window.$message?.warning('版本信息加载失败，暂不能配置该智能体');
      return;
    }
    availableVersions = loadedVersions;
  }
  engineTypeLocked.value = Boolean(agent && availableVersions.length > 0);
  const draft = availableVersions.find(item => item.status === 'draft');
  const published = availableVersions.find(item => item.status === 'published');
  const base = preferredVersion || draft || published || availableVersions[0] || null;
  sourceVersion.value = base;
  editingVersion.value = !forceNewVersion && base?.status === 'draft' ? base : null;
  resetWizard(agent || null, base);
  wizardLoading.value = false;
}

async function validateCurrentStep() {
  try {
    await wizardFormRef.value?.validate();
  } catch {
    return false;
  }
  if (wizardStep.value === 2 && !wizardForm.systemPrompt.trim()) {
    window.$message?.warning('请先填写系统提示词');
    return false;
  }
  if (wizardStep.value === 3 && !wizardForm.modelId) {
    window.$message?.warning('请先选择对话模型');
    return false;
  }
  return true;
}

async function nextStep() {
  if (await validateCurrentStep()) wizardStep.value = Math.min(5, wizardStep.value + 1);
}

function previousStep() {
  wizardStep.value = Math.max(1, wizardStep.value - 1);
}

function applyPromptTemplate(type: SaveAgentPayload['agentType']) {
  const templates: Partial<Record<SaveAgentPayload['agentType'], string>> = {
    assistant: '你是一名企业业务助手。请准确理解用户目标，优先给出可执行的结论；信息不足时先提出必要问题，不要编造事实。',
    knowledge: '你是一名企业知识助手。回答必须以已授权知识库内容为依据，清晰标注引用；证据不足时明确说明无法确认。',
    data: '你是一名企业数据分析助手。先确认统计口径，再使用已授权数据工具完成分析；结论需说明数据范围和限制。',
    coding: '你是一名研发协作助手。先理解现有工程约束，再进行修改；保持变更范围清晰，并说明验证结果。',
    supervisor: '你是一名任务主管智能体。负责拆解目标、选择合适能力、跟踪执行状态，并在关键决策点请求人工确认。'
  };
  wizardForm.systemPrompt = templates[type] || '你是一名企业智能助手。请围绕用户目标提供准确、清晰且可执行的帮助。';
}

async function optimizeWizardPrompt() {
  const content = wizardForm.systemPrompt.trim();
  if (!content || promptOptimizeLoading.value) return;
  promptOptimizeOriginal.value = content;
  promptOptimizeResult.value = null;
  promptOptimizeError.value = '';
  promptOptimizeVisible.value = true;
  promptOptimizeLoading.value = true;
  try {
    const result = await optimizePortalPrompt(content);
    if (result.error) {
      promptOptimizeError.value = result.error.message || '提示词优化失败';
    } else {
      promptOptimizeResult.value = result.data;
    }
  } catch (error) {
    promptOptimizeError.value = error instanceof Error ? error.message : '提示词优化失败';
  } finally {
    promptOptimizeLoading.value = false;
  }
}

function applyWizardPromptOptimization() {
  const suggestion = promptOptimizeResult.value?.optimized_content;
  if (!suggestion) return;
  if (
    wizardForm.systemPrompt.trim() !== promptOptimizeOriginal.value
    && !window.confirm('系统提示词在优化期间已发生变化，确认用优化建议覆盖吗？')
  ) {
    return;
  }
  wizardForm.systemPrompt = suggestion;
  promptOptimizeVisible.value = false;
  window.$message?.success('优化建议已应用到当前草稿，保存后生效');
}

async function testSelectedModel() {
  if (wizardForm.engineType !== 'agentscope_java' || !wizardForm.modelId) return;
  modelTesting.value = true;
  const { data, error } = await testModel(wizardForm.modelId);
  if (!error) {
    const notify = data.success ? window.$message?.success : window.$message?.error;
    notify?.(`${data.message}，耗时 ${data.latencyMs} ms`);
  }
  modelTesting.value = false;
}

function buildBindings(
  type: 'tool' | 'skill' | 'knowledge_base',
  resourceIds: string[],
  permission: AgentResourceBindingPayload['permission']
) {
  const existing = new Map(bindingsOf(sourceVersion.value, type).map(binding => [binding.resourceId, binding]));
  return resourceIds.map(resourceId => {
    const binding = existing.get(resourceId);
    const config = binding?.config || (type === 'knowledge_base'
      ? { topK: 5, scoreThreshold: 0, citationRequired: true }
      : { enabled: true });
    return { resourceId, permission: binding?.permission || permission, config };
  });
}

async function submitWizard() {
  if (!wizardForm.agentKey.match(/^[a-z][a-z0-9._-]{0,127}$/) || !wizardForm.name.trim()) {
    wizardStep.value = 1;
    window.$message?.warning('请检查智能体名称和稳定标识');
    return;
  }
  if (!wizardForm.systemPrompt.trim()) {
    wizardStep.value = 2;
    window.$message?.warning('请填写系统提示词');
    return;
  }
  if (!wizardForm.modelId) {
    wizardStep.value = 3;
    window.$message?.warning('请选择对话模型');
    return;
  }
  const incompleteWelcomeCards = wizardForm.welcomeEnabled
    && wizardForm.welcomeMode === 'manual'
    && (wizardForm.welcomeCards.length !== 3
      || wizardForm.welcomeCards.some(card => !card.title.trim() || !card.subtitle.trim() || !card.prompt.trim()));
  if (incompleteWelcomeCards) {
    wizardStep.value = 5;
    window.$message?.warning('手工欢迎卡需要完整配置 3 张卡片');
    return;
  }

  wizardSubmitting.value = true;
  try {
    const engineConfig = buildAgentEngineConfig({
      engineType: wizardForm.engineType,
      maxIterations: numberValue(wizardForm.maxIterations, 12),
      timeoutSeconds: numberValue(wizardForm.timeoutSeconds, 300),
      workspaceAccess: wizardForm.workspaceAccess,
      responseFormat: wizardForm.responseFormat
    });
    const agentPayload: SaveAgentPayload = {
      name: wizardForm.name.trim(),
      description: wizardForm.description.trim() || undefined,
      avatarUrl: wizardForm.avatarUrl.trim() || undefined,
      agentType: wizardForm.agentType,
      defaultAgent: wizardForm.defaultAgent,
      sortOrder: wizardForm.sortOrder,
      engineType: wizardForm.engineType,
      engineConfig
    };
    const editableConfig = editableRuntimeConfig(sourceVersion.value?.runtimeConfig);
    const runtimeConfig = {
      ...editableConfig,
      temperature: numberValue(wizardForm.temperature, 0.7),
      topP: numberValue(wizardForm.topP, 1),
      maxIterations: numberValue(wizardForm.maxIterations, 12),
      timeoutSeconds: numberValue(wizardForm.timeoutSeconds, 300),
      workspaceAccess: wizardForm.workspaceAccess,
      responseFormat: wizardForm.responseFormat
    };
    const welcomeCards = wizardForm.welcomeCards.map(card => ({
      icon: card.icon,
      title: card.title.trim(),
      subtitle: card.subtitle.trim(),
      prompt: card.prompt.trim()
    }));
    const welcomeConfig = {
      ...sourceVersion.value?.welcomeConfig,
      enabled: wizardForm.welcomeEnabled,
      mode: wizardForm.welcomeMode,
      generation_requirement: wizardForm.welcomeRequirement.trim(),
      cards: welcomeCards,
      // Keep the legacy keys for older clients while the new card contract rolls out.
      message: wizardForm.welcomeMessage.trim(),
      suggestions: wizardForm.welcomeSuggestions.map(item => item.trim()).filter(Boolean),
      showSuggestions: wizardForm.welcomeEnabled && wizardForm.showSuggestions
    };
    const versionPayload: SaveAgentVersionPayload = {
      systemPrompt: wizardForm.systemPrompt.trim(),
      modelId: wizardForm.modelId,
      synthesisModelId: wizardForm.synthesisModelId || undefined,
      runtimeConfig,
      welcomeConfig,
      routingTags: wizardForm.routingTags,
      tools: buildBindings('tool', wizardForm.toolIds, 'invoke'),
      skills: buildBindings('skill', wizardForm.skillIds, 'use'),
      knowledgeBases: buildBindings('knowledge_base', wizardForm.knowledgeBaseIds, 'read')
    };
    let savedAgent: AgentView;
    let savedVersion: AgentVersionView;
    if (!editingAgent.value) {
      const onboardingResult = await onboardAgent({
        onboardingKey: onboardingKey.value,
        agent: { ...agentPayload, agentKey: wizardForm.agentKey.trim() },
        version: versionPayload
      });
      if (onboardingResult.error) return;
      savedAgent = onboardingResult.data.agent;
      savedVersion = onboardingResult.data.version;
    } else {
      const agentResult = await updateAgent(editingAgent.value.id, agentPayload);
      if (agentResult.error) return;
      savedAgent = agentResult.data;
      const versionResult = editingVersion.value
        ? await updateAgentVersion(savedAgent.id, editingVersion.value.id, versionPayload)
        : await createAgentVersion(savedAgent.id, versionPayload);
      if (versionResult.error) return;
      savedVersion = versionResult.data;
    }
    editingAgent.value = savedAgent;
    activeAgent.value = savedAgent;
    editingVersion.value = savedVersion;
    sourceVersion.value = savedVersion;
    if (publishAfterSave.value) {
      const publishResult = await publishAgentVersion(savedAgent.id, savedVersion.id);
      if (publishResult.error) return;
    }
    wizardVisible.value = false;
    window.$message?.success(publishAfterSave.value ? '智能体已保存并发布' : '智能体配置已保存为草稿');
    await loadData();
    if (versionVisible.value) await openVersions(savedAgent);
  } finally {
    wizardSubmitting.value = false;
  }
}

async function changeStatus(agent: AgentView, status: 'active' | 'disabled' | 'archived') {
  const { error } = await updateAgentStatus(agent.id, status);
  if (!error) {
    window.$message?.success('状态已更新');
    await loadData();
  }
}

async function moveAgent(agent: AgentView, offset: -1 | 1) {
  if (reorderLoading.value || search.value.trim()) return;
  const currentIndex = agents.value.findIndex(item => item.id === agent.id);
  const targetIndex = currentIndex + offset;
  if (currentIndex < 0 || targetIndex < 0 || targetIndex >= agents.value.length) return;
  const reordered = [...agents.value];
  const [moved] = reordered.splice(currentIndex, 1);
  reordered.splice(targetIndex, 0, moved);
  const items = reordered.map((item, index) => ({
    id: item.id,
    sortOrder: Math.min(10_000, (reordered.length - index) * 10)
  }));
  reorderLoading.value = true;
  try {
    const { error } = await reorderAgents(items);
    if (error) return;
    agents.value = reordered.map((item, index) => ({ ...item, sortOrder: items[index].sortOrder }));
    window.$message?.success('智能体顺序已更新');
  } finally {
    reorderLoading.value = false;
  }
}

function confirmDelete(agent: AgentView) {
  dialog.warning({
    title: '删除智能体',
    content: `确认删除“${agent.name}”？存在任务引用时后端会拒绝。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      const { error } = await deleteAgent(agent.id);
      if (!error) {
        window.$message?.success('智能体已删除');
        await loadData();
      }
    }
  });
}

async function openVersions(agent: AgentView) {
  activeAgent.value = agent;
  versionVisible.value = true;
  versionLoading.value = true;
  await fetchVersions(agent);
  versionLoading.value = false;
}

async function openExecutionHistory(agent: AgentView) {
  historyAgent.value = agent;
  historyVisible.value = true;
  historyLoading.value = true;
  historyError.value = '';
  executionHistory.value = [];
  const result = await fetchAgentExecutionHistory(agent.id, 100);
  if (result.error) {
    historyError.value = result.error.message || '执行历史加载失败';
  } else {
    executionHistory.value = result.data;
  }
  historyLoading.value = false;
}

async function openExecutionTrace(row: AgentExecutionHistoryView) {
  if (!row.trace_id) {
    window.$message?.warning('该执行记录没有可用的 Trace');
    return;
  }
  traceVisible.value = true;
  traceLoading.value = true;
  traceError.value = '';
  traceLog.value = null;
  traceStepDetail.value = null;
  const result = await fetchNhsV1TraceLogs(row.trace_id);
  if (result.error) {
    traceError.value = result.error.message || 'Trace 加载失败';
  } else {
    traceLog.value = result.data;
  }
  traceLoading.value = false;
}

function openTraceStepDetail(step: NhsV1TraceStepView) {
  traceStepDetail.value = step;
  traceStepDetailVisible.value = true;
}

function openVersionEditor(version?: AgentVersionView) {
  if (!activeAgent.value) return;
  const base = version || versions.value.find(item => item.status === 'published') || versions.value[0];
  void openWizard(activeAgent.value, base, version ? 2 : 1, !version);
}

async function publishVersion(version: AgentVersionView) {
  if (!activeAgent.value) return;
  const { data, error } = await publishAgentVersion(activeAgent.value.id, version.id);
  if (!error) {
    window.$message?.success(data.replayed ? '该版本已发布' : '版本发布成功');
    await openVersions(activeAgent.value);
    await loadData();
  }
}

async function cloneVersion(version: AgentVersionView) {
  if (!activeAgent.value) return;
  versionActionKey.value = `clone:${version.id}`;
  try {
    const { data, error } = await cloneAgentVersion(activeAgent.value.id, version.id);
    if (error) return;
    window.$message?.success(`已基于 v${version.versionNo} 创建草稿 v${data.versionNo}`);
    await openVersionEditor(data);
  } finally {
    versionActionKey.value = '';
  }
}

function confirmDeleteVersion(version: AgentVersionView) {
  if (!activeAgent.value) return;
  const agent = activeAgent.value;
  dialog.warning({
    title: '删除草稿版本',
    content: `确认删除 v${version.versionNo}？该草稿及其资源绑定将永久删除。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      versionActionKey.value = `delete:${version.id}`;
      try {
        const { error } = await deleteAgentVersion(agent.id, version.id);
        if (!error) {
          window.$message?.success('草稿版本已删除');
          await openVersions(agent);
        }
      } finally {
        versionActionKey.value = '';
      }
    }
  });
}

async function archiveVersion(version: AgentVersionView) {
  if (!activeAgent.value) return;
  const { error } = await archiveAgentVersion(activeAgent.value.id, version.id);
  if (!error) {
    window.$message?.success('版本已归档');
    await openVersions(activeAgent.value);
  }
}

const columns: DataTableColumns<AgentView> = [
  {
    title: '智能体',
    key: 'name',
    minWidth: 280,
    render: row => h('div', { class: 'primary-cell' }, [h('strong', row.name), h('span', `${row.agentKey} / ${row.description || '暂无描述'}`)])
  },
  { title: '类型', key: 'agentType', width: 110, render: row => String(agentTypeOptions.find(option => option.value === row.agentType)?.label || row.agentType) },
  { title: '引擎', key: 'engineType', width: 135, render: row => engineTypeText(row.engineType) },
  {
    title: '发布状态',
    key: 'publishedVersionId',
    width: 120,
    render: row => row.publishedVersionId ? '已有发布版本' : '尚未发布'
  },
  { title: '状态', key: 'status', width: 95, render: row => h(NTag, { size: 'small', type: statusType(row.status) }, () => statusText(row.status)) },
  {
    title: '排序',
    key: 'sortOrder',
    width: 118,
    render: row => {
      const index = agents.value.findIndex(item => item.id === row.id);
      const disabled = reorderLoading.value || Boolean(search.value.trim());
      return h(NSpace, { size: 2, align: 'center', wrap: false }, () => [
        h('span', { class: 'sort-value' }, String(row.sortOrder)),
        h(NButton, {
          size: 'tiny', quaternary: true, title: '上移',
          disabled: disabled || index <= 0,
          onClick: () => moveAgent(row, -1)
        }, { icon: () => h(SvgIcon, { icon: 'lucide:chevron-up' }) }),
        h(NButton, {
          size: 'tiny', quaternary: true, title: '下移',
          disabled: disabled || index < 0 || index >= agents.value.length - 1,
          onClick: () => moveAgent(row, 1)
        }, { icon: () => h(SvgIcon, { icon: 'lucide:chevron-down' }) })
      ]);
    }
  },
  { title: '更新时间', key: 'updateTime', width: 165, render: row => dayjs(row.updateTime || row.createTime).format('YYYY-MM-DD HH:mm') },
  {
    title: '操作',
    key: 'actions',
    width: 410,
    fixed: 'right',
    render: row => h(NSpace, { size: 6 }, () => [
      h(NButton, { size: 'small', type: 'primary', secondary: true, disabled: row.systemAgent, onClick: () => openWizard(row) }, () => '配置'),
      h(NButton, { size: 'small', secondary: true, onClick: () => openVersions(row) }, () => '版本'),
      h(NButton, { size: 'small', secondary: true, onClick: () => openExecutionHistory(row) }, () => '历史'),
      row.status === 'active'
        ? h(NButton, { size: 'small', quaternary: true, disabled: row.systemAgent, onClick: () => changeStatus(row, 'disabled') }, () => '停用')
        : h(NButton, { size: 'small', quaternary: true, disabled: row.systemAgent || row.status === 'archived', onClick: () => changeStatus(row, 'active') }, () => '启用'),
      h(NButton, { size: 'small', type: 'error', quaternary: true, disabled: row.systemAgent, onClick: () => confirmDelete(row) }, () => '删除')
    ])
  }
];

const versionColumns: DataTableColumns<AgentVersionView> = [
  { title: '版本', key: 'versionNo', width: 85, render: row => `v${row.versionNo}` },
  { title: '模型', key: 'modelId', minWidth: 150, render: row => modelName(row.modelId) },
  { title: '绑定资源', key: 'bindings', width: 100, render: row => row.bindings.length },
  {
    title: '欢迎卡',
    key: 'welcomeConfig',
    width: 115,
    render: row => h(NTag, { size: 'small', bordered: false }, () => welcomeConfigSummary(row.welcomeConfig))
  },
  { title: '状态', key: 'status', width: 95, render: row => h(NTag, { size: 'small', type: statusType(row.status) }, () => statusText(row.status)) },
  { title: '创建时间', key: 'createdAt', width: 165, render: row => dayjs(row.createdAt).format('YYYY-MM-DD HH:mm') },
  {
    title: '操作',
    key: 'actions',
    width: 430,
    render: row => h(NSpace, { size: 6 }, () => row.status === 'draft'
      ? [
          h(NButton, { size: 'small', quaternary: true, title: '查看欢迎卡快照', onClick: () => openWelcomeSnapshot(row) }, { icon: () => h(SvgIcon, { icon: 'lucide:message-square-more' }) }),
          h(NButton, { size: 'small', quaternary: true, onClick: () => openVersionEditor(row) }, () => '编辑'),
          h(NButton, {
            size: 'small', quaternary: true,
            loading: versionActionKey.value === `clone:${row.id}`,
            onClick: () => cloneVersion(row)
          }, () => '克隆'),
          h(NButton, { size: 'small', type: 'primary', secondary: true, onClick: () => publishVersion(row) }, () => '发布'),
          h(NButton, { size: 'small', quaternary: true, onClick: () => archiveVersion(row) }, () => '归档'),
          h(NButton, {
            size: 'small', type: 'error', quaternary: true,
            loading: versionActionKey.value === `delete:${row.id}`,
            onClick: () => confirmDeleteVersion(row)
          }, () => '删除')
        ]
      : [
          h(NButton, { size: 'small', quaternary: true, title: '查看欢迎卡快照', onClick: () => openWelcomeSnapshot(row) }, { icon: () => h(SvgIcon, { icon: 'lucide:message-square-more' }) }),
          h(NButton, {
            size: 'small', quaternary: true,
            loading: versionActionKey.value === `clone:${row.id}`,
            onClick: () => cloneVersion(row)
          }, () => '克隆'),
          ...(row.status === 'published'
            ? [h(NButton, { size: 'small', quaternary: true, onClick: () => archiveVersion(row) }, () => '归档')]
            : [])
        ])
  }
];

const historyColumns: DataTableColumns<AgentExecutionHistoryView> = [
  {
    title: '时间',
    key: 'created_at',
    width: 165,
    render: row => dayjs(row.created_at).format('YYYY-MM-DD HH:mm')
  },
  {
    title: '用户',
    key: 'username',
    width: 125,
    render: row => row.username || '-'
  },
  {
    title: '问题',
    key: 'query',
    minWidth: 260,
    ellipsis: { tooltip: true },
    render: row => row.query || row.summary || '-'
  },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: row => h(NTag, { size: 'small', type: statusType(row.status) }, () => row.status)
  },
  {
    title: '耗时',
    key: 'execution_time_ms',
    width: 100,
    render: row => row.execution_time_ms == null ? '-' : `${row.execution_time_ms} ms`
  },
  {
    title: 'Trace',
    key: 'trace_id',
    width: 180,
    ellipsis: { tooltip: true },
    render: row => row.trace_id || '-'
  },
  {
    title: '操作',
    key: 'actions',
    width: 100,
    render: row => h(NButton, {
      size: 'small',
      quaternary: true,
      disabled: !row.trace_id,
      onClick: () => openExecutionTrace(row)
    }, { icon: () => h(SvgIcon, { icon: 'lucide:route' }), default: () => '详情' })
  }
];

const traceColumns: DataTableColumns<NhsV1TraceStepView> = [
  { title: '#', key: 'step_number', width: 55 },
  { title: '事件', key: 'event_type', width: 150 },
  { title: 'Agent', key: 'agent_name', width: 150 },
  { title: '模型', key: 'model', width: 150, render: row => row.model || '-' },
  { title: '工具', key: 'tool_name', width: 160, render: row => row.tool_name || '-' },
  {
    title: '状态',
    key: 'status',
    width: 95,
    render: row => h(NTag, { size: 'small', type: statusType(row.status) }, () => row.status)
  },
  {
    title: '耗时',
    key: 'execution_time_ms',
    width: 100,
    render: row => row.execution_time_ms == null ? '-' : `${row.execution_time_ms} ms`
  },
  {
    title: 'Token',
    key: 'total_tokens',
    width: 95,
    render: row => `${row.total_tokens || 0}`
  },
  {
    title: '错误',
    key: 'error_message',
    width: 180,
    ellipsis: { tooltip: true },
    render: row => row.error_message || '-'
  },
  {
    title: '时间',
      key: 'timestamp',
      width: 170,
      render: row => row.timestamp ? dayjs(row.timestamp).format('YYYY-MM-DD HH:mm:ss') : '-'
  },
  {
    title: '详情',
    key: 'detail',
    width: 80,
    render: row => h(NButton, {
      size: 'small',
      quaternary: true,
      title: '查看完整事件字段',
      onClick: () => openTraceStepDetail(row)
    }, { icon: () => h(SvgIcon, { icon: 'lucide:scan-search' }) })
  }
];

onMounted(loadData);
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h2>{{ $t('page.agentCenter.title') }}</h2>
        <p>{{ $t('page.agentCenter.desc') }}</p>
      </div>
      <NButton type="primary" @click="openWizard()">
        <template #icon><SvgIcon icon="lucide:plus" /></template>
        创建智能体
      </NButton>
    </div>

    <div class="toolbar">
      <NInput v-model:value="search" clearable placeholder="搜索名称、标识或描述" class="search-input">
        <template #prefix><SvgIcon icon="lucide:search" /></template>
      </NInput>
      <NButton secondary :loading="loading" @click="loadData">
        <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
        刷新
      </NButton>
    </div>

    <NAlert v-if="search.trim()" type="info" :bordered="false" class="sort-hint">清除搜索条件后可调整智能体顺序。</NAlert>
    <NDataTable :columns="columns" :data="filteredAgents" :loading="loading || reorderLoading" :scroll-x="1460" :row-key="row => row.id" />

    <NModal
      v-model:show="wizardVisible"
      preset="card"
      :title="editingAgent ? '配置智能体' : '创建智能体'"
      :mask-closable="false"
      class="wizard-modal"
      style="width: min(1040px, calc(100vw - 32px))"
    >
      <NSpin :show="wizardLoading">
        <NSteps :current="wizardStep" size="small" class="wizard-steps">
          <NStep v-for="title in stepTitles" :key="title" :title="title" />
        </NSteps>

        <NForm ref="wizardFormRef" :model="wizardForm" :rules="wizardRules" label-placement="top">
          <div v-if="wizardStep === 1" class="step-content">
            <div class="section-heading">
              <h3>定义智能体身份</h3>
              <p>名称和稳定标识用于会话路由与版本管理。</p>
            </div>
            <div class="form-grid">
              <NFormItem label="稳定标识" path="agentKey">
                <NInput v-model:value="wizardForm.agentKey" :disabled="Boolean(editingAgent)" placeholder="research_assistant" />
              </NFormItem>
              <NFormItem label="智能体名称" path="name">
                <NInput v-model:value="wizardForm.name" maxlength="128" placeholder="研究分析助手" />
              </NFormItem>
              <NFormItem label="智能体类型">
                <NSelect v-model:value="wizardForm.agentType" :options="agentTypeOptions" />
              </NFormItem>
              <NFormItem label="运行引擎">
                <NRadioGroup :value="wizardForm.engineType" :disabled="engineTypeLocked" @update:value="selectEngineType">
                  <NRadioButton v-for="engine in engineTypeOptions" :key="engine.value" :value="engine.value">
                    {{ engine.label }}
                  </NRadioButton>
                </NRadioGroup>
              </NFormItem>
              <NAlert v-if="engineTypeLocked" type="info" :bordered="false" class="full-field engine-lock-alert">
                该智能体已有版本，运行引擎不可切换。
              </NAlert>
              <NFormItem label="头像地址">
                <NInput v-model:value="wizardForm.avatarUrl" clearable placeholder="站内路径或 HTTPS 地址" />
              </NFormItem>
              <NFormItem label="业务描述" class="full-field">
                <NInput v-model:value="wizardForm.description" type="textarea" :autosize="{ minRows: 3, maxRows: 6 }" maxlength="4000" placeholder="说明它负责什么业务、服务哪些用户" />
              </NFormItem>
              <NFormItem label="默认智能体">
                <NSwitch v-model:value="wizardForm.defaultAgent">
                  <template #checked>设为默认</template>
                  <template #unchecked>普通智能体</template>
                </NSwitch>
              </NFormItem>
              <NFormItem label="列表顺序">
                <NInputNumber v-model:value="wizardForm.sortOrder" :min="-10000" :max="10000" />
              </NFormItem>
            </div>
          </div>

          <div v-else-if="wizardStep === 2" class="step-content">
            <div class="section-heading with-action">
              <div>
                <h3>设置角色与边界</h3>
                <p>系统提示词定义角色、目标、工作原则和禁止事项。</p>
              </div>
              <NSpace size="small">
                <NButton secondary size="small" @click="applyPromptTemplate(wizardForm.agentType)">使用类型模板</NButton>
                <NButton secondary size="small" :loading="promptOptimizeLoading" :disabled="!wizardForm.systemPrompt.trim()" @click="optimizeWizardPrompt">
                  <template #icon><SvgIcon icon="lucide:sparkles" /></template>
                  AI 优化
                </NButton>
              </NSpace>
            </div>
            <NFormItem label="系统提示词" path="systemPrompt">
              <NInput
                v-model:value="wizardForm.systemPrompt"
                type="textarea"
                :autosize="{ minRows: 13, maxRows: 22 }"
                maxlength="100000"
                show-count
                placeholder="描述智能体的角色、工作目标、工具使用规则和输出要求"
              />
            </NFormItem>
          </div>

          <div v-else-if="wizardStep === 3" class="step-content">
            <div class="section-heading">
              <h3>选择模型与运行策略</h3>
              <p>常用选项直接配置，高级底层参数由模型中心统一管理。</p>
            </div>
            <div class="model-row">
              <NFormItem label="主对话模型" path="modelId" class="model-select">
                <NSelect v-model:value="wizardForm.modelId" :options="modelOptions" filterable />
              </NFormItem>
              <NButton secondary :loading="modelTesting" :disabled="!wizardForm.modelId" @click="testSelectedModel">
                <template #icon><SvgIcon icon="lucide:plug-zap" /></template>
                测试模型
              </NButton>
            </div>
            <NAlert v-if="currentModel" type="info" :bordered="false" class="model-summary">
              {{ currentModel.providerType }} / {{ currentModel.modelName }} / 上下文 {{ currentModel.contextSize || '由服务商决定' }}
            </NAlert>
            <div class="form-grid runtime-grid">
              <NFormItem label="综合模型">
                <NSelect v-model:value="wizardForm.synthesisModelId" :options="modelOptions" filterable clearable placeholder="默认使用主模型" />
              </NFormItem>
              <NFormItem label="工作区权限">
                <NSelect v-model:value="wizardForm.workspaceAccess" :options="workspaceOptions" />
              </NFormItem>
              <NFormItem label="生成随机度">
                <NSlider v-model:value="wizardForm.temperature" :min="0" :max="2" :step="0.1" :tooltip="true" />
              </NFormItem>
              <NFormItem label="候选采样范围">
                <NSlider v-model:value="wizardForm.topP" :min="0" :max="1" :step="0.05" :tooltip="true" />
              </NFormItem>
              <NFormItem label="最大执行轮次">
                <NInputNumber v-model:value="wizardForm.maxIterations" :min="1" :max="100" />
              </NFormItem>
              <NFormItem label="执行超时（秒）">
                <NInputNumber v-model:value="wizardForm.timeoutSeconds" :min="1" :max="86400" />
              </NFormItem>
              <NFormItem label="回答形式">
                <NSelect v-model:value="wizardForm.responseFormat" :options="responseFormatOptions" />
              </NFormItem>
            </div>
          </div>

          <div v-else-if="wizardStep === 4" class="step-content">
            <div class="section-heading">
              <h3>装配可用能力</h3>
              <p>只展示已登记资源，发布时会冻结当前选择及其配置。</p>
            </div>
            <div class="binding-section">
              <div class="binding-title"><SvgIcon icon="lucide:wrench" /><span>工具</span><NTag size="small">{{ wizardForm.toolIds.length }}</NTag></div>
              <NSelect v-model:value="wizardForm.toolIds" :options="toolOptions" multiple filterable clearable placeholder="选择 API、搜索、SQL、沙箱或 MCP 工具" />
            </div>
            <div class="binding-section">
              <div class="binding-title"><SvgIcon icon="lucide:package-open" /><span>Skills</span><NTag size="small">{{ wizardForm.skillIds.length }}</NTag></div>
              <NSelect v-model:value="wizardForm.skillIds" :options="skillOptions" multiple filterable clearable placeholder="选择已发布的 Skill" />
            </div>
            <div class="binding-section">
              <div class="binding-title"><SvgIcon icon="lucide:library" /><span>知识库</span><NTag size="small">{{ wizardForm.knowledgeBaseIds.length }}</NTag></div>
              <NSelect v-model:value="wizardForm.knowledgeBaseIds" :options="knowledgeOptions" multiple filterable clearable placeholder="选择可检索的知识库" />
            </div>
            <NFormItem label="路由标签" class="routing-tags">
              <NDynamicTags v-model:value="wizardForm.routingTags" :max="32" />
            </NFormItem>
          </div>

          <div v-else class="step-content">
            <div class="section-heading">
              <h3>设置欢迎体验并确认发布</h3>
              <p>欢迎卡会展示在新会话开始位置，点击后直接发起对应问题。</p>
            </div>
            <NCheckbox v-model:checked="wizardForm.welcomeEnabled">启用欢迎卡</NCheckbox>
            <div v-if="wizardForm.welcomeEnabled" class="welcome-config">
              <NFormItem label="卡片来源">
                <NSelect v-model:value="wizardForm.welcomeMode" :options="welcomeModeOptions" />
              </NFormItem>
              <NFormItem v-if="wizardForm.welcomeMode === 'ai'" label="AI 推荐要求">
                <NInput
                  v-model:value="wizardForm.welcomeRequirement"
                  type="textarea"
                  :autosize="{ minRows: 2, maxRows: 4 }"
                  maxlength="500"
                  placeholder="例如：优先推荐经营分析、风险提醒和知识检索问题"
                />
              </NFormItem>
              <div v-else class="welcome-card-editor">
                <div v-for="(card, index) in wizardForm.welcomeCards" :key="index" class="welcome-card-row">
                  <div class="welcome-card-row-title">卡片 {{ index + 1 }}</div>
                  <NSelect v-model:value="card.icon" :options="welcomeIconOptions" />
                  <NInput v-model:value="card.title" maxlength="40" placeholder="标题" />
                  <NInput v-model:value="card.subtitle" maxlength="100" placeholder="副标题" />
                  <NInput v-model:value="card.prompt" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" maxlength="300" placeholder="点击后发送的问题" />
                </div>
              </div>
            </div>
            <NFormItem label="兼容欢迎语">
              <NInput v-model:value="wizardForm.welcomeMessage" type="textarea" :autosize="{ minRows: 3, maxRows: 6 }" maxlength="2000" placeholder="你好，我可以帮你处理哪些工作" />
            </NFormItem>
            <NFormItem label="兼容推荐问题">
              <NDynamicTags v-model:value="wizardForm.welcomeSuggestions" :max="8" />
            </NFormItem>
            <NCheckbox v-model:checked="wizardForm.showSuggestions">兼容旧版客户端展示推荐问题</NCheckbox>
            <NDivider />
            <NDescriptions :column="2" bordered size="small" class="review-summary">
              <NDescriptionsItem label="智能体">{{ wizardForm.name }}</NDescriptionsItem>
              <NDescriptionsItem label="运行引擎">{{ engineTypeText(wizardForm.engineType) }}</NDescriptionsItem>
              <NDescriptionsItem label="主模型">
                {{ currentModel?.displayName || '未选择' }}
              </NDescriptionsItem>
              <NDescriptionsItem label="工具 / Skill">{{ wizardForm.toolIds.length }} / {{ wizardForm.skillIds.length }}</NDescriptionsItem>
              <NDescriptionsItem label="知识库">{{ wizardForm.knowledgeBaseIds.length }}</NDescriptionsItem>
              <NDescriptionsItem label="工作区">
                {{ workspaceOptions.find(item => item.value === wizardForm.workspaceAccess)?.label }}
              </NDescriptionsItem>
              <NDescriptionsItem label="版本动作">{{ editingVersion ? `更新草稿 v${editingVersion.versionNo}` : '创建新草稿' }}</NDescriptionsItem>
            </NDescriptions>
            <NCheckbox v-model:checked="publishAfterSave" class="publish-option">保存完成后立即发布该版本</NCheckbox>
          </div>
        </NForm>
      </NSpin>

      <template #footer>
        <div class="wizard-footer">
          <NButton @click="wizardVisible = false">取消</NButton>
          <NSpace>
            <NButton v-if="wizardStep > 1" secondary @click="previousStep">
              <template #icon><SvgIcon icon="lucide:arrow-left" /></template>
              上一步
            </NButton>
            <NButton v-if="wizardStep < 5" type="primary" @click="nextStep">
              下一步
              <template #icon><SvgIcon icon="lucide:arrow-right" /></template>
            </NButton>
            <NButton v-else type="primary" :loading="wizardSubmitting" @click="submitWizard">
              <template #icon><SvgIcon :icon="publishAfterSave ? 'lucide:rocket' : 'lucide:save'" /></template>
              {{ publishAfterSave ? '保存并发布' : '保存草稿' }}
            </NButton>
          </NSpace>
        </div>
      </template>
    </NModal>

    <NModal v-model:show="promptOptimizeVisible" preset="card" title="AI 优化系统提示词" style="width: min(1080px, 96vw)">
      <NAlert v-if="promptOptimizeError" type="error" :bordered="false" class="history-alert">
        {{ promptOptimizeError }}
      </NAlert>
      <NGrid :x-gap="12" :y-gap="12" responsive="screen" item-responsive>
        <NGi span="24 m:12">
          <section class="prompt-result-panel">
            <div class="prompt-result-label">当前提示词</div>
            <pre>{{ promptOptimizeOriginal }}</pre>
          </section>
        </NGi>
        <NGi span="24 m:12">
          <section class="prompt-result-panel">
            <div class="prompt-result-label">优化建议</div>
            <div v-if="promptOptimizeLoading" class="prompt-loading">
              <NSpin size="small" />
              <span>正在生成优化建议</span>
            </div>
            <pre v-else-if="promptOptimizeResult">{{ promptOptimizeResult.optimized_content }}</pre>
            <div v-else-if="!promptOptimizeError" class="prompt-loading" />
          </section>
        </NGi>
      </NGrid>
      <template #footer>
        <NSpace justify="end">
          <NButton :disabled="promptOptimizeLoading" @click="promptOptimizeVisible = false">关闭</NButton>
          <NButton secondary :loading="promptOptimizeLoading" @click="optimizeWizardPrompt">重新生成</NButton>
          <NButton type="primary" :disabled="!promptOptimizeResult || promptOptimizeLoading" @click="applyWizardPromptOptimization">
            应用到编辑器
          </NButton>
        </NSpace>
      </template>
    </NModal>

    <NDrawer v-model:show="versionVisible" width="min(920px, calc(100vw - 16px))" placement="right">
      <NDrawerContent closable>
        <template #header>
          <div class="drawer-header">
            <span>{{ activeAgent?.name || '' }}版本管理</span>
            <NButton type="primary" size="small" @click="openVersionEditor()">
              <template #icon><SvgIcon icon="lucide:plus" /></template>
              新建草稿
            </NButton>
          </div>
        </template>
        <NDataTable :columns="versionColumns" :data="versions" :loading="versionLoading" :scroll-x="900" :row-key="row => row.id" />
      </NDrawerContent>
    </NDrawer>

    <NModal
      v-model:show="welcomeSnapshotVisible"
      preset="card"
      title="欢迎卡版本快照"
      style="width: min(860px, 94vw)"
    >
      <template v-if="welcomeSnapshotVersion">
        <NDescriptions :column="2" bordered size="small" class="mb-12px">
          <NDescriptionsItem label="版本">v{{ welcomeSnapshotVersion.versionNo }}</NDescriptionsItem>
          <NDescriptionsItem label="状态">{{ statusText(welcomeSnapshotVersion.status) }}</NDescriptionsItem>
          <NDescriptionsItem label="配置">{{ welcomeConfigSummary(welcomeSnapshotVersion.welcomeConfig) }}</NDescriptionsItem>
          <NDescriptionsItem label="创建时间">{{ dayjs(welcomeSnapshotVersion.createdAt).format('YYYY-MM-DD HH:mm') }}</NDescriptionsItem>
        </NDescriptions>
        <div v-if="welcomeSnapshotVersion.welcomeConfig.mode === 'ai'" class="welcome-snapshot-requirement">
          <div class="prompt-result-label">AI 推荐要求</div>
          <p>{{ welcomeRequirement(welcomeSnapshotVersion.welcomeConfig) }}</p>
        </div>
        <div
          v-if="welcomeCards(welcomeSnapshotVersion.welcomeConfig).length"
          class="welcome-snapshot-cards"
        >
          <div
            v-for="(card, index) in welcomeCards(welcomeSnapshotVersion.welcomeConfig)"
            :key="`${welcomeSnapshotVersion.id}-${index}`"
            class="welcome-snapshot-card"
          >
            <div class="welcome-snapshot-card-head">
              <NTag size="small" :bordered="false">卡片 {{ index + 1 }}</NTag>
              <SvgIcon :icon="welcomeIcon(card.icon)" />
            </div>
            <strong>{{ card.title || '未命名' }}</strong>
            <span>{{ card.subtitle || '无副标题' }}</span>
            <p>{{ card.prompt || '无点击问题' }}</p>
          </div>
        </div>
        <NCode
          :code="JSON.stringify(welcomeSnapshotVersion.welcomeConfig, null, 2)"
          language="json"
          word-wrap
          class="mt-12px"
        />
      </template>
    </NModal>

    <NDrawer v-model:show="historyVisible" width="min(1120px, calc(100vw - 16px))" placement="right">
      <NDrawerContent closable>
        <template #header>
          <div class="drawer-header">
            <span>{{ historyAgent?.name || '' }}执行历史</span>
            <NButton
              secondary
              size="small"
              :loading="historyLoading"
              @click="historyAgent && openExecutionHistory(historyAgent)"
            >
              <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
              刷新
            </NButton>
          </div>
        </template>
        <NAlert v-if="historyError" type="error" :bordered="false" class="history-alert">
          {{ historyError }}
        </NAlert>
        <NEmpty v-else-if="!historyLoading && !executionHistory.length" description="暂无执行历史" />
        <NDataTable
          v-else
          :columns="historyColumns"
          :data="executionHistory"
          :loading="historyLoading"
          :scroll-x="980"
          :row-key="row => row.id"
        />
      </NDrawerContent>
    </NDrawer>

    <NModal v-model:show="traceVisible" preset="card" title="执行 Trace" style="width: min(1180px, 96vw)">
      <NAlert v-if="traceError" type="error" :bordered="false" class="history-alert">
        {{ traceError }}
      </NAlert>
      <NSpin :show="traceLoading">
        <div v-if="traceLog" class="trace-summary">
          <NTag type="info" size="small" :bordered="false">{{ traceLog.trace_id }}</NTag>
          <span>{{ traceLog.total_steps }} 个步骤</span>
          <span v-if="traceLog.history.status">终态：{{ traceLog.history.status }}</span>
        </div>
        <NDataTable
          v-if="traceLog"
          :columns="traceColumns"
          :data="traceLog.steps"
          :scroll-x="980"
          :row-key="row => `${row.step_number}-${row.event_type}`"
        />
        <NEmpty v-else-if="!traceLoading && !traceError" description="暂无 Trace 事件" />
      </NSpin>
    </NModal>

    <NModal v-model:show="traceStepDetailVisible" preset="card" title="Trace 事件详情" style="width: min(900px, 94vw)">
      <template v-if="traceStepDetail">
        <NDescriptions :column="2" bordered size="small" class="trace-step-descriptions">
          <NDescriptionsItem label="步骤">#{{ traceStepDetail.step_number }}</NDescriptionsItem>
          <NDescriptionsItem label="事件">{{ traceStepDetail.event_type }}</NDescriptionsItem>
          <NDescriptionsItem label="Agent">{{ traceStepDetail.agent_name || '-' }}</NDescriptionsItem>
          <NDescriptionsItem label="模型">{{ traceStepDetail.model || '-' }}</NDescriptionsItem>
          <NDescriptionsItem label="工具">{{ traceStepDetail.tool_name || '-' }}</NDescriptionsItem>
          <NDescriptionsItem label="状态">{{ traceStepDetail.status }}</NDescriptionsItem>
          <NDescriptionsItem label="耗时">{{ traceStepDetail.execution_time_ms == null ? '-' : `${traceStepDetail.execution_time_ms} ms` }}</NDescriptionsItem>
          <NDescriptionsItem label="Token">输入 {{ traceStepDetail.prompt_tokens }} / 输出 {{ traceStepDetail.completion_tokens }} / 总计 {{ traceStepDetail.total_tokens }}</NDescriptionsItem>
          <NDescriptionsItem label="Span">{{ traceStepDetail.span_id || '-' }}</NDescriptionsItem>
          <NDescriptionsItem label="父 Span">{{ traceStepDetail.parent_span_id || '-' }}</NDescriptionsItem>
          <NDescriptionsItem label="错误" :span="2">{{ traceStepDetail.error_message || '无' }}</NDescriptionsItem>
        </NDescriptions>
        <NCollapse class="trace-step-collapse">
          <NCollapseItem title="工具输入" name="tool-input"><NCode :code="JSON.stringify(traceStepDetail.tool_input, null, 2)" language="json" word-wrap /></NCollapseItem>
          <NCollapseItem title="工具输出" name="tool-output"><NCode :code="JSON.stringify(traceStepDetail.tool_output, null, 2)" language="json" word-wrap /></NCollapseItem>
          <NCollapseItem title="事件元数据" name="meta"><NCode :code="JSON.stringify(traceStepDetail.meta_info, null, 2)" language="json" word-wrap /></NCollapseItem>
        </NCollapse>
      </template>
    </NModal>
  </div>
</template>

<style scoped lang="scss">
.page-container { padding: 16px; }
.page-header, .toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.page-header { margin-bottom: 20px; }
.page-header h2 { margin: 0 0 4px; font-size: 20px; }
.page-header p, .section-heading p { margin: 0; color: var(--n-text-color-3); }
.toolbar { margin-bottom: 16px; }
.search-input { width: min(420px, 100%); }
.sort-hint { margin-bottom: 12px; }
:deep(.sort-value) { min-width: 30px; color: var(--n-text-color-3); font-variant-numeric: tabular-nums; }
.wizard-steps { margin-bottom: 24px; padding: 0 8px 20px; border-bottom: 1px solid var(--n-border-color); }
.step-content { min-height: 430px; }
.section-heading { margin-bottom: 20px; }
.section-heading h3 { margin: 0 0 5px; font-size: 17px; }
.section-heading.with-action { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 18px; }
.full-field { grid-column: 1 / -1; }
.model-row { display: flex; align-items: flex-end; gap: 12px; }
.model-select, .provider-select { flex: 1; }
.provider-row { display: flex; align-items: flex-end; gap: 12px; }
.engine-lock-alert { margin-bottom: 18px; }
.model-summary { margin-bottom: 20px; }
.runtime-grid { margin-top: 8px; }
.binding-section { padding: 16px 0; border-bottom: 1px solid var(--n-border-color); }
.binding-title { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; font-weight: 600; }
.routing-tags { margin-top: 18px; }
.welcome-config { margin: 16px 0 4px; padding: 14px; border: 1px solid var(--n-border-color); border-radius: 6px; }
.welcome-card-editor { display: grid; gap: 12px; }
.welcome-card-row { display: grid; grid-template-columns: 64px 130px minmax(120px, 1fr) minmax(150px, 1.2fr) minmax(220px, 2fr); align-items: start; gap: 8px; }
.welcome-card-row-title { padding-top: 8px; color: var(--n-text-color-2); font-size: 12px; font-weight: 600; }
.review-summary { margin-top: 18px; }
.publish-option { margin-top: 18px; font-weight: 600; }
.wizard-footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.drawer-header { display: flex; width: 100%; align-items: center; justify-content: space-between; gap: 16px; }
.history-alert { margin-bottom: 12px; }
.prompt-result-panel { min-width: 0; padding: 12px; border: 1px solid var(--n-border-color); border-radius: 6px; }
.prompt-result-label { margin-bottom: 8px; font-size: 13px; font-weight: 600; }
.prompt-result-panel pre { max-height: 360px; margin: 0; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
.prompt-loading { min-height: 120px; display: flex; align-items: center; justify-content: center; gap: 8px; color: var(--n-text-color-3); }
.trace-summary { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; color: var(--n-text-color-3); }
.trace-step-descriptions { margin-bottom: 12px; }
.trace-step-collapse { margin-top: 12px; }
.welcome-snapshot-requirement { margin-bottom: 12px; padding: 12px; border: 1px solid var(--n-border-color); border-radius: 6px; background: var(--n-color-modal); }
.welcome-snapshot-requirement p { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; color: var(--n-text-color-2); }
.welcome-snapshot-cards { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.welcome-snapshot-card { display: flex; min-width: 0; flex-direction: column; gap: 6px; padding: 12px; border: 1px solid var(--n-border-color); border-radius: 6px; background: var(--n-color-modal); }
.welcome-snapshot-card-head { display: flex; align-items: center; justify-content: space-between; color: var(--n-text-color-3); }
.welcome-snapshot-card strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.welcome-snapshot-card span, .welcome-snapshot-card p { margin: 0; color: var(--n-text-color-3); font-size: 12px; }
.welcome-snapshot-card p { max-height: 100px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
:deep(.primary-cell) { display: flex; min-width: 0; flex-direction: column; gap: 4px; }
:deep(.primary-cell span) { overflow: hidden; color: var(--n-text-color-3); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 700px) {
  .page-header, .toolbar, .model-row, .provider-row, .section-heading.with-action, .wizard-footer { align-items: stretch; flex-direction: column; }
  .form-grid { grid-template-columns: 1fr; }
  .welcome-card-row { grid-template-columns: 1fr; }
  .welcome-snapshot-cards { grid-template-columns: 1fr; }
  .welcome-card-row-title { padding-top: 0; }
  .full-field { grid-column: auto; }
  .wizard-steps { overflow-x: auto; }
  .step-content { min-height: 0; }
}
</style>
