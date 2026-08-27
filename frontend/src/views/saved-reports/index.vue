<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import dayjs from 'dayjs';
import type { DataTableColumns, FormInst, FormRules, SelectOption } from 'naive-ui';
import { NButton, NCheckbox, NCheckboxGroup, NForm, NFormItem, NInput, NModal, NSelect, NSpace, NSwitch, NTag, useDialog } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  archiveReport,
  createReportSubscription,
  createReport,
  deleteReportSubscription,
  executeReport,
  executeReportSubscription,
  fetchDatasets,
  fetchReportRuns,
  fetchReports,
  fetchReportSubscriptions,
  updateReport,
  updateReportSubscriptionStatus
} from '@/service/api';
import type { DatasetView, ReportRunView, ReportSubscriptionView, ReportView } from '@/service/api';

const dialog = useDialog();
const loading = ref(false);
const reports = ref<ReportView[]>([]);
const datasets = ref<DatasetView[]>([]);
const selected = ref<ReportView | null>(null);
const runs = ref<ReportRunView[]>([]);
const subscriptions = ref<ReportSubscriptionView[]>([]);
const detailVisible = ref(false);
const detailLoading = ref(false);
const detailTab = ref<'runs' | 'subscriptions'>('runs');
const subscriptionVisible = ref(false);
const subscriptionSubmitting = ref(false);
const subscriptionActionId = ref<string | null>(null);
const runnerVisible = ref(false);
const runnerSubmitting = ref(false);
const editorVisible = ref(false);
const editorSubmitting = ref(false);
const editorRef = ref<FormInst | null>(null);
const search = ref('');
const status = ref<ReportView['status'] | undefined>();
const runnerParameters = reactive<Record<string, unknown>>({});
const subscriptionForm = reactive({
  scheduleType: 'cron' as ReportSubscriptionView['scheduleType'],
  cronExpr: '0 0 9 * * *',
  intervalMinutes: 60,
  timezone: 'Asia/Shanghai',
  paramsJson: '{}',
  notifyChannels: ['inbox'] as string[],
  notifyOnSuccess: true,
  notifyOnFailure: true,
  maxAttempts: 3
});
const form = reactive({
  reportKey: '',
  name: '',
  datasetId: null as string | null,
  sqlTemplate: 'SELECT * FROM {{table}} LIMIT 100',
  paramsSchemaJson: '{}',
  visibility: 'private' as ReportView['visibility'],
  state: 'draft' as Exclude<ReportView['status'], 'archived'>
});

const rules: FormRules = {
  reportKey: [{ required: true, message: '请输入报表标识', trigger: ['input', 'blur'] }],
  name: [{ required: true, message: '请输入报表名称', trigger: ['input', 'blur'] }],
  datasetId: [{ required: true, message: '请选择数据集', trigger: 'change' }],
  sqlTemplate: [{ required: true, message: '请输入只读 SQL 模板', trigger: ['input', 'blur'] }]
};

const datasetOptions = computed<SelectOption[]>(() =>
  datasets.value.filter(item => item.status === 'active').map(item => ({ label: item.name, value: item.id }))
);
const timezoneOptions: SelectOption[] = [
  { label: '中国标准时间 (Asia/Shanghai)', value: 'Asia/Shanghai' },
  { label: '协调世界时 (UTC)', value: 'UTC' },
  { label: '东京 (Asia/Tokyo)', value: 'Asia/Tokyo' },
  { label: '新加坡 (Asia/Singapore)', value: 'Asia/Singapore' }
];
const cronPresetOptions: SelectOption[] = [
  { label: '每天 09:00', value: '0 0 9 * * *' },
  { label: '每个工作日 09:00', value: '0 0 9 * * MON-FRI' },
  { label: '每周一 09:00', value: '0 0 9 * * MON' },
  { label: '每月 1 日 09:00', value: '0 0 9 1 * *' }
];

interface ReportParameterField {
  key: string;
  label: string;
  type: string;
  required: boolean;
  options: SelectOption[];
  defaultValue: unknown;
}

const parameterFields = computed<ReportParameterField[]>(() => {
  if (!selected.value) return [];
  const schema = parseSchema(selected.value.paramsSchemaJson);
  const properties = isRecord(schema.properties) ? schema.properties : schema;
  const required = new Set(Array.isArray(schema.required) ? schema.required.map(String) : []);
  return Object.entries(properties)
    .filter(([key]) => key !== 'required' && key !== 'properties')
    .map(([key, raw]) => {
      const definition = isRecord(raw) ? raw : {};
      const values = Array.isArray(definition.enum) ? definition.enum : [];
      return {
        key,
        label: typeof definition.title === 'string' && definition.title.trim() ? definition.title : key,
        type: typeof definition.type === 'string' ? definition.type : 'string',
        required: required.has(key) || definition.required === true,
        options: values.map(value => ({ label: String(value), value: String(value) })),
        defaultValue: definition.default ?? ''
      };
    });
});

const columns: DataTableColumns<ReportView> = [
  { title: '报表', key: 'name', minWidth: 220, render: row => h('div', [h('div', { class: 'font-500' }, row.name), h('div', { class: 'text-12px op-60' }, row.reportKey)]) },
  { title: '数据集', key: 'datasetId', width: 120 },
  { title: '可见范围', key: 'visibility', width: 120, render: row => visibilityLabel(row.visibility) },
  { title: '状态', key: 'status', width: 100, render: row => h(NTag, { type: statusType(row.status), size: 'small' }, { default: () => statusLabel(row.status) }) },
  { title: '操作', key: 'actions', width: 250, render: row => h(NSpace, { size: 6 }, {
    default: () => [
      h(NButton, { size: 'small', onClick: () => openEditor(row) }, { default: () => '编辑' }),
      h(NButton, { size: 'small', type: 'primary', disabled: row.status !== 'active', onClick: () => openRunner(row) }, { default: () => '执行' }),
      h(NButton, { size: 'small', tertiary: true, onClick: () => openDetails(row) }, { default: () => '运行与订阅' }),
      h(NButton, { size: 'small', type: 'error', tertiary: true, onClick: () => remove(row) }, { default: () => '归档' })
    ]
  }) }
];

const runColumns: DataTableColumns<ReportRunView> = [
  { title: '时间', key: 'createdAt', width: 165, render: row => formatTime(row.createdAt) },
  { title: '触发方式', key: 'triggerType', width: 110, render: row => triggerTypeLabel(row.triggerType) },
  { title: '状态', key: 'status', width: 100, render: row => h(NTag, { size: 'small', type: runStatusType(row.status) }, { default: () => runStatusLabel(row.status) }) },
  { title: '行数', key: 'rowCount', width: 90, render: row => row.rowCount ?? '-' },
  { title: '结果哈希', key: 'resultHash', width: 190, render: row => row.resultHash || '-' },
  { title: 'SQL', key: 'executedSql', minWidth: 260, ellipsis: { tooltip: true } },
  { title: '错误', key: 'errorSummary', minWidth: 180, ellipsis: { tooltip: true }, render: row => row.errorSummary || '-' }
];

const subscriptionColumns: DataTableColumns<ReportSubscriptionView> = [
  { title: '状态', key: 'status', width: 90, render: row => h(NTag, { size: 'small', type: row.status === 'active' ? 'success' : 'default' }, { default: () => row.status === 'active' ? '启用' : '暂停' }) },
  { title: '执行计划', key: 'scheduleType', minWidth: 190, render: row => scheduleLabel(row) },
  { title: '时区', key: 'timezone', width: 145 },
  { title: '失败重试', key: 'maxAttempts', width: 100, render: row => `${row.maxAttempts} 次` },
  { title: '上次执行', key: 'lastRunAt', width: 165, render: row => formatTime(row.lastRunAt) },
  { title: '下次执行', key: 'nextRunAt', width: 165, render: row => formatTime(row.nextRunAt) },
  { title: '操作', key: 'actions', width: 260, fixed: 'right', render: row => h(NSpace, { size: 6 }, {
    default: () => [
      h(NButton, { size: 'small', type: 'primary', secondary: true, loading: subscriptionActionId.value === row.id, onClick: () => runSubscription(row) }, { default: () => '立即执行' }),
      h(NButton, { size: 'small', tertiary: true, onClick: () => toggleSubscription(row) }, { default: () => row.status === 'active' ? '暂停' : '启用' }),
      h(NButton, { size: 'small', type: 'error', tertiary: true, onClick: () => confirmDeleteSubscription(row) }, { default: () => '删除' })
    ]
  }) }
];

function statusType(value: string) {
  return value === 'active' ? 'success' : value === 'disabled' ? 'warning' : 'default';
}
function statusLabel(value: string) {
  return ({ draft: '草稿', active: '启用', disabled: '停用', archived: '已归档' } as Record<string, string>)[value] || value;
}
function visibilityLabel(value: string) {
  return ({ private: '仅自己', enterprise_shared: '企业共享', restricted: '受限共享' } as Record<string, string>)[value] || value;
}
function scheduleLabel(subscription: ReportSubscriptionView) {
  return subscription.scheduleType === 'interval'
    ? `每 ${subscription.intervalMinutes} 分钟`
    : subscription.cronExpr || '-';
}
function parseSchema(raw: string) {
  try {
    const value = JSON.parse(raw || '{}');
    return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
  } catch {
    return {};
  }
}

function isRecord(value: unknown): value is Record<string, any> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function parseJsonObject(raw: string, label: string) {
  try {
    const value: unknown = JSON.parse(raw || '{}');
    if (!isRecord(value)) throw new Error();
    return value;
  } catch {
    window.$message?.error(`${label}必须是 JSON 对象`);
    return null;
  }
}

function formatTime(value: string | null) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-';
}

function triggerTypeLabel(value: string) {
  return ({ manual: '手动', subscription: '订阅', automation: '自动化' } as Record<string, string>)[value] || value;
}

function runStatusType(value: ReportRunView['status']) {
  return value === 'succeeded' ? 'success' : value === 'failed' ? 'error' : 'warning';
}

function runStatusLabel(value: ReportRunView['status']) {
  return ({ running: '运行中', succeeded: '成功', failed: '失败' } as Record<string, string>)[value] || value;
}

async function load() {
  loading.value = true;
  const [reportResult, datasetResult] = await Promise.all([fetchReports({ status: status.value, search: search.value || undefined }), fetchDatasets()]);
  if (!reportResult.error) reports.value = reportResult.data;
  if (!datasetResult.error) datasets.value = datasetResult.data;
  loading.value = false;
}

function openEditor(report?: ReportView) {
  selected.value = report || null;
  form.reportKey = report?.reportKey || '';
  form.name = report?.name || '';
  form.datasetId = report?.datasetId || null;
  form.sqlTemplate = report?.sqlTemplate || 'SELECT * FROM {{table}} LIMIT 100';
  form.paramsSchemaJson = report?.paramsSchemaJson || '{}';
  form.visibility = report?.visibility || 'private';
  form.state = report?.status === 'archived' ? 'disabled' : report?.status || 'draft';
  editorVisible.value = true;
}

async function save() {
  try {
    await editorRef.value?.validate();
  } catch {
    return;
  }
  if (!form.datasetId) return;
  if (form.paramsSchemaJson && Object.keys(parseSchema(form.paramsSchemaJson)).length === 0 && form.paramsSchemaJson.trim() !== '{}') {
    window.$message?.error('参数 Schema 必须是 JSON 对象');
    return;
  }
  editorSubmitting.value = true;
  try {
    const result = selected.value
      ? await updateReport(selected.value.id, { name: form.name, datasetId: form.datasetId, sqlTemplate: form.sqlTemplate, paramsSchemaJson: form.paramsSchemaJson, status: form.state, visibility: form.visibility })
      : await createReport({ reportKey: form.reportKey, name: form.name, datasetId: form.datasetId, sqlTemplate: form.sqlTemplate, paramsSchemaJson: form.paramsSchemaJson, visibility: form.visibility });
    if (!result.error) {
      editorVisible.value = false;
      await load();
      window.$message?.success(selected.value ? '报表已更新' : '报表已创建');
    }
  } finally {
    editorSubmitting.value = false;
  }
}

function remove(report: ReportView) {
  dialog.warning({ title: '归档报表', content: `确认归档“${report.name}”？`, positiveText: '归档', negativeText: '取消', onPositiveClick: async () => { const result = await archiveReport(report.id); if (!result.error) await load(); } });
}

function openRunner(report: ReportView) {
  selected.value = report;
  Object.keys(runnerParameters).forEach(key => delete runnerParameters[key]);
  parameterFields.value.forEach(field => {
    runnerParameters[field.key] = field.defaultValue;
  });
  runnerVisible.value = true;
}

async function runReport() {
  if (!selected.value) return;
  for (const field of parameterFields.value) {
    if (field.required && (runnerParameters[field.key] === '' || runnerParameters[field.key] == null)) {
      window.$message?.warning(`请填写${field.label}`);
      return;
    }
  }
  const parameters: Record<string, unknown> = {};
  parameterFields.value.forEach(field => {
    const value = runnerParameters[field.key];
    if (field.type === 'number' || field.type === 'integer') {
      parameters[field.key] = value === '' || value == null ? null : Number(value);
    } else if (field.type === 'boolean') {
      parameters[field.key] = value === true || value === 'true';
    } else {
      parameters[field.key] = value;
    }
  });
  runnerSubmitting.value = true;
  try {
    const result = await executeReport(selected.value.id, parameters);
    if (!result.error) {
      runnerVisible.value = false;
      window.$message?.success(`执行完成，共 ${result.data.rowCount} 行`);
    }
  } finally {
    runnerSubmitting.value = false;
  }
}

async function refreshDetails() {
  if (!selected.value) return;
  detailLoading.value = true;
  try {
    const [runResult, subscriptionResult] = await Promise.all([
      fetchReportRuns(selected.value.id),
      fetchReportSubscriptions(selected.value.id)
    ]);
    if (!runResult.error) runs.value = runResult.data;
    if (!subscriptionResult.error) subscriptions.value = subscriptionResult.data;
  } finally {
    detailLoading.value = false;
  }
}

async function openDetails(report: ReportView) {
  selected.value = report;
  detailTab.value = 'runs';
  detailVisible.value = true;
  await refreshDetails();
}

function openSubscriptionEditor() {
  subscriptionForm.scheduleType = 'cron';
  subscriptionForm.cronExpr = '0 0 9 * * *';
  subscriptionForm.intervalMinutes = 60;
  subscriptionForm.timezone = 'Asia/Shanghai';
  subscriptionForm.paramsJson = '{}';
  subscriptionForm.notifyChannels = ['inbox'];
  subscriptionForm.notifyOnSuccess = true;
  subscriptionForm.notifyOnFailure = true;
  subscriptionForm.maxAttempts = 3;
  subscriptionVisible.value = true;
}

async function saveSubscription() {
  if (!selected.value) return;
  if (!parseJsonObject(subscriptionForm.paramsJson, '订阅参数')) return;
  if (!subscriptionForm.notifyChannels.length) {
    window.$message?.warning('至少选择一个通知渠道');
    return;
  }
  if (subscriptionForm.scheduleType === 'cron' && !subscriptionForm.cronExpr.trim()) {
    window.$message?.warning('请输入 Cron 表达式');
    return;
  }
  if (subscriptionForm.scheduleType === 'interval'
    && (subscriptionForm.intervalMinutes < 1 || subscriptionForm.intervalMinutes > 525600)) {
    window.$message?.warning('固定周期必须在 1 到 525600 分钟之间');
    return;
  }
  subscriptionSubmitting.value = true;
  try {
    const result = await createReportSubscription(selected.value.id, {
      scheduleType: subscriptionForm.scheduleType,
      ...(subscriptionForm.scheduleType === 'cron'
        ? { cronExpr: subscriptionForm.cronExpr.trim() }
        : { intervalMinutes: subscriptionForm.intervalMinutes }),
      timezone: subscriptionForm.timezone,
      paramsJson: subscriptionForm.paramsJson,
      notifyPolicyJson: JSON.stringify({
        channels: subscriptionForm.notifyChannels,
        onSuccess: subscriptionForm.notifyOnSuccess,
        onFailure: subscriptionForm.notifyOnFailure
      }),
      maxAttempts: subscriptionForm.maxAttempts
    });
    if (!result.error) {
      subscriptionVisible.value = false;
      detailTab.value = 'subscriptions';
      await refreshDetails();
      window.$message?.success('订阅已创建并进入调度');
    }
  } finally {
    subscriptionSubmitting.value = false;
  }
}

async function toggleSubscription(subscription: ReportSubscriptionView) {
  const next = subscription.status === 'active' ? 'paused' : 'active';
  if (!selected.value) return;
  const result = await updateReportSubscriptionStatus(selected.value.id, subscription.id, next);
  if (!result.error && selected.value) {
    const refreshed = await fetchReportSubscriptions(selected.value.id);
    if (!refreshed.error) subscriptions.value = refreshed.data;
  }
}

async function runSubscription(subscription: ReportSubscriptionView) {
  if (!selected.value || subscriptionActionId.value) return;
  subscriptionActionId.value = subscription.id;
  try {
    const result = await executeReportSubscription(selected.value.id, subscription.id);
    if (!result.error) {
      window.$message?.success(`订阅执行完成，共 ${result.data.rowCount} 行`);
      await refreshDetails();
    }
  } finally {
    subscriptionActionId.value = null;
  }
}

function confirmDeleteSubscription(subscription: ReportSubscriptionView) {
  if (!selected.value) return;
  const reportId = selected.value.id;
  dialog.warning({
    title: '删除订阅',
    content: '确认删除这个报表订阅？历史运行记录仍会保留。',
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      const result = await deleteReportSubscription(reportId, subscription.id);
      if (!result.error) {
        await refreshDetails();
        window.$message?.success('订阅已删除');
      }
    }
  });
}

onMounted(load);
</script>

<template>
  <div class="h-full flex-col-stretch gap-12px overflow-hidden p-16px">
    <NCard title="保存的报表" :bordered="false" size="small" class="shrink-0">
      <template #header-extra>
        <NSpace>
          <NInput v-model:value="search" clearable placeholder="搜索名称或标识" style="width: 220px" @keyup.enter="load" />
          <NSelect v-model:value="status" clearable placeholder="全部状态" :options="[
            { label: '草稿', value: 'draft' }, { label: '启用', value: 'active' }, { label: '停用', value: 'disabled' }
          ]" style="width: 120px" @update:value="load" />
          <NButton type="primary" @click="openEditor()"><template #icon><SvgIcon icon="lucide:plus" /></template>新建报表</NButton>
        </NSpace>
      </template>
      <NDataTable :columns="columns" :data="reports" :loading="loading" :single-line="false" flex-height class="h-full" />
    </NCard>

    <NModal v-model:show="editorVisible" preset="card" :title="selected ? '编辑报表' : '新建报表'" style="width: min(760px, 94vw)">
      <NForm ref="editorRef" :model="form" :rules="rules" label-placement="left" label-width="100">
        <NFormItem v-if="!selected" label="报表标识" path="reportKey"><NInput v-model:value="form.reportKey" placeholder="sales.monthly" /></NFormItem>
        <NFormItem label="名称" path="name"><NInput v-model:value="form.name" /></NFormItem>
        <NFormItem label="数据集" path="datasetId"><NSelect v-model:value="form.datasetId" :options="datasetOptions" /></NFormItem>
        <NFormItem label="SQL 模板" path="sqlTemplate"><NInput v-model:value="form.sqlTemplate" type="textarea" :autosize="{ minRows: 5, maxRows: 12 }" /></NFormItem>
        <NFormItem label="参数 Schema" path="paramsSchemaJson"><NInput v-model:value="form.paramsSchemaJson" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }" /></NFormItem>
        <NSpace>
          <NSelect v-model:value="form.visibility" :options="[
            { label: '仅自己', value: 'private' }, { label: '企业共享', value: 'enterprise_shared' }, { label: '受限共享', value: 'restricted' }
          ]" style="width: 150px" />
          <NSelect v-if="selected" v-model:value="form.state" :options="[
            { label: '草稿', value: 'draft' }, { label: '启用', value: 'active' }, { label: '停用', value: 'disabled' }
          ]" style="width: 120px" />
        </NSpace>
      </NForm>
      <template #footer><NSpace justify="end"><NButton @click="editorVisible = false">取消</NButton><NButton type="primary" :loading="editorSubmitting" @click="save">保存</NButton></NSpace></template>
    </NModal>

    <NModal v-model:show="runnerVisible" preset="card" :title="`执行报表 · ${selected?.name || ''}`" style="width: min(640px, 94vw)" :mask-closable="!runnerSubmitting">
      <NAlert v-if="!parameterFields.length" type="info" :show-icon="false" class="mb-16px">该报表不需要运行参数。</NAlert>
      <NForm label-placement="top">
        <NFormItem v-for="field in parameterFields" :key="field.key" :label="field.label" :required="field.required">
          <NSelect
            v-if="field.options.length"
            :value="runnerParameters[field.key] == null ? null : String(runnerParameters[field.key])"
            :options="field.options"
            clearable
            @update:value="value => runnerParameters[field.key] = value"
          />
          <NSelect
            v-else-if="field.type === 'boolean'"
            :value="runnerParameters[field.key] == null ? null : String(runnerParameters[field.key])"
            :options="[{ label: '是', value: 'true' }, { label: '否', value: 'false' }]"
            @update:value="value => runnerParameters[field.key] = value"
          />
          <NInput
            v-else
            :value="runnerParameters[field.key] == null ? '' : String(runnerParameters[field.key])"
            :placeholder="field.key"
            @update:value="value => runnerParameters[field.key] = value"
          />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end"><NButton :disabled="runnerSubmitting" @click="runnerVisible = false">取消</NButton><NButton type="primary" :loading="runnerSubmitting" @click="runReport"><template #icon><SvgIcon icon="lucide:play" /></template>执行</NButton></NSpace>
      </template>
    </NModal>

    <NModal v-model:show="detailVisible" preset="card" :title="`${selected?.name || ''} · 运行与订阅`" style="width: min(1120px, 96vw)">
      <NTabs v-model:value="detailTab" type="line" animated>
        <NTabPane name="runs" tab="执行历史">
          <div class="mb-12px flex justify-end"><NButton secondary size="small" :loading="detailLoading" @click="refreshDetails"><template #icon><SvgIcon icon="lucide:refresh-cw" /></template>刷新</NButton></div>
          <NDataTable :columns="runColumns" :data="runs" :loading="detailLoading" :scroll-x="1000" :row-key="row => row.id" />
        </NTabPane>
        <NTabPane name="subscriptions" tab="报表订阅">
          <div class="mb-12px flex items-start justify-between gap-12px">
            <NAlert type="info" :show-icon="false" class="flex-1">订阅使用报表独立调度，执行失败会按配置重试并通过通知策略投递结果。</NAlert>
            <NSpace class="shrink-0">
              <NButton secondary size="small" :loading="detailLoading" @click="refreshDetails"><template #icon><SvgIcon icon="lucide:refresh-cw" /></template>刷新</NButton>
              <NButton type="primary" size="small" @click="openSubscriptionEditor"><template #icon><SvgIcon icon="lucide:plus" /></template>新建订阅</NButton>
            </NSpace>
          </div>
          <NDataTable :columns="subscriptionColumns" :data="subscriptions" :loading="detailLoading" :scroll-x="1000" :row-key="row => row.id" />
        </NTabPane>
      </NTabs>
    </NModal>

    <NModal v-model:show="subscriptionVisible" preset="card" title="新建报表订阅" style="width: min(680px, 94vw)" :mask-closable="!subscriptionSubmitting">
      <NForm label-placement="top">
        <NFormItem label="调度方式">
          <NRadioGroup v-model:value="subscriptionForm.scheduleType" size="small">
            <NRadioButton value="cron">Cron 计划</NRadioButton>
            <NRadioButton value="interval">固定周期</NRadioButton>
          </NRadioGroup>
        </NFormItem>
        <NFormItem v-if="subscriptionForm.scheduleType === 'cron'" label="Cron 表达式">
          <div class="grid w-full grid-cols-1 gap-8px sm:grid-cols-2">
            <NSelect v-model:value="subscriptionForm.cronExpr" :options="cronPresetOptions" tag filterable />
            <NInput v-model:value="subscriptionForm.cronExpr" maxlength="128" placeholder="0 0 9 * * *" />
          </div>
        </NFormItem>
        <NFormItem v-else label="执行周期（分钟）">
          <NInputNumber v-model:value="subscriptionForm.intervalMinutes" :min="1" :max="525600" class="w-full" />
        </NFormItem>
        <NFormItem label="时区"><NSelect v-model:value="subscriptionForm.timezone" :options="timezoneOptions" /></NFormItem>
        <NFormItem label="最大尝试次数"><NInputNumber v-model:value="subscriptionForm.maxAttempts" :min="1" :max="10" class="w-full" /></NFormItem>
        <NFormItem label="订阅参数"><NInput v-model:value="subscriptionForm.paramsJson" type="textarea" :autosize="{ minRows: 4, maxRows: 10 }" placeholder="JSON 对象，例如 {&quot;region&quot;:&quot;华东&quot;}" /></NFormItem>
        <NFormItem label="通知渠道">
          <NCheckboxGroup v-model:value="subscriptionForm.notifyChannels">
            <NSpace>
              <NCheckbox value="inbox" label="站内信" />
              <NCheckbox value="dingtalk" label="钉钉" />
              <NCheckbox value="wechat_work" label="企业微信" />
              <NCheckbox value="email" label="邮件" />
            </NSpace>
          </NCheckboxGroup>
        </NFormItem>
        <NFormItem label="通知时机">
          <NSpace align="center">
            <NSwitch v-model:value="subscriptionForm.notifyOnSuccess" size="small" />
            <span class="text-12px op-70">执行成功</span>
            <NSwitch v-model:value="subscriptionForm.notifyOnFailure" size="small" />
            <span class="text-12px op-70">执行失败</span>
          </NSpace>
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end"><NButton :disabled="subscriptionSubmitting" @click="subscriptionVisible = false">取消</NButton><NButton type="primary" :loading="subscriptionSubmitting" @click="saveSubscription">创建</NButton></NSpace>
      </template>
    </NModal>
  </div>
</template>
