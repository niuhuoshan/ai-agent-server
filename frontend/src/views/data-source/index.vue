<script setup lang="ts">
import { computed, h, onMounted, reactive, ref, watch } from 'vue';
import dayjs from 'dayjs';
import type { DataTableColumns, FormInst, FormRules, SelectOption } from 'naive-ui';
import { NButton, NSpace, NTag, useDialog } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  createDataset,
  createDataSource,
  deleteDataset,
  deleteDataSource,
  downloadDataQueryExport,
  executeDataQuery,
  fetchDatasetMetadata,
  fetchDatasetDeleteImpact,
  fetchDatasets,
  fetchDataSources,
  syncDatasetMetadata,
  testDataSource,
  updateDataset,
  updateDataSource,
  validateDataQuery
} from '@/service/api';
import type {
  DataQueryResultView,
  DataQueryExportFormat,
  DataQueryValidationView,
  DatasetDeleteImpactView,
  DatasetView,
  DataSourceDatabaseType,
  DataSourceView,
  DataTableView,
  MetadataSyncView,
  SaveDatasetPayload,
  SaveDataSourcePayload
} from '@/service/api';
import {
  dataSourceSslOptions,
  defaultDataSourceSslMode,
  normalizeDataSourceSslMode
} from './ssl-policy';
import MetadataGovernance from './metadata-governance.vue';
import MetadataImport from './metadata-import.vue';
import MetadataChanges from './metadata-changes.vue';
import MetadataMetrics from './metadata-metrics.vue';
import MetadataProfile from './metadata-profile.vue';
import MetadataRelations from './metadata-relations.vue';
import MetadataRowPolicy from './metadata-row-policy.vue';

interface DatabaseChoice {
  value: DataSourceDatabaseType;
  label: string;
  description: string;
  defaultPort: number;
  icon: string;
  enabled: boolean;
}

const dialog = useDialog();
const queryExportOptions = [
  { label: '导出 CSV', key: 'csv' },
  { label: '导出 Excel', key: 'xlsx' }
];
const activeTab = ref('sources');
const loading = ref(false);
const sources = ref<DataSourceView[]>([]);
const datasets = ref<DatasetView[]>([]);

const databaseChoices: DatabaseChoice[] = [
  { value: 'postgresql', label: 'PostgreSQL', description: 'Schema、视图与只读查询', defaultPort: 5432, icon: 'lucide:database', enabled: true },
  { value: 'mysql', label: 'MySQL', description: '库表元数据与只读查询', defaultPort: 3306, icon: 'lucide:database', enabled: true },
  { value: 'clickhouse', label: 'ClickHouse', description: 'HTTP JDBC 与分析查询', defaultPort: 8123, icon: 'lucide:database', enabled: true },
  { value: 'oracle', label: 'Oracle', description: 'Service Name 与 Schema', defaultPort: 1521, icon: 'lucide:database', enabled: true },
  { value: 'sqlserver', label: 'SQL Server', description: 'Database、Schema 与只读查询', defaultPort: 1433, icon: 'lucide:database', enabled: true }
];
const databaseChoiceMap = new Map(databaseChoices.map(choice => [choice.value, choice]));

const sourceVisible = ref(false);
const sourceSubmitting = ref(false);
const sourceStep = ref(1);
const sourceFormRef = ref<FormInst | null>(null);
const editingSource = ref<DataSourceView | null>(null);
const sourceForm = reactive({
  dbType: 'postgresql' as DataSourceDatabaseType,
  sourceKey: '',
  name: '',
  host: '',
  port: 5432,
  databaseName: '',
  credentialEnv: '',
  sslMode: 'prefer',
  status: 'active' as DataSourceView['status'],
  connectionTimeoutSeconds: 5,
  statementTimeoutSeconds: 30,
  maxRows: 1000,
  maxResultMb: 1
});
const sourceRules: FormRules = {
  sourceKey: [
    { required: true, message: '请输入稳定标识', trigger: ['input', 'blur'] },
    { pattern: /^[a-z0-9][a-z0-9._-]*$/, message: '仅支持小写字母、数字、点、下划线和短横线', trigger: ['input', 'blur'] }
  ],
  name: [{ required: true, message: '请输入数据源名称', trigger: ['input', 'blur'] }],
  host: [{ required: true, message: '请输入数据库主机', trigger: ['input', 'blur'] }],
  databaseName: [{ required: true, message: '请输入数据库名', trigger: ['input', 'blur'] }],
  credentialEnv: [
    { pattern: /^[A-Z][A-Z0-9_]{0,127}$/, message: '使用大写字母、数字和下划线，且必须以字母开头', trigger: ['input', 'blur'] }
  ]
};

const datasetVisible = ref(false);
const datasetSubmitting = ref(false);
const datasetStep = ref(1);
const syncAfterSave = ref(true);
const datasetFormRef = ref<FormInst | null>(null);
const editingDataset = ref<DatasetView | null>(null);
const datasetForm = reactive({
  dataSourceId: null as string | null,
  datasetKey: '',
  name: '',
  description: '',
  schemaNames: ['public'] as string[],
  status: 'active' as DatasetView['status']
});
const datasetRules: FormRules = {
  dataSourceId: [{ required: true, message: '请选择数据源', trigger: ['change', 'blur'] }],
  datasetKey: [
    { required: true, message: '请输入稳定标识', trigger: ['input', 'blur'] },
    { pattern: /^[a-z0-9][a-z0-9._-]*$/, message: '仅支持小写字母、数字、点、下划线和短横线', trigger: ['input', 'blur'] }
  ],
  name: [{ required: true, message: '请输入数据集名称', trigger: ['input', 'blur'] }]
};

const metadataVisible = ref(false);
const metadataLoading = ref(false);
const metadataTab = ref('catalog');
const metadataLoadError = ref('');
const activeDataset = ref<DatasetView | null>(null);
const metadata = ref<DataTableView[]>([]);
const syncingDatasetIds = ref(new Set<string>());
const metadataSyncErrors = ref<Record<string, string>>({});
const metadataSyncResult = ref<MetadataSyncView | null>(null);

const queryForm = reactive({ datasetId: null as string | null, userQuery: '', sql: '' });
const querySubmitting = ref(false);
const queryExporting = ref(false);
const queryValidation = ref<DataQueryValidationView | null>(null);
const queryResult = ref<DataQueryResultView | null>(null);

const activeSources = computed(() => sources.value.filter(source => source.status === 'active'));
const sourceOptions = computed<SelectOption[]>(() =>
  activeSources.value.map(source => ({ label: `${source.name} / ${source.databaseName}`, value: source.id }))
);
const datasetOptions = computed<SelectOption[]>(() =>
  datasets.value.filter(dataset => dataset.status === 'active').map(dataset => ({ label: dataset.name, value: dataset.id }))
);
const sslModeOptions = computed(() => dataSourceSslOptions(sourceForm.dbType) as SelectOption[]);
const selectedDatasetSource = computed(() => sources.value.find(source => source.id === datasetForm.dataSourceId));

function statusType(status: string | null) {
  if (['active', 'success', 'connected'].includes(status || '')) return 'success';
  if (['failed', 'error'].includes(status || '')) return 'error';
  return 'default';
}

function statusText(status: string | null) {
  return ({ active: '启用', disabled: '停用', success: '成功', failed: '失败', connected: '已连接', error: '错误' } as Record<string, string>)[status || ''] || status || '未测试';
}

async function loadData() {
  loading.value = true;
  const [sourceResult, datasetResult] = await Promise.all([fetchDataSources(), fetchDatasets()]);
  if (!sourceResult.error) sources.value = sourceResult.data;
  if (!datasetResult.error) {
    datasets.value = datasetResult.data;
    if (activeDataset.value) {
      activeDataset.value = datasetResult.data.find(dataset => dataset.id === activeDataset.value?.id) || activeDataset.value;
    }
  }
  loading.value = false;
}

function requestError(error: unknown, fallback: string) {
  if (!error || typeof error !== 'object') return fallback;
  const value = error as { response?: { data?: { msg?: string; message?: string } }; message?: string };
  return value.response?.data?.msg || value.response?.data?.message || value.message || fallback;
}

function setDatasetSyncing(datasetId: string, syncing: boolean) {
  const next = new Set(syncingDatasetIds.value);
  if (syncing) next.add(datasetId);
  else next.delete(datasetId);
  syncingDatasetIds.value = next;
}

function parseEndpoint(endpointUrl: string, dbType: DataSourceDatabaseType, fallbackDatabase: string) {
  const choice = databaseChoiceMap.get(dbType) || databaseChoices[0];
  const prefix = `${dbType}://`;
  const legacyPrefix = `jdbc:${dbType}://`;
  const value = endpointUrl.startsWith(legacyPrefix)
    ? endpointUrl.slice(legacyPrefix.length)
    : endpointUrl.startsWith(prefix)
      ? endpointUrl.slice(prefix.length)
      : endpointUrl;
  const slashIndex = value.indexOf('/');
  const authority = slashIndex >= 0 ? value.slice(0, slashIndex) : value;
  const path = slashIndex >= 0 ? value.slice(slashIndex + 1) : '';
  const databaseName = path.split('?', 1)[0] || fallbackDatabase;
  if (authority.startsWith('[')) {
    const bracketIndex = authority.indexOf(']');
    const host = bracketIndex >= 0 ? authority.slice(0, bracketIndex + 1) : authority;
    const portText = bracketIndex >= 0 && authority[bracketIndex + 1] === ':' ? authority.slice(bracketIndex + 2) : '';
    return { host, port: Number(portText) || choice.defaultPort, databaseName };
  }
  const colonIndex = authority.lastIndexOf(':');
  const host = colonIndex > 0 ? authority.slice(0, colonIndex) : authority;
  const port = colonIndex > 0 ? Number(authority.slice(colonIndex + 1)) || choice.defaultPort : choice.defaultPort;
  return { host, port, databaseName };
}

function buildEndpoint() {
  const host = sourceForm.host.trim();
  const renderedHost = host.includes(':') && !host.startsWith('[') ? `[${host}]` : host;
  return `${sourceForm.dbType}://${renderedHost}:${sourceForm.port}`;
}

function openSource(source?: DataSourceView) {
  editingSource.value = source || null;
  const dbType = source?.dbType || 'postgresql';
  const choice = databaseChoiceMap.get(dbType) || databaseChoices[0];
  const connection = source
    ? parseEndpoint(source.endpointUrl, dbType, source.databaseName)
    : { host: '', port: choice.defaultPort, databaseName: '' };
  sourceForm.dbType = dbType;
  sourceForm.sourceKey = source?.sourceKey || '';
  sourceForm.name = source?.name || '';
  sourceForm.host = connection.host;
  sourceForm.port = connection.port;
  sourceForm.databaseName = connection.databaseName;
  sourceForm.credentialEnv = '';
  sourceForm.sslMode = normalizeDataSourceSslMode(dbType, source?.config?.sslMode);
  sourceForm.status = source?.status || 'active';
  sourceForm.connectionTimeoutSeconds = Math.max(1, Math.round((source?.connectionTimeoutMs || 5000) / 1000));
  sourceForm.statementTimeoutSeconds = Math.max(1, Math.round((source?.statementTimeoutMs || 30000) / 1000));
  sourceForm.maxRows = source?.maxRows || 1000;
  sourceForm.maxResultMb = Math.max(0.1, Number(((source?.maxResultBytes || 1048576) / 1048576).toFixed(1)));
  sourceStep.value = 1;
  sourceVisible.value = true;
}

function chooseDatabase(choice: DatabaseChoice) {
  if (!choice.enabled || editingSource.value) return;
  sourceForm.dbType = choice.value;
  sourceForm.port = choice.defaultPort;
  sourceForm.sslMode = defaultDataSourceSslMode(choice.value);
}

async function validateSourceStep() {
  try {
    await sourceFormRef.value?.validate();
  } catch {
    return false;
  }
  if (sourceStep.value === 2 && (
    !sourceForm.host.trim()
    || !sourceForm.databaseName.trim()
    || !Number.isInteger(sourceForm.port)
    || sourceForm.port < 1
    || sourceForm.port > 65535
  )) {
    window.$message?.warning('请检查数据库主机、端口和数据库名');
    return false;
  }
  return true;
}

async function nextSourceStep() {
  if (await validateSourceStep()) sourceStep.value = Math.min(3, sourceStep.value + 1);
}

async function submitSource(testAfterSave: boolean) {
  if (!sourceForm.sourceKey.match(/^[a-z0-9][a-z0-9._-]*$/) || !sourceForm.name.trim()) {
    sourceStep.value = 2;
    window.$message?.warning('请检查数据源名称和稳定标识');
    return;
  }
  if (!sourceForm.host.trim() || !sourceForm.databaseName.trim()) {
    sourceStep.value = 2;
    window.$message?.warning('请填写数据库主机和数据库名');
    return;
  }
  if (!Number.isInteger(sourceForm.port) || sourceForm.port < 1 || sourceForm.port > 65535) {
    sourceStep.value = 2;
    window.$message?.warning('数据库端口必须在 1 到 65535 之间');
    return;
  }
  if (!editingSource.value && !sourceForm.credentialEnv.match(/^[A-Z][A-Z0-9_]{0,127}$/)) {
    sourceStep.value = 2;
    window.$message?.warning('首次创建需要填写凭证环境变量名称');
    return;
  }
  if (
    !Number.isFinite(sourceForm.connectionTimeoutSeconds)
    || !Number.isFinite(sourceForm.statementTimeoutSeconds)
    || !Number.isFinite(sourceForm.maxRows)
    || !Number.isFinite(sourceForm.maxResultMb)
  ) {
    sourceStep.value = 3;
    window.$message?.warning('请完整填写连接和查询限制');
    return;
  }

  const config = { ...(editingSource.value?.config || {}), sslMode: sourceForm.sslMode };
  const payload: SaveDataSourcePayload = {
    name: sourceForm.name.trim(),
    dbType: sourceForm.dbType,
    endpointUrl: buildEndpoint(),
    databaseName: sourceForm.databaseName.trim(),
    credentialRef: sourceForm.credentialEnv ? `env:${sourceForm.credentialEnv}` : undefined,
    config,
    status: sourceForm.status,
    connectionTimeoutMs: Math.round(sourceForm.connectionTimeoutSeconds * 1000),
    statementTimeoutMs: Math.round(sourceForm.statementTimeoutSeconds * 1000),
    maxRows: sourceForm.maxRows,
    maxResultBytes: Math.round(sourceForm.maxResultMb * 1048576)
  };
  sourceSubmitting.value = true;
  try {
    const result = editingSource.value
      ? await updateDataSource(editingSource.value.id, { ...payload, revisionNo: editingSource.value.revisionNo })
      : await createDataSource({ ...payload, sourceKey: sourceForm.sourceKey.trim() });
    if (result.error) return;
    let tested = false;
    if (testAfterSave) {
      const testResult = await testDataSource(result.data.id);
      if (!testResult.error) {
        tested = testResult.data.success;
        const notify = tested ? window.$message?.success : window.$message?.error;
        notify?.(`${testResult.data.message}，耗时 ${testResult.data.latencyMs} ms`);
      }
    }
    sourceVisible.value = false;
    if (!testAfterSave) window.$message?.success(editingSource.value ? '数据源已更新' : '数据源已创建');
    if (testAfterSave && tested) window.$message?.success('数据源已保存并通过连接测试');
    await loadData();
  } finally {
    sourceSubmitting.value = false;
  }
}

async function runConnectionTest(source: DataSourceView) {
  const { data, error } = await testDataSource(source.id);
  if (!error) {
    const notify = data.success ? window.$message?.success : window.$message?.error;
    notify?.(`${data.message}，耗时 ${data.latencyMs} ms`);
    await loadData();
  }
}

function confirmDeleteSource(source: DataSourceView) {
  dialog.warning({
    title: '删除数据源',
    content: `确认删除“${source.name}”？存在数据集引用时后端会拒绝。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      const { error } = await deleteDataSource(source.id);
      if (!error) {
        window.$message?.success('数据源已删除');
        await loadData();
      }
    }
  });
}

function openDataset(dataset?: DatasetView) {
  editingDataset.value = dataset || null;
  datasetForm.dataSourceId = dataset?.dataSourceId || activeSources.value[0]?.id || null;
  datasetForm.datasetKey = dataset?.datasetKey || '';
  datasetForm.name = dataset?.name || '';
  datasetForm.description = dataset?.description || '';
  datasetForm.schemaNames = dataset?.schemaNames.length ? [...dataset.schemaNames] : ['public'];
  datasetForm.status = dataset?.status || 'active';
  syncAfterSave.value = !dataset?.lastSyncAt;
  datasetStep.value = 1;
  datasetVisible.value = true;
}

async function validateDatasetStep() {
  try {
    await datasetFormRef.value?.validate();
  } catch {
    return false;
  }
  if (datasetStep.value === 1 && !datasetForm.dataSourceId) return false;
  if (datasetStep.value === 2) {
    const names = datasetForm.schemaNames.map(value => value.trim()).filter(Boolean);
    if (!names.length) {
      window.$message?.warning('至少添加一个业务 Schema');
      return false;
    }
  }
  return true;
}

async function nextDatasetStep() {
  if (await validateDatasetStep()) datasetStep.value = Math.min(3, datasetStep.value + 1);
}

async function submitDataset() {
  const schemaNames = [...new Set(datasetForm.schemaNames.map(value => value.trim()).filter(Boolean))];
  if (!datasetForm.dataSourceId || !datasetForm.datasetKey.match(/^[a-z0-9][a-z0-9._-]*$/) || !datasetForm.name.trim()) {
    datasetStep.value = datasetForm.dataSourceId ? 2 : 1;
    window.$message?.warning('请检查数据源、名称和稳定标识');
    return;
  }
  if (!schemaNames.length) {
    datasetStep.value = 2;
    window.$message?.warning('至少添加一个业务 Schema');
    return;
  }
  const payload: SaveDatasetPayload = {
    name: datasetForm.name.trim(),
    description: datasetForm.description.trim() || undefined,
    schemaNames,
    status: datasetForm.status
  };
  datasetSubmitting.value = true;
  try {
    const result = editingDataset.value
      ? await updateDataset(editingDataset.value.id, { ...payload, revisionNo: editingDataset.value.revisionNo })
      : await createDataset({ ...payload, dataSourceId: datasetForm.dataSourceId, datasetKey: datasetForm.datasetKey.trim() });
    if (result.error) return;
    if (syncAfterSave.value) {
      const syncResult = await syncDatasetMetadata(result.data.id);
      if (syncResult.error) return;
      window.$message?.success(`数据集已保存，同步到 ${syncResult.data.tableCount} 张表和 ${syncResult.data.columnCount} 个字段`);
    } else {
      window.$message?.success(editingDataset.value ? '数据集已更新' : '数据集已创建');
    }
    datasetVisible.value = false;
    await loadData();
  } finally {
    datasetSubmitting.value = false;
  }
}

async function syncMetadata(dataset: DatasetView) {
  if (syncingDatasetIds.value.has(dataset.id)) return;
  setDatasetSyncing(dataset.id, true);
  metadataSyncErrors.value = { ...metadataSyncErrors.value, [dataset.id]: '' };
  try {
    const { data, error } = await syncDatasetMetadata(dataset.id);
    if (error) {
      metadataSyncErrors.value = {
        ...metadataSyncErrors.value,
        [dataset.id]: requestError(error, '元数据同步失败')
      };
      return;
    }
    metadataSyncResult.value = data;
    window.$message?.success(`同步完成：${data.tableCount} 张表，${data.columnCount} 个字段`);
    await loadData();
    if (activeDataset.value?.id === dataset.id) await loadMetadata();
  } catch (error) {
    metadataSyncErrors.value = {
      ...metadataSyncErrors.value,
      [dataset.id]: requestError(error, '元数据同步失败')
    };
  } finally {
    setDatasetSyncing(dataset.id, false);
  }
}

async function openMetadata(dataset: DatasetView) {
  activeDataset.value = dataset;
  metadata.value = [];
  metadataTab.value = 'catalog';
  metadataLoadError.value = '';
  metadataSyncResult.value = null;
  metadataVisible.value = true;
  await loadMetadata();
}

async function loadMetadata() {
  if (!activeDataset.value) return;
  metadataLoading.value = true;
  metadataLoadError.value = '';
  try {
    const { data, error } = await fetchDatasetMetadata(activeDataset.value.id);
    if (error) metadataLoadError.value = requestError(error, '元数据目录加载失败');
    else metadata.value = data;
  } catch (error) {
    metadataLoadError.value = requestError(error, '元数据目录加载失败');
  } finally {
    metadataLoading.value = false;
  }
}

async function metadataImportApplied() {
  await Promise.all([loadMetadata(), loadData()]);
}

const deleteImpactLabels: Record<string, string> = {
  active_task_bindings: '活动任务绑定',
  active_reports: '未归档报表',
  running_data_queries: '进行中的数据查询',
  running_profile_jobs: '进行中的画像任务',
  draft_smart_imports: '未过期智能导入预览',
  draft_catalog_imports: '未过期 DDL/YAML 导入预览',
  running_metadata_syncs: '进行中的元数据同步',
  active_agent_dataset_bindings: '活动 Agent 数据集绑定',
  active_permission_profile_references: '权限包数据集引用',
  active_permission_override_references: '用户权限覆盖引用',
  active_temporary_grant_references: '临时授权数据集引用',
  active_permission_snapshot_references: '用户权限快照引用'
};

function deleteImpactText(impact: DatasetDeleteImpactView | null) {
  if (!impact || impact.blockingTotal === 0) return '当前没有检测到活动引用。';
  return impact.categories
    .filter(category => category.count > 0)
    .map(category => `${deleteImpactLabels[category.category] || category.category} ${category.count} 项`)
    .join('；');
}

async function confirmDeleteDataset(dataset: DatasetView) {
  const impactResult = await fetchDatasetDeleteImpact(dataset.id);
  if (impactResult.error) {
    window.$message?.error(requestError(impactResult.error, '无法读取数据集删除影响，请稍后重试'));
    return;
  }
  const impact = impactResult.data;
  dialog.warning({
    title: '删除数据集',
    content: `确认删除“${dataset.name}”？${deleteImpactText(impact)}${impact?.deletable ? '' : ' 存在活动引用时服务端会拒绝删除。'}`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      const result = await deleteDataset(dataset.id);
      const { error } = result;
      if (!error) {
        window.$message?.success('数据集已删除');
        await loadData();
      } else if (result.response?.status === 409) {
        const latestImpact = await fetchDatasetDeleteImpact(dataset.id);
        const message = latestImpact.error
          ? requestError(error, '数据集存在活动引用，删除未执行')
          : `数据集存在活动引用，删除未执行：${deleteImpactText(latestImpact.data)}`;
        window.$message?.warning(message);
      }
    }
  });
}

async function validateQuery() {
  if (!queryForm.datasetId || !queryForm.userQuery.trim() || !queryForm.sql.trim()) {
    window.$message?.warning('请选择数据集并填写问题和 SQL');
    return;
  }
  querySubmitting.value = true;
  queryResult.value = null;
  const { data, error } = await validateDataQuery({
    datasetId: queryForm.datasetId,
    userQuery: queryForm.userQuery.trim(),
    sql: queryForm.sql.trim()
  });
  if (!error) {
    queryValidation.value = data;
    window.$message?.success('SQL 已通过只读与授权校验');
  }
  querySubmitting.value = false;
}

async function executeQuery() {
  if (!queryValidation.value || !queryForm.datasetId) return;
  querySubmitting.value = true;
  const { data, error } = await executeDataQuery({
    datasetId: queryForm.datasetId,
    userQuery: queryForm.userQuery.trim(),
    sql: queryForm.sql.trim()
  });
  if (!error) queryResult.value = data;
  querySubmitting.value = false;
}

async function exportQuery(format: DataQueryExportFormat) {
  if (!queryResult.value) return;
  queryExporting.value = true;
  try {
    const { blob, fileName } = await downloadDataQueryExport(queryResult.value.queryId, format);
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = fileName;
    anchor.click();
    URL.revokeObjectURL(url);
    window.$message?.success(`${format === 'xlsx' ? 'Excel' : 'CSV'} 已从持久化查询快照导出`);
  } catch (error) {
    window.$message?.error(error instanceof Error ? error.message : '查询结果导出失败');
  } finally {
    queryExporting.value = false;
  }
}

watch(() => [queryForm.datasetId, queryForm.userQuery, queryForm.sql], () => {
  queryValidation.value = null;
  queryResult.value = null;
});

const sourceColumns: DataTableColumns<DataSourceView> = [
  { title: '数据源', key: 'name', minWidth: 240, render: row => h('div', { class: 'primary-cell' }, [h('strong', row.name), h('span', `${row.sourceKey} / ${row.databaseName}`)]) },
  { title: '数据库', key: 'dbType', width: 120, render: row => databaseChoiceMap.get(row.dbType)?.label || row.dbType },
  { title: '地址', key: 'endpointUrl', minWidth: 260, ellipsis: { tooltip: true } },
  { title: '凭证', key: 'credentialConfigured', width: 95, render: row => row.credentialConfigured ? '已托管' : '未配置' },
  { title: '连接测试', key: 'lastTestStatus', width: 115, render: row => h(NTag, { size: 'small', type: statusType(row.lastTestStatus) }, () => statusText(row.lastTestStatus)) },
  { title: '状态', key: 'status', width: 90, render: row => h(NTag, { size: 'small', type: statusType(row.status) }, () => statusText(row.status)) },
  { title: '更新时间', key: 'updateTime', width: 165, render: row => dayjs(row.updateTime || row.createTime).format('YYYY-MM-DD HH:mm') },
  { title: '操作', key: 'actions', width: 240, fixed: 'right', render: row => h(NSpace, { size: 6 }, () => [
    h(NButton, { size: 'small', type: 'primary', secondary: true, onClick: () => runConnectionTest(row) }, () => '测试'),
    h(NButton, { size: 'small', quaternary: true, onClick: () => openSource(row) }, () => '编辑'),
    h(NButton, { size: 'small', type: 'error', quaternary: true, onClick: () => confirmDeleteSource(row) }, () => '删除')
  ]) }
];

const datasetColumns: DataTableColumns<DatasetView> = [
  { title: '数据集', key: 'name', minWidth: 240, render: row => h('div', { class: 'primary-cell' }, [h('strong', row.name), h('span', `${row.datasetKey} / ${row.description || '暂无描述'}`)]) },
  { title: '数据源', key: 'dataSourceId', width: 150, render: row => sources.value.find(source => source.id === row.dataSourceId)?.name || '数据源不可用' },
  { title: 'Schema', key: 'schemaNames', minWidth: 180, render: row => row.schemaNames.join(', ') },
  {
    title: '元数据同步',
    key: 'lastSyncAt',
    minWidth: 210,
    render: row => h('div', { class: 'primary-cell' }, [
      h('strong', row.lastSyncAt ? dayjs(row.lastSyncAt).format('YYYY-MM-DD HH:mm') : '尚未同步'),
      h('span', metadataSyncErrors.value[row.id] || row.lastSyncError || '同步状态正常')
    ])
  },
  { title: '状态', key: 'status', width: 90, render: row => h(NTag, { size: 'small', type: statusType(row.status) }, () => statusText(row.status)) },
  { title: '操作', key: 'actions', width: 310, fixed: 'right', render: row => h(NSpace, { size: 6 }, () => [
    h(NButton, {
      size: 'small',
      type: 'primary',
      secondary: true,
      loading: syncingDatasetIds.value.has(row.id),
      disabled: syncingDatasetIds.value.has(row.id),
      onClick: () => syncMetadata(row)
    }, () => '同步'),
    h(NButton, { size: 'small', quaternary: true, onClick: () => openMetadata(row) }, () => '元数据'),
    h(NButton, { size: 'small', quaternary: true, onClick: () => openDataset(row) }, () => '编辑'),
    h(NButton, { size: 'small', type: 'error', quaternary: true, onClick: () => confirmDeleteDataset(row) }, () => '删除')
  ]) }
];

const resultColumns = computed<DataTableColumns<Record<string, unknown>>>(() =>
  (queryResult.value?.columns || []).map((column, index) => ({ title: column, key: String(index), minWidth: 140, render: row => String(row[String(index)] ?? '') }))
);
const resultRows = computed(() => (queryResult.value?.rows || []).map((row, rowIndex) => Object.fromEntries([['__key', rowIndex], ...row.map((value, index) => [String(index), value])])));

onMounted(loadData);
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h2>{{ $t('page.dataSource.title') }}</h2>
        <p>{{ $t('page.dataSource.desc') }}</p>
      </div>
    </div>
    <NTabs v-model:value="activeTab" type="line" animated>
      <NTabPane name="sources" tab="数据源">
        <div class="tab-toolbar">
          <NButton secondary :loading="loading" @click="loadData">
            <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
            刷新
          </NButton>
          <NButton type="primary" @click="openSource()">
            <template #icon><SvgIcon icon="lucide:plus" /></template>
            添加数据源
          </NButton>
        </div>
        <NDataTable :columns="sourceColumns" :data="sources" :loading="loading" :scroll-x="1390" :row-key="row => row.id" />
      </NTabPane>
      <NTabPane name="datasets" tab="数据集与元数据">
        <div class="tab-toolbar">
          <span></span>
          <NButton type="primary" :disabled="!activeSources.length" @click="openDataset()">
            <template #icon><SvgIcon icon="lucide:plus" /></template>
            创建数据集
          </NButton>
        </div>
        <NDataTable :columns="datasetColumns" :data="datasets" :loading="loading" :scroll-x="1250" :row-key="row => row.id" />
      </NTabPane>
      <NTabPane name="query" tab="只读查询">
        <div class="query-layout">
          <NForm label-placement="top">
            <NFormItem label="授权数据集"><NSelect v-model:value="queryForm.datasetId" :options="datasetOptions" filterable /></NFormItem>
            <NFormItem label="业务问题"><NInput v-model:value="queryForm.userQuery" placeholder="例如：统计最近 30 天订单数" maxlength="4000" /></NFormItem>
            <NFormItem label="只读 SQL"><NInput v-model:value="queryForm.sql" type="textarea" :autosize="{ minRows: 7, maxRows: 14 }" placeholder="SELECT ..." maxlength="65536" /></NFormItem>
          </NForm>
          <NSpace>
            <NButton type="primary" :loading="querySubmitting" @click="validateQuery"><template #icon><SvgIcon icon="lucide:shield-check" /></template>校验 SQL</NButton>
            <NButton type="success" :loading="querySubmitting" :disabled="!queryValidation" @click="executeQuery"><template #icon><SvgIcon icon="lucide:play" /></template>确认执行</NButton>
          </NSpace>
          <NAlert v-if="queryValidation" type="success" :bordered="false">
            已授权表：{{ queryValidation.tables.join(', ') || '-' }}；最大 {{ queryValidation.maxRows }} 行；超时 {{ queryValidation.statementTimeoutMs }} ms
          </NAlert>
          <div v-if="queryResult" class="query-result">
            <div class="result-summary">
              <span>查询 {{ queryResult.rowCount }} 行，耗时 {{ queryResult.elapsedMs }} ms</span>
              <NTag v-if="queryResult.truncated" type="warning" size="small">结果已截断</NTag>
              <NDropdown :options="queryExportOptions" trigger="click" @select="exportQuery">
                <NButton size="small" secondary :loading="queryExporting">
                  <template #icon><SvgIcon icon="lucide:download" /></template>
                  导出
                </NButton>
              </NDropdown>
            </div>
            <NDataTable :columns="resultColumns" :data="resultRows" :row-key="row => row.__key as number" :scroll-x="Math.max(700, resultColumns.length * 140)" max-height="440" />
          </div>
        </div>
      </NTabPane>
    </NTabs>

    <NModal
      v-model:show="sourceVisible"
      preset="card"
      :title="editingSource ? '编辑数据源' : '添加数据源'"
      :mask-closable="false"
      class="source-modal"
      style="width: min(960px, calc(100vw - 32px))"
    >
      <NSteps :current="sourceStep" size="small" class="wizard-steps">
        <NStep title="选择数据库" />
        <NStep title="连接信息" />
        <NStep title="安全与限制" />
      </NSteps>
      <NForm ref="sourceFormRef" :model="sourceForm" :rules="sourceRules" label-placement="top">
        <div v-if="sourceStep === 1" class="step-content">
          <div class="section-heading">
            <h3>选择数据库类型</h3>
            <p>选择企业业务库类型。所有连接都经过只读策略、网络目标校验和数据集授权。</p>
          </div>
          <div class="database-grid">
            <button
              v-for="choice in databaseChoices"
              :key="choice.value"
              type="button"
              class="database-choice"
              :class="{ selected: sourceForm.dbType === choice.value, disabled: !choice.enabled }"
              :disabled="!choice.enabled || Boolean(editingSource)"
              @click="chooseDatabase(choice)"
            >
              <SvgIcon :icon="choice.icon" class="database-icon" />
              <span class="database-label">{{ choice.label }}</span>
              <span class="database-note">{{ choice.description }}</span>
              <NTag :type="choice.enabled ? 'success' : 'default'" size="small">{{ choice.enabled ? '已支持' : '暂未开放' }}</NTag>
            </button>
          </div>
        </div>

        <div v-else-if="sourceStep === 2" class="step-content">
          <div class="section-heading">
            <h3>填写 {{ databaseChoiceMap.get(sourceForm.dbType)?.label }} 连接信息</h3>
            <p>连接目标由主机和端口生成，数据库名或 Service Name 单独保存。</p>
          </div>
          <div class="form-grid">
            <NFormItem label="稳定标识" path="sourceKey">
              <NInput v-model:value="sourceForm.sourceKey" :disabled="Boolean(editingSource)" placeholder="analytics_pg" />
            </NFormItem>
            <NFormItem label="数据源名称" path="name"><NInput v-model:value="sourceForm.name" placeholder="经营分析库" /></NFormItem>
            <NFormItem label="主机地址" path="host"><NInput v-model:value="sourceForm.host" placeholder="db.internal" /></NFormItem>
            <NFormItem label="端口"><NInputNumber v-model:value="sourceForm.port" :min="1" :max="65535" /></NFormItem>
            <NFormItem :label="sourceForm.dbType === 'oracle' ? 'Service Name' : '数据库名'" path="databaseName"><NInput v-model:value="sourceForm.databaseName" placeholder="analytics" /></NFormItem>
            <NFormItem label="凭证环境变量" path="credentialEnv">
              <NInputGroup>
                <NInputGroupLabel>env:</NInputGroupLabel>
                <NInput v-model:value="sourceForm.credentialEnv" :placeholder="editingSource ? '留空保持原凭证' : 'ANALYTICS_DB'" />
              </NInputGroup>
            </NFormItem>
          </div>
          <NAlert type="info" :bordered="false">
            凭证由服务端环境变量托管，值为仅包含 username、password 的 JSON；页面不会保存或回显明文。
          </NAlert>
        </div>

        <div v-else class="step-content">
          <div class="section-heading">
            <h3>设置安全策略与查询限制</h3>
            <p>这些限制同时作用于连接测试、元数据同步和只读查询。</p>
          </div>
          <div class="form-grid">
            <NFormItem label="SSL 模式"><NSelect v-model:value="sourceForm.sslMode" :options="sslModeOptions" /></NFormItem>
            <NFormItem label="数据源状态">
              <NSwitch v-model:value="sourceForm.status" checked-value="active" unchecked-value="disabled">
                <template #checked>启用</template>
                <template #unchecked>停用</template>
              </NSwitch>
            </NFormItem>
            <NFormItem label="连接超时（秒）"><NInputNumber v-model:value="sourceForm.connectionTimeoutSeconds" :min="1" :max="30" /></NFormItem>
            <NFormItem label="查询超时（秒）"><NInputNumber v-model:value="sourceForm.statementTimeoutSeconds" :min="1" :max="120" /></NFormItem>
            <NFormItem label="单次最大行数"><NInputNumber v-model:value="sourceForm.maxRows" :min="1" :max="5000" /></NFormItem>
            <NFormItem label="单次最大结果（MB）"><NInputNumber v-model:value="sourceForm.maxResultMb" :min="0.1" :max="10" :step="0.1" /></NFormItem>
          </div>
          <NDescriptions :column="1" bordered size="small">
            <NDescriptionsItem label="连接地址">{{ buildEndpoint() }}</NDescriptionsItem>
            <NDescriptionsItem label="凭证">{{ editingSource && !sourceForm.credentialEnv ? '保持现有托管凭证' : `env:${sourceForm.credentialEnv || '未填写'}` }}</NDescriptionsItem>
          </NDescriptions>
        </div>
      </NForm>
      <template #footer>
        <div class="wizard-footer">
          <NButton @click="sourceVisible = false">取消</NButton>
          <NSpace>
            <NButton v-if="sourceStep > 1" secondary @click="sourceStep--"><template #icon><SvgIcon icon="lucide:arrow-left" /></template>上一步</NButton>
            <NButton v-if="sourceStep < 3" type="primary" @click="nextSourceStep">下一步<template #icon><SvgIcon icon="lucide:arrow-right" /></template></NButton>
            <template v-else>
              <NButton :loading="sourceSubmitting" secondary @click="submitSource(false)">仅保存</NButton>
              <NButton type="primary" :loading="sourceSubmitting" @click="submitSource(true)"><template #icon><SvgIcon icon="lucide:plug-zap" /></template>保存并测试</NButton>
            </template>
          </NSpace>
        </div>
      </template>
    </NModal>

    <NModal
      v-model:show="datasetVisible"
      preset="card"
      :title="editingDataset ? '编辑数据集' : '创建数据集'"
      :mask-closable="false"
      class="dataset-modal"
      style="width: min(820px, calc(100vw - 32px))"
    >
      <NSteps :current="datasetStep" size="small" class="wizard-steps">
        <NStep title="选择数据源" />
        <NStep title="定义数据集" />
        <NStep title="同步元数据" />
      </NSteps>
      <NForm ref="datasetFormRef" :model="datasetForm" :rules="datasetRules" label-placement="top">
        <div v-if="datasetStep === 1" class="step-content compact-step">
          <div class="section-heading">
            <h3>选择已连接的数据源</h3>
            <p>数据集只能绑定一个启用的数据源，创建后不可更换。</p>
          </div>
          <NFormItem label="数据源" path="dataSourceId">
            <NSelect v-model:value="datasetForm.dataSourceId" :options="sourceOptions" :disabled="Boolean(editingDataset)" filterable />
          </NFormItem>
          <NAlert v-if="selectedDatasetSource" :type="selectedDatasetSource.lastTestStatus === 'success' ? 'success' : 'warning'" :bordered="false">
            {{ selectedDatasetSource.name }} / {{ selectedDatasetSource.databaseName }} / 连接状态：{{ statusText(selectedDatasetSource.lastTestStatus) }}
          </NAlert>
        </div>

        <div v-else-if="datasetStep === 2" class="step-content compact-step">
          <div class="section-heading">
            <h3>定义业务数据集</h3>
            <p>Schema 决定后续元数据同步和只读查询的授权范围。</p>
          </div>
          <div class="form-grid">
            <NFormItem label="稳定标识" path="datasetKey"><NInput v-model:value="datasetForm.datasetKey" :disabled="Boolean(editingDataset)" placeholder="sales_analysis" /></NFormItem>
            <NFormItem label="数据集名称" path="name"><NInput v-model:value="datasetForm.name" placeholder="销售经营分析" /></NFormItem>
            <NFormItem label="允许的 Schema" class="full-field"><NDynamicTags v-model:value="datasetForm.schemaNames" :max="16" /></NFormItem>
            <NFormItem label="业务描述" class="full-field"><NInput v-model:value="datasetForm.description" type="textarea" :autosize="{ minRows: 3, maxRows: 6 }" maxlength="4000" /></NFormItem>
            <NFormItem label="数据集状态">
              <NSwitch v-model:value="datasetForm.status" checked-value="active" unchecked-value="disabled">
                <template #checked>启用</template>
                <template #unchecked>停用</template>
              </NSwitch>
            </NFormItem>
          </div>
        </div>

        <div v-else class="step-content compact-step">
          <div class="section-heading">
            <h3>确认并同步元数据</h3>
            <p>同步会读取表与字段定义，不会读取业务数据内容。</p>
          </div>
          <NDescriptions :column="2" bordered size="small">
            <NDescriptionsItem label="数据源">{{ selectedDatasetSource?.name || '未选择' }}</NDescriptionsItem>
            <NDescriptionsItem label="数据集">{{ datasetForm.name }}</NDescriptionsItem>
            <NDescriptionsItem label="Schema">{{ datasetForm.schemaNames.join(', ') }}</NDescriptionsItem>
            <NDescriptionsItem label="状态">{{ statusText(datasetForm.status) }}</NDescriptionsItem>
          </NDescriptions>
          <NCheckbox v-model:checked="syncAfterSave" class="sync-option">保存后立即同步表和字段元数据</NCheckbox>
        </div>
      </NForm>
      <template #footer>
        <div class="wizard-footer">
          <NButton @click="datasetVisible = false">取消</NButton>
          <NSpace>
            <NButton v-if="datasetStep > 1" secondary @click="datasetStep--"><template #icon><SvgIcon icon="lucide:arrow-left" /></template>上一步</NButton>
            <NButton v-if="datasetStep < 3" type="primary" @click="nextDatasetStep">下一步<template #icon><SvgIcon icon="lucide:arrow-right" /></template></NButton>
            <NButton v-else type="primary" :loading="datasetSubmitting" @click="submitDataset"><template #icon><SvgIcon :icon="syncAfterSave ? 'lucide:refresh-cw' : 'lucide:save'" /></template>{{ syncAfterSave ? '保存并同步' : '保存数据集' }}</NButton>
          </NSpace>
        </div>
      </template>
    </NModal>

    <NDrawer v-model:show="metadataVisible" width="min(1180px, calc(100vw - 16px))" placement="right">
      <NDrawerContent closable>
        <template #header>
          <div class="drawer-header">
            <span>{{ activeDataset?.name || '' }}元数据</span>
            <NButton
              size="small"
              type="primary"
              :loading="Boolean(activeDataset && syncingDatasetIds.has(activeDataset.id))"
              :disabled="Boolean(activeDataset && syncingDatasetIds.has(activeDataset.id))"
              @click="activeDataset && syncMetadata(activeDataset)"
            ><template #icon><SvgIcon icon="lucide:refresh-cw" /></template>同步元数据</NButton>
          </div>
        </template>
        <div v-if="activeDataset" class="metadata-dataset-summary">
          <div><span>数据集标识</span><strong>{{ activeDataset.datasetKey }}</strong></div>
          <div><span>授权 Schema</span><strong>{{ activeDataset.schemaNames.join(', ') || '-' }}</strong></div>
          <div><span>上次同步</span><strong>{{ activeDataset.lastSyncAt ? dayjs(activeDataset.lastSyncAt).format('YYYY-MM-DD HH:mm') : '尚未同步' }}</strong></div>
          <div><span>目录规模</span><strong>{{ metadata.length }} 张表 · {{ metadata.reduce((total, table) => total + table.columns.length, 0) }} 个字段</strong></div>
        </div>
        <NAlert v-if="activeDataset?.lastSyncError || (activeDataset && metadataSyncErrors[activeDataset.id])" type="error" :bordered="false" class="mb-12px">
          {{ activeDataset && metadataSyncErrors[activeDataset.id] ? metadataSyncErrors[activeDataset.id] : activeDataset?.lastSyncError }}
        </NAlert>
        <NAlert v-if="metadataLoadError" type="error" :bordered="false" class="mb-12px">{{ metadataLoadError }}</NAlert>
        <NAlert v-if="metadataSyncResult" type="success" :bordered="false" class="mb-12px">
          本次同步完成：{{ metadataSyncResult.tableCount }} 张表、{{ metadataSyncResult.columnCount }} 个字段，完成时间 {{ dayjs(metadataSyncResult.synchronizedAt).format('YYYY-MM-DD HH:mm:ss') }}。
        </NAlert>
        <NTabs v-model:value="metadataTab" type="line" animated class="metadata-tabs">
          <NTabPane name="catalog" tab="目录与字段">
            <MetadataGovernance
              :dataset-id="activeDataset?.id || ''"
              :metadata="metadata"
              :loading="metadataLoading"
              @refresh="loadMetadata"
            />
          </NTabPane>
          <NTabPane name="metrics" tab="业务指标">
            <MetadataMetrics v-if="metadataTab === 'metrics'" :dataset-id="activeDataset?.id || ''" />
          </NTabPane>
          <NTabPane name="profile" tab="智能画像">
            <MetadataProfile
              v-if="metadataTab === 'profile'"
              :dataset-id="activeDataset?.id || ''"
              @applied="loadMetadata"
            />
          </NTabPane>
          <NTabPane name="import" tab="导入导出">
            <MetadataImport
              v-if="metadataTab === 'import'"
              :dataset-id="activeDataset?.id || ''"
              :dataset-name="activeDataset?.name"
              @applied="metadataImportApplied"
            />
          </NTabPane>
          <NTabPane name="relations" tab="表关系">
            <MetadataRelations
              v-if="metadataTab === 'relations'"
              :dataset-id="activeDataset?.id || ''"
              :metadata="metadata"
            />
          </NTabPane>
          <NTabPane name="row-policy" tab="行策略">
            <MetadataRowPolicy
              v-if="metadataTab === 'row-policy'"
              :dataset-id="activeDataset?.id || ''"
              :metadata="metadata"
            />
          </NTabPane>
          <NTabPane name="changes" tab="变更记录">
            <MetadataChanges v-if="metadataTab === 'changes'" :dataset-id="activeDataset?.id || ''" />
          </NTabPane>
        </NTabs>
      </NDrawerContent>
    </NDrawer>
  </div>
</template>

<style scoped lang="scss">
.page-container { padding: 16px; }
.page-header { margin-bottom: 16px; }
.page-header h2 { margin: 0 0 4px; font-size: 20px; }
.page-header p, .section-heading p { margin: 0; color: var(--n-text-color-3); }
.tab-toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.query-layout { display: grid; max-width: 1100px; gap: 16px; }
.query-result { min-width: 0; }
.result-summary { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; font-weight: 500; }
.wizard-steps { margin-bottom: 24px; padding: 0 8px 20px; border-bottom: 1px solid var(--n-border-color); }
.step-content { min-height: 400px; }
.compact-step { min-height: 320px; }
.section-heading { margin-bottom: 20px; }
.section-heading h3 { margin: 0 0 5px; font-size: 17px; }
.database-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.database-choice { display: grid; min-height: 150px; grid-template-columns: auto 1fr; align-content: start; gap: 8px 10px; padding: 16px; border: 1px solid var(--n-border-color); border-radius: 6px; background: var(--n-color); color: var(--n-text-color); text-align: left; cursor: pointer; }
.database-choice:hover:not(:disabled), .database-choice.selected { border-color: var(--n-primary-color); box-shadow: 0 0 0 1px var(--n-primary-color); }
.database-choice.disabled { cursor: not-allowed; opacity: 0.55; }
.database-icon { width: 22px; height: 22px; color: var(--n-primary-color); }
.database-label { align-self: center; font-weight: 700; }
.database-note { grid-column: 1 / -1; min-height: 38px; color: var(--n-text-color-3); font-size: 12px; line-height: 19px; }
.database-choice :deep(.n-tag) { grid-column: 1 / -1; justify-self: start; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 18px; }
.full-field { grid-column: 1 / -1; }
.wizard-footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.sync-option { margin-top: 20px; font-weight: 600; }
.column-table { margin-top: 12px; }
.drawer-header { display: flex; width: 100%; align-items: center; justify-content: space-between; gap: 16px; }
.metadata-dataset-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; margin-bottom: 12px; }
.metadata-dataset-summary > div { display: grid; gap: 4px; padding: 10px; border: 1px solid var(--n-border-color); border-radius: 6px; background: var(--n-action-color); }
.metadata-dataset-summary span { color: var(--n-text-color-3); font-size: 10px; }
.metadata-dataset-summary strong { overflow: hidden; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.metadata-tabs { margin-top: 2px; }
:deep(.primary-cell) { display: flex; min-width: 0; flex-direction: column; gap: 4px; }
:deep(.primary-cell span) { overflow: hidden; color: var(--n-text-color-3); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 700px) {
  .database-grid, .form-grid { grid-template-columns: 1fr; }
  .full-field { grid-column: auto; }
  .wizard-footer { align-items: stretch; flex-direction: column; }
  .wizard-steps { overflow-x: auto; }
  .step-content, .compact-step { min-height: 0; }
  .result-summary { align-items: flex-start; flex-direction: column; }
  .metadata-dataset-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
