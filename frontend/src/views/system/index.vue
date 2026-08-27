<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import dayjs from 'dayjs';
import type { DataTableColumns, FormInst, FormRules, SelectOption } from 'naive-ui';
import { NButton, NSpace, NTag, useDialog } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { useAuthStore } from '@/store/modules/auth';
import SystemHealthOverview from './modules/system-health-overview.vue';
import RedisOperations from './modules/redis-operations.vue';
import PlatformConfiguration from './modules/platform-configuration.vue';
import LogMaintenance from './modules/log-maintenance.vue';
import PermissionCopyHistory from './modules/permission-copy-history.vue';
import IdentitySync from './modules/identity-sync.vue';
import {
  bindPermissionProfile,
  changeSystemUserStatus,
  copyUserPermissions,
  createPermissionProfile,
  createPermissionProfileVersion,
  createSystemUser,
  createTemporaryGrant,
  deletePortalQuotaPolicy,
  fetchPermissionDiff,
  fetchPermissionProfiles,
  fetchPermissionSummary,
  fetchSystemUserDetail,
  fetchSystemUsers,
  fetchPortalQuota,
  fetchPortalQuotaPolicy,
  patchPermissionOverrides,
  publishPermissionProfile,
  revokeTemporaryGrant,
  updatePortalQuotaPolicy,
  updateSystemUser
} from '@/service/api';
import type {
  PermissionDiffView,
  PermissionProfileView,
  PermissionRulePayload,
  PermissionRuleView,
  PermissionSummaryView,
  SaveSystemUserPayload,
  SystemRoleView,
  SystemUserView
} from '@/service/api';
import type { PortalQuotaPolicy, PortalQuotaStatus } from '@/service/api';

const authStore = useAuthStore();
const dialog = useDialog();
const canManageIam = computed(() => authStore.userInfo.roles.some(role => ['platform_admin', 'superadmin'].includes(role)));
const activeTab = ref('members');
const loading = ref(false);
const users = ref<SystemUserView[]>([]);
const profiles = ref<PermissionProfileView[]>([]);
const userSearch = ref('');
const quotaStatus = ref<PortalQuotaStatus | null>(null);
const quotaPolicy = ref<PortalQuotaPolicy | null>(null);
const quotaForm = reactive({ enabled: false, limitTokens: null as number | null });
const quotaLoading = ref(false);
const quotaSubmitting = ref(false);
const quotaRoles = ref<SystemRoleView[]>([]);
const quotaUserId = ref<string | null>(null);
const quotaRoleId = ref<string | null>(null);
const quotaUserPolicy = ref<PortalQuotaPolicy | null>(null);
const quotaRolePolicy = ref<PortalQuotaPolicy | null>(null);
const quotaUserForm = reactive({ enabled: true, limitTokens: null as number | null });
const quotaRoleForm = reactive({ enabled: true, limitTokens: null as number | null });
const quotaTargetLoading = ref<'user' | 'role' | null>(null);
const quotaTargetSubmitting = ref<'user' | 'role' | null>(null);
const quotaUserOptions = computed<SelectOption[]>(() => users.value.map(user => ({
  label: `${user.nickName} (${user.userName})`,
  value: user.userId,
  disabled: user.status !== '0'
})));
const quotaRoleOptions = computed<SelectOption[]>(() => quotaRoles.value.map(role => ({
  label: `${role.roleName} (${role.roleKey})`,
  value: role.roleId,
  disabled: role.status !== '0'
})));

const memberVisible = ref(false);
const memberLoading = ref(false);
const memberSubmitting = ref(false);
const memberFormRef = ref<FormInst | null>(null);
const editingMember = ref<SystemUserView | null>(null);
const memberRoleOptions = ref<SelectOption[]>([]);
const memberForm = reactive({
  userName: '', nickName: '', email: '', phoneNumber: '', password: '', gender: '2',
  status: '0', remark: '', roleIds: [] as string[]
});
const memberRules: FormRules = {
  userName: [
    { required: true, message: '请输入登录账号', trigger: ['input', 'blur'] },
    { pattern: /^[A-Za-z0-9._-]{2,30}$/, message: '账号需为 2-30 位字母、数字或 ._-', trigger: ['input', 'blur'] }
  ],
  nickName: [{ required: true, message: '请输入成员姓名', trigger: ['input', 'blur'] }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: ['input', 'blur'] }],
  roleIds: [{ type: 'array', required: true, min: 1, message: '至少选择一个角色', trigger: ['change', 'blur'] }]
};

const profileVisible = ref(false);
const profileSubmitting = ref(false);
const profileFormRef = ref<FormInst | null>(null);
type PermissionRuleDraft = Omit<PermissionRulePayload, 'policy'> & { policy?: Record<string, unknown> };

const permissionActionMap = {
  agent: ['view', 'use'],
  agent_version: ['view', 'use'],
  model: ['view', 'use'],
  tool: ['invoke'],
  skill: ['use', 'approve', 'reject'],
  knowledge_base: ['read'],
  data_source: ['read'],
  dataset: ['read', 'query', 'export', 'export_sensitive'],
  workflow: ['use'],
  connector: ['use'],
  sandbox: ['use', 'execute'],
  api_application: ['use'],
  webhook: ['invoke'],
  cron: ['execute']
} as const;

type PermissionResourceType = keyof typeof permissionActionMap;

const permissionResourceLabels: Record<PermissionResourceType, string> = {
  agent: 'Agent',
  agent_version: 'Agent 版本',
  model: '模型',
  tool: '工具',
  skill: 'Skill',
  knowledge_base: '知识库',
  data_source: '数据源',
  dataset: '数据集',
  workflow: '工作流',
  connector: '连接器',
  sandbox: '沙箱',
  api_application: 'API 应用',
  webhook: 'Webhook',
  cron: '定时任务执行'
};

const permissionResourceOptions: SelectOption[] = Object.keys(permissionActionMap).map(value => ({
  label: permissionResourceLabels[value as PermissionResourceType],
  value
}));

function permissionActionOptions(resourceType: string): SelectOption[] {
  const actions = permissionActionMap[resourceType as PermissionResourceType] || [];
  return actions.map(value => ({ label: value, value }));
}

function resetPermissionAction(rule: PermissionRuleDraft) {
  const actions = permissionActionMap[rule.resourceType as PermissionResourceType] || [];
  if (!actions.includes(rule.action as never)) rule.action = actions[0] || '';
}

function emptyPermissionRule(): PermissionRuleDraft {
  return { resourceType: 'agent', resourceId: '', resourceKey: '*', action: 'view', effect: 'allow', policy: {}, reason: '' };
}
const profileForm = reactive({
  profileKey: '', name: '', description: '', entries: [emptyPermissionRule()] as PermissionRuleDraft[]
});
const profileRules: FormRules = {
  profileKey: [
    { required: true, message: '请输入权限包标识', trigger: ['input', 'blur'] },
    { pattern: /^[A-Za-z0-9._:-]+$/, message: '权限包标识包含非法字符', trigger: ['input', 'blur'] }
  ],
  name: [{ required: true, message: '请输入权限包名称', trigger: ['input', 'blur'] }],
};

const versionVisible = ref(false);
const versionSubmitting = ref(false);
const activeProfile = ref<PermissionProfileView | null>(null);
const versionForm = reactive({
  name: '', description: '', entries: [emptyPermissionRule()] as PermissionRuleDraft[]
});

const permissionVisible = ref(false);
const permissionLoading = ref(false);
const activeUser = ref<SystemUserView | null>(null);
const permissionSummary = ref<PermissionSummaryView | null>(null);
const selectedProfileId = ref<string | null>(null);
const sourceUserId = ref<string | null>(null);
const permissionDiff = ref<PermissionDiffView | null>(null);
const copySubmitting = ref(false);
type PermissionCopyMode = 'copy_base' | 'append_missing' | 'replace_base' | 'save_template';
const copyMode = ref<PermissionCopyMode>('copy_base');
const copyTemplateKey = ref('');
const copyTemplateName = ref('');
const copyModeOptions: SelectOption[] = [
  { label: '复制基础权限（生成独立快照）', value: 'copy_base' },
  { label: '追加缺失权限（保留目标已有）', value: 'append_missing' },
  { label: '覆盖基础权限（完全替换）', value: 'replace_base' },
  { label: '另存为权限模板（不修改目标）', value: 'save_template' }
];
const copyModeDescription = computed(() => {
  const descriptions: Record<PermissionCopyMode, string> = {
    copy_base: '用参考用户当前可复用的基础权限生成目标用户快照，目标原有基础权限会被替换。',
    append_missing: '只补充目标用户尚未拥有的基础权限，不移除目标已有权限。',
    replace_base: '清空目标当前基础权限后，完全按参考用户的可复用权限重建快照。',
    save_template: '将参考用户的可复用权限保存为新的草稿权限包，不改变目标用户权限。'
  };
  return descriptions[copyMode.value];
});
const copyRequiresTemplate = computed(() => copyMode.value === 'save_template');
const copyButtonLabel = computed(() => copyMode.value === 'save_template' ? '保存权限模板' : '确认执行复制');
const canCopyPermissions = computed(() => {
  if (!permissionDiff.value || !sourceUserId.value) return false;
  if (!copyRequiresTemplate.value) return true;
  const key = copyTemplateKey.value.trim();
  return Boolean(key && copyTemplateName.value.trim() && /^[A-Za-z0-9._:-]+$/.test(key));
});

const ruleVisible = ref(false);
const ruleSubmitting = ref(false);
const ruleMode = ref<'override' | 'temporary'>('override');
const ruleForm = reactive({
  resourceType: 'agent', resourceId: '', resourceKey: '*', action: 'view',
  effect: 'allow' as PermissionRulePayload['effect'], reason: '', expiresAt: ''
});

const profileOptions = computed<SelectOption[]>(() =>
  profiles.value.filter(profile => profile.status === 'published').map(profile => ({
    label: `${profile.name} · v${profile.versionNo}`,
    value: profile.id,
    version: profile.versionNo
  }))
);
const sourceUserOptions = computed<SelectOption[]>(() =>
  users.value.filter(user => user.userId !== activeUser.value?.userId && user.status === '0').map(user => ({
    label: `${user.nickName || user.userName} (@${user.userName})`,
    value: user.userId
  }))
);

function statusType(status: string) {
  if (['0', 'published', 'active'].includes(status)) return 'success';
  if (status === 'draft') return 'warning';
  return 'default';
}

function statusText(status: string) {
  return ({ '0': '正常', '1': '停用', draft: '草稿', published: '已发布', archived: '已归档', active: '启用' } as Record<string, string>)[status] || status;
}

function effectType(effect: PermissionRuleView['effect']) {
  return effect === 'allow' ? 'success' : effect === 'deny' ? 'error' : 'warning';
}

function resourceStateLabel(state: PermissionRuleView['resourceState']) {
  return ({ active: '有效', inactive: '已停用', missing: '已失效', unresolved: '运行时匹配' } as const)[state];
}

function resourceStateType(state: PermissionRuleView['resourceState']) {
  if (state === 'active') return 'success';
  if (state === 'inactive') return 'warning';
  if (state === 'missing') return 'error';
  return 'default';
}

async function loadData() {
  loading.value = true;
  const userResult = await fetchSystemUsers(userSearch.value.trim() || undefined, 1, 200);
  if (!userResult.error) users.value = userResult.data.rows;
  if (canManageIam.value) {
    const [profileResult, roleResult] = await Promise.all([
      fetchPermissionProfiles(),
      fetchSystemUserDetail()
    ]);
    if (!profileResult.error) profiles.value = profileResult.data;
    if (!roleResult.error) {
      quotaRoles.value = roleResult.data.roles;
      memberRoleOptions.value = roleResult.data.roles.filter(role => role.status === '0').map(role => ({
        label: `${role.roleName} (${role.roleKey})`, value: role.roleId
      }));
    }
    if (!quotaUserId.value) quotaUserId.value = users.value.find(user => user.status === '0')?.userId || null;
    if (!quotaRoleId.value) quotaRoleId.value = quotaRoles.value.find(role => role.status === '0')?.roleId || null;
  }
  loading.value = false;
  await loadQuota();
}

async function loadQuota() {
  quotaLoading.value = true;
  const statusResult = await fetchPortalQuota();
  if (!statusResult.error) quotaStatus.value = statusResult.data;
  if (canManageIam.value) {
    const [policyResult, userResult, roleResult] = await Promise.all([
      fetchPortalQuotaPolicy('system'),
      quotaUserId.value ? fetchPortalQuotaPolicy('user', quotaUserId.value) : null,
      quotaRoleId.value ? fetchPortalQuotaPolicy('role', quotaRoleId.value) : null
    ]);
    if (!policyResult.error) {
      quotaPolicy.value = policyResult.data;
      quotaForm.enabled = policyResult.data.enabled;
      quotaForm.limitTokens = policyResult.data.limit_tokens;
    }
    if (userResult && !userResult.error) applyQuotaTarget('user', userResult.data);
    if (roleResult && !roleResult.error) applyQuotaTarget('role', roleResult.data);
  }
  quotaLoading.value = false;
}

function applyQuotaTarget(scope: 'user' | 'role', policy: PortalQuotaPolicy) {
  const form = scope === 'user' ? quotaUserForm : quotaRoleForm;
  form.enabled = policy.enabled;
  form.limitTokens = policy.limit_tokens;
  if (scope === 'user') quotaUserPolicy.value = policy;
  else quotaRolePolicy.value = policy;
}

async function loadQuotaTarget(scope: 'user' | 'role') {
  const scopeId = scope === 'user' ? quotaUserId.value : quotaRoleId.value;
  if (!scopeId) {
    if (scope === 'user') quotaUserPolicy.value = null;
    else quotaRolePolicy.value = null;
    return;
  }
  quotaTargetLoading.value = scope;
  const result = await fetchPortalQuotaPolicy(scope, scopeId);
  if (!result.error) applyQuotaTarget(scope, result.data);
  quotaTargetLoading.value = null;
}

async function saveQuotaPolicy() {
  quotaSubmitting.value = true;
  const result = await updatePortalQuotaPolicy('system', undefined, {
    enabled: quotaForm.enabled,
    limitTokens: quotaForm.limitTokens
  });
  if (!result.error) {
    quotaPolicy.value = result.data;
    window.$message?.success('系统额度策略已保存');
    await loadQuota();
  }
  quotaSubmitting.value = false;
}

async function saveQuotaTarget(scope: 'user' | 'role') {
  const scopeId = scope === 'user' ? quotaUserId.value : quotaRoleId.value;
  if (!scopeId) return;
  const form = scope === 'user' ? quotaUserForm : quotaRoleForm;
  quotaTargetSubmitting.value = scope;
  const result = await updatePortalQuotaPolicy(scope, scopeId, {
    enabled: form.enabled,
    limitTokens: form.limitTokens
  });
  if (!result.error) {
    window.$message?.success(scope === 'user' ? '用户专属额度已保存' : '角色额度模板已保存');
    await loadQuotaTarget(scope);
  }
  quotaTargetSubmitting.value = null;
}

function confirmClearQuota(scope: 'system' | 'user' | 'role') {
  const scopeId = scope === 'user' ? quotaUserId.value : scope === 'role' ? quotaRoleId.value : undefined;
  if (scope !== 'system' && !scopeId) return;
  const label = scope === 'system' ? '系统默认额度' : scope === 'user' ? '用户专属额度' : '角色额度模板';
  dialog.warning({
    title: `清除${label}`,
    content: scope === 'system'
      ? '清除后，未命中用户或角色策略的成员将不再受系统默认额度限制。'
      : `清除后将立即恢复继承${scope === 'user' ? '角色或系统' : '系统'}策略。`,
    positiveText: '确认清除',
    negativeText: '取消',
    onPositiveClick: async () => {
      const result = await deletePortalQuotaPolicy(scope, scopeId || undefined);
      if (result.error) return false;
      window.$message?.success(`${label}已清除`);
      if (scope === 'system') await loadQuota();
      else await loadQuotaTarget(scope);
      return true;
    }
  });
}

async function openMember(user?: SystemUserView) {
  editingMember.value = user || null;
  Object.assign(memberForm, {
    userName: '', nickName: '', email: '', phoneNumber: '', password: '', gender: '2',
    status: '0', remark: '', roleIds: []
  });
  memberVisible.value = true;
  memberLoading.value = true;
  const { data, error } = await fetchSystemUserDetail(user?.userId);
  if (!error) {
    memberRoleOptions.value = data.roles.filter(role => role.status === '0').map(role => ({
      label: `${role.roleName} (${role.roleKey})`, value: role.roleId
    }));
    if (user && data.user) {
      Object.assign(memberForm, {
        userName: data.user.userName,
        nickName: data.user.nickName,
        email: data.user.email || '',
        phoneNumber: data.user.phoneNumber || '',
        gender: data.user.gender || '2',
        status: data.user.status,
        remark: data.user.remark || '',
        roleIds: data.roleIds || []
      });
    } else {
      const memberRole = data.roles.find(role => role.roleKey === 'member' && role.status === '0');
      memberForm.roleIds = memberRole ? [memberRole.roleId] : [];
    }
  } else {
    memberVisible.value = false;
  }
  memberLoading.value = false;
}

async function submitMember() {
  await memberFormRef.value?.validate();
  if (!editingMember.value && memberForm.password.length < 6) {
    window.$message?.warning('初始密码至少 6 位');
    return;
  }
  const payload: SaveSystemUserPayload = {
    userName: memberForm.userName.trim(), nickName: memberForm.nickName.trim(),
    email: memberForm.email.trim() || undefined, phoneNumber: memberForm.phoneNumber.trim() || undefined,
    gender: memberForm.gender, status: memberForm.status, remark: memberForm.remark.trim() || undefined,
    roleIds: memberForm.roleIds
  };
  memberSubmitting.value = true;
  const result = editingMember.value
    ? await updateSystemUser({ ...payload, userId: editingMember.value.userId, deptId: editingMember.value.deptId })
    : await createSystemUser({ ...payload, password: memberForm.password });
  if (!result.error) {
    memberVisible.value = false;
    window.$message?.success(editingMember.value ? '成员信息已更新' : '成员已创建');
    await loadData();
  }
  memberSubmitting.value = false;
}

function toggleMemberStatus(user: SystemUserView) {
  const nextStatus = user.status === '0' ? '1' : '0';
  dialog.warning({
    title: nextStatus === '1' ? '停用成员' : '启用成员',
    content: `确认${nextStatus === '1' ? '停用' : '启用'} ${user.nickName || user.userName}？`,
    positiveText: '确认', negativeText: '取消',
    onPositiveClick: async () => {
      const { error } = await changeSystemUserStatus(user.userId, nextStatus);
      if (!error) { window.$message?.success('成员状态已更新'); await loadData(); }
    }
  });
}

function openProfile() {
  Object.assign(profileForm, {
    profileKey: '', name: '', description: '', entries: [emptyPermissionRule()]
  });
  profileVisible.value = true;
}

function addProfileRule(target: { entries: PermissionRuleDraft[] }) {
  target.entries.push(emptyPermissionRule());
}

function removeProfileRule(target: { entries: PermissionRuleDraft[] }, index: number) {
  if (target.entries.length <= 1) {
    window.$message?.warning('权限包至少需要一条规则');
    return;
  }
  target.entries.splice(index, 1);
}

function permissionRulePayloads(entries: PermissionRuleDraft[]) {
  const rules = entries.map(entry => ({
    resourceType: entry.resourceType.trim(),
    resourceId: entry.resourceId?.trim() || undefined,
    resourceKey: entry.resourceKey?.trim() || undefined,
    action: entry.action.trim(),
    effect: entry.effect,
    policy: entry.policy || {},
    reason: entry.reason?.trim() || undefined
  } satisfies PermissionRulePayload));
  if (rules.some(rule => !rule.resourceType || !rule.action || (!rule.resourceId && !rule.resourceKey))) {
    window.$message?.warning('每条规则都需要填写资源类型、资源 Key 或资源 ID、动作');
    return null;
  }
  return rules;
}

async function submitProfile() {
  await profileFormRef.value?.validate();
  const entries = permissionRulePayloads(profileForm.entries);
  if (!entries) return;
  profileSubmitting.value = true;
  const { error } = await createPermissionProfile({
    profileKey: profileForm.profileKey.trim(), name: profileForm.name.trim(),
    description: profileForm.description.trim() || undefined, profileType: 'custom', entries
  });
  if (!error) { profileVisible.value = false; window.$message?.success('权限包草稿已创建'); await loadData(); }
  profileSubmitting.value = false;
}

function openProfileVersion(row: PermissionProfileView) {
  activeProfile.value = row;
  Object.assign(versionForm, { name: `${row.name} v${row.versionNo + 1}`, description: row.description || '', entries: row.entries.map(entry => ({
    resourceType: entry.resourceType,
    resourceId: entry.resourceId || '',
    resourceKey: entry.resourceKey || '*',
    action: entry.action,
    effect: entry.effect,
    policy: entry.policy || {},
    reason: entry.reason || ''
  })) });
  if (!versionForm.entries.length) versionForm.entries.push(emptyPermissionRule());
  versionVisible.value = true;
}

async function submitProfileVersion() {
  if (!activeProfile.value) return;
  const entries = permissionRulePayloads(versionForm.entries);
  if (!entries || !versionForm.name.trim()) {
    window.$message?.warning('请填写版本名称和至少一条有效规则');
    return;
  }
  versionSubmitting.value = true;
  const { error } = await createPermissionProfileVersion(activeProfile.value.id, {
    name: versionForm.name.trim(), description: versionForm.description.trim() || undefined, entries
  });
  if (!error) {
    versionVisible.value = false;
    window.$message?.success('权限包新版本草稿已创建');
    await loadData();
  }
  versionSubmitting.value = false;
}

async function publishProfile(row: PermissionProfileView) {
  const { error } = await publishPermissionProfile(row.id);
  if (!error) { window.$message?.success('权限包已发布并冻结'); await loadData(); }
}

async function openPermissions(user: SystemUserView) {
  activeUser.value = user;
  selectedProfileId.value = null;
  sourceUserId.value = null;
  permissionDiff.value = null;
  copyMode.value = 'copy_base';
  copyTemplateKey.value = '';
  copyTemplateName.value = '';
  permissionVisible.value = true;
  await loadPermissionSummary();
}

async function loadPermissionSummary() {
  if (!activeUser.value) return;
  permissionLoading.value = true;
  const { data, error } = await fetchPermissionSummary(activeUser.value.userId);
  if (!error) {
    permissionSummary.value = data;
    selectedProfileId.value = data.binding?.profileId || null;
  }
  permissionLoading.value = false;
}

async function bindProfile() {
  if (!activeUser.value || !selectedProfileId.value) return;
  const profile = profiles.value.find(item => item.id === selectedProfileId.value);
  if (!profile) return;
  const { error } = await bindPermissionProfile(activeUser.value.userId, profile.id, profile.versionNo);
  if (!error) { window.$message?.success('基础权限包已绑定'); await loadPermissionSummary(); }
}

async function previewDiff() {
  if (!activeUser.value || !sourceUserId.value || sourceUserId.value === activeUser.value.userId) {
    window.$message?.warning('请选择与目标成员不同的参考用户');
    return;
  }
  const { data, error } = await fetchPermissionDiff(activeUser.value.userId, sourceUserId.value);
  if (!error) permissionDiff.value = data;
}

async function copyPermissions() {
  if (!activeUser.value || !permissionDiff.value || !sourceUserId.value) return;
  const templateKey = copyTemplateKey.value.trim();
  const templateName = copyTemplateName.value.trim();
  if (copyRequiresTemplate.value && (!templateKey || !templateName || !/^[A-Za-z0-9._:-]+$/.test(templateKey))) {
    window.$message?.warning('另存为模板时请输入合法的模板标识和模板名称');
    return;
  }
  copySubmitting.value = true;
  const payload = {
    idempotencyKey: crypto.randomUUID(),
    sourceUserId: sourceUserId.value,
    copyMode: copyMode.value,
    ...(copyRequiresTemplate.value ? { templateKey, templateName } : {})
  };
  const { data, error } = await copyUserPermissions(activeUser.value.userId, payload);
  if (!error) {
    if (copyMode.value === 'save_template') {
      window.$message?.success(data.replayed ? '重复请求已返回原模板结果' : `权限模板已保存 v${data.createdProfileVersion || 1}`);
    } else {
      const action = copyMode.value === 'append_missing' ? '追加' : '复制';
      window.$message?.success(data.replayed ? '重复请求已返回原复制结果' : `已${action} ${data.addedRuleCount} 条基础规则`);
    }
    permissionDiff.value = null;
    await loadPermissionSummary();
  }
  copySubmitting.value = false;
}

function openRule(mode: 'override' | 'temporary') {
  ruleMode.value = mode;
  Object.assign(ruleForm, {
    resourceType: 'agent', resourceId: '', resourceKey: '*', action: 'view', effect: 'allow',
    reason: '', expiresAt: mode === 'temporary' ? dayjs().add(1, 'day').format('YYYY-MM-DDTHH:mm:ss') : ''
  });
  ruleVisible.value = true;
}

function rulePayload(row = ruleForm): PermissionRulePayload {
  return {
    resourceType: row.resourceType.trim(), resourceId: row.resourceId.trim() || undefined,
    resourceKey: row.resourceKey.trim() || undefined, action: row.action.trim(),
    effect: row.effect, policy: {}, reason: row.reason.trim() || undefined
  };
}

async function submitRule() {
  if (!activeUser.value || !ruleForm.resourceType.trim() || !ruleForm.action.trim() || !ruleForm.reason.trim()) {
    window.$message?.warning('资源类型、动作和原因不能为空');
    return;
  }
  if (ruleForm.resourceId.trim() && !/^\d+$/.test(ruleForm.resourceId.trim())) {
    window.$message?.warning('资源 ID 必须为正整数');
    return;
  }
  if (ruleMode.value === 'temporary' && (!dayjs(ruleForm.expiresAt).isValid() || !dayjs(ruleForm.expiresAt).isAfter(dayjs()))) {
    window.$message?.warning('临时授权必须设置未来到期时间');
    return;
  }
  ruleSubmitting.value = true;
  const result = ruleMode.value === 'override'
    ? await patchPermissionOverrides(activeUser.value.userId, [{
        operation: 'upsert', rule: rulePayload(), expiresAt: ruleForm.expiresAt || undefined
      }])
    : await createTemporaryGrant(activeUser.value.userId, {
        rule: rulePayload(), reason: ruleForm.reason.trim(), expiresAt: dayjs(ruleForm.expiresAt).format('YYYY-MM-DDTHH:mm:ss')
      });
  if (!result.error) {
    ruleVisible.value = false;
    window.$message?.success(ruleMode.value === 'override' ? '个人覆盖项已保存' : '临时授权已创建');
    await loadPermissionSummary();
  }
  ruleSubmitting.value = false;
}

function revokeOverride(row: PermissionRuleView) {
  if (!activeUser.value) return;
  dialog.warning({
    title: '撤销个人覆盖项', content: `确认撤销 ${row.resourceType}:${row.resourceId || row.resourceKey || '*'} 的 ${row.action} 规则？`, positiveText: '撤销', negativeText: '取消',
    onPositiveClick: async () => {
      if (!activeUser.value) return;
      const { error } = await patchPermissionOverrides(activeUser.value.userId, [{
        operation: 'revoke',
        rule: {
          resourceType: row.resourceType, resourceId: row.resourceId, resourceKey: row.resourceKey,
          action: row.action, effect: row.effect, policy: row.policy, reason: row.reason
        }
      }]);
      if (!error) { window.$message?.success('个人覆盖项已撤销'); await loadPermissionSummary(); }
    }
  });
}

function revokeGrant(row: PermissionRuleView) {
  if (!activeUser.value) return;
  dialog.warning({
    title: '撤销临时授权', content: '确认立即撤销该临时授权？', positiveText: '撤销', negativeText: '取消',
    onPositiveClick: async () => {
      if (!activeUser.value) return;
      const { error } = await revokeTemporaryGrant(activeUser.value.userId, row.id);
      if (!error) { window.$message?.success('临时授权已撤销'); await loadPermissionSummary(); }
    }
  });
}

const userColumns: DataTableColumns<SystemUserView> = [
  { title: '用户', key: 'nickName', minWidth: 220, render: row => h('div', { class: 'primary-cell' }, [h('strong', row.nickName || row.userName), h('span', `@${row.userName}`)]) },
  { title: '部门', key: 'deptName', width: 150, render: row => row.deptName || '-' },
  { title: '邮箱', key: 'email', minWidth: 200, render: row => row.email || '-' },
  { title: '状态', key: 'status', width: 90, render: row => h(NTag, { size: 'small', type: statusType(row.status) }, () => statusText(row.status)) },
  { title: '创建时间', key: 'createTime', width: 165, render: row => dayjs(row.createTime).format('YYYY-MM-DD HH:mm') },
  { title: '操作', key: 'actions', width: 240, fixed: 'right', render: row => canManageIam.value ? h(NSpace, { size: 6 }, () => [
    h(NButton, { size: 'small', secondary: true, onClick: () => openMember(row) }, () => '编辑'),
    h(NButton, { size: 'small', type: 'primary', secondary: true, onClick: () => openPermissions(row) }, () => '权限'),
    h(NButton, {
      size: 'small', quaternary: true, type: row.status === '0' ? 'warning' : 'success',
      disabled: row.userId === authStore.userInfo.userId,
      onClick: () => toggleMemberStatus(row)
    }, () => row.status === '0' ? '停用' : '启用')
  ]) : '-' }
];

const profileColumns: DataTableColumns<PermissionProfileView> = [
  { title: '权限包', key: 'name', minWidth: 240, render: row => h('div', { class: 'primary-cell' }, [h('strong', row.name), h('span', `${row.profileKey} · ${row.description || '暂无描述'}`)]) },
  { title: '类型', key: 'profileType', width: 100 },
  { title: '版本', key: 'versionNo', width: 80, render: row => `v${row.versionNo}` },
  { title: '规则数', key: 'entries', width: 90, render: row => row.entries.length },
  { title: '状态', key: 'status', width: 100, render: row => h(NTag, { size: 'small', type: statusType(row.status) }, () => statusText(row.status)) },
  { title: '创建时间', key: 'createdAt', width: 165, render: row => dayjs(row.createdAt).format('YYYY-MM-DD HH:mm') },
  {
    title: '操作',
    key: 'actions',
    width: 190,
    render: row => h(NSpace, { size: 6 }, () => [
      row.status === 'draft'
        ? h(NButton, { size: 'small', type: 'primary', secondary: true, onClick: () => publishProfile(row) }, () => '发布')
        : null,
      row.status !== 'archived'
        ? h(NButton, { size: 'small', quaternary: true, onClick: () => openProfileVersion(row) }, () => '新版本')
        : null
    ])
  }
];

const ruleColumns: DataTableColumns<PermissionRuleView> = [
  { title: '资源', key: 'resourceType', minWidth: 180, render: row => `${row.resourceType}:${row.resourceId || row.resourceKey || '*'}` },
  { title: '资源状态', key: 'resourceState', width: 110, render: row => h(NTag, { size: 'small', type: resourceStateType(row.resourceState) }, () => resourceStateLabel(row.resourceState)) },
  { title: '动作', key: 'action', width: 120 },
  { title: '效果', key: 'effect', width: 130, render: row => h(NTag, { size: 'small', type: effectType(row.effect) }, () => row.effect) },
  { title: '原因', key: 'reason', minWidth: 200, ellipsis: { tooltip: true }, render: row => row.reason || '-' },
  { title: '到期', key: 'expiresAt', width: 165, render: row => row.expiresAt ? dayjs(row.expiresAt).format('YYYY-MM-DD HH:mm') : '长期' }
];
const overrideColumns: DataTableColumns<PermissionRuleView> = [
  ...ruleColumns,
  { title: '操作', key: 'actions', width: 90, render: row => h(NButton, { size: 'small', type: 'error', quaternary: true, onClick: () => revokeOverride(row) }, () => '撤销') }
];
const temporaryColumns: DataTableColumns<PermissionRuleView> = [
  ...ruleColumns,
  { title: '操作', key: 'actions', width: 90, render: row => h(NButton, { size: 'small', type: 'error', quaternary: true, onClick: () => revokeGrant(row) }, () => '撤销') }
];

const diffRows = computed(() => permissionDiff.value ? [
  ...permissionDiff.value.missingOnTarget.map(rule => ({ ...rule, diffType: '参考用户新增' })),
  ...permissionDiff.value.changed.map(rule => ({ ...rule, diffType: '规则不同' })),
  ...permissionDiff.value.excludedFromCopy.map(rule => ({ ...rule, diffType: '明确排除' }))
] : []);
const diffColumns: DataTableColumns<PermissionRuleView & { diffType: string }> = [
  { title: '差异', key: 'diffType', width: 120 },
  { title: '资源', key: 'resourceType', minWidth: 180, render: row => `${row.resourceType}:${row.resourceId || row.resourceKey || '*'}` },
  { title: '资源状态', key: 'resourceState', width: 110, render: row => h(NTag, { size: 'small', type: resourceStateType(row.resourceState) }, () => resourceStateLabel(row.resourceState)) },
  { title: '动作', key: 'action', width: 110 },
  { title: '效果', key: 'effect', width: 120 }
];

onMounted(loadData);
</script>

<template>
  <div class="page-container">
    <div class="page-header"><div><h2>{{ $t('page.system.title') }}</h2><p>{{ $t('page.system.desc') }}</p></div><NButton secondary :loading="loading" @click="loadData"><template #icon><SvgIcon icon="lucide:refresh-cw" /></template>刷新</NButton></div>
    <NTabs v-model:value="activeTab" type="line" animated>
      <NTabPane name="members" tab="成员">
        <div class="tab-toolbar">
          <NSpace><NInput v-model:value="userSearch" clearable placeholder="搜索账号" class="search-input" @keyup.enter="loadData"><template #prefix><SvgIcon icon="lucide:search" /></template></NInput><NButton secondary @click="loadData">查询</NButton></NSpace>
          <NButton v-if="canManageIam" type="primary" @click="openMember()"><template #icon><SvgIcon icon="lucide:user-plus" /></template>新增成员</NButton>
        </div>
        <NDataTable :columns="userColumns" :data="users" :loading="loading" :scroll-x="1050" :row-key="row => row.userId" />
      </NTabPane>
      <NTabPane name="roles" tab="固定角色">
        <NAlert type="info" :bordered="false" class="role-alert">一期固定角色只决定平台管理职责；业务资源权限由权限包、覆盖项、临时授权和运行时资源交集共同决定。</NAlert>
        <NDescriptions :column="1" bordered label-placement="left">
          <NDescriptionsItem label="platform_admin">初始化、成员、全局连接器、策略与审计管理</NDescriptionsItem>
          <NDescriptionsItem label="member">使用明确授权的智能体、项目和任务</NDescriptionsItem>
          <NDescriptionsItem label="approval_user">处理高风险审批、验收和人工接管</NDescriptionsItem>
          <NDescriptionsItem label="service_account">仅作为机器身份分类说明，不能登录用户界面，也不继承人类 IAM</NDescriptionsItem>
        </NDescriptions>
      </NTabPane>
      <NTabPane v-if="canManageIam" name="profiles" tab="权限包">
        <div class="tab-toolbar"><span></span><NButton type="primary" @click="openProfile"><template #icon><SvgIcon icon="lucide:plus" /></template>创建权限包</NButton></div>
        <NDataTable :columns="profileColumns" :data="profiles" :loading="loading" :scroll-x="1000" :row-key="row => row.id" />
      </NTabPane>
      <NTabPane v-if="canManageIam" name="permission-history" tab="权限复制历史">
        <PermissionCopyHistory :users="users" />
      </NTabPane>
      <NTabPane v-if="canManageIam" name="identity-sync" tab="身份同步">
        <IdentitySync />
      </NTabPane>
      <NTabPane name="quota" tab="Token 额度">
        <NSpin :show="quotaLoading">
          <NGrid :cols="2" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
            <NGi span="24 s:24 m:12">
              <NCard size="small" :bordered="false" class="quota-card" title="我的本月额度">
                <NDescriptions v-if="quotaStatus" :column="1" bordered label-placement="left">
                  <NDescriptionsItem label="已用 Token">{{ quotaStatus.used_tokens.toLocaleString() }}</NDescriptionsItem>
                  <NDescriptionsItem label="额度上限">{{ quotaStatus.limit_tokens === null ? '不限额' : quotaStatus.limit_tokens.toLocaleString() }}</NDescriptionsItem>
                  <NDescriptionsItem label="剩余">{{ quotaStatus.remaining_tokens === null ? '不限额' : quotaStatus.remaining_tokens.toLocaleString() }}</NDescriptionsItem>
                  <NDescriptionsItem label="来源">{{ quotaStatus.source_label || '未配置策略' }}</NDescriptionsItem>
                </NDescriptions>
                <NEmpty v-else description="额度数据不可用" />
              </NCard>
            </NGi>
            <NGi v-if="canManageIam" span="24 s:24 m:12">
              <NCard size="small" :bordered="false" class="quota-card" title="系统默认策略">
                <NForm label-placement="top">
                  <NFormItem label="启用策略"><NSwitch v-model:value="quotaForm.enabled"><template #checked>启用</template><template #unchecked>关闭</template></NSwitch></NFormItem>
                  <NFormItem label="每月 Token 上限"><NInputNumber v-model:value="quotaForm.limitTokens" :min="0" clearable placeholder="留空表示不限额" class="w-full" /></NFormItem>
                </NForm>
                <template #footer><NSpace justify="end"><NButton v-if="quotaPolicy && !quotaPolicy.inherit" secondary type="error" @click="confirmClearQuota('system')"><template #icon><SvgIcon icon="lucide:trash-2" /></template>清除</NButton><NButton type="primary" :loading="quotaSubmitting" @click="saveQuotaPolicy"><template #icon><SvgIcon icon="lucide:save" /></template>保存策略</NButton></NSpace></template>
              </NCard>
            </NGi>
            <NGi v-if="canManageIam" span="24 s:24 m:12">
              <NCard size="small" :bordered="false" class="quota-card">
                <template #header><NSpace align="center"><span>用户专属额度</span><NTag v-if="quotaUserPolicy?.inherit" size="small">继承中</NTag></NSpace></template>
                <NSpin :show="quotaTargetLoading === 'user'">
                  <NForm label-placement="top">
                    <NFormItem label="成员"><NSelect v-model:value="quotaUserId" :options="quotaUserOptions" filterable placeholder="选择成员" @update:value="loadQuotaTarget('user')" /></NFormItem>
                    <NFormItem label="启用专属策略"><NSwitch v-model:value="quotaUserForm.enabled" :disabled="!quotaUserId"><template #checked>启用</template><template #unchecked>关闭</template></NSwitch></NFormItem>
                    <NFormItem label="每月 Token 上限"><NInputNumber v-model:value="quotaUserForm.limitTokens" :disabled="!quotaUserId" :min="0" clearable placeholder="留空表示不限额" class="w-full" /></NFormItem>
                  </NForm>
                  <NAlert v-if="quotaUserPolicy?.effective" type="info" :bordered="false">
                    当前有效策略：{{ quotaUserPolicy.effective.source_label || '未配置' }}，上限 {{ quotaUserPolicy.effective.limit_tokens === null ? '不限额' : quotaUserPolicy.effective.limit_tokens.toLocaleString() }} Token
                  </NAlert>
                </NSpin>
                <template #footer><NSpace justify="end"><NButton v-if="quotaUserPolicy && !quotaUserPolicy.inherit" secondary type="error" @click="confirmClearQuota('user')"><template #icon><SvgIcon icon="lucide:trash-2" /></template>恢复继承</NButton><NButton type="primary" :disabled="!quotaUserId" :loading="quotaTargetSubmitting === 'user'" @click="saveQuotaTarget('user')"><template #icon><SvgIcon icon="lucide:save" /></template>保存专属额度</NButton></NSpace></template>
              </NCard>
            </NGi>
            <NGi v-if="canManageIam" span="24 s:24 m:12">
              <NCard size="small" :bordered="false" class="quota-card">
                <template #header><NSpace align="center"><span>角色额度模板</span><NTag v-if="quotaRolePolicy?.inherit" size="small">继承系统</NTag></NSpace></template>
                <NSpin :show="quotaTargetLoading === 'role'">
                  <NForm label-placement="top">
                    <NFormItem label="角色"><NSelect v-model:value="quotaRoleId" :options="quotaRoleOptions" filterable placeholder="选择角色" @update:value="loadQuotaTarget('role')" /></NFormItem>
                    <NFormItem label="启用角色策略"><NSwitch v-model:value="quotaRoleForm.enabled" :disabled="!quotaRoleId"><template #checked>启用</template><template #unchecked>关闭</template></NSwitch></NFormItem>
                    <NFormItem label="每月 Token 上限"><NInputNumber v-model:value="quotaRoleForm.limitTokens" :disabled="!quotaRoleId" :min="0" clearable placeholder="留空表示不限额" class="w-full" /></NFormItem>
                  </NForm>
                  <NAlert type="info" :bordered="false">用户未配置专属额度时，按其角色策略中的最高上限执行；任一角色配置为不限额时优先不限额，均未配置则继承系统策略。</NAlert>
                </NSpin>
                <template #footer><NSpace justify="end"><NButton v-if="quotaRolePolicy && !quotaRolePolicy.inherit" secondary type="error" @click="confirmClearQuota('role')"><template #icon><SvgIcon icon="lucide:trash-2" /></template>恢复继承</NButton><NButton type="primary" :disabled="!quotaRoleId" :loading="quotaTargetSubmitting === 'role'" @click="saveQuotaTarget('role')"><template #icon><SvgIcon icon="lucide:save" /></template>保存角色模板</NButton></NSpace></template>
              </NCard>
            </NGi>
          </NGrid>
        </NSpin>
      </NTabPane>
      <NTabPane v-if="canManageIam" name="health" tab="运行健康">
        <SystemHealthOverview />
      </NTabPane>
      <NTabPane v-if="canManageIam" name="redis" tab="Redis 运维">
        <RedisOperations />
      </NTabPane>
      <NTabPane v-if="canManageIam" name="logs" tab="日志留存">
        <LogMaintenance />
      </NTabPane>
      <NTabPane v-if="canManageIam" name="configuration" tab="平台配置">
        <PlatformConfiguration />
      </NTabPane>
    </NTabs>

  <NModal v-model:show="memberVisible" preset="card" :title="editingMember ? '编辑成员' : '新增成员'" class="form-modal" style="width: min(780px, calc(100vw - 32px))" :mask-closable="!memberSubmitting">
    <NSpin :show="memberLoading">
      <NForm ref="memberFormRef" :model="memberForm" :rules="memberRules" label-placement="top">
        <NGrid :cols="2" :x-gap="16">
          <NFormItemGi label="登录账号" path="userName"><NInput v-model:value="memberForm.userName" :disabled="Boolean(editingMember)" placeholder="例如 zhangsan" /></NFormItemGi>
          <NFormItemGi label="成员姓名" path="nickName"><NInput v-model:value="memberForm.nickName" /></NFormItemGi>
          <NFormItemGi label="邮箱" path="email"><NInput v-model:value="memberForm.email" /></NFormItemGi>
          <NFormItemGi label="手机号"><NInput v-model:value="memberForm.phoneNumber" /></NFormItemGi>
          <NFormItemGi v-if="!editingMember" label="初始密码" required><NInput v-model:value="memberForm.password" type="password" show-password-on="click" placeholder="至少 6 位" /></NFormItemGi>
          <NFormItemGi label="性别"><NSelect v-model:value="memberForm.gender" :options="[{ label: '未知', value: '2' }, { label: '男', value: '0' }, { label: '女', value: '1' }]" /></NFormItemGi>
          <NFormItemGi span="2" label="系统角色" path="roleIds"><NSelect v-model:value="memberForm.roleIds" :options="memberRoleOptions" multiple filterable placeholder="选择成员承担的平台职责" /></NFormItemGi>
          <NFormItemGi label="账号状态"><NSwitch v-model:value="memberForm.status" checked-value="0" unchecked-value="1"><template #checked>正常</template><template #unchecked>停用</template></NSwitch></NFormItemGi>
          <NFormItemGi span="2" label="备注"><NInput v-model:value="memberForm.remark" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" /></NFormItemGi>
        </NGrid>
      </NForm>
    </NSpin>
    <template #footer><NSpace justify="end"><NButton :disabled="memberSubmitting" @click="memberVisible = false">取消</NButton><NButton type="primary" :loading="memberSubmitting" :disabled="memberLoading" @click="submitMember">保存</NButton></NSpace></template>
  </NModal>

  <NModal v-model:show="profileVisible" preset="card" title="创建权限包草稿" class="form-modal" style="width: min(780px, calc(100vw - 32px))">
    <NForm ref="profileFormRef" :model="profileForm" :rules="profileRules" label-placement="top">
      <NGrid :cols="2" :x-gap="16"><NFormItemGi label="权限包标识" path="profileKey"><NInput v-model:value="profileForm.profileKey" placeholder="developer.basic" /></NFormItemGi><NFormItemGi label="名称" path="name"><NInput v-model:value="profileForm.name" /></NFormItemGi><NFormItemGi span="2" label="描述"><NInput v-model:value="profileForm.description" type="textarea" /></NFormItemGi></NGrid>
      <div class="section-heading"><h3>显式规则</h3><NButton secondary size="small" @click="addProfileRule(profileForm)"><template #icon><SvgIcon icon="lucide:plus" /></template>添加规则</NButton></div>
      <div v-for="(entry, index) in profileForm.entries" :key="index" class="permission-rule-editor">
        <NGrid :cols="2" :x-gap="16">
          <NFormItemGi label="资源类型"><NSelect v-model:value="entry.resourceType" :options="permissionResourceOptions" filterable @update:value="resetPermissionAction(entry)" /></NFormItemGi>
          <NFormItemGi label="资源 ID（可选）"><NInput v-model:value="entry.resourceId" /></NFormItemGi>
          <NFormItemGi label="资源 Key"><NInput v-model:value="entry.resourceKey" placeholder="*" /></NFormItemGi>
          <NFormItemGi label="动作"><NSelect v-model:value="entry.action" :options="permissionActionOptions(entry.resourceType)" /></NFormItemGi>
          <NFormItemGi label="效果"><NRadioGroup v-model:value="entry.effect"><NRadioButton value="allow">允许</NRadioButton><NRadioButton value="deny">拒绝</NRadioButton><NRadioButton value="approval_required">需审批</NRadioButton></NRadioGroup></NFormItemGi>
          <NFormItemGi label="原因"><NInput v-model:value="entry.reason" /></NFormItemGi>
        </NGrid>
        <NButton v-if="profileForm.entries.length > 1" quaternary circle title="删除规则" @click="removeProfileRule(profileForm, index)"><template #icon><SvgIcon icon="lucide:trash-2" /></template></NButton>
      </div>
      <NAlert type="warning" :bordered="false">显式 deny 始终覆盖 allow 与 approval_required；未配置规则默认拒绝。</NAlert>
    </NForm>
    <template #footer><NSpace justify="end"><NButton @click="profileVisible = false">取消</NButton><NButton type="primary" :loading="profileSubmitting" @click="submitProfile">创建草稿</NButton></NSpace></template>
  </NModal>

  <NModal v-model:show="versionVisible" preset="card" :title="`创建 ${activeProfile?.profileKey || '权限包'} 新版本`" class="version-modal" style="width: min(880px, calc(100vw - 32px))">
    <NForm label-placement="top">
      <NFormItem label="版本名称" required><NInput v-model:value="versionForm.name" maxlength="128" /></NFormItem>
      <NFormItem label="版本说明"><NInput v-model:value="versionForm.description" type="textarea" /></NFormItem>
      <div class="section-heading"><h3>规则</h3><NButton secondary size="small" @click="addProfileRule(versionForm)"><template #icon><SvgIcon icon="lucide:plus" /></template>添加规则</NButton></div>
      <div v-for="(entry, index) in versionForm.entries" :key="index" class="permission-rule-editor">
        <NGrid :cols="2" :x-gap="16">
          <NFormItemGi label="资源类型"><NSelect v-model:value="entry.resourceType" :options="permissionResourceOptions" filterable @update:value="resetPermissionAction(entry)" /></NFormItemGi>
          <NFormItemGi label="资源 ID"><NInput v-model:value="entry.resourceId" /></NFormItemGi>
          <NFormItemGi label="资源 Key"><NInput v-model:value="entry.resourceKey" /></NFormItemGi>
          <NFormItemGi label="动作"><NSelect v-model:value="entry.action" :options="permissionActionOptions(entry.resourceType)" /></NFormItemGi>
          <NFormItemGi label="效果"><NRadioGroup v-model:value="entry.effect"><NRadioButton value="allow">允许</NRadioButton><NRadioButton value="deny">拒绝</NRadioButton><NRadioButton value="approval_required">需审批</NRadioButton></NRadioGroup></NFormItemGi>
          <NFormItemGi label="原因"><NInput v-model:value="entry.reason" /></NFormItemGi>
        </NGrid>
        <NButton v-if="versionForm.entries.length > 1" quaternary circle title="删除规则" @click="removeProfileRule(versionForm, index)"><template #icon><SvgIcon icon="lucide:trash-2" /></template></NButton>
      </div>
    </NForm>
    <template #footer><NSpace justify="end"><NButton @click="versionVisible = false">取消</NButton><NButton type="primary" :loading="versionSubmitting" @click="submitProfileVersion">创建草稿版本</NButton></NSpace></template>
  </NModal>

  <NDrawer v-model:show="permissionVisible" width="min(980px, calc(100vw - 16px))" placement="right">
    <NDrawerContent :title="`${activeUser?.nickName || activeUser?.userName || ''} · 权限`" closable>
      <NSpin :show="permissionLoading">
        <NAlert type="info" :bordered="false" class="permission-alert">基础权限、个人覆盖和临时授权分层展示。参考复制不会复制秘密、临时授权、超级管理员或私人数据。</NAlert>
        <section class="permission-section">
          <h3>基础权限包</h3>
          <NSpace align="center"><NSelect v-model:value="selectedProfileId" :options="profileOptions" filterable class="profile-select" /><NButton type="primary" :disabled="!selectedProfileId" @click="bindProfile">绑定</NButton></NSpace>
        </section>
        <section class="permission-section">
          <div class="permission-title"><h3>权限摘要</h3><NSpace><NButton size="small" secondary @click="openRule('override')">新增个人覆盖</NButton><NButton size="small" secondary @click="openRule('temporary')">新增临时授权</NButton></NSpace></div>
          <NTabs type="segment">
            <NTabPane name="base" :tab="`基础规则 (${permissionSummary?.baseRules.length || 0})`"><NDataTable :columns="ruleColumns" :data="permissionSummary?.baseRules || []" :scroll-x="850" :row-key="row => row.id" /></NTabPane>
            <NTabPane name="override" :tab="`个人覆盖 (${permissionSummary?.overrides.length || 0})`"><NDataTable :columns="overrideColumns" :data="permissionSummary?.overrides || []" :scroll-x="940" :row-key="row => row.id" /></NTabPane>
            <NTabPane name="temporary" :tab="`临时授权 (${permissionSummary?.temporaryGrants.length || 0})`"><NDataTable :columns="temporaryColumns" :data="permissionSummary?.temporaryGrants || []" :scroll-x="940" :row-key="row => row.id" /></NTabPane>
          </NTabs>
        </section>
        <section class="permission-section">
          <h3>参考用户复制</h3>
          <NSpace align="center" wrap>
            <NSelect v-model:value="sourceUserId" :options="sourceUserOptions" filterable clearable placeholder="搜索姓名或账号" class="source-user-input" @update:value="permissionDiff = null" />
            <NSelect v-model:value="copyMode" :options="copyModeOptions" class="copy-mode-select" />
            <NButton secondary :disabled="!sourceUserId" @click="previewDiff">预览差异</NButton>
            <NButton type="primary" :disabled="!canCopyPermissions" :loading="copySubmitting" @click="copyPermissions">{{ copyButtonLabel }}</NButton>
          </NSpace>
          <p class="copy-mode-hint">{{ copyModeDescription }}</p>
          <NGrid v-if="copyRequiresTemplate" :cols="2" :x-gap="16" class="copy-template-grid">
            <NFormItemGi label="模板标识" required><NInput v-model:value="copyTemplateKey" maxlength="128" placeholder="例如 developer.basic" /></NFormItemGi>
            <NFormItemGi label="模板名称" required><NInput v-model:value="copyTemplateName" maxlength="128" placeholder="例如 开发人员基础权限" /></NFormItemGi>
          </NGrid>
          <NDataTable v-if="permissionDiff" class="diff-table" :columns="diffColumns" :data="diffRows" :scroll-x="650" :row-key="row => `${row.diffType}:${row.id}`" />
        </section>
      </NSpin>
    </NDrawerContent>
  </NDrawer>

  <NModal v-model:show="ruleVisible" preset="card" :title="ruleMode === 'override' ? '新增个人覆盖项' : '新增临时授权'" class="form-modal" style="width: min(780px, calc(100vw - 32px))">
    <NForm label-placement="top"><NGrid :cols="2" :x-gap="16"><NFormItemGi label="资源类型"><NSelect v-model:value="ruleForm.resourceType" :options="permissionResourceOptions" filterable @update:value="resetPermissionAction(ruleForm)" /></NFormItemGi><NFormItemGi label="资源 ID（可选）"><NInput v-model:value="ruleForm.resourceId" /></NFormItemGi><NFormItemGi label="资源 Key"><NInput v-model:value="ruleForm.resourceKey" /></NFormItemGi><NFormItemGi label="动作"><NSelect v-model:value="ruleForm.action" :options="permissionActionOptions(ruleForm.resourceType)" /></NFormItemGi><NFormItemGi label="效果"><NSelect v-model:value="ruleForm.effect" :options="[{ label: '允许', value: 'allow' }, { label: '拒绝', value: 'deny' }, { label: '需审批', value: 'approval_required' }]" /></NFormItemGi><NFormItemGi v-if="ruleMode === 'temporary'" label="到期时间"><NInput v-model:value="ruleForm.expiresAt" placeholder="2026-08-16T18:00:00" /></NFormItemGi><NFormItemGi span="2" label="原因"><NInput v-model:value="ruleForm.reason" maxlength="1000" /></NFormItemGi></NGrid></NForm>
    <template #footer><NSpace justify="end"><NButton @click="ruleVisible = false">取消</NButton><NButton type="primary" :loading="ruleSubmitting" @click="submitRule">保存</NButton></NSpace></template>
  </NModal>
  </div>
</template>

<style scoped lang="scss">
.page-container { padding: 16px; }
.page-header, .tab-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.page-header { margin-bottom: 16px; }
.page-header h2 { margin: 0 0 4px; font-size: 20px; }
.page-header p { margin: 0; color: var(--n-text-color-3); }
.tab-toolbar { margin-bottom: 16px; }
.permission-rule-editor { position: relative; margin: 12px 0; padding: 12px 44px 4px 12px; border: 1px solid var(--n-border-color); }
.permission-rule-editor > .n-button { position: absolute; top: 8px; right: 8px; }
.search-input { width: min(360px, 100%); }
.role-alert, .permission-alert { margin-bottom: 16px; }
.permission-section { padding: 18px 0; border-bottom: 1px solid var(--n-border-color); }
.permission-section h3 { margin: 0 0 12px; font-size: 15px; }
.permission-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.profile-select { width: min(460px, 70vw); }
.source-user-input { width: min(360px, 75vw); }
.copy-mode-select { width: min(320px, 75vw); }
.copy-mode-hint { margin: 10px 0 0; color: var(--n-text-color-3); font-size: 13px; }
.copy-template-grid { margin-top: 12px; }
.diff-table { margin-top: 14px; }
.quota-card { min-height: 260px; }
:deep(.primary-cell) { display: flex; min-width: 0; flex-direction: column; gap: 4px; }
:deep(.primary-cell span) { overflow: hidden; color: var(--n-text-color-3); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 640px) { .page-header, .tab-toolbar { align-items: stretch; flex-direction: column; } }
</style>
