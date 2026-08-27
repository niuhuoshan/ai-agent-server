<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import dayjs from 'dayjs';
import type { DataTableColumns, DataTableRowKey, SelectOption } from 'naive-ui';
import { NButton, NSpace, NTag } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  executeIdentitySync,
  fetchIdentitySyncColumns,
  fetchIdentitySyncConfig,
  fetchIdentitySyncDataSources,
  fetchIdentitySyncRuns,
  fetchIdentitySyncTables,
  previewIdentitySync,
  retryIdentitySyncRun,
  saveIdentitySyncConfig
} from '@/service/api';
import type {
  IdentityProviderType,
  IdentitySyncColumn,
  IdentitySyncConfig,
  IdentitySyncDataSource,
  IdentitySyncExtraMapping,
  IdentitySyncPreview,
  IdentitySyncPreviewItem,
  IdentitySyncRun,
  IdentitySyncSchedule,
  IdentitySyncTable,
  SaveIdentitySyncConfigPayload
} from '@/service/api';

interface IdentitySyncForm {
  enabled: boolean;
  providerType: IdentityProviderType;
  dataSourceId: string | null;
  endpointUrl: string;
  credentialRef: string;
  authType: IdentitySyncConfig['authType'];
  credentialHeader: string;
  requestMethod: IdentitySyncConfig['requestMethod'];
  requestHeadersText: string;
  requestBodyText: string;
  responseItemsPath: string;
  tableName: string | null;
  userNameColumn: string;
  displayNameColumn: string | null;
  emailColumn: string | null;
  phoneColumn: string | null;
  remarkColumn: string | null;
  statusColumn: string | null;
  extraMappings: IdentitySyncExtraMapping[];
  defaultRoleKey: string;
  schedule: IdentitySyncSchedule;
  revisionNo: number;
}

const loading = ref(false);
const saving = ref(false);
const discoveringTables = ref(false);
const discoveringColumns = ref(false);
const previewing = ref(false);
const executing = ref(false);
const retryingRunId = ref<string | null>(null);
const configState = ref<IdentitySyncConfig | null>(null);
const dataSources = ref<IdentitySyncDataSource[]>([]);
const tables = ref<IdentitySyncTable[]>([]);
const columns = ref<IdentitySyncColumn[]>([]);
const preview = ref<IdentitySyncPreview | null>(null);
const checkedNames = ref<DataTableRowKey[]>([]);
const runs = ref<IdentitySyncRun[]>([]);

const form = reactive<IdentitySyncForm>({
  enabled: false,
  providerType: 'database',
  dataSourceId: null,
  endpointUrl: '',
  credentialRef: '',
  authType: 'none',
  credentialHeader: '',
  requestMethod: 'GET',
  requestHeadersText: '{}',
  requestBodyText: '{}',
  responseItemsPath: 'data',
  tableName: null,
  userNameColumn: 'user_name',
  displayNameColumn: null,
  emailColumn: null,
  phoneColumn: null,
  remarkColumn: null,
  statusColumn: null,
  extraMappings: [],
  defaultRoleKey: '',
  schedule: 'off',
  revisionNo: 1
});

const providerOptions: SelectOption[] = [
  { label: '数据库表', value: 'database' },
  { label: 'HTTP JSON / SSO', value: 'http_json' }
];
const authOptions: SelectOption[] = [
  { label: '无认证', value: 'none' },
  { label: 'Basic', value: 'basic' },
  { label: 'Bearer Token', value: 'bearer' },
  { label: '自定义请求头', value: 'header' }
];
const scheduleOptions: SelectOption[] = [
  { label: '关闭', value: 'off' },
  { label: '每小时', value: 'hourly' },
  { label: '每日 02:00', value: 'daily' },
  { label: '每周一 02:00', value: 'weekly' }
];

const dataSourceOptions = computed<SelectOption[]>(() => dataSources.value.map(source => ({
  label: `${source.name} · ${source.dbType} / ${source.databaseName}`,
  value: source.id,
  disabled: source.status !== 'active'
})));
const tableOptions = computed<SelectOption[]>(() => tables.value.map(table => ({
  label: `${table.qualifiedName} · ${table.type}`,
  value: table.qualifiedName
})));
const columnOptions = computed<SelectOption[]>(() => columns.value.map(column => ({
  label: column.sample ? `${column.name} · ${column.type} · 示例 ${column.sample}` : `${column.name} · ${column.type}`,
  value: column.name
})));

function applyConfig(value: IdentitySyncConfig) {
  configState.value = value;
  form.enabled = value.enabled;
  form.providerType = value.providerType;
  form.dataSourceId = value.dataSourceId;
  form.endpointUrl = value.endpointUrl || '';
  form.credentialRef = value.credentialRef || '';
  form.authType = value.authType;
  form.credentialHeader = value.credentialHeader || '';
  form.requestMethod = value.requestMethod;
  form.requestHeadersText = JSON.stringify(value.requestHeaders || {}, null, 2);
  form.requestBodyText = JSON.stringify(value.requestBody || {}, null, 2);
  form.responseItemsPath = value.responseItemsPath || '';
  form.tableName = value.tableName;
  form.userNameColumn = value.fieldMapping.userName;
  form.displayNameColumn = value.fieldMapping.displayName;
  form.emailColumn = value.fieldMapping.email;
  form.phoneColumn = value.fieldMapping.phoneNumber;
  form.remarkColumn = value.fieldMapping.remark;
  form.statusColumn = value.fieldMapping.status;
  form.extraMappings = value.extraMappings.map(item => ({ ...item }));
  form.defaultRoleKey = value.defaultRoleKey || '';
  form.schedule = value.schedule;
  form.revisionNo = value.revisionNo;
}

function parseObject(text: string, label: string): Record<string, unknown> {
  const value = JSON.parse(text || '{}');
  if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error(`${label}必须是 JSON 对象`);
  return value as Record<string, unknown>;
}

function buildPayload(): SaveIdentitySyncConfigPayload | null {
  try {
    const requestHeaders = parseObject(form.requestHeadersText, '请求头');
    const headerEntries = Object.entries(requestHeaders);
    if (headerEntries.some(([, value]) => typeof value !== 'string')) throw new Error('请求头的值必须是字符串');
    if (!form.userNameColumn.trim()) throw new Error('必须配置用户名字段');
    if (form.providerType === 'database' && (!form.dataSourceId || !form.tableName)) {
      throw new Error('数据库身份源必须选择数据源和用户表');
    }
    if (form.providerType === 'http_json' && !form.endpointUrl.trim()) throw new Error('HTTP Provider 地址不能为空');
    return {
      enabled: form.enabled,
      providerType: form.providerType,
      dataSourceId: form.dataSourceId || undefined,
      endpointUrl: form.endpointUrl.trim() || undefined,
      credentialRef: form.credentialRef.trim() || undefined,
      authType: form.authType,
      credentialHeader: form.credentialHeader.trim() || undefined,
      requestMethod: form.requestMethod,
      requestHeaders: Object.fromEntries(headerEntries) as Record<string, string>,
      requestBody: parseObject(form.requestBodyText, '请求体'),
      responseItemsPath: form.responseItemsPath.trim() || undefined,
      tableName: form.tableName || undefined,
      fieldMapping: {
        userName: form.userNameColumn.trim(),
        displayName: form.displayNameColumn || null,
        email: form.emailColumn || null,
        phoneNumber: form.phoneColumn || null,
        remark: form.remarkColumn || null,
        status: form.statusColumn || null
      },
      extraMappings: form.extraMappings
        .map(item => ({ key: item.key.trim(), sourceColumn: item.sourceColumn.trim() }))
        .filter(item => item.key && item.sourceColumn),
      defaultRoleKey: form.defaultRoleKey.trim(),
      schedule: form.schedule,
      expectedRevision: form.revisionNo
    };
  } catch (error) {
    window.$message?.error(error instanceof Error ? error.message : '同步配置不完整');
    return null;
  }
}

async function load() {
  loading.value = true;
  const [configResult, sourceResult, runResult] = await Promise.all([
    fetchIdentitySyncConfig(),
    fetchIdentitySyncDataSources(),
    fetchIdentitySyncRuns()
  ]);
  if (!configResult.error) applyConfig(configResult.data);
  if (!sourceResult.error) dataSources.value = sourceResult.data;
  if (!runResult.error) runs.value = runResult.data;
  loading.value = false;
  if (form.providerType === 'database' && form.dataSourceId) await loadTables(false);
}

async function loadTables(reset = true) {
  if (!form.dataSourceId) {
    tables.value = [];
    columns.value = [];
    return;
  }
  discoveringTables.value = true;
  const result = await fetchIdentitySyncTables(form.dataSourceId);
  discoveringTables.value = false;
  if (result.error) return;
  tables.value = result.data;
  if (reset && !result.data.some(item => item.qualifiedName === form.tableName)) form.tableName = null;
  if (form.tableName) await loadColumns(false);
}

async function loadColumns(reset = true) {
  if (!form.dataSourceId || !form.tableName) {
    columns.value = [];
    return;
  }
  discoveringColumns.value = true;
  const result = await fetchIdentitySyncColumns(form.dataSourceId, form.tableName);
  discoveringColumns.value = false;
  if (result.error) return;
  columns.value = result.data;
  if (reset) {
    form.userNameColumn = '';
    form.displayNameColumn = null;
    form.emailColumn = null;
    form.phoneColumn = null;
    form.remarkColumn = null;
    form.statusColumn = null;
    form.extraMappings = [];
  }
}

async function saveConfig() {
  const payload = buildPayload();
  if (!payload) return;
  saving.value = true;
  const result = await saveIdentitySyncConfig(payload);
  saving.value = false;
  if (result.error) return;
  applyConfig(result.data);
  window.$message?.success('身份同步配置已保存');
}

async function previewUsers() {
  const payload = buildPayload();
  if (!payload) return;
  previewing.value = true;
  const result = await previewIdentitySync(payload);
  previewing.value = false;
  if (result.error) return;
  preview.value = result.data;
  checkedNames.value = result.data.items.filter(item => item.action !== 'invalid').map(item => item.userName);
}

async function executeUsers() {
  const payload = buildPayload();
  if (!payload) return;
  if (!payload.enabled) {
    window.$message?.warning('请先启用身份同步');
    return;
  }
  const userNames = checkedNames.value.map(String);
  executing.value = true;
  const result = await executeIdentitySync(payload, userNames);
  executing.value = false;
  if (result.error) {
    await loadRuns();
    return;
  }
  window.$message?.success(`同步完成：新增 ${result.data.createdCount}，更新 ${result.data.updatedCount}`);
  await loadRuns();
}

async function loadRuns() {
  const result = await fetchIdentitySyncRuns();
  if (!result.error) runs.value = result.data;
}

async function retryRun(run: IdentitySyncRun) {
  retryingRunId.value = run.id;
  const result = await retryIdentitySyncRun(run.id);
  retryingRunId.value = null;
  if (!result.error) window.$message?.success(`重试完成，运行 ID ${result.data.id}`);
  await loadRuns();
}

function addExtraMapping() {
  form.extraMappings.push({ key: '', sourceColumn: '' });
}

function removeExtraMapping(index: number) {
  form.extraMappings.splice(index, 1);
}

function previewActionType(action: IdentitySyncPreviewItem['action']) {
  if (action === 'create') return 'success';
  if (action === 'update') return 'info';
  return 'error';
}

function runStatusType(status: IdentitySyncRun['status']) {
  if (status === 'succeeded') return 'success';
  if (status === 'running') return 'info';
  if (status === 'partial') return 'warning';
  return 'error';
}

const previewColumns: DataTableColumns<IdentitySyncPreviewItem> = [
  { type: 'selection', disabled: row => row.action === 'invalid' },
  { title: '账号', key: 'userName', minWidth: 160 },
  { title: '姓名', key: 'displayName', minWidth: 140, render: row => row.displayName || '-' },
  { title: '邮箱', key: 'email', minWidth: 190, render: row => row.email || '-' },
  { title: '手机号', key: 'phoneNumber', width: 140, render: row => row.phoneNumber || '-' },
  {
    title: '动作', key: 'action', width: 100,
    render: row => h(NTag, { size: 'small', type: previewActionType(row.action) }, () => ({ create: '新增', update: '更新', invalid: '无效账号' })[row.action])
  }
];

const runColumns: DataTableColumns<IdentitySyncRun> = [
  { title: '运行 ID', key: 'id', width: 190, ellipsis: { tooltip: true } },
  { title: 'Provider', key: 'providerType', width: 110 },
  {
    title: '状态', key: 'status', width: 110,
    render: row => h(NTag, { size: 'small', type: runStatusType(row.status) }, () => row.status)
  },
  { title: '发现 / 选择', key: 'selectedCount', width: 120, render: row => `${row.discoveredCount} / ${row.selectedCount}` },
  { title: '新增', key: 'createdCount', width: 80 },
  { title: '更新', key: 'updatedCount', width: 80 },
  { title: '失败', key: 'failedCount', width: 80 },
  { title: '开始时间', key: 'startedAt', width: 170, render: row => dayjs(row.startedAt).format('YYYY-MM-DD HH:mm:ss') },
  { title: '错误', key: 'errorSummary', minWidth: 220, ellipsis: { tooltip: true }, render: row => row.errorSummary || '-' },
  {
    title: '操作', key: 'actions', width: 100, fixed: 'right',
    render: row => row.retryable ? h(NButton, {
      size: 'small', secondary: true, type: 'warning', loading: retryingRunId.value === row.id,
      onClick: () => retryRun(row)
    }, { icon: () => h(SvgIcon, { icon: 'lucide:rotate-cw' }), default: () => '重试' }) : '-'
  }
];

onMounted(load);
</script>

<template>
  <NSpin :show="loading">
    <div class="identity-sync">
      <section class="config-section">
        <div class="section-title">
          <div>
            <h3>身份源配置</h3>
            <p>凭证仅填写 env:NAME 引用；未配置或 Provider 不可达时，接口会明确返回 unavailable。</p>
          </div>
          <NSpace>
            <NButton secondary :loading="previewing" @click="previewUsers">
              <template #icon><SvgIcon icon="lucide:scan-search" /></template>
              预览
            </NButton>
            <NButton type="primary" :loading="saving" @click="saveConfig">
              <template #icon><SvgIcon icon="lucide:save" /></template>
              保存
            </NButton>
          </NSpace>
        </div>

        <NForm label-placement="top">
          <NGrid :cols="24" :x-gap="16" :y-gap="4" responsive="screen">
            <NFormItemGi span="24 s:8 m:5" label="同步开关">
              <NSwitch v-model:value="form.enabled"><template #checked>启用</template><template #unchecked>关闭</template></NSwitch>
            </NFormItemGi>
            <NFormItemGi span="24 s:8 m:7" label="Provider">
              <NSelect v-model:value="form.providerType" :options="providerOptions" @update:value="preview = null" />
            </NFormItemGi>
            <NFormItemGi span="24 s:8 m:6" label="默认角色">
              <NInput v-model:value="form.defaultRoleKey" placeholder="可选，例如 member" />
            </NFormItemGi>
            <NFormItemGi span="24 s:8 m:6" label="调度周期">
              <NSelect v-model:value="form.schedule" :options="scheduleOptions" />
            </NFormItemGi>

            <template v-if="form.providerType === 'database'">
              <NFormItemGi span="24 m:12" label="只读数据源">
                <NSelect v-model:value="form.dataSourceId" :options="dataSourceOptions" filterable clearable :loading="discoveringTables" @update:value="loadTables(true)" />
              </NFormItemGi>
              <NFormItemGi span="24 m:12" label="用户表">
                <NSelect v-model:value="form.tableName" :options="tableOptions" filterable clearable :loading="discoveringColumns" @update:value="loadColumns(true)" />
              </NFormItemGi>
            </template>

            <template v-else>
              <NFormItemGi span="24 m:12" label="HTTPS Provider 地址">
                <NInput v-model:value="form.endpointUrl" placeholder="https://identity.example.com/api/users" />
              </NFormItemGi>
              <NFormItemGi span="24 m:6" label="认证方式"><NSelect v-model:value="form.authType" :options="authOptions" /></NFormItemGi>
              <NFormItemGi span="24 m:6" label="请求方法"><NRadioGroup v-model:value="form.requestMethod"><NRadioButton value="GET">GET</NRadioButton><NRadioButton value="POST">POST</NRadioButton></NRadioGroup></NFormItemGi>
              <NFormItemGi v-if="form.authType !== 'none'" span="24 m:12" label="凭证引用">
                <NInput v-model:value="form.credentialRef" placeholder="env:IDENTITY_PROVIDER_CREDENTIAL" />
              </NFormItemGi>
              <NFormItemGi v-if="form.authType === 'header'" span="24 m:12" label="凭证请求头">
                <NInput v-model:value="form.credentialHeader" placeholder="YOVOLE-LAPLACE-API-ACCESS-TOKEN" />
              </NFormItemGi>
              <NFormItemGi span="24 m:12" label="用户数组路径">
                <NInput v-model:value="form.responseItemsPath" placeholder="data.items；顶层数组可留空" />
              </NFormItemGi>
              <NFormItemGi span="24 m:12" label="非敏感请求头 JSON">
                <NInput v-model:value="form.requestHeadersText" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" />
              </NFormItemGi>
              <NFormItemGi span="24 m:12" label="请求体 JSON">
                <NInput v-model:value="form.requestBodyText" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" :disabled="form.requestMethod !== 'POST'" />
              </NFormItemGi>
            </template>
          </NGrid>
        </NForm>
      </section>

      <section class="mapping-section">
        <div class="section-title compact"><div><h3>字段映射</h3><p>用户名是本地映射主键；未映射的字段不会覆盖本地值。</p></div></div>
        <NGrid :cols="24" :x-gap="16" responsive="screen">
          <NFormItemGi span="24 s:12 m:8" label="用户名字段" required><NSelect v-if="form.providerType === 'database'" v-model:value="form.userNameColumn" :options="columnOptions" filterable tag /><NInput v-else v-model:value="form.userNameColumn" /></NFormItemGi>
          <NFormItemGi span="24 s:12 m:8" label="姓名字段"><NSelect v-if="form.providerType === 'database'" v-model:value="form.displayNameColumn" :options="columnOptions" filterable clearable tag /><NInput v-else v-model:value="form.displayNameColumn" /></NFormItemGi>
          <NFormItemGi span="24 s:12 m:8" label="邮箱字段"><NSelect v-if="form.providerType === 'database'" v-model:value="form.emailColumn" :options="columnOptions" filterable clearable tag /><NInput v-else v-model:value="form.emailColumn" /></NFormItemGi>
          <NFormItemGi span="24 s:12 m:8" label="手机号字段"><NSelect v-if="form.providerType === 'database'" v-model:value="form.phoneColumn" :options="columnOptions" filterable clearable tag /><NInput v-else v-model:value="form.phoneColumn" /></NFormItemGi>
          <NFormItemGi span="24 s:12 m:8" label="备注字段"><NSelect v-if="form.providerType === 'database'" v-model:value="form.remarkColumn" :options="columnOptions" filterable clearable tag /><NInput v-else v-model:value="form.remarkColumn" /></NFormItemGi>
          <NFormItemGi span="24 s:12 m:8" label="状态字段"><NSelect v-if="form.providerType === 'database'" v-model:value="form.statusColumn" :options="columnOptions" filterable clearable tag /><NInput v-else v-model:value="form.statusColumn" /></NFormItemGi>
        </NGrid>
        <div class="extra-heading"><span>扩展字段快照</span><NButton size="small" secondary @click="addExtraMapping"><template #icon><SvgIcon icon="lucide:plus" /></template>添加映射</NButton></div>
        <div v-for="(mapping, index) in form.extraMappings" :key="index" class="extra-row">
          <NInput v-model:value="mapping.key" placeholder="JSON 键名" />
          <NSelect v-if="form.providerType === 'database'" v-model:value="mapping.sourceColumn" :options="columnOptions" filterable tag placeholder="来源字段" />
          <NInput v-else v-model:value="mapping.sourceColumn" placeholder="来源字段" />
          <NTooltip><template #trigger><NButton quaternary circle type="error" @click="removeExtraMapping(index)"><template #icon><SvgIcon icon="lucide:trash-2" /></template></NButton></template>删除映射</NTooltip>
        </div>
        <NEmpty v-if="form.extraMappings.length === 0" size="small" description="未配置扩展字段" />
      </section>

      <section v-if="preview" class="preview-section">
        <div class="section-title compact">
          <div><h3>同步预览</h3><p>发现 {{ preview.total }} 人，预计新增 {{ preview.creates }} 人、更新 {{ preview.updates }} 人。</p></div>
          <NButton type="primary" :loading="executing" :disabled="checkedNames.length === 0" @click="executeUsers">
            <template #icon><SvgIcon icon="lucide:play" /></template>
            同步已选 {{ checkedNames.length }} 人
          </NButton>
        </div>
        <NDataTable v-model:checked-row-keys="checkedNames" :columns="previewColumns" :data="preview.items" :row-key="row => row.userName" :scroll-x="900" />
      </section>

      <section class="runs-section">
        <div class="section-title compact"><div><h3>运行记录</h3><p>部分成功、失败和 Provider 不可用的运行可直接重试。</p></div><NButton quaternary circle title="刷新运行记录" @click="loadRuns"><template #icon><SvgIcon icon="lucide:refresh-cw" /></template></NButton></div>
        <NDataTable :columns="runColumns" :data="runs" :row-key="row => row.id" :scroll-x="1320" />
      </section>

      <NAlert v-if="configState?.lastError" type="warning" :bordered="false">最近错误：{{ configState.lastError }}</NAlert>
    </div>
  </NSpin>
</template>

<style scoped lang="scss">
.identity-sync { display: grid; gap: 22px; }
.config-section, .mapping-section, .preview-section, .runs-section { padding-bottom: 22px; border-bottom: 1px solid var(--n-border-color); }
.section-title { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.section-title.compact { align-items: center; margin-bottom: 14px; }
.section-title h3 { margin: 0 0 4px; font-size: 16px; }
.section-title p { margin: 0; color: var(--n-text-color-3); font-size: 13px; }
.extra-heading { display: flex; align-items: center; justify-content: space-between; margin: 4px 0 10px; font-weight: 600; }
.extra-row { display: grid; grid-template-columns: minmax(180px, 0.8fr) minmax(260px, 1.2fr) 34px; align-items: center; gap: 10px; margin-bottom: 10px; }
@media (max-width: 720px) {
  .section-title { align-items: stretch; flex-direction: column; }
  .extra-row { grid-template-columns: 1fr 1fr 34px; }
}
</style>
