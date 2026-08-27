<script setup lang="ts">
import { computed, h, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import dayjs from 'dayjs';
import type { DataTableColumns, FormInst, FormRules, SelectOption } from 'naive-ui';
import { NButton, NSpace, NTag, useDialog } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { useAuthStore } from '@/store/modules/auth';
import { AgentEmbedWidget } from '@/embed/widget-sdk';
import { issueEmbedBrowserCredential } from '@/service/api/embed-widget';
import {
  buildEmbedApplicationConfig,
  emptyEmbedApplicationConfig,
  readEmbedApplicationConfig
} from './embed-application';
import ApiPlayground from './api-playground.vue';
import {
  createApiApplication,
  createServiceAccountGrant,
  createServiceAccount,
  fetchApiApplications,
  fetchApiCredentials,
  fetchAllowedAgents,
  fetchLegacyExecutionArchives,
  fetchRuntimeStatus,
  fetchServiceAccounts,
  fetchServiceAccountGrants,
  fetchSystemUsers,
  issueApiCredential,
  revokeApiCredential,
  revokeServiceAccountGrant,
  updateApiApplication,
  updateApiApplicationStatus,
  updateServiceAccountStatus
} from '@/service/api';
import type {
  ApiApplicationView,
  ApiCredentialView,
  AgentOptionView,
  EmbedSessionView,
  EmbedStreamEvent,
  LegacyExecutionArchiveView,
  RuntimeStatusView,
  ServiceAccountView,
  ServiceAccountGrantView,
  SystemUserView
} from '@/service/api';

const authStore = useAuthStore();
const dialog = useDialog();
const route = useRoute();
const router = useRouter();
const activeTab = ref(typeof route.query.tab === 'string' ? route.query.tab : 'applications');
const loading = ref(false);
const applications = ref<ApiApplicationView[]>([]);
const serviceAccounts = ref<ServiceAccountView[]>([]);
const users = ref<SystemUserView[]>([]);

const appVisible = ref(false);
const appSubmitting = ref(false);
const appFormRef = ref<FormInst | null>(null);
const editingApplication = ref<ApiApplicationView | null>(null);
const appForm = reactive({
  appKey: '', name: '', appType: 'open_api' as ApiApplicationView['appType'],
  ownerId: authStore.userInfo.userId, callbackUrl: '', scopes: [] as string[],
  embed: emptyEmbedApplicationConfig()
});
const appRules: FormRules = {
  appKey: [
    { required: true, message: '请输入应用标识', trigger: ['input', 'blur'] },
    { pattern: /^[A-Za-z0-9._:-]+$/, message: '应用标识包含非法字符', trigger: ['input', 'blur'] }
  ],
  name: [{ required: true, message: '请输入应用名称', trigger: ['input', 'blur'] }],
  ownerId: [{ required: true, message: '请选择负责人', trigger: ['change', 'blur'] }]
};

const accountVisible = ref(false);
const accountSubmitting = ref(false);
const accountFormRef = ref<FormInst | null>(null);
const accountForm = reactive({
  accountKey: '', name: '', description: '', ownerId: authStore.userInfo.userId,
  purpose: 'automation', environment: 'production', contact: ''
});
const accountRules: FormRules = {
  accountKey: [
    { required: true, message: '请输入账号标识', trigger: ['input', 'blur'] },
    { pattern: /^[A-Za-z0-9._:-]+$/, message: '账号标识包含非法字符', trigger: ['input', 'blur'] }
  ],
  name: [{ required: true, message: '请输入账号名称', trigger: ['input', 'blur'] }],
  ownerId: [{ required: true, message: '请选择负责人', trigger: ['change', 'blur'] }]
};

const grantVisible = ref(false);
const grantLoading = ref(false);
const activeServiceAccount = ref<ServiceAccountView | null>(null);
const grants = ref<ServiceAccountGrantView[]>([]);
const grantFormVisible = ref(false);
const grantSubmitting = ref(false);
const grantForm = reactive({
  resourceType: 'task', resourceId: '', resourceKey: '*', action: 'read',
  effect: 'allow' as 'allow' | 'deny', reason: ''
});

const credentialVisible = ref(false);
const credentialLoading = ref(false);
const activeApplication = ref<ApiApplicationView | null>(null);
const credentials = ref<ApiCredentialView[]>([]);
const issueVisible = ref(false);
const issueSubmitting = ref(false);
const issueForm = reactive({ serviceAccountId: null as string | null, scopes: [] as string[] });
const issuedSecretVisible = ref(false);
const issuedSecret = ref('');
const issuedPrefix = ref('');

const embedApiKey = ref('');
const embedAgents = ref<AgentOptionView[]>([]);
const embedAgentVersionId = ref<string | null>(null);
const embedExternalUserKey = ref('');
const embedExpiresInMinutes = ref<number | null>(60);
const embedSession = ref<EmbedSessionView | null>(null);
const embedWidgetContainer = ref<HTMLElement | null>(null);
const embedInput = ref('');
const embedEvents = ref<EmbedStreamEvent[]>([]);
const embedCreating = ref(false);
const embedStreaming = ref(false);
const embedLoading = ref(false);
const embedRuntime = ref<RuntimeStatusView | null>(null);
let embedWidget: AgentEmbedWidget | null = null;
const embedIntegrationExample = computed(() => `<script src="${window.location.origin}/embed-widget.js"><\/script>
<div id="agent-assistant"></div>
<script>
const { credential } = await fetch('/your-server/embed-token').then(r => r.json());
const widget = AgentEmbed.create({
  container: document.getElementById('agent-assistant'),
  embedUrl: '${window.location.origin}/embed/chat',
  credential,
  protocolMode: 'nhs-v1',
  context: { page: location.pathname },
  theme: 'auto'
});
  widget.on('NHS_WIDGET_READY', payload => console.debug('widget ready', payload));
  widget.on('ERROR', error => console.error(error));
<\/script>`);
const embedServerExample = `app.post('/your-server/embed-token', async (req, res) => {
  const response = await fetch(process.env.NHS_SERVER_URL + '/open/v1/embed/browser-credentials', {
    method: 'POST',
    headers: {
      Authorization: 'Bearer ' + process.env.NHS_EMBED_API_KEY,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      origin: 'https://portal.example.com',
      agentVersionId: process.env.NHS_VERSION_ID,
      externalUserKey: req.user.id,
      sessionMinutes: 30
    })
  });
  res.status(response.status).json((await response.json()).data);
});`;

const archiveLoading = ref(false);
const archives = ref<LegacyExecutionArchiveView[]>([]);
const archiveFilters = reactive({ traceId: '', executionId: '', sourceStatus: '' });

const scopeValues = [
  'agents:use', 'chat:invoke', 'tasks:create', 'tasks:read', 'tasks:run', 'events:read',
  'artifacts:read', 'acceptance:write', 'webhooks:invoke', 'cron:execute', 'mcp:invoke'
];
const scopeOptions: SelectOption[] = scopeValues.map(value => ({ label: value, value }));
const appTypeOptions: SelectOption[] = [
  { label: 'Open API', value: 'open_api' }, { label: 'Embed Chat', value: 'embed' },
  { label: 'Webhook', value: 'webhook' }, { label: '内部集成', value: 'internal' }
];
const environmentOptions: SelectOption[] = [
  { label: '生产环境', value: 'production' }, { label: '测试环境', value: 'staging' },
  { label: '开发环境', value: 'development' }
];
const purposeOptions: SelectOption[] = [
  { label: '自动化执行', value: 'automation' }, { label: '开放 API 调用', value: 'open_api' },
  { label: 'Webhook 集成', value: 'webhook' }, { label: '嵌入式对话', value: 'embed' }
];
const userOptions = computed<SelectOption[]>(() => users.value.filter(item => item.status === '0').map(item => ({
  label: `${item.nickName || item.userName} (@${item.userName})`, value: item.userId
})));
const embedAgentOptions = computed<SelectOption[]>(() => embedAgents.value
  .filter(item => item.status === 'active' && item.publishedVersionId)
  .map(item => ({ label: `${item.name} · ${item.agentKey}`, value: item.publishedVersionId as string })));
const accountOptions = computed<SelectOption[]>(() =>
  serviceAccounts.value.filter(item => item.status === 'active').map(item => ({ label: `${item.name} · ${item.accountKey}`, value: item.id }))
);
const issueScopeOptions = computed<SelectOption[]>(() =>
  (activeApplication.value?.scopes || []).map(value => ({ label: value, value }))
);
const docsUrl = computed(() => `${window.location.origin}/doc.html`);
const apiDocGroups = [
  {
    title: '任务与运行', scope: 'tasks:create / tasks:run / tasks:read',
    endpoints: [
      ['POST', '/open/v1/tasks', '创建固定版本任务'],
      ['POST', '/open/v1/tasks/{taskId}/runs', '启动任务运行'],
      ['GET', '/open/v1/tasks/{taskId}/runs/{runId}', '查询运行状态'],
      ['GET', '/open/v1/tasks/{taskId}/runs/{runId}/events', '增量读取运行事件'],
      ['GET', '/open/v1/tasks/{taskId}/artifacts', '获取任务制品'],
      ['POST', '/open/v1/tasks/{taskId}/runs/{runId}/acceptance', '提交验收结果']
    ]
  },
  {
    title: '嵌入式对话', scope: 'chat:invoke',
    endpoints: [
      ['POST', '/open/v1/embed/browser-credentials', '服务端换取五分钟启动凭证'],
      ['POST', '/open/v1/embed/widget/sessions', 'iframe 消费启动凭证并创建会话'],
      ['POST', '/open/v1/embed/widget/sessions/{sessionId}/messages', '发送消息并接收可恢复 SSE'],
      ['GET', '/open/v1/embed/widget/sessions/{sessionId}/turns/{turnId}/events', '断线后从游标恢复事件'],
      ['POST', '/open/v1/embed/widget/sessions/{sessionId}/turns/{turnId}/stop', '持久停止回合']
    ]
  },
  {
    title: 'Webhook 自动化', scope: 'webhooks:invoke',
    endpoints: [['POST', '/open/platform/webhooks/{triggerKey}', '触发已发布的 Webhook 自动化']]
  }
] as const;

function ownerName(ownerId: string) {
  const user = users.value.find(item => item.userId === ownerId);
  return user ? `${user.nickName || user.userName} (@${user.userName})` : '未知成员';
}

function serviceAccountName(accountId: string) {
  const account = serviceAccounts.value.find(item => item.id === accountId);
  return account ? `${account.name} (${account.accountKey})` : '服务账号不可见';
}

function statusType(status: string) {
  if (status === 'active') return 'success';
  if (status === 'revoked') return 'error';
  return 'default';
}

function statusText(status: string) {
  return ({ active: '启用', disabled: '停用', revoked: '已撤销', expired: '已过期' } as Record<string, string>)[status] || status;
}

async function loadData() {
  loading.value = true;
  const [appResult, accountResult, userResult] = await Promise.all([
    fetchApiApplications(), fetchServiceAccounts(), fetchSystemUsers(undefined, 1, 200)
  ]);
  if (!appResult.error) applications.value = appResult.data;
  if (!accountResult.error) serviceAccounts.value = accountResult.data;
  if (!userResult.error) users.value = userResult.data.rows;
  loading.value = false;
}

async function loadEmbedAgents() {
  embedLoading.value = true;
  const [agentResult, runtimeResult] = await Promise.all([fetchAllowedAgents(), fetchRuntimeStatus()]);
  if (!agentResult.error) {
    embedAgents.value = agentResult.data;
    if (!embedAgentVersionId.value) embedAgentVersionId.value = embedAgentOptions.value[0]?.value as string | undefined || null;
  }
  if (!runtimeResult.error) embedRuntime.value = runtimeResult.data;
  embedLoading.value = false;
}

async function createEmbedDebugSession() {
  if (!embedApiKey.value.trim() || !embedAgentVersionId.value || !embedExternalUserKey.value.trim()) {
    window.$message?.warning('API Key、Agent 发布版本和外部用户 Key 不能为空');
    return;
  }
  embedCreating.value = true;
  try {
    const launch = await issueEmbedBrowserCredential(embedApiKey.value, {
      origin: window.location.origin,
      agentVersionId: embedAgentVersionId.value,
      externalUserKey: embedExternalUserKey.value.trim(),
      sessionMinutes: embedExpiresInMinutes.value || 60
    });
    embedApiKey.value = '';
    if (!embedWidgetContainer.value) throw new Error('Widget 调试容器尚未就绪');
    embedWidget?.destroy();
    embedWidget = new AgentEmbedWidget({
      container: embedWidgetContainer.value,
      embedUrl: `${window.location.origin}/embed/chat`,
      credential: launch.credential,
      theme: 'auto',
      context: { debugger: 'niuhuoshan-admin', locale: navigator.language }
    });
    embedEvents.value = [];
    embedWidget.on('*', (payload, envelope) => {
      embedEvents.value.push({
        event: envelope.type,
        id: envelope.correlationId,
        data: payload && typeof payload === 'object'
          ? payload as Record<string, unknown>
          : { value: payload }
      });
      if (envelope.type === 'INITIALIZED' || envelope.type === 'INIT_SUCCESS') {
        const state = payload as { session?: EmbedSessionView };
        embedSession.value = state.session || null;
      }
      if (envelope.type === 'MESSAGE_START' || envelope.type === 'CONNECTION_STATUS') embedStreaming.value = true;
      if (envelope.type === 'MESSAGE_COMPLETE' || envelope.type === 'CONVERSATION_CHANGED'
        || envelope.type === 'GENERATION_STOPPED' || envelope.type === 'INIT_FAILURE'
        || envelope.type === 'ERROR') embedStreaming.value = false;
    });
    window.$message?.success('Widget 调试器已连接');
  } catch (error) {
    window.$message?.error(error instanceof Error ? error.message : 'Embed 会话创建失败，请检查 API Key 和 Scope');
  } finally {
    embedCreating.value = false;
  }
}

async function sendEmbedDebugMessage() {
  if (!embedWidget || !embedInput.value.trim() || embedStreaming.value) return;
  embedStreaming.value = true;
  const input = embedInput.value.trim();
  embedInput.value = '';
  try {
    await embedWidget.sendMessage(input);
  } catch (error) {
    window.$message?.error(error instanceof Error ? error.message : 'Embed 消息发送失败');
  } finally {
    embedStreaming.value = false;
  }
}

async function stopEmbedDebugMessage() {
  if (!embedWidget) return;
  try {
    await embedWidget.stop();
  } catch (error) {
    window.$message?.error(error instanceof Error ? error.message : 'Embed 停止失败');
  }
}

function formatEmbedEvent(event: EmbedStreamEvent) {
  return JSON.stringify(event.data, null, 2);
}

function openWidgetDebugger() {
  void router.push({ path: '/client/debug', query: { agentVersionId: embedAgentVersionId.value || undefined } });
}

function formatArchiveTime(value: string | null) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-';
}

async function loadArchives() {
  archiveLoading.value = true;
  const { data, error } = await fetchLegacyExecutionArchives({
    traceId: archiveFilters.traceId.trim() || undefined,
    executionId: archiveFilters.executionId.trim() || undefined,
    sourceStatus: archiveFilters.sourceStatus.trim() || undefined,
    limit: 50
  });
  if (!error) archives.value = data;
  archiveLoading.value = false;
}

function openApp(application?: ApiApplicationView) {
  editingApplication.value = application || null;
  Object.assign(appForm, {
    appKey: application?.appKey || '', name: application?.name || '',
    appType: application?.appType || 'open_api',
    ownerId: application?.ownerId || authStore.userInfo.userId,
    callbackUrl: application?.callbackUrl || '', scopes: [...(application?.scopes || [])],
    embed: application?.appType === 'embed'
      ? readEmbedApplicationConfig(application.config)
      : emptyEmbedApplicationConfig()
  });
  appVisible.value = true;
}

async function submitApp() {
  await appFormRef.value?.validate();
  let config: Record<string, unknown> | undefined;
  if (appForm.appType === 'embed') {
    try {
      config = buildEmbedApplicationConfig(appForm.embed);
    } catch (error) {
      window.$message?.warning(error instanceof Error ? error.message : 'Embed 应用配置无效');
      return;
    }
  }
  appSubmitting.value = true;
  const payload = {
    name: appForm.name.trim(), appType: appForm.appType,
    ownerId: appForm.ownerId, callbackUrl: appForm.callbackUrl.trim() || undefined,
    scopes: appForm.scopes, config
  };
  const { error } = editingApplication.value
    ? await updateApiApplication(editingApplication.value.id, payload)
    : await createApiApplication({ ...payload, appKey: appForm.appKey.trim() });
  if (!error) {
    appVisible.value = false;
    window.$message?.success(editingApplication.value ? 'API 应用已更新' : 'API 应用已创建');
    await loadData();
  }
  appSubmitting.value = false;
}

async function setApplicationStatus(row: ApiApplicationView, status: 'active' | 'disabled' | 'revoked') {
  const { error } = await updateApiApplicationStatus(row.id, status);
  if (!error) { window.$message?.success('应用状态已更新'); await loadData(); }
}

function openAccount() {
  Object.assign(accountForm, {
    accountKey: '', name: '', description: '', ownerId: authStore.userInfo.userId,
    purpose: 'automation', environment: 'production', contact: ''
  });
  accountVisible.value = true;
}

async function submitAccount() {
  await accountFormRef.value?.validate();
  accountSubmitting.value = true;
  const { error } = await createServiceAccount({
    accountKey: accountForm.accountKey.trim(), name: accountForm.name.trim(),
    description: accountForm.description.trim() || undefined, ownerId: accountForm.ownerId,
    metadata: {
      purpose: accountForm.purpose,
      environment: accountForm.environment,
      contact: accountForm.contact.trim() || undefined
    }
  });
  if (!error) { accountVisible.value = false; window.$message?.success('服务账号已创建，默认不继承任何人类权限'); await loadData(); }
  accountSubmitting.value = false;
}

async function setAccountStatus(row: ServiceAccountView, status: 'active' | 'disabled' | 'revoked') {
  const { error } = await updateServiceAccountStatus(row.id, status);
  if (!error) { window.$message?.success('服务账号状态已更新'); await loadData(); }
}

async function openGrants(row: ServiceAccountView) {
  activeServiceAccount.value = row;
  grantVisible.value = true;
  await loadGrants();
}

async function loadGrants() {
  if (!activeServiceAccount.value) return;
  grantLoading.value = true;
  const { data, error } = await fetchServiceAccountGrants(activeServiceAccount.value.id);
  if (!error) grants.value = data;
  grantLoading.value = false;
}

function openGrantForm() {
  Object.assign(grantForm, { resourceType: 'task', resourceId: '', resourceKey: '*', action: 'read', effect: 'allow', reason: '' });
  grantFormVisible.value = true;
}

async function submitGrant() {
  if (!activeServiceAccount.value || !grantForm.resourceType.trim() || !grantForm.action.trim() || !grantForm.reason.trim()) {
    window.$message?.warning('资源类型、动作和授权原因不能为空');
    return;
  }
  grantSubmitting.value = true;
  const { error } = await createServiceAccountGrant(activeServiceAccount.value.id, {
    resourceType: grantForm.resourceType.trim(), resourceId: grantForm.resourceId.trim() || undefined,
    resourceKey: grantForm.resourceKey.trim() || undefined, action: grantForm.action.trim(),
    effect: grantForm.effect, reason: grantForm.reason.trim()
  });
  if (!error) { grantFormVisible.value = false; window.$message?.success('机器身份授权已创建'); await loadGrants(); }
  grantSubmitting.value = false;
}

async function revokeGrant(row: ServiceAccountGrantView) {
  if (!activeServiceAccount.value) return;
  const { error } = await revokeServiceAccountGrant(activeServiceAccount.value.id, row.id);
  if (!error) { window.$message?.success('机器身份授权已撤销'); await loadGrants(); }
}

async function openCredentials(row: ApiApplicationView) {
  activeApplication.value = row;
  credentialVisible.value = true;
  await loadCredentials();
}

async function loadCredentials() {
  if (!activeApplication.value) return;
  credentialLoading.value = true;
  const { data, error } = await fetchApiCredentials(activeApplication.value.id);
  if (!error) credentials.value = data;
  credentialLoading.value = false;
}

function openIssue() {
  issueForm.serviceAccountId = null;
  issueForm.scopes = [];
  issueVisible.value = true;
}

async function submitIssue() {
  if (!activeApplication.value || !issueForm.serviceAccountId || !issueForm.scopes.length) {
    window.$message?.warning('请选择服务账号和至少一个 scope');
    return;
  }
  issueSubmitting.value = true;
  const { data, error } = await issueApiCredential(activeApplication.value.id, {
    serviceAccountId: issueForm.serviceAccountId, scopes: issueForm.scopes
  });
  if (!error) {
    issueVisible.value = false;
    issuedSecret.value = data.secret;
    issuedPrefix.value = data.credential.keyPrefix;
    issuedSecretVisible.value = true;
    await loadCredentials();
  }
  issueSubmitting.value = false;
}

function closeIssuedSecret() {
  issuedSecretVisible.value = false;
  issuedSecret.value = '';
  issuedPrefix.value = '';
}

async function copySecret() {
  try {
    await navigator.clipboard.writeText(issuedSecret.value);
    window.$message?.success('密钥已复制');
  } catch {
    window.$message?.error('浏览器拒绝访问剪贴板');
  }
}

function confirmRevokeCredential(row: ApiCredentialView) {
  if (!activeApplication.value) return;
  const appId = activeApplication.value.id;
  dialog.warning({
    title: '撤销 API 凭证', content: `确认撤销 ${row.keyPrefix}…？此操作不可恢复。`, positiveText: '撤销', negativeText: '取消',
    onPositiveClick: async () => {
      const { error } = await revokeApiCredential(appId, row.id);
      if (!error) { window.$message?.success('凭证已撤销'); await loadCredentials(); }
    }
  });
}

function openDocs() {
  window.open(docsUrl.value, '_blank', 'noopener,noreferrer');
}

const appColumns: DataTableColumns<ApiApplicationView> = [
  { title: '应用', key: 'name', minWidth: 230, render: row => h('div', { class: 'primary-cell' }, [h('strong', row.name), h('span', `${row.appKey} · ${row.appType}`)]) },
  { title: '负责人', key: 'ownerId', minWidth: 190, render: row => ownerName(row.ownerId) },
  { title: 'Scope 上限', key: 'scopes', minWidth: 260, render: row => row.scopes.join(', ') || '-' },
  {
    title: '嵌入范围', key: 'config', minWidth: 150,
    render: row => row.appType === 'embed'
      ? `${Array.isArray(row.config.allowedOrigins) ? row.config.allowedOrigins.length : 0} Origin · ${Array.isArray(row.config.agentVersionIds) ? row.config.agentVersionIds.length : 0} Agent`
      : '-'
  },
  { title: '状态', key: 'status', width: 95, render: row => h(NTag, { size: 'small', type: statusType(row.status) }, () => statusText(row.status)) },
  { title: '创建时间', key: 'createdAt', width: 165, render: row => dayjs(row.createdAt).format('YYYY-MM-DD HH:mm') },
  { title: '操作', key: 'actions', width: 300, fixed: 'right', render: row => h(NSpace, { size: 6 }, () => [
    h(NButton, { size: 'small', secondary: true, disabled: row.status === 'revoked', onClick: () => openApp(row) }, () => '编辑'),
    h(NButton, { size: 'small', type: 'primary', secondary: true, onClick: () => openCredentials(row) }, () => '凭证'),
    row.status === 'active'
      ? h(NButton, { size: 'small', quaternary: true, onClick: () => setApplicationStatus(row, 'disabled') }, () => '停用')
      : h(NButton, { size: 'small', quaternary: true, disabled: row.status === 'revoked', onClick: () => setApplicationStatus(row, 'active') }, () => '启用'),
    h(NButton, { size: 'small', type: 'error', quaternary: true, disabled: row.status === 'revoked', onClick: () => setApplicationStatus(row, 'revoked') }, () => '撤销')
  ]) }
];

const accountColumns: DataTableColumns<ServiceAccountView> = [
  { title: '服务账号', key: 'name', minWidth: 250, render: row => h('div', { class: 'primary-cell' }, [h('strong', row.name), h('span', `${row.accountKey} · ${row.description || '暂无描述'}`)]) },
  { title: '负责人', key: 'ownerId', minWidth: 190, render: row => ownerName(row.ownerId) },
  { title: '最后使用', key: 'lastUsedAt', width: 165, render: row => row.lastUsedAt ? dayjs(row.lastUsedAt).format('YYYY-MM-DD HH:mm') : '从未使用' },
  { title: '状态', key: 'status', width: 95, render: row => h(NTag, { size: 'small', type: statusType(row.status) }, () => statusText(row.status)) },
  { title: '创建时间', key: 'createdAt', width: 165, render: row => dayjs(row.createdAt).format('YYYY-MM-DD HH:mm') },
  { title: '操作', key: 'actions', width: 170, render: row => h(NSpace, { size: 6 }, () => [
    h(NButton, { size: 'small', type: 'primary', secondary: true, onClick: () => openGrants(row) }, () => '授权'),
    row.status === 'active'
      ? h(NButton, { size: 'small', quaternary: true, onClick: () => setAccountStatus(row, 'disabled') }, () => '停用')
      : h(NButton, { size: 'small', quaternary: true, disabled: row.status === 'revoked', onClick: () => setAccountStatus(row, 'active') }, () => '启用'),
    h(NButton, { size: 'small', type: 'error', quaternary: true, disabled: row.status === 'revoked', onClick: () => setAccountStatus(row, 'revoked') }, () => '撤销')
  ]) }
];

const grantColumns: DataTableColumns<ServiceAccountGrantView> = [
  { title: '资源', key: 'resourceType', minWidth: 190, render: row => `${row.resourceType}:${row.resourceId || row.resourceKey || '*'}` },
  { title: '动作', key: 'action', width: 120 },
  { title: '效果', key: 'effect', width: 100, render: row => h(NTag, { size: 'small', type: row.effect === 'allow' ? 'success' : 'error' }, () => row.effect) },
  { title: '原因', key: 'reason', minWidth: 220, ellipsis: { tooltip: true } },
  { title: '状态', key: 'revokedAt', width: 100, render: row => row.revokedAt ? '已撤销' : '生效中' },
  { title: '操作', key: 'actions', width: 90, render: row => h(NButton, { size: 'small', type: 'error', quaternary: true, disabled: Boolean(row.revokedAt), onClick: () => revokeGrant(row) }, () => '撤销') }
];

const credentialColumns: DataTableColumns<ApiCredentialView> = [
  { title: 'Key 前缀', key: 'keyPrefix', minWidth: 160, render: row => `${row.keyPrefix}…` },
  { title: '服务账号', key: 'serviceAccountId', minWidth: 210, render: row => serviceAccountName(row.serviceAccountId) },
  { title: 'Scopes', key: 'scopes', minWidth: 260, render: row => row.scopes.join(', ') },
  { title: '状态', key: 'status', width: 95, render: row => h(NTag, { size: 'small', type: statusType(row.status) }, () => statusText(row.status)) },
  { title: '创建时间', key: 'createdAt', width: 165, render: row => dayjs(row.createdAt).format('YYYY-MM-DD HH:mm') },
  { title: '操作', key: 'actions', width: 90, render: row => h(NButton, { size: 'small', type: 'error', quaternary: true, disabled: row.status !== 'active', onClick: () => confirmRevokeCredential(row) }, () => '撤销') }
];

const archiveColumns: DataTableColumns<LegacyExecutionArchiveView> = [
  { title: '源系统', key: 'sourceSystem', width: 120 },
  { title: 'Trace ID', key: 'sourceTraceId', minWidth: 180, ellipsis: { tooltip: true } },
  { title: 'Execution ID', key: 'sourceExecutionId', minWidth: 180, ellipsis: { tooltip: true } },
  { title: 'Agent / 用户', key: 'sourceAgentId', minWidth: 180, render: row => `${row.sourceAgentId || '-'} / ${row.sourceUserId || '-'}` },
  { title: '状态', key: 'sourceStatus', width: 110, render: row => row.sourceStatus || '-' },
  { title: '开始时间', key: 'startedAt', width: 170, render: row => formatArchiveTime(row.startedAt) },
  { title: '结束时间', key: 'finishedAt', width: 170, render: row => formatArchiveTime(row.finishedAt) },
  { title: '摘要', key: 'summary', minWidth: 240, ellipsis: { tooltip: true }, render: row => row.summary || '-' },
  { title: '内容 Hash', key: 'contentHash', minWidth: 180, ellipsis: { tooltip: true } }
];

watch(activeTab, value => {
  if (value === 'embed' && !embedAgents.value.length) void loadEmbedAgents();
  if (value === 'archives' && !archives.value.length) void loadArchives();
});
watch(() => route.query.tab, value => {
  if (typeof value === 'string' && value !== activeTab.value) activeTab.value = value;
});
watch(() => appForm.appType, value => {
  if (value === 'embed') {
    if (!appForm.scopes.includes('chat:invoke')) appForm.scopes.push('chat:invoke');
    if (!embedAgents.value.length) void loadEmbedAgents();
  }
});
watch(issuedSecretVisible, visible => { if (!visible) closeIssuedSecret(); });
onUnmounted(() => {
  issuedSecret.value = '';
  embedWidget?.destroy();
  embedWidget = null;
});
onMounted(loadData);
</script>

<template>
  <div class="page-container">
    <div class="page-header"><div><h2>{{ $t('page.openApi.title') }}</h2><p>{{ $t('page.openApi.desc') }}</p></div><NSpace><NButton secondary :loading="loading" @click="loadData"><template #icon><SvgIcon icon="lucide:refresh-cw" /></template>刷新</NButton><NButton secondary @click="openDocs"><template #icon><SvgIcon icon="lucide:book-open" /></template>API 文档</NButton></NSpace></div>
    <NTabs v-model:value="activeTab" type="line" animated>
      <NTabPane name="applications" tab="API 应用"><div class="tab-toolbar"><span></span><NButton type="primary" @click="openApp()"><template #icon><SvgIcon icon="lucide:plus" /></template>创建应用</NButton></div><NDataTable :columns="appColumns" :data="applications" :loading="loading" :scroll-x="1400" :row-key="row => row.id" /></NTabPane>
      <NTabPane name="accounts" tab="服务账号"><NAlert type="info" :bordered="false" class="identity-alert">服务账号是独立机器身份，默认无权限，不继承同 ID 人类用户的角色、权限包或私有数据。</NAlert><div class="tab-toolbar"><span></span><NButton type="primary" @click="openAccount"><template #icon><SvgIcon icon="lucide:plus" /></template>创建服务账号</NButton></div><NDataTable :columns="accountColumns" :data="serviceAccounts" :loading="loading" :scroll-x="1050" :row-key="row => row.id" /></NTabPane>
      <NTabPane name="playground" tab="API Playground"><ApiPlayground /></NTabPane>
      <NTabPane name="docs" tab="接口文档">
        <NAlert type="info" :bordered="false" class="identity-alert">所有开放接口使用一次性签发的 API Key 鉴权，并受应用 Scope 上限与服务账号显式授权共同约束。</NAlert>
        <section v-for="group in apiDocGroups" :key="group.title" class="api-doc-group">
          <div class="api-doc-heading"><div><h3>{{ group.title }}</h3><span>所需 Scope：{{ group.scope }}</span></div></div>
          <div class="endpoint-list">
            <div v-for="endpoint in group.endpoints" :key="endpoint[1]" class="endpoint-row">
              <NTag size="small" :type="endpoint[0] === 'GET' ? 'success' : 'info'">{{ endpoint[0] }}</NTag>
              <code>{{ endpoint[1] }}</code><span>{{ endpoint[2] }}</span>
            </div>
          </div>
        </section>
      </NTabPane>
      <NTabPane name="embed" tab="Embed 调试">
        <NAlert v-if="embedRuntime && !embedRuntime.available" type="warning" :bordered="false" class="identity-alert">
          {{ embedRuntime.message }}。当前可以创建会话，但发送消息会被运行时拒绝。
        </NAlert>
        <NAlert v-else type="info" :bordered="false" class="identity-alert">
          API Key 只用于换取五分钟启动凭证，换取成功后立即从表单清除；iframe 只接收限定 Origin、Agent 版本和会话的短期凭证。
        </NAlert>
        <div class="embed-grid">
          <section class="embed-panel">
            <div class="panel-heading"><div><h3>创建外部会话</h3><span>使用已发布的 Agent 版本验证 Embed API 鉴权和会话生命周期。</span></div><NButton size="small" secondary @click="openWidgetDebugger"><template #icon><SvgIcon icon="lucide:scan-search" /></template>打开协议调试器</NButton></div>
            <NForm label-placement="top">
              <NFormItem label="API Key"><NInput v-model:value="embedApiKey" type="password" show-password-on="click" placeholder="仅在本页使用" /></NFormItem>
              <NFormItem label="Agent 发布版本"><NSelect v-model:value="embedAgentVersionId" :options="embedAgentOptions" :loading="embedLoading" filterable clearable placeholder="选择有发布版本的 Agent" /></NFormItem>
              <NFormItem label="外部用户 Key"><NInput v-model:value="embedExternalUserKey" placeholder="customer-001" /></NFormItem>
              <NFormItem label="会话有效期（分钟）"><NInputNumber v-model:value="embedExpiresInMinutes" :min="1" :max="1440" class="full-width" /></NFormItem>
            </NForm>
            <NButton type="primary" :loading="embedCreating" :disabled="!embedAgentVersionId" @click="createEmbedDebugSession"><template #icon><SvgIcon icon="lucide:play" /></template>连接 Widget</NButton>
            <NDescriptions v-if="embedSession" bordered size="small" :column="1" class="embed-session">
              <NDescriptionsItem label="会话 ID">{{ embedSession.id }}</NDescriptionsItem>
              <NDescriptionsItem label="状态"><NTag size="small" type="success">{{ embedSession.status }}</NTag></NDescriptionsItem>
              <NDescriptionsItem label="过期时间">{{ formatArchiveTime(embedSession.expiresAt) }}</NDescriptionsItem>
            </NDescriptions>
          </section>
          <section class="embed-panel">
            <div class="panel-heading"><div><h3>Widget 预览与协议事件</h3><span>真实 iframe、短期凭证和 postMessage 1.0 协议。</span></div></div>
            <div ref="embedWidgetContainer" class="embed-widget-container"></div>
            <NInput v-model:value="embedInput" type="textarea" :autosize="{ minRows: 4, maxRows: 8 }" :disabled="!embedSession || embedStreaming" placeholder="输入一条消息" />
            <NSpace class="embed-actions">
              <NButton type="primary" :loading="embedStreaming" :disabled="!embedSession || !embedInput.trim()" @click="sendEmbedDebugMessage"><template #icon><SvgIcon icon="lucide:send" /></template>发送</NButton>
              <NButton v-if="embedStreaming" secondary @click="stopEmbedDebugMessage"><template #icon><SvgIcon icon="lucide:square" /></template>停止</NButton>
              <NButton v-if="embedEvents.length" quaternary @click="embedEvents = []">清空事件</NButton>
            </NSpace>
            <div class="embed-event-list">
              <NEmpty v-if="!embedEvents.length" description="暂无事件" />
              <div v-for="(event, index) in embedEvents" :key="`${event.id || 'event'}-${index}`" class="embed-event">
                <div class="embed-event-heading"><NTag size="small" :type="event.event === 'meta' ? 'info' : 'success'">{{ event.event }}</NTag><span>{{ event.id || '无游标' }}</span></div>
                <NCode :code="formatEmbedEvent(event)" language="json" word-wrap />
              </div>
            </div>
          </section>
          <section class="embed-panel embed-integration-example">
            <div class="panel-heading"><div><h3>宿主页面接入</h3><span>启动凭证必须由宿主服务端签发接口代理获取。</span></div></div>
            <div class="integration-code-grid">
              <NCode :code="embedServerExample" language="javascript" word-wrap />
              <NCode :code="embedIntegrationExample" language="html" word-wrap />
            </div>
          </section>
        </div>
      </NTabPane>
      <NTabPane name="archives" tab="迁移归档">
        <NAlert type="info" :bordered="false" class="identity-alert">仅展示历史迁移执行的只读元数据，不暴露原始输入、输出或凭证内容。</NAlert>
        <NSpace class="archive-filters" align="center">
          <NInput v-model:value="archiveFilters.traceId" clearable placeholder="源 Trace ID" @keyup.enter="loadArchives" />
          <NInput v-model:value="archiveFilters.executionId" clearable placeholder="源 Execution ID" @keyup.enter="loadArchives" />
          <NInput v-model:value="archiveFilters.sourceStatus" clearable placeholder="源状态" @keyup.enter="loadArchives" />
          <NButton type="primary" :loading="archiveLoading" @click="loadArchives"><template #icon><SvgIcon icon="lucide:search" /></template>查询</NButton>
        </NSpace>
        <NDataTable :columns="archiveColumns" :data="archives" :loading="archiveLoading" :scroll-x="1500" :row-key="row => row.id" />
      </NTabPane>
    </NTabs>

    <NModal v-model:show="appVisible" preset="card" :title="editingApplication ? '编辑 API 应用' : '创建 API 应用'" class="form-modal" style="width: min(820px, calc(100vw - 32px))">
      <NForm ref="appFormRef" :model="appForm" :rules="appRules" label-placement="top">
        <NGrid :cols="2" :x-gap="16">
          <NFormItemGi label="应用标识" path="appKey"><NInput v-model:value="appForm.appKey" :disabled="Boolean(editingApplication)" placeholder="erp.integration" /></NFormItemGi>
          <NFormItemGi label="名称" path="name"><NInput v-model:value="appForm.name" /></NFormItemGi>
          <NFormItemGi label="类型"><NSelect v-model:value="appForm.appType" :options="appTypeOptions" :disabled="Boolean(editingApplication)" /></NFormItemGi>
          <NFormItemGi label="负责人" path="ownerId"><NSelect v-model:value="appForm.ownerId" :options="userOptions" filterable placeholder="按姓名或账号搜索" /></NFormItemGi>
          <NFormItemGi span="2" label="Scope 上限"><NSelect v-model:value="appForm.scopes" :options="scopeOptions" multiple filterable /></NFormItemGi>
          <NFormItemGi span="2" label="回调地址"><NInput v-model:value="appForm.callbackUrl" placeholder="https://...（可选）" /></NFormItemGi>
        </NGrid>
        <section v-if="appForm.appType === 'embed'" class="embed-policy-form">
          <div class="embed-policy-heading">
            <strong>浏览器嵌入策略</strong>
            <NTag size="small" type="warning">服务端强制校验</NTag>
          </div>
          <NGrid :cols="2" :x-gap="16">
            <NFormItemGi span="2" label="允许的宿主 Origin（一行一个）">
              <NInput v-model:value="appForm.embed.allowedOrigins" type="textarea" :autosize="{ minRows: 3, maxRows: 6 }" placeholder="https://portal.example.com" />
            </NFormItemGi>
            <NFormItemGi span="2" label="允许嵌入的 Agent 发布版本">
              <NSelect v-model:value="appForm.embed.agentVersionIds" :options="embedAgentOptions" :loading="embedLoading" multiple filterable placeholder="选择可嵌入的 Agent" />
            </NFormItemGi>
            <NFormItemGi label="显示名称"><NInput v-model:value="appForm.embed.displayName" placeholder="例如：财务助手" maxlength="128" /></NFormItemGi>
            <NFormItemGi label="品牌色"><NInput v-model:value="appForm.embed.primaryColor" placeholder="#18a058" /></NFormItemGi>
            <NFormItemGi label="会话有效期上限（分钟）"><NInputNumber v-model:value="appForm.embed.maxSessionMinutes" :min="5" :max="1440" class="full-width" /></NFormItemGi>
            <NFormItemGi label="显示平台水印"><NSwitch v-model:value="appForm.embed.watermark" /></NFormItemGi>
          </NGrid>
        </section>
      </NForm>
      <template #footer><NSpace justify="end"><NButton @click="appVisible = false">取消</NButton><NButton type="primary" :loading="appSubmitting" @click="submitApp">{{ editingApplication ? '保存' : '创建' }}</NButton></NSpace></template>
    </NModal>

    <NModal v-model:show="accountVisible" preset="card" title="创建服务账号" class="form-modal" style="width: min(760px, calc(100vw - 32px))"><NForm ref="accountFormRef" :model="accountForm" :rules="accountRules" label-placement="top"><NGrid :cols="2" :x-gap="16"><NFormItemGi label="账号标识" path="accountKey"><NInput v-model:value="accountForm.accountKey" placeholder="automation.runner" /></NFormItemGi><NFormItemGi label="名称" path="name"><NInput v-model:value="accountForm.name" /></NFormItemGi><NFormItemGi span="2" label="描述"><NInput v-model:value="accountForm.description" type="textarea" /></NFormItemGi><NFormItemGi label="负责人" path="ownerId"><NSelect v-model:value="accountForm.ownerId" :options="userOptions" filterable placeholder="按姓名或账号搜索" /></NFormItemGi><NFormItemGi label="使用场景"><NSelect v-model:value="accountForm.purpose" :options="purposeOptions" /></NFormItemGi><NFormItemGi label="运行环境"><NSelect v-model:value="accountForm.environment" :options="environmentOptions" /></NFormItemGi><NFormItemGi label="运维联系人"><NInput v-model:value="accountForm.contact" placeholder="邮箱、群组或值班账号" /></NFormItemGi></NGrid></NForm><template #footer><NSpace justify="end"><NButton @click="accountVisible = false">取消</NButton><NButton type="primary" :loading="accountSubmitting" @click="submitAccount">创建</NButton></NSpace></template></NModal>

    <NDrawer v-model:show="credentialVisible" width="min(900px, calc(100vw - 16px))" placement="right"><NDrawerContent closable><template #header><div class="drawer-header"><span>{{ activeApplication?.name || '' }} · API 凭证</span><NButton size="small" type="primary" :disabled="activeApplication?.status !== 'active'" @click="openIssue"><template #icon><SvgIcon icon="lucide:key-round" /></template>签发凭证</NButton></div></template><NAlert type="warning" :bordered="false" class="identity-alert">密钥只在签发成功时显示一次；列表只保留不可逆元数据。</NAlert><NDataTable :columns="credentialColumns" :data="credentials" :loading="credentialLoading" :scroll-x="980" :row-key="row => row.id" /></NDrawerContent></NDrawer>

    <NDrawer v-model:show="grantVisible" width="min(900px, calc(100vw - 16px))" placement="right"><NDrawerContent closable><template #header><div class="drawer-header"><span>{{ activeServiceAccount?.name || '' }} · 显式授权</span><NButton size="small" type="primary" :disabled="activeServiceAccount?.status !== 'active'" @click="openGrantForm"><template #icon><SvgIcon icon="lucide:plus" /></template>新增授权</NButton></div></template><NAlert type="info" :bordered="false" class="identity-alert">机器身份只使用这里的显式授权；无规则默认拒绝，deny 覆盖 allow。</NAlert><NDataTable :columns="grantColumns" :data="grants" :loading="grantLoading" :scroll-x="900" :row-key="row => row.id" /></NDrawerContent></NDrawer>

    <NModal v-model:show="grantFormVisible" preset="card" title="新增机器身份授权" class="small-modal" style="width: min(560px, calc(100vw - 32px))"><NForm label-placement="top"><NGrid :cols="2" :x-gap="16"><NFormItemGi label="资源类型"><NInput v-model:value="grantForm.resourceType" /></NFormItemGi><NFormItemGi label="资源 ID（可选）"><NInput v-model:value="grantForm.resourceId" /></NFormItemGi><NFormItemGi label="资源 Key"><NInput v-model:value="grantForm.resourceKey" /></NFormItemGi><NFormItemGi label="动作"><NInput v-model:value="grantForm.action" /></NFormItemGi><NFormItemGi label="效果"><NRadioGroup v-model:value="grantForm.effect"><NRadioButton value="allow">允许</NRadioButton><NRadioButton value="deny">拒绝</NRadioButton></NRadioGroup></NFormItemGi><NFormItemGi span="2" label="原因"><NInput v-model:value="grantForm.reason" maxlength="1000" /></NFormItemGi></NGrid></NForm><template #footer><NSpace justify="end"><NButton @click="grantFormVisible = false">取消</NButton><NButton type="primary" :loading="grantSubmitting" @click="submitGrant">创建</NButton></NSpace></template></NModal>

    <NModal v-model:show="issueVisible" preset="card" title="签发 API 凭证" class="small-modal" style="width: min(560px, calc(100vw - 32px))"><NForm label-placement="top"><NFormItem label="服务账号"><NSelect v-model:value="issueForm.serviceAccountId" :options="accountOptions" filterable /></NFormItem><NFormItem label="收窄后的 Scopes"><NSelect v-model:value="issueForm.scopes" :options="issueScopeOptions" multiple filterable /></NFormItem></NForm><template #footer><NSpace justify="end"><NButton @click="issueVisible = false">取消</NButton><NButton type="primary" :loading="issueSubmitting" @click="submitIssue">签发</NButton></NSpace></template></NModal>

    <NModal :show="issuedSecretVisible" preset="card" title="一次性 API 密钥" class="secret-modal" style="width: min(680px, calc(100vw - 32px))" :mask-closable="false" :close-on-esc="false" :closable="false"><NAlert type="warning" :bordered="false">关闭后平台无法再次显示该密钥。请立即存入受控 Secret Provider。</NAlert><NInput :value="issuedSecret" readonly type="textarea" :autosize="{ minRows: 3, maxRows: 5 }" class="secret-input" /><div class="secret-prefix">Key 前缀：{{ issuedPrefix }}…</div><template #footer><NSpace justify="end"><NButton secondary @click="copySecret"><template #icon><SvgIcon icon="lucide:copy" /></template>复制</NButton><NButton type="primary" @click="closeIssuedSecret">我已安全保存并关闭</NButton></NSpace></template></NModal>
  </div>
</template>

<style scoped lang="scss">
.page-container { padding: 16px; }
.page-header, .tab-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.page-header { margin-bottom: 16px; }
.page-header h2 { margin: 0 0 4px; font-size: 20px; }
.page-header p { margin: 0; color: var(--n-text-color-3); }
.tab-toolbar { margin-bottom: 16px; }
.identity-alert { margin-bottom: 16px; }
.api-doc-group { padding: 18px 0; border-bottom: 1px solid var(--n-border-color); }
.api-doc-heading h3 { margin: 0 0 4px; font-size: 15px; }
.api-doc-heading span { color: var(--n-text-color-3); font-size: 12px; }
.endpoint-list { margin-top: 12px; border-top: 1px solid var(--n-border-color); }
.endpoint-row { display: grid; grid-template-columns: 58px minmax(280px, 1fr) minmax(180px, 1fr); align-items: center; gap: 12px; min-height: 44px; border-bottom: 1px solid var(--n-border-color); }
.endpoint-row code { overflow-wrap: anywhere; color: var(--n-text-color-1); font-size: 12px; }
.endpoint-row > span { color: var(--n-text-color-3); font-size: 13px; }
.secret-input { margin-top: 16px; font-family: monospace; }
.secret-prefix { margin-top: 10px; color: var(--n-text-color-3); font-size: 12px; }
.drawer-header { display: flex; width: 100%; align-items: center; justify-content: space-between; gap: 16px; }
.embed-grid { display: grid; grid-template-columns: minmax(320px, 0.8fr) minmax(420px, 1.2fr); gap: 16px; }
.embed-panel { min-width: 0; padding: 18px; border: 1px solid var(--n-border-color); background: var(--n-color); }
.embed-widget-container { min-height: 420px; margin-bottom: 14px; background: var(--n-color-modal); border: 1px solid var(--n-border-color); }
.embed-integration-example { grid-column: 1 / -1; }
.integration-code-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.panel-heading h3 { margin: 0 0 4px; font-size: 15px; }
.panel-heading span { color: var(--n-text-color-3); font-size: 12px; }
.embed-session { margin-top: 16px; }
.embed-actions { margin-top: 12px; }
.embed-event-list { display: flex; max-height: 480px; flex-direction: column; gap: 10px; overflow: auto; margin-top: 16px; padding-top: 12px; border-top: 1px solid var(--n-border-color); }
.embed-event { padding: 10px; border: 1px solid var(--n-border-color); }
.embed-event-heading { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.embed-event-heading span { color: var(--n-text-color-3); font-size: 12px; }
.embed-policy-form { margin-top: 4px; padding-top: 14px; border-top: 1px solid var(--n-border-color); }
.embed-policy-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.archive-filters { margin-bottom: 16px; }
.archive-filters :deep(.n-input) { width: 220px; }
.full-width { width: 100%; }
:deep(.primary-cell) { display: flex; min-width: 0; flex-direction: column; gap: 4px; }
:deep(.primary-cell span) { overflow: hidden; color: var(--n-text-color-3); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 1024px) {
  .embed-grid { grid-template-columns: 1fr; }
  .integration-code-grid { grid-template-columns: 1fr; }
}

@media (max-width: 800px) {
  .endpoint-row { grid-template-columns: 58px minmax(0, 1fr); padding: 8px 0; }
  .endpoint-row > span { grid-column: 2; }
}

@media (max-width: 640px) {
  .page-header { align-items: stretch; flex-direction: column; }
  .archive-filters { align-items: stretch; flex-direction: column; }
  .archive-filters :deep(.n-input) { width: 100%; }
}
</style>
