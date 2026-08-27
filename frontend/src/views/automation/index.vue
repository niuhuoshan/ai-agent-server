<script setup lang="ts">
import { computed, h, nextTick, onMounted, reactive, ref } from 'vue';
import dayjs from 'dayjs';
import type { DataTableColumns, FormInst, FormRules, SelectOption } from 'naive-ui';
import { NButton, NPopconfirm, NSpace, NTag } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  createAutomationTrigger,
  fetchAutomationTrigger,
  fetchAutomationTriggers,
  fetchServiceAccounts,
  fetchSystemUsers,
  fetchTasks,
  fireAutomationTrigger,
  updateAutomationTrigger
} from '@/service/api';
import type {
  AutomationTriggerView,
  CreateAutomationTriggerPayload,
  ServiceAccountView,
  SystemUserView,
  TaskView
} from '@/service/api';
import {
  automationFormFromTrigger,
  automationStatusLabel,
  automationStatusType,
  buildAutomationStatusPayload,
  buildAutomationUpdatePayload,
  canFireAutomation,
  createEmptyAutomationForm,
  isAutomationRevisionConflict,
  resolveCronExpression
} from './automation-state';
import type { AutomationFormState } from './automation-state';

const loading = ref(false);
const submitting = ref(false);
const actionKey = ref('');
const formVisible = ref(false);
const formRef = ref<FormInst | null>(null);
const editingTrigger = ref<AutomationTriggerView | null>(null);
const triggers = ref<AutomationTriggerView[]>([]);
const serviceAccounts = ref<ServiceAccountView[]>([]);
const tasks = ref<TaskView[]>([]);
const users = ref<SystemUserView[]>([]);
const search = ref('');
const typeFilter = ref<string | null>(null);
const statusFilter = ref<string | null>(null);
const form = reactive<AutomationFormState>(createEmptyAutomationForm());

const rules: FormRules = {
  triggerKey: [
    { required: true, message: '请输入触发器标识', trigger: ['input', 'blur'] },
    { pattern: /^[A-Za-z0-9._:-]+$/, message: '标识只支持字母、数字和 ._:-', trigger: ['input', 'blur'] }
  ],
  name: [{ required: true, message: '请输入自动化名称', trigger: ['input', 'blur'] }],
  taskId: [{ required: true, message: '请选择任务', trigger: ['change', 'blur'] }],
  serviceAccountId: [{ required: true, message: '请选择执行身份', trigger: ['change', 'blur'] }]
};

const triggerTypeOptions: SelectOption[] = [
  { label: '手动触发', value: 'manual' },
  { label: '定时计划', value: 'cron' },
  { label: 'Webhook', value: 'webhook' }
];
const statusOptions: SelectOption[] = [
  { label: '启用', value: 'active' },
  { label: '已暂停', value: 'paused' },
  { label: '异常', value: 'error' },
  { label: '已归档', value: 'archived' }
];
const scheduleOptions: SelectOption[] = [
  { label: '每小时', value: 'hourly' },
  { label: '每天', value: 'daily' },
  { label: '工作日', value: 'weekdays' },
  { label: '每周指定日期', value: 'weekly' },
  { label: '自定义 Cron', value: 'custom' }
];
const weekdayOptions: SelectOption[] = [
  { label: '周一', value: 'MON' }, { label: '周二', value: 'TUE' }, { label: '周三', value: 'WED' },
  { label: '周四', value: 'THU' }, { label: '周五', value: 'FRI' }, { label: '周六', value: 'SAT' },
  { label: '周日', value: 'SUN' }
];
const timezoneOptions: SelectOption[] = [
  { label: '中国标准时间 (Asia/Shanghai)', value: 'Asia/Shanghai' },
  { label: '协调世界时 (UTC)', value: 'UTC' },
  { label: '东京 (Asia/Tokyo)', value: 'Asia/Tokyo' },
  { label: '新加坡 (Asia/Singapore)', value: 'Asia/Singapore' }
];
const misfireOptions: SelectOption[] = [
  { label: '恢复后补执行一次', value: 'fire_once' },
  { label: '跳过错过的计划', value: 'skip' },
  { label: '按上限追赶执行', value: 'catch_up' }
];

const modalTitle = computed(() => {
  if (!editingTrigger.value) return '新建自动化';
  return editingTrigger.value.status === 'archived' ? '自动化详情' : '编辑自动化';
});
const formReadOnly = computed(() => editingTrigger.value?.status === 'archived');
const userNameMap = computed(() => new Map(users.value.map(user => [
  user.userId,
  `${user.nickName || user.userName} (@${user.userName})`
])));
const accountMap = computed(() => new Map(serviceAccounts.value.map(account => [account.id, account])));
const taskMap = computed(() => new Map(tasks.value.map(task => [task.id, task])));
const accountOptions = computed<SelectOption[]>(() => serviceAccounts.value
  .filter(item => item.status === 'active' || item.id === form.serviceAccountId)
  .map(item => ({
    label: `${item.name} · 负责人 ${userNameMap.value.get(item.ownerId) || '未知成员'}`,
    value: item.id
  })));
const taskOptions = computed<SelectOption[]>(() => tasks.value
  .filter(task => task.currentVersionId || task.id === form.taskId)
  .map(task => ({ label: task.title, value: task.id })));
const cronExpression = computed(() => resolveCronExpression(form));
const schedulePreview = computed(() => {
  if (form.scheduleMode === 'custom') return cronExpression.value || '尚未填写 Cron 表达式';
  const time = `${String(form.hour).padStart(2, '0')}:${String(form.minute).padStart(2, '0')}`;
  if (form.scheduleMode === 'hourly') return `每小时第 ${form.minute} 分钟执行`;
  if (form.scheduleMode === 'weekdays') return `每个工作日 ${time} 执行`;
  if (form.scheduleMode === 'weekly') {
    const labels = form.weekdays.map(value => weekdayOptions.find(item => item.value === value)?.label).join('、');
    return `${labels || '周一'} ${time} 执行`;
  }
  return `每天 ${time} 执行`;
});
const filteredTriggers = computed(() => {
  const keyword = search.value.trim().toLowerCase();
  return triggers.value.filter(item => {
    const matchesType = !typeFilter.value || item.triggerType === typeFilter.value;
    const matchesStatus = !statusFilter.value || item.status === statusFilter.value;
    const matchesKeyword = !keyword || `${item.name} ${item.triggerKey}`.toLowerCase().includes(keyword);
    return matchesType && matchesStatus && matchesKeyword;
  });
});

function triggerTypeText(type: AutomationTriggerView['triggerType']) {
  return ({ manual: '手动', cron: '定时', webhook: 'Webhook' } as const)[type];
}

function scheduleText(row: AutomationTriggerView) {
  if (row.triggerType === 'webhook') return `/open/platform/webhooks/${row.triggerKey}`;
  if (row.triggerType === 'cron') return `${row.cronExpression || '-'} · ${row.timezone || 'Asia/Shanghai'}`;
  return '按需手动触发';
}

function formatTime(value: string | null) {
  if (!value) return '-';
  const utcValue = /(?:Z|[+-]\d{2}:\d{2})$/.test(value) ? value : `${value}Z`;
  return dayjs(utcValue).format('YYYY-MM-DD HH:mm');
}

async function loadData() {
  loading.value = true;
  try {
    const [triggerResult, accountResult, taskResult, userResult] = await Promise.all([
      fetchAutomationTriggers(), fetchServiceAccounts(), fetchTasks(200), fetchSystemUsers(undefined, 1, 200)
    ]);
    if (!triggerResult.error) triggers.value = triggerResult.data;
    if (!accountResult.error) serviceAccounts.value = accountResult.data;
    if (!taskResult.error) tasks.value = taskResult.data;
    if (!userResult.error) users.value = userResult.data.rows;
  } finally {
    loading.value = false;
  }
}

function resetForm(value: AutomationFormState) {
  Object.assign(form, value);
  void nextTick(() => formRef.value?.restoreValidation());
}

function openCreate() {
  editingTrigger.value = null;
  resetForm(createEmptyAutomationForm());
  formVisible.value = true;
}

async function openDetail(row: AutomationTriggerView) {
  const key = `detail:${row.id}`;
  actionKey.value = key;
  const { data, error } = await fetchAutomationTrigger(row.id);
  if (!error) {
    editingTrigger.value = data;
    resetForm(automationFormFromTrigger(data));
    formVisible.value = true;
  }
  if (actionKey.value === key) actionKey.value = '';
}

function selectedTaskVersionId() {
  return tasks.value.find(item => item.id === form.taskId)?.currentVersionId || null;
}

async function reloadConflict(triggerId: string) {
  window.$message?.warning('配置已被其他人更新，已重新加载最新内容');
  const [detailResult] = await Promise.all([fetchAutomationTrigger(triggerId), loadData()]);
  if (!detailResult.error && editingTrigger.value?.id === triggerId) {
    editingTrigger.value = detailResult.data;
    resetForm(automationFormFromTrigger(detailResult.data));
  }
}

async function submit() {
  try {
    await formRef.value?.validate();
  } catch {
    return;
  }
  const taskVersionId = selectedTaskVersionId();
  if (!form.taskId || !taskVersionId || !form.serviceAccountId) {
    window.$message?.warning('所选任务需要存在当前已发布版本');
    return;
  }
  if (form.triggerType === 'cron' && !form.inputTemplate.trim()) {
    window.$message?.warning('定时任务需要填写固定执行指令');
    return;
  }
  if (form.triggerType === 'cron' && !cronExpression.value) {
    window.$message?.warning('请填写 Cron 表达式');
    return;
  }

  submitting.value = true;
  try {
    if (editingTrigger.value) {
      const current = editingTrigger.value;
      const { error } = await updateAutomationTrigger(
        current.id,
        buildAutomationUpdatePayload(current, form, taskVersionId)
      );
      if (error) {
        if (isAutomationRevisionConflict(error)) await reloadConflict(current.id);
        return;
      }
      formVisible.value = false;
      window.$message?.success('自动化已更新');
    } else {
      const payload: CreateAutomationTriggerPayload = {
        triggerKey: form.triggerKey.trim(),
        name: form.name.trim(),
        triggerType: form.triggerType,
        taskId: form.taskId,
        taskVersionId,
        serviceAccountId: form.serviceAccountId,
        cronExpression: form.triggerType === 'cron' ? cronExpression.value : undefined,
        timezone: form.triggerType === 'cron' ? form.timezone : undefined,
        misfirePolicy: form.triggerType === 'cron' ? form.misfirePolicy : undefined,
        maxCatchupCount: form.triggerType === 'cron' && form.misfirePolicy === 'catch_up' ? form.maxCatchupCount : 1,
        maxAttempts: form.maxAttempts,
        inputTemplate: form.inputTemplate.trim() || undefined,
        config: {}
      };
      const { error } = await createAutomationTrigger(payload);
      if (error) return;
      formVisible.value = false;
      window.$message?.success('自动化已创建');
    }
    await loadData();
  } finally {
    submitting.value = false;
  }
}

async function changeStatus(row: AutomationTriggerView, status: AutomationTriggerView['status']) {
  const key = `status:${row.id}:${status}`;
  actionKey.value = key;
  try {
    const { error } = await updateAutomationTrigger(row.id, buildAutomationStatusPayload(row, status));
    if (error) {
      if (isAutomationRevisionConflict(error)) await reloadConflict(row.id);
      return;
    }
    const successText = status === 'active' ? '自动化已恢复' : status === 'paused' ? '自动化已暂停' : '自动化已归档';
    window.$message?.success(successText);
    await loadData();
  } finally {
    if (actionKey.value === key) actionKey.value = '';
  }
}

async function fire(row: AutomationTriggerView) {
  if (!canFireAutomation(row)) {
    window.$message?.warning('只有启用中的手动触发器可以立即执行');
    return;
  }
  const key = `fire:${row.id}`;
  actionKey.value = key;
  try {
    const { data, error } = await fireAutomationTrigger(row.id, crypto.randomUUID(), row.inputTemplate || undefined);
    if (!error) {
      window.$message?.success(data.replayed ? `已返回原运行 ${data.runId || ''}` : `已启动运行 ${data.runId || ''}`);
      await loadData();
    }
  } finally {
    if (actionKey.value === key) actionKey.value = '';
  }
}

function iconButton(
  label: string,
  icon: string,
  props: Record<string, unknown>
) {
  return h(NButton, { size: 'small', secondary: true, ...props }, {
    icon: () => h(SvgIcon, { icon }),
    default: () => label
  });
}

function rowActions(row: AutomationTriggerView) {
  const actions = [iconButton(
    row.status === 'archived' ? '查看' : '编辑',
    row.status === 'archived' ? 'lucide:eye' : 'lucide:pencil',
    { loading: actionKey.value === `detail:${row.id}`, onClick: () => openDetail(row) }
  )];
  if (row.triggerType === 'manual') {
    actions.push(iconButton('执行', 'lucide:play', {
      type: 'primary',
      disabled: !canFireAutomation(row),
      loading: actionKey.value === `fire:${row.id}`,
      title: canFireAutomation(row) ? '立即执行' : '仅启用状态可执行',
      onClick: () => fire(row)
    }));
  }
  if (row.status === 'active') {
    actions.push(iconButton('暂停', 'lucide:pause', {
      loading: actionKey.value === `status:${row.id}:paused`,
      onClick: () => changeStatus(row, 'paused')
    }));
  } else if (row.status === 'paused' || row.status === 'error') {
    actions.push(iconButton('恢复', 'lucide:rotate-ccw', {
      loading: actionKey.value === `status:${row.id}:active`,
      onClick: () => changeStatus(row, 'active')
    }));
  }
  if (row.status !== 'archived') {
    actions.push(h(NPopconfirm, {
      positiveText: '归档',
      negativeText: '取消',
      onPositiveClick: () => changeStatus(row, 'archived')
    }, {
      trigger: () => iconButton('归档', 'lucide:archive', {
        loading: actionKey.value === `status:${row.id}:archived`
      }),
      default: () => `归档后“${row.name}”将不能再编辑或执行，确认继续？`
    }));
  }
  return h(NSpace, { size: 6, wrap: false }, () => actions);
}

const columns: DataTableColumns<AutomationTriggerView> = [
  {
    title: '自动化', key: 'name', minWidth: 220,
    render: row => h('div', { class: 'primary-cell' }, [h('strong', row.name), h('span', row.triggerKey)])
  },
  {
    title: '类型', key: 'triggerType', width: 90,
    render: row => h(NTag, { size: 'small', bordered: false }, () => triggerTypeText(row.triggerType))
  },
  { title: '任务', key: 'taskId', minWidth: 170, render: row => taskMap.value.get(row.taskId)?.title || '任务不可见' },
  {
    title: '执行身份', key: 'serviceAccountId', minWidth: 210,
    render: row => {
      const account = accountMap.value.get(row.serviceAccountId);
      return account ? `${account.name} · ${userNameMap.value.get(account.ownerId) || '未知负责人'}` : '服务账号不可见';
    }
  },
  { title: '计划 / 入口', key: 'schedule', minWidth: 260, ellipsis: { tooltip: true }, render: scheduleText },
  {
    title: '状态', key: 'status', width: 100,
    render: row => h(NTag, { size: 'small', type: automationStatusType(row.status) }, () => automationStatusLabel(row.status))
  },
  { title: '上次执行', key: 'lastRunAt', width: 160, render: row => formatTime(row.lastRunAt) },
  { title: '下次执行', key: 'nextRunAt', width: 160, render: row => formatTime(row.nextRunAt) },
  { title: '操作', key: 'actions', width: 330, fixed: 'right', render: rowActions }
];

onMounted(loadData);
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h2>{{ $t('page.automation.title') }}</h2>
        <p>{{ $t('page.automation.desc') }}</p>
      </div>
      <NSpace>
        <NButton secondary :loading="loading" @click="loadData">
          <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
          刷新
        </NButton>
        <NButton type="primary" :disabled="!serviceAccounts.length || !tasks.length" @click="openCreate">
          <template #icon><SvgIcon icon="lucide:plus" /></template>
          新建自动化
        </NButton>
      </NSpace>
    </header>

    <div class="toolbar">
      <NInput v-model:value="search" clearable placeholder="搜索名称或标识" class="search-input">
        <template #prefix><SvgIcon icon="lucide:search" /></template>
      </NInput>
      <NSelect v-model:value="typeFilter" :options="triggerTypeOptions" clearable placeholder="全部类型" class="filter-select" />
      <NSelect v-model:value="statusFilter" :options="statusOptions" clearable placeholder="全部状态" class="filter-select" />
      <span class="result-count">{{ filteredTriggers.length }} 个自动化</span>
    </div>
    <NDataTable
      :columns="columns"
      :data="filteredTriggers"
      :loading="loading"
      :scroll-x="1700"
      :row-key="row => row.id"
    />

    <NModal
      v-model:show="formVisible"
      preset="card"
      :title="modalTitle"
      class="form-modal"
      style="width: min(780px, calc(100vw - 32px))"
      :mask-closable="!submitting"
    >
      <div v-if="editingTrigger" class="detail-meta">
        <NTag size="small" :type="automationStatusType(editingTrigger.status)">
          {{ automationStatusLabel(editingTrigger.status) }}
        </NTag>
        <span>修订 {{ editingTrigger.revisionNo }}</span>
        <span>上次执行：{{ formatTime(editingTrigger.lastRunAt) }}</span>
        <span>下次执行：{{ formatTime(editingTrigger.nextRunAt) }}</span>
      </div>

      <NForm ref="formRef" :model="form" :rules="rules" label-placement="top">
        <NFormItem label="触发方式">
          <NRadioGroup v-model:value="form.triggerType" :disabled="Boolean(editingTrigger) || formReadOnly">
            <NRadioButton v-for="option in triggerTypeOptions" :key="String(option.value)" :value="option.value">
              {{ option.label }}
            </NRadioButton>
          </NRadioGroup>
        </NFormItem>
        <NGrid :cols="2" :x-gap="16">
          <NFormItemGi label="自动化标识" path="triggerKey">
            <NInput v-model:value="form.triggerKey" placeholder="daily.report" :disabled="Boolean(editingTrigger) || formReadOnly" />
          </NFormItemGi>
          <NFormItemGi label="名称" path="name"><NInput v-model:value="form.name" :disabled="formReadOnly" /></NFormItemGi>
          <NFormItemGi span="2" label="执行任务" path="taskId">
            <NSelect v-model:value="form.taskId" :options="taskOptions" filterable placeholder="选择已发布版本的任务" :disabled="formReadOnly" />
          </NFormItemGi>
          <NFormItemGi span="2" label="执行身份" path="serviceAccountId">
            <NSelect v-model:value="form.serviceAccountId" :options="accountOptions" filterable placeholder="选择服务账号，可按负责人搜索" :disabled="formReadOnly" />
          </NFormItemGi>
        </NGrid>

        <template v-if="form.triggerType === 'cron'">
          <NDivider>执行计划</NDivider>
          <NGrid :cols="2" :x-gap="16">
            <NFormItemGi label="频率"><NSelect v-model:value="form.scheduleMode" :options="scheduleOptions" :disabled="formReadOnly" /></NFormItemGi>
            <NFormItemGi label="时区"><NSelect v-model:value="form.timezone" :options="timezoneOptions" filterable :disabled="formReadOnly" /></NFormItemGi>
            <NFormItemGi v-if="form.scheduleMode === 'custom'" span="2" label="Cron 表达式">
              <NInput v-model:value="form.rawCronExpression" placeholder="0 0 9 * * ?" :disabled="formReadOnly" />
            </NFormItemGi>
            <template v-else>
              <NFormItemGi v-if="form.scheduleMode !== 'hourly'" label="小时"><NInputNumber v-model:value="form.hour" :min="0" :max="23" :disabled="formReadOnly" /></NFormItemGi>
              <NFormItemGi label="分钟"><NInputNumber v-model:value="form.minute" :min="0" :max="59" :disabled="formReadOnly" /></NFormItemGi>
              <NFormItemGi v-if="form.scheduleMode === 'weekly'" span="2" label="执行日期"><NSelect v-model:value="form.weekdays" :options="weekdayOptions" multiple :disabled="formReadOnly" /></NFormItemGi>
            </template>
            <NFormItemGi label="错过计划时"><NSelect v-model:value="form.misfirePolicy" :options="misfireOptions" :disabled="formReadOnly" /></NFormItemGi>
            <NFormItemGi v-if="form.misfirePolicy === 'catch_up'" label="最多追赶次数"><NInputNumber v-model:value="form.maxCatchupCount" :min="1" :max="10" :disabled="formReadOnly" /></NFormItemGi>
          </NGrid>
          <NAlert type="info" :bordered="false" class="schedule-preview">{{ schedulePreview }}</NAlert>
        </template>

        <NGrid :cols="2" :x-gap="16">
          <NFormItemGi label="失败重试次数"><NInputNumber v-model:value="form.maxAttempts" :min="1" :max="10" :disabled="formReadOnly" /></NFormItemGi>
          <NFormItemGi span="2" :label="form.triggerType === 'cron' ? '固定执行指令' : '默认执行指令（可选）'">
            <NInput v-model:value="form.inputTemplate" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" placeholder="描述每次触发后要完成的工作" :disabled="formReadOnly" />
          </NFormItemGi>
        </NGrid>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton :disabled="submitting" @click="formVisible = false">{{ formReadOnly ? '关闭' : '取消' }}</NButton>
          <NButton v-if="!formReadOnly" type="primary" :loading="submitting" @click="submit">
            {{ editingTrigger ? '保存' : '创建' }}
          </NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped lang="scss">
.page-container { min-height: 100%; padding: 16px; }
.page-header, .toolbar { display: flex; align-items: center; gap: 14px; }
.page-header { justify-content: space-between; margin-bottom: 18px; padding-bottom: 16px; border-bottom: 1px solid var(--n-border-color); }
.page-header h2 { margin: 0 0 4px; font-size: 20px; }
.page-header p { margin: 0; color: var(--n-text-color-3); }
.toolbar { margin-bottom: 14px; }
.search-input { width: min(360px, 100%); }
.filter-select { width: 150px; }
.result-count { margin-left: auto; color: var(--n-text-color-3); font-size: 13px; }
.detail-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; margin-bottom: 14px; color: var(--n-text-color-3); font-size: 13px; }
.schedule-preview { margin: 0 0 16px; }
:deep(.primary-cell) { display: flex; min-width: 0; flex-direction: column; gap: 4px; }
:deep(.primary-cell span) { overflow: hidden; color: var(--n-text-color-3); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 680px) {
  .page-header, .toolbar { align-items: stretch; flex-direction: column; }
  .filter-select { width: 100%; }
  .result-count { margin-left: 0; }
}
</style>
