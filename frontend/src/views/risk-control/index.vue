<script setup lang="ts">
import { computed, h, onMounted, reactive, ref, watch } from 'vue';
import dayjs from 'dayjs';
import type { DataTableColumns, FormInst, FormRules, SelectOption } from 'naive-ui';
import { NButton, NDataTable, NSpace, NTag, useDialog } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import AuditConsole from './audit-console.vue';
import { useAuthStore } from '@/store/modules/auth';
import {
  decideApproval,
  createRiskPolicy,
  deletePortalInboxNotification,
  deleteReadPortalInboxNotifications,
  deleteRiskPolicy,
  fetchApprovals,
  fetchAuditEvents,
  fetchNotificationUnreadCount,
  fetchNotifications,
  fetchRiskPolicies,
  markAllNotificationsRead,
  markNotificationRead,
  updateRiskPolicy,
  updateRiskPolicyStatus
} from '@/service/api';
import type {
  ApprovalStatus,
  ApprovalView,
  AuditDecision,
  AuditEventView,
  NotificationCategory,
  NotificationView,
  RiskDisposition,
  RiskLevel,
  RiskPolicyStatus,
  RiskPolicyView,
  SaveRiskPolicyPayload
} from '@/service/api';

const authStore = useAuthStore();
const dialog = useDialog();
const canReview = computed(() =>
  authStore.userInfo.roles.some(role => ['approval_user', 'platform_admin', 'superadmin'].includes(role))
);
const canAudit = computed(() => authStore.userInfo.roles.some(role => ['platform_admin', 'superadmin'].includes(role)));
const activeTab = ref(canReview.value ? 'approval' : 'notification');

const approvalLoading = ref(false);
const approvals = ref<ApprovalView[]>([]);
const approvalStatus = ref<ApprovalStatus>('pending');
const approvalStatusOptions: SelectOption[] = [
  { label: '待审批', value: 'pending' },
  { label: '已批准', value: 'approved' },
  { label: '已拒绝', value: 'rejected' },
  { label: '已撤销', value: 'revoked' },
  { label: '已过期', value: 'expired' }
];

const decisionVisible = ref(false);
const decisionSubmitting = ref(false);
const decisionTarget = ref<ApprovalView | null>(null);
const decisionType = ref<'approve' | 'reject'>('approve');
const decisionComment = ref('');

const auditLoading = ref(false);
const auditEvents = ref<AuditEventView[]>([]);
const auditHasMore = ref(false);
const auditFilters = reactive({
  actorType: null as string | null,
  actorId: '',
  action: '',
  resourceType: '',
  decision: null as string | null
});
const actorTypeOptions: SelectOption[] = [
  { label: '用户', value: 'user' },
  { label: '服务账号', value: 'service_account' },
  { label: '应用', value: 'application' },
  { label: '智能体', value: 'agent' },
  { label: '系统', value: 'system' }
];
const decisionOptions: SelectOption[] = [
  { label: '允许', value: 'allow' },
  { label: '拒绝', value: 'deny' },
  { label: '需要审批', value: 'approval_required' },
  { label: '成功', value: 'success' },
  { label: '失败', value: 'failure' }
];

const notificationLoading = ref(false);
const notificationMutationError = ref('');
const deletingNotificationId = ref<string | null>(null);
const clearingReadNotifications = ref(false);
const notifications = ref<NotificationView[]>([]);
const unreadCount = ref(0);
const notificationCategory = ref<NotificationCategory | null>(null);
const unreadOnly = ref(false);
const notificationCategoryOptions: SelectOption[] = [
  { label: '任务', value: 'task' },
  { label: '审批', value: 'approval' },
  { label: '运行', value: 'run' },
  { label: '制品', value: 'artifact' },
  { label: '验收', value: 'acceptance' },
  { label: '系统', value: 'system' }
];
const notificationCategoryMeta: Record<NotificationCategory, { label: string; meaning: string }> = {
  task: { label: '任务动态', meaning: '任务分配、状态变化与到期提醒' },
  approval: { label: '审批提醒', meaning: '待审批事项与审批结果' },
  run: { label: '运行状态', meaning: '智能体运行启动、完成或异常' },
  artifact: { label: '交付制品', meaning: '制品生成、更新与交付提醒' },
  acceptance: { label: '验收结果', meaning: '待验收、通过或驳回结果' },
  system: { label: '系统通知', meaning: '平台配置、安全与维护消息' }
};

const policyLoading = ref(false);
const policies = ref<RiskPolicyView[]>([]);
const policyFilters = reactive({
  search: '',
  resourceType: null as string | null,
  riskLevel: null as RiskLevel | null,
  status: null as RiskPolicyStatus | null
});
const policyVisible = ref(false);
const policySubmitting = ref(false);
const policyFormRef = ref<FormInst | null>(null);
const editingPolicy = ref<RiskPolicyView | null>(null);
const policyForm = reactive<SaveRiskPolicyPayload>({
  policyKey: '',
  name: '',
  resourceType: 'tool',
  action: 'invoke',
  riskLevel: 'R2',
  disposition: 'approval_required',
  approvalRole: 'approval_user',
  notifyEnabled: true,
  priority: 100,
  description: '',
  status: 'active'
});
const resourceTypeOptions: SelectOption[] = [
  { label: '智能体', value: 'agent' },
  { label: '任务', value: 'task' },
  { label: '工具', value: 'tool' },
  { label: '模型', value: 'model' },
  { label: '知识库', value: 'knowledge_base' },
  { label: '数据集', value: 'dataset' },
  { label: '数据源', value: 'data_source' },
  { label: '连接器', value: 'connector' },
  { label: '自动化', value: 'automation' },
  { label: '开放接口', value: 'api' }
];
const actionOptions: SelectOption[] = [
  { label: '读取', value: 'read' },
  { label: '调用', value: 'invoke' },
  { label: '执行', value: 'execute' },
  { label: '创建', value: 'create' },
  { label: '更新', value: 'update' },
  { label: '发布', value: 'publish' },
  { label: '导出', value: 'export' },
  { label: '删除', value: 'delete' },
  { label: '管理', value: 'manage' },
  { label: '全部动作', value: '*' }
];
const riskLevelOptions: SelectOption[] = [
  { label: 'R0 · 低风险', value: 'R0' },
  { label: 'R1 · 一般风险', value: 'R1' },
  { label: 'R2 · 高风险', value: 'R2' },
  { label: 'R3 · 严重风险', value: 'R3' }
];
const dispositionOptions: SelectOption[] = [
  { label: '直接允许', value: 'allow' },
  { label: '需要审批', value: 'approval_required' },
  { label: '直接拒绝', value: 'deny' }
];
const approvalRoleOptions: SelectOption[] = [
  { label: '审批人员', value: 'approval_user' },
  { label: '平台管理员', value: 'platform_admin' }
];
const policyStatusOptions: SelectOption[] = [
  { label: '已启用', value: 'active' },
  { label: '已停用', value: 'disabled' }
];
const policyRules: FormRules = {
  policyKey: [
    { required: true, message: '请输入策略标识', trigger: ['input', 'blur'] },
    { pattern: /^[a-z][a-z0-9._-]{0,127}$/, message: '使用小写字母开头，可包含数字、点、下划线和短横线', trigger: ['input', 'blur'] }
  ],
  name: [{ required: true, message: '请输入策略名称', trigger: ['input', 'blur'] }],
  resourceType: [{ required: true, message: '请选择适用资源', trigger: ['change', 'blur'] }],
  action: [{ required: true, message: '请选择动作', trigger: ['change', 'blur'] }],
  approvalRole: [{ required: true, message: '请选择审批角色', trigger: ['change', 'blur'] }]
};

function formatTime(value: string | null) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-';
}

function riskType(level: ApprovalView['riskLevel']) {
  return ({ R0: 'success', R1: 'info', R2: 'warning', R3: 'error' } as const)[level];
}

function notificationLevelText(level: NotificationView['level']) {
  return { info: '信息', success: '成功', warning: '提醒', error: '严重' }[level];
}

function resourceTypeText(value: string) {
  return resourceTypeOptions.find(option => option.value === value)?.label || value;
}

function actionText(value: string) {
  return actionOptions.find(option => option.value === value)?.label || value;
}

function dispositionText(value: RiskDisposition) {
  return { allow: '直接允许', approval_required: '需要审批', deny: '直接拒绝' }[value];
}

function dispositionType(value: RiskDisposition) {
  return ({ allow: 'success', approval_required: 'warning', deny: 'error' } as const)[value];
}

function statusType(status: ApprovalStatus) {
  return (
    {
      pending: 'warning',
      approved: 'success',
      rejected: 'error',
      revoked: 'default',
      expired: 'default'
    } as const
  )[status];
}

function statusText(status: ApprovalStatus) {
  return {
    pending: '待审批',
    approved: '已批准',
    rejected: '已拒绝',
    revoked: '已撤销',
    expired: '已过期'
  }[status];
}

function decisionTypeFor(value: AuditDecision) {
  if (value === 'allow' || value === 'success') return 'success';
  if (value === 'deny' || value === 'failure') return 'error';
  return 'warning';
}

function decisionText(value: AuditDecision) {
  return {
    allow: '允许',
    deny: '拒绝',
    approval_required: '需要审批',
    success: '成功',
    failure: '失败'
  }[value];
}

function icon(name: string) {
  return () => h(SvgIcon, { icon: name, class: 'text-16px' });
}

const approvalColumns: DataTableColumns<ApprovalView> = [
  {
    title: '审批事项',
    key: 'actionSummary',
    minWidth: 250,
    render: row =>
      h('div', { class: 'summary-cell' }, [
        h('strong', row.actionSummary),
        h('span', row.impactScope || '当前任务运行')
      ])
  },
  {
    title: '风险',
    key: 'riskLevel',
    width: 90,
    render: row => h(NTag, { type: riskType(row.riskLevel), size: 'small', bordered: false }, () => row.riskLevel)
  },
  {
    title: '任务 / 运行',
    key: 'taskId',
    width: 180,
    render: row => `#${row.taskId} / #${row.runId}`
  },
  { title: '申请人', key: 'requestedBy', width: 110, render: row => `#${row.requestedBy}` },
  { title: '申请时间', key: 'createdAt', width: 170, render: row => formatTime(row.createdAt) },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: row => h(NTag, { type: statusType(row.status), size: 'small' }, () => statusText(row.status))
  },
  {
    title: '操作',
    key: 'actions',
    width: 190,
    fixed: 'right',
    render: row =>
      row.status === 'pending'
        ? h(NSpace, { size: 8 }, () => [
            h(
              NButton,
              {
                size: 'small',
                type: 'success',
                secondary: true,
                renderIcon: icon('lucide:check'),
                onClick: () => openDecision(row, 'approve')
              },
              () => '批准'
            ),
            h(
              NButton,
              {
                size: 'small',
                type: 'error',
                secondary: true,
                renderIcon: icon('lucide:x'),
                onClick: () => openDecision(row, 'reject')
              },
              () => '拒绝'
            )
          ])
        : '-'
  }
];

const auditColumns: DataTableColumns<AuditEventView> = [
  {
    title: '主体',
    key: 'actorType',
    width: 170,
    render: row => `${row.actorType}${row.actorId ? ` #${row.actorId}` : ''}`
  },
  { title: '操作', key: 'action', width: 140 },
  {
    title: '资源',
    key: 'resourceType',
    minWidth: 180,
    render: row => (row.resourceType ? `${row.resourceType}${row.resourceId ? ` #${row.resourceId}` : ''}` : '-')
  },
  {
    title: '任务 / 运行',
    key: 'taskId',
    width: 190,
    render: row => `${row.taskId ? `#${row.taskId}` : '-'} / ${row.runId ? `#${row.runId}` : '-'}`
  },
  {
    title: '决策',
    key: 'decision',
    width: 110,
    render: row => h(NTag, { type: decisionTypeFor(row.decision), size: 'small' }, () => decisionText(row.decision))
  },
  { title: '原因', key: 'decisionReason', minWidth: 260, ellipsis: { tooltip: true } },
  { title: '时间', key: 'createdAt', width: 170, render: row => formatTime(row.createdAt) }
];

const policyColumns: DataTableColumns<RiskPolicyView> = [
  {
    title: '策略',
    key: 'name',
    minWidth: 230,
    render: row => h('div', { class: 'summary-cell' }, [h('strong', row.name), h('span', row.policyKey)])
  },
  {
    title: '匹配范围',
    key: 'resourceType',
    minWidth: 190,
    render: row => `${resourceTypeText(row.resourceType)} · ${actionText(row.action)}`
  },
  {
    title: '风险',
    key: 'riskLevel',
    width: 90,
    render: row => h(NTag, { type: riskType(row.riskLevel), size: 'small', bordered: false }, () => row.riskLevel)
  },
  {
    title: '处置',
    key: 'disposition',
    width: 115,
    render: row => h(NTag, { type: dispositionType(row.disposition), size: 'small' }, () => dispositionText(row.disposition))
  },
  { title: '审批角色', key: 'approvalRole', width: 120, render: row => row.approvalRole === 'approval_user' ? '审批人员' : row.approvalRole === 'platform_admin' ? '平台管理员' : '-' },
  { title: '通知', key: 'notifyEnabled', width: 75, render: row => row.notifyEnabled ? '开启' : '关闭' },
  { title: '优先级', key: 'priority', width: 85 },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render: row => h(NTag, { type: row.status === 'active' ? 'success' : 'default', size: 'small' }, () => row.status === 'active' ? '已启用' : '已停用')
  },
  {
    title: '操作',
    key: 'actions',
    width: 245,
    fixed: 'right',
    render: row => h(NSpace, { size: 6 }, () => [
      h(NButton, { size: 'small', quaternary: true, onClick: () => openPolicy(row) }, () => '编辑'),
      h(
        NButton,
        {
          size: 'small',
          secondary: true,
          type: row.status === 'active' ? 'warning' : 'success',
          onClick: () => togglePolicy(row)
        },
        () => row.status === 'active' ? '停用' : '启用'
      ),
      h(NButton, { size: 'small', quaternary: true, type: 'error', onClick: () => confirmDeletePolicy(row) }, () => '删除')
    ])
  }
];

async function loadApprovals() {
  if (!canReview.value) return;
  approvalLoading.value = true;
  const { data, error } = await fetchApprovals(approvalStatus.value);
  if (!error) approvals.value = data;
  approvalLoading.value = false;
}

function openDecision(row: ApprovalView, type: 'approve' | 'reject') {
  decisionTarget.value = row;
  decisionType.value = type;
  decisionComment.value = '';
  decisionVisible.value = true;
}

async function submitDecision() {
  if (!decisionTarget.value) return;
  decisionSubmitting.value = true;
  const target = decisionTarget.value;
  const { error } = await decideApproval(
    target.id,
    decisionType.value,
    `risk-ui:${authStore.userInfo.userId}:${target.id}:${decisionType.value}`,
    decisionComment.value
  );
  if (!error) {
    window.$message?.success(decisionType.value === 'approve' ? '审批已批准' : '审批已拒绝');
    decisionVisible.value = false;
    await loadApprovals();
  }
  decisionSubmitting.value = false;
}

async function loadAudit(append = false) {
  if (!canAudit.value) return;
  auditLoading.value = true;
  const beforeId = append ? auditEvents.value.at(-1)?.id : undefined;
  const { data, error } = await fetchAuditEvents({
    actorType: auditFilters.actorType || undefined,
    actorId: auditFilters.actorId.trim() || undefined,
    action: auditFilters.action.trim() || undefined,
    resourceType: auditFilters.resourceType.trim() || undefined,
    decision: auditFilters.decision || undefined,
    beforeId,
    limit: 100
  });
  if (!error) {
    auditEvents.value = append ? [...auditEvents.value, ...data] : data;
    auditHasMore.value = data.length === 100;
  }
  auditLoading.value = false;
}

function resetAudit() {
  Object.assign(auditFilters, { actorType: null, actorId: '', action: '', resourceType: '', decision: null });
  loadAudit();
}

async function loadNotifications() {
  notificationLoading.value = true;
  notificationMutationError.value = '';
  const [listResult, countResult] = await Promise.all([
    fetchNotifications(notificationCategory.value || undefined, unreadOnly.value),
    fetchNotificationUnreadCount()
  ]);
  if (!listResult.error) notifications.value = listResult.data;
  if (!countResult.error) unreadCount.value = countResult.data;
  notificationLoading.value = false;
}

async function readNotification(notification: NotificationView) {
  if (notification.readAt) return;
  const { error } = await markNotificationRead(notification.id);
  if (!error) await loadNotifications();
}

async function readAllNotifications() {
  const { error } = await markAllNotificationsRead();
  if (!error) await loadNotifications();
}

function notificationRequestError(error: unknown, fallback: string) {
  const response = (error as { response?: { data?: { message?: string; msg?: string } } } | null)?.response;
  return response?.data?.message || response?.data?.msg || fallback;
}

function confirmDeleteNotification(notification: NotificationView) {
  dialog.warning({
    title: '删除通知',
    content: `确认删除“${notification.title}”？删除后无法恢复。`,
    positiveText: '删除',
    negativeText: '取消',
    async onPositiveClick() {
      deletingNotificationId.value = notification.id;
      notificationMutationError.value = '';
      const result = await deletePortalInboxNotification(notification.id);
      if (result.error) {
        notificationMutationError.value = notificationRequestError(result.error, '通知删除失败');
      } else {
        window.$message?.success('通知已删除');
        await loadNotifications();
      }
      deletingNotificationId.value = null;
    }
  });
}

function confirmClearReadNotifications() {
  dialog.warning({
    title: '清理已读通知',
    content: '确认删除当前账号的全部已读通知？未读通知会保留。',
    positiveText: '清理',
    negativeText: '取消',
    async onPositiveClick() {
      clearingReadNotifications.value = true;
      notificationMutationError.value = '';
      const result = await deleteReadPortalInboxNotifications();
      if (result.error) {
        notificationMutationError.value = notificationRequestError(result.error, '已读通知清理失败');
      } else {
        window.$message?.success(`已清理 ${result.data.deleted} 条已读通知`);
        await loadNotifications();
      }
      clearingReadNotifications.value = false;
    }
  });
}

async function loadPolicies() {
  if (!canAudit.value) return;
  policyLoading.value = true;
  const { data, error } = await fetchRiskPolicies({
    resourceType: policyFilters.resourceType || undefined,
    riskLevel: policyFilters.riskLevel || undefined,
    status: policyFilters.status || undefined,
    search: policyFilters.search.trim() || undefined
  });
  if (!error) policies.value = data;
  policyLoading.value = false;
}

function resetPolicyFilters() {
  Object.assign(policyFilters, { search: '', resourceType: null, riskLevel: null, status: null });
  loadPolicies();
}

function openPolicy(policy?: RiskPolicyView) {
  editingPolicy.value = policy || null;
  Object.assign(policyForm, policy ? {
    policyKey: policy.policyKey,
    name: policy.name,
    resourceType: policy.resourceType,
    action: policy.action,
    riskLevel: policy.riskLevel,
    disposition: policy.disposition,
    approvalRole: policy.approvalRole || 'approval_user',
    notifyEnabled: policy.notifyEnabled,
    priority: policy.priority,
    description: policy.description || '',
    status: policy.status
  } : {
    policyKey: '', name: '', resourceType: 'tool', action: 'invoke', riskLevel: 'R2',
    disposition: 'approval_required', approvalRole: 'approval_user', notifyEnabled: true,
    priority: 100, description: '', status: 'active'
  });
  policyVisible.value = true;
}

async function savePolicy() {
  if (!await policyFormRef.value?.validate().then(() => true).catch(() => false)) return;
  policySubmitting.value = true;
  const payload: SaveRiskPolicyPayload = {
    ...policyForm,
    approvalRole: policyForm.disposition === 'approval_required' ? policyForm.approvalRole : undefined,
    description: policyForm.description?.trim() || undefined
  };
  const result = editingPolicy.value
    ? await updateRiskPolicy(editingPolicy.value.id, payload)
    : await createRiskPolicy(payload);
  if (!result.error) {
    window.$message?.success(editingPolicy.value ? '风险策略已更新' : '风险策略已创建');
    policyVisible.value = false;
    await loadPolicies();
  }
  policySubmitting.value = false;
}

async function togglePolicy(policy: RiskPolicyView) {
  const status: RiskPolicyStatus = policy.status === 'active' ? 'disabled' : 'active';
  const { error } = await updateRiskPolicyStatus(policy.id, status);
  if (!error) {
    window.$message?.success(status === 'active' ? '风险策略已启用' : '风险策略已停用');
    await loadPolicies();
  }
}

function confirmDeletePolicy(policy: RiskPolicyView) {
  dialog.warning({
    title: '删除风险策略',
    content: `确认删除“${policy.name}”吗？删除后不再参与风险判定。`,
    positiveText: '删除',
    negativeText: '取消',
    async onPositiveClick() {
      const { error } = await deleteRiskPolicy(policy.id);
      if (!error) {
        window.$message?.success('风险策略已删除');
        await loadPolicies();
      }
    }
  });
}

watch(approvalStatus, loadApprovals);
watch([notificationCategory, unreadOnly], loadNotifications);
watch(activeTab, value => {
  if (value === 'audit' && auditEvents.value.length === 0) loadAudit();
  if (value === 'notification' && notifications.value.length === 0) loadNotifications();
  if (value === 'policy' && policies.value.length === 0) loadPolicies();
});

onMounted(() => {
  loadApprovals();
  loadNotifications();
});
</script>

<template>
  <div class="risk-control-container">
    <header class="page-header">
      <div>
        <h2 class="page-title">{{ $t('page.riskControl.title') }}</h2>
        <p class="page-desc">{{ $t('page.riskControl.desc') }}</p>
      </div>
      <NBadge :value="unreadCount" :max="99" :show-zero="false">
        <NButton quaternary circle title="通知收件箱" @click="activeTab = 'notification'">
          <template #icon><SvgIcon icon="lucide:bell" /></template>
        </NButton>
      </NBadge>
    </header>

    <NTabs v-model:value="activeTab" type="line" animated>
      <NTabPane name="approval" tab="审批工作台">
        <NAlert v-if="!canReview" type="warning" :show-icon="true">当前账号没有审批查看权限。</NAlert>
        <template v-else>
          <div class="toolbar">
            <NSelect v-model:value="approvalStatus" :options="approvalStatusOptions" class="control-sm" />
            <NButton :loading="approvalLoading" secondary @click="loadApprovals">
              <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
              刷新
            </NButton>
          </div>
          <NDataTable
            :columns="approvalColumns"
            :data="approvals"
            :loading="approvalLoading"
            :row-key="row => row.id"
            :scroll-x="1280"
            :max-height="560"
          />
        </template>
      </NTabPane>

      <NTabPane name="audit" tab="审计日志">
        <NAlert v-if="!canAudit" type="warning" :show-icon="true">审计检索仅对平台管理员开放。</NAlert>
        <AuditConsole v-else />
      </NTabPane>

      <NTabPane name="notification" tab="通知收件箱">
        <div class="toolbar">
          <NSelect
            v-model:value="notificationCategory"
            clearable
            placeholder="全部类别"
            :options="notificationCategoryOptions"
            class="control-sm"
          />
          <NCheckbox v-model:checked="unreadOnly">仅未读</NCheckbox>
          <NButton :disabled="unreadCount === 0" secondary @click="readAllNotifications">
            <template #icon><SvgIcon icon="lucide:check-check" /></template>
            全部已读
          </NButton>
          <NButton secondary :loading="clearingReadNotifications" @click="confirmClearReadNotifications">
            <template #icon><SvgIcon icon="lucide:trash-2" /></template>
            清理已读
          </NButton>
          <NButton :loading="notificationLoading" quaternary @click="loadNotifications">
            <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
            刷新
          </NButton>
        </div>
        <NAlert v-if="notificationMutationError" class="mb-12px" type="error" :show-icon="true">
          {{ notificationMutationError }}
        </NAlert>
        <NList bordered hoverable>
          <NListItem v-for="item in notifications" :key="item.id" @click="readNotification(item)">
            <div class="notification-row" :class="{ unread: !item.readAt }">
              <span class="unread-dot" aria-hidden="true"></span>
              <div class="notification-body">
                <div class="notification-title">
                  <strong>{{ item.title }}</strong>
                  <NSpace :size="6" :wrap="false">
                    <NTag size="small" :bordered="false">{{ notificationCategoryMeta[item.category].label }}</NTag>
                    <NTag size="small" :type="item.level">{{ notificationLevelText(item.level) }}</NTag>
                    <NButton
                      quaternary
                      circle
                      size="small"
                      type="error"
                      title="删除通知"
                      :loading="deletingNotificationId === item.id"
                      :disabled="Boolean(deletingNotificationId)"
                      @click.stop="confirmDeleteNotification(item)"
                    >
                      <template #icon><SvgIcon icon="lucide:trash-2" /></template>
                    </NButton>
                  </NSpace>
                </div>
                <p v-if="item.content">{{ item.content }}</p>
                <span class="notification-meaning">{{ notificationCategoryMeta[item.category].meaning }}</span>
                <span>{{ formatTime(item.createdAt) }}</span>
              </div>
            </div>
          </NListItem>
        </NList>
        <NEmpty v-if="!notificationLoading && notifications.length === 0" description="暂无通知" />
      </NTabPane>

      <NTabPane name="policy" tab="风险策略">
        <NAlert v-if="!canAudit" type="warning" :show-icon="true">风险策略仅对平台管理员开放。</NAlert>
        <template v-else>
          <div class="toolbar policy-toolbar">
            <NInput
              v-model:value="policyFilters.search"
              clearable
              placeholder="搜索名称、标识或说明"
              class="policy-search"
              @keyup.enter="loadPolicies"
            />
            <NSelect
              v-model:value="policyFilters.resourceType"
              clearable
              placeholder="全部资源"
              :options="resourceTypeOptions"
              class="control-sm"
            />
            <NSelect
              v-model:value="policyFilters.riskLevel"
              clearable
              placeholder="全部风险"
              :options="riskLevelOptions"
              class="control-sm"
            />
            <NSelect
              v-model:value="policyFilters.status"
              clearable
              placeholder="全部状态"
              :options="policyStatusOptions"
              class="control-sm"
            />
            <NButton type="primary" :loading="policyLoading" @click="loadPolicies">
              <template #icon><SvgIcon icon="lucide:search" /></template>
              查询
            </NButton>
            <NButton quaternary @click="resetPolicyFilters">重置</NButton>
            <NButton type="primary" class="create-policy" @click="openPolicy()">
              <template #icon><SvgIcon icon="lucide:plus" /></template>
              新建策略
            </NButton>
          </div>
          <NDataTable
            :columns="policyColumns"
            :data="policies"
            :loading="policyLoading"
            :row-key="row => row.id"
            :scroll-x="1260"
            :max-height="540"
          />
        </template>
      </NTabPane>
    </NTabs>

    <NModal
      v-model:show="decisionVisible"
      preset="card"
      :title="decisionType === 'approve' ? '批准审批' : '拒绝审批'"
      class="decision-modal"
      style="width: min(560px, calc(100vw - 32px))"
      :mask-closable="!decisionSubmitting"
    >
      <div v-if="decisionTarget" class="decision-summary">
        <strong>{{ decisionTarget.actionSummary }}</strong>
        <span>任务 #{{ decisionTarget.taskId }} · 运行 #{{ decisionTarget.runId }}</span>
      </div>
      <NInput
        v-model:value="decisionComment"
        type="textarea"
        :maxlength="2000"
        show-count
        :autosize="{ minRows: 3, maxRows: 6 }"
        placeholder="审批意见（可选）"
      />
      <template #footer>
        <div class="modal-actions">
          <NButton :disabled="decisionSubmitting" @click="decisionVisible = false">取消</NButton>
          <NButton
            :type="decisionType === 'approve' ? 'success' : 'error'"
            :loading="decisionSubmitting"
            @click="submitDecision"
          >
            <template #icon>
              <SvgIcon :icon="decisionType === 'approve' ? 'lucide:check' : 'lucide:x'" />
            </template>
            确认{{ decisionType === 'approve' ? '批准' : '拒绝' }}
          </NButton>
        </div>
      </template>
    </NModal>

    <NModal
      v-model:show="policyVisible"
      preset="card"
      :title="editingPolicy ? '编辑风险策略' : '新建风险策略'"
      class="policy-modal"
      style="width: min(760px, calc(100vw - 32px))"
      :mask-closable="!policySubmitting"
    >
      <NForm ref="policyFormRef" :model="policyForm" :rules="policyRules" label-placement="top">
        <div class="policy-form-grid">
          <NFormItem label="策略名称" path="name">
            <NInput v-model:value="policyForm.name" :maxlength="128" placeholder="例如：高风险工具调用审批" />
          </NFormItem>
          <NFormItem label="策略标识" path="policyKey">
            <NInput v-model:value="policyForm.policyKey" :maxlength="128" placeholder="例如：tool.high_risk.approval" />
          </NFormItem>
          <NFormItem label="适用资源" path="resourceType">
            <NSelect v-model:value="policyForm.resourceType" :options="resourceTypeOptions" />
          </NFormItem>
          <NFormItem label="动作" path="action">
            <NSelect v-model:value="policyForm.action" :options="actionOptions" />
          </NFormItem>
          <NFormItem label="风险等级" path="riskLevel">
            <NSelect v-model:value="policyForm.riskLevel" :options="riskLevelOptions" />
          </NFormItem>
          <NFormItem label="处置方式" path="disposition">
            <NSelect v-model:value="policyForm.disposition" :options="dispositionOptions" />
          </NFormItem>
          <NFormItem v-if="policyForm.disposition === 'approval_required'" label="审批角色" path="approvalRole">
            <NSelect v-model:value="policyForm.approvalRole" :options="approvalRoleOptions" />
          </NFormItem>
          <NFormItem label="优先级" path="priority">
            <NInputNumber v-model:value="policyForm.priority" :min="0" :max="9999" class="full-width" />
          </NFormItem>
          <NFormItem label="策略状态" path="status">
            <NSelect v-model:value="policyForm.status" :options="policyStatusOptions" />
          </NFormItem>
          <NFormItem label="发送通知" path="notifyEnabled">
            <div class="switch-field">
              <NSwitch v-model:value="policyForm.notifyEnabled" />
              <span>{{ policyForm.notifyEnabled ? '命中策略后通知相关人员' : '不发送命中通知' }}</span>
            </div>
          </NFormItem>
          <NFormItem label="说明" path="description" class="form-span-2">
            <NInput
              v-model:value="policyForm.description"
              type="textarea"
              :maxlength="500"
              show-count
              :autosize="{ minRows: 3, maxRows: 5 }"
              placeholder="说明适用场景、风险依据和处置要求"
            />
          </NFormItem>
        </div>
      </NForm>
      <template #footer>
        <div class="modal-actions">
          <NButton :disabled="policySubmitting" @click="policyVisible = false">取消</NButton>
          <NButton type="primary" :loading="policySubmitting" @click="savePolicy">
            <template #icon><SvgIcon icon="lucide:save" /></template>
            保存策略
          </NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style scoped lang="scss">
.risk-control-container {
  min-height: 100%;
  padding: 16px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--n-border-color, #e5e7eb);
}

.page-title {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 600;
}

.page-desc {
  margin: 0;
  color: #737373;
  font-size: 14px;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}

.control-sm {
  width: 150px;
}

.control-md {
  width: 190px;
}

.policy-search {
  width: 240px;
}

.create-policy {
  margin-left: auto;
}

.summary-cell {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.summary-cell strong,
.summary-cell span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.summary-cell span {
  color: #737373;
  font-size: 12px;
}

.load-more {
  display: flex;
  justify-content: center;
  padding: 16px 0 4px;
}

.notification-row {
  display: grid;
  grid-template-columns: 8px minmax(0, 1fr);
  gap: 10px;
  width: 100%;
  padding: 4px 0;
  cursor: pointer;
}

.unread-dot {
  width: 7px;
  height: 7px;
  margin-top: 7px;
  border-radius: 50%;
  background: transparent;
}

.notification-row.unread .unread-dot {
  background: #2080f0;
}

.notification-body,
.notification-title {
  display: flex;
  min-width: 0;
}

.notification-body {
  flex-direction: column;
  gap: 5px;
}

.notification-title {
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.notification-body p {
  margin: 0;
  color: #525252;
  line-height: 1.55;
}

.notification-body > span {
  color: #8a8a8a;
  font-size: 12px;
}

.notification-body .notification-meaning {
  color: #737373;
}

.policy-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 18px;
}

.form-span-2 {
  grid-column: 1 / -1;
}

.full-width {
  width: 100%;
}

.switch-field {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 34px;
  color: #737373;
  font-size: 13px;
}

.decision-summary {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-bottom: 14px;
}

.decision-summary span {
  color: #737373;
  font-size: 13px;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

@media (max-width: 720px) {
  .risk-control-container {
    padding: 12px;
  }

  .audit-filters > *,
  .policy-toolbar > *,
  .control-sm,
  .control-md,
  .policy-search {
    width: 100%;
  }

  .create-policy {
    margin-left: 0;
  }

  .policy-form-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .form-span-2 {
    grid-column: auto;
  }

  .notification-title {
    align-items: flex-start;
  }
}
</style>
