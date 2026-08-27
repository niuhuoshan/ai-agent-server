<script setup lang="ts">
import { computed, h, onBeforeUnmount, reactive, ref, watch } from 'vue';
import dayjs from 'dayjs';
import type { DataTableColumns, DataTableRowKey, SelectOption } from 'naive-ui';
import { NButton, NProgress, NSpace, NTag, useDialog } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  applyMetadataSmartImportPreview,
  cancelMetadataProfileJob,
  createMetadataProfileJob,
  createMetadataSmartImportPreview,
  fetchMetadataProfileJob,
  fetchMetadataProfileJobs,
  fetchMetadataRelatedTables,
  fetchMetadataSmartImportPreview,
  fetchMetadataTableProfile,
  fetchMetadataTableProfiles,
  fetchMetadataTableProfileStats,
  resumeMetadataProfileJob,
  updateMetadataTableProfileIgnore
} from '@/service/api';
import type {
  MetadataColumnImportProposalView,
  MetadataColumnProfileView,
  MetadataProfileJobDetailView,
  MetadataProfileJobMode,
  MetadataProfileJobTableView,
  MetadataProfileJobView,
  MetadataProfileSampleValueView,
  MetadataRelatedTableView,
  MetadataSmartImportItemView,
  MetadataSmartImportPreviewView,
  MetadataTableProfileDetailView,
  MetadataTableProfileStatsView,
  MetadataTableProfileSummaryView,
  MetadataTemporaryClassification
} from '@/service/api';
import {
  canResumeMetadataProfileJob,
  formatMetadataProfileValue,
  isMetadataProfileJobActive,
  metadataProfileClassificationText,
  metadataProfileClassificationType,
  metadataProfileJobStatusText,
  metadataProfileJobStatusType,
  metadataProfileProgress,
  metadataProfileRequestError,
  smartImportItemDescription,
  smartImportItemTitle
} from './metadata-profile-state';

const props = defineProps<{ datasetId: string }>();
const emit = defineEmits<{ applied: [] }>();
const dialog = useDialog();

const jobsLoading = ref(false);
const jobDetailLoading = ref(false);
const jobSubmitting = ref(false);
const jobsError = ref('');
const jobs = ref<MetadataProfileJobView[]>([]);
const selectedJobId = ref<string | null>(null);
const selectedJobDetail = ref<MetadataProfileJobDetailView | null>(null);

const statsLoading = ref(false);
const statsError = ref('');
const stats = ref<MetadataTableProfileStatsView | null>(null);

const profilesLoading = ref(false);
const profilesError = ref('');
const profiles = ref<MetadataTableProfileSummaryView[]>([]);
const profileIndex = ref<Record<string, MetadataTableProfileSummaryView>>({});
const profilesTotal = ref(0);
const profilesPages = ref(0);
const checkedProfileIds = ref<DataTableRowKey[]>([]);
const ignoreSubmittingIds = ref(new Set<string>());
const filters = reactive({
  page: 1,
  pageSize: 20,
  query: '',
  tag: null as string | null,
  ignored: 'all' as 'all' | 'active' | 'ignored',
  classification: null as MetadataTemporaryClassification | null,
  status: null as MetadataTableProfileSummaryView['status'] | null,
  sort: 'default_desc'
});

const detailVisible = ref(false);
const detailLoading = ref(false);
const detailError = ref('');
const detail = ref<MetadataTableProfileDetailView | null>(null);
const relatedTables = ref<MetadataRelatedTableView[]>([]);
const relatedLoading = ref(false);
const relatedError = ref('');

const previewVisible = ref(false);
const previewLoading = ref(false);
const previewApplying = ref(false);
const previewError = ref('');
const preview = ref<MetadataSmartImportPreviewView | null>(null);
const activePreviewItem = ref<MetadataSmartImportItemView | null>(null);
const checkedPreviewItemIds = ref<string[]>([]);
const resumablePreviewId = ref<string | null>(null);

let jobPollTimer: number | null = null;
let jobPollInFlight = false;

const activeJob = computed(() => jobs.value.find(job => isMetadataProfileJobActive(job)) || null);
const selectedJob = computed(() => {
  if (selectedJobDetail.value?.job.id === selectedJobId.value) return selectedJobDetail.value.job;
  return jobs.value.find(job => job.id === selectedJobId.value) || null;
});
const selectedJobTables = computed(() => selectedJobDetail.value?.job.id === selectedJobId.value ? selectedJobDetail.value.tables : []);
const selectedTableIds = computed(() => checkedProfileIds.value.map(String));
const selectedProfileJobId = computed(() => {
  const jobIds = new Set(selectedTableIds.value
    .map(tableId => profileIndex.value[tableId]?.jobId)
    .filter((jobId): jobId is string => Boolean(jobId)));
  const jobId = jobIds.size === 1 ? [...jobIds][0] || null : null;
  return jobId;
});
const selectedProfilesImportable = computed(() => Boolean(selectedProfileJobId.value)
  && selectedTableIds.value.length > 0
  && selectedTableIds.value.every(tableId => {
    const item = profileIndex.value[tableId];
    return item?.ignored !== true && item?.profileId !== null && item?.jobId === selectedProfileJobId.value;
  }));
const availablePreviewItems = computed(() => preview.value?.items.filter(item => item.status === 'available') || []);
const tagOptions = computed<SelectOption[]>(() => (stats.value?.tags || []).map(tag => ({
  label: `${tag.name} (${tag.count})`,
  value: tag.name
})));

const classificationOptions: SelectOption[] = [
  { label: '业务表', value: 'business' },
  { label: '临时表', value: 'temporary' },
  { label: '备份表', value: 'backup' },
  { label: '中间表', value: 'staging' },
  { label: '系统表', value: 'system' }
];

const ignoredOptions: SelectOption[] = [
  { label: '全部', value: 'all' },
  { label: '未忽略', value: 'active' },
  { label: '已忽略', value: 'ignored' }
];

const profileStatusOptions: SelectOption[] = [
  { label: '等待画像', value: 'pending' },
  { label: '画像中', value: 'running' },
  { label: '已完成', value: 'success' },
  { label: '失败', value: 'failed' }
];

const sortOptions: SelectOption[] = [
  { label: '默认排序', value: 'default_desc' },
  { label: '置信度从高到低', value: 'confidence_desc' },
  { label: '置信度从低到高', value: 'confidence_asc' },
  { label: '表名 A-Z', value: 'name_asc' },
  { label: '表名 Z-A', value: 'name_desc' },
  { label: '业务术语 A-Z', value: 'term_asc' },
  { label: '最近生成', value: 'created_desc' }
];

function updateJob(job: MetadataProfileJobView, select = true) {
  const index = jobs.value.findIndex(item => item.id === job.id);
  jobs.value = index >= 0
    ? jobs.value.map(item => (item.id === job.id ? { ...item, ...job } : item))
    : [job, ...jobs.value];
  if (selectedJobDetail.value?.job.id === job.id) {
    selectedJobDetail.value = { ...selectedJobDetail.value, job };
  }
  if (select) selectedJobId.value = job.id;
}

function stopJobPolling() {
  if (jobPollTimer !== null) window.clearInterval(jobPollTimer);
  jobPollTimer = null;
}

function syncJobPolling() {
  stopJobPolling();
  if (!activeJob.value) return;
  // Polls only persisted server state; progress is never synthesized in the browser.
  jobPollTimer = window.setInterval(() => {
    if (!activeJob.value || jobPollInFlight) return;
    jobPollInFlight = true;
    void refreshJob(activeJob.value.id, false).finally(() => {
      jobPollInFlight = false;
    });
  }, 3000);
}

async function loadJobs() {
  if (!props.datasetId) return;
  const datasetId = props.datasetId;
  jobsLoading.value = true;
  jobsError.value = '';
  try {
    const result = await fetchMetadataProfileJobs(datasetId, 50);
    if (props.datasetId !== datasetId) return;
    if (result.error) {
      jobsError.value = metadataProfileRequestError(result.error, '画像任务加载失败');
      return;
    }
    jobs.value = result.data;
    const preferred = result.data.find(job => isMetadataProfileJobActive(job)) || result.data[0] || null;
    if (!selectedJobId.value || !result.data.some(job => job.id === selectedJobId.value)) {
      selectedJobId.value = preferred?.id || null;
      selectedJobDetail.value = null;
    }
    if (selectedJobId.value) await refreshJob(selectedJobId.value, false);
  } catch (reason) {
    if (props.datasetId === datasetId) jobsError.value = metadataProfileRequestError(reason, '画像任务加载失败');
  } finally {
    if (props.datasetId === datasetId) jobsLoading.value = false;
  }
}

async function refreshJob(jobId: string, foreground = true) {
  if (!props.datasetId) return;
  const datasetId = props.datasetId;
  if (foreground) jobDetailLoading.value = true;
  const previousStatus = jobs.value.find(job => job.id === jobId)?.status;
  try {
    const result = await fetchMetadataProfileJob(datasetId, jobId);
    if (props.datasetId !== datasetId) return;
    if (result.error) {
      jobsError.value = metadataProfileRequestError(result.error, '画像任务状态加载失败');
      return;
    }
    jobsError.value = '';
    const keepSelected = foreground || selectedJobId.value === jobId;
    updateJob(result.data.job, keepSelected);
    if (keepSelected) selectedJobDetail.value = result.data;
    if (previousStatus && previousStatus !== result.data.job.status && !isMetadataProfileJobActive(result.data.job)) {
      await Promise.all([loadProfiles(), loadStats()]);
    }
  } catch (reason) {
    if (props.datasetId === datasetId) jobsError.value = metadataProfileRequestError(reason, '画像任务状态加载失败');
  } finally {
    if (foreground && props.datasetId === datasetId) jobDetailLoading.value = false;
  }
}

async function selectJob(job: MetadataProfileJobView) {
  selectedJobId.value = job.id;
  selectedJobDetail.value = null;
  await refreshJob(job.id);
}

async function startProfile(mode: MetadataProfileJobMode) {
  if (!props.datasetId || jobSubmitting.value) return;
  jobSubmitting.value = true;
  jobsError.value = '';
  try {
    const tableIds = selectedTableIds.value;
    const result = await createMetadataProfileJob(props.datasetId, {
      mode,
      ...(tableIds.length ? { tableIds } : {})
    });
    if (result.error) {
      jobsError.value = metadataProfileRequestError(result.error, '画像任务启动失败');
      return;
    }
    updateJob(result.data);
    await refreshJob(result.data.id, false);
    window.$message?.success(mode === 'full' ? '全量画像任务已提交' : '增量画像任务已提交');
  } catch (reason) {
    jobsError.value = metadataProfileRequestError(reason, '画像任务启动失败');
  } finally {
    jobSubmitting.value = false;
  }
}

function confirmFullProfile() {
  const scope = selectedTableIds.value.length ? `选中的 ${selectedTableIds.value.length} 张表` : '当前数据集全部授权表';
  dialog.warning({
    title: '启动全量画像',
    content: `将重新采集${scope}的画像事实。任务会持久化执行，可以离开页面后再查看进度。`,
    positiveText: '启动任务',
    negativeText: '取消',
    onPositiveClick: () => startProfile('full')
  });
}

async function cancelJob(job: MetadataProfileJobView) {
  if (!props.datasetId || jobSubmitting.value) return;
  jobSubmitting.value = true;
  jobsError.value = '';
  try {
    const result = await cancelMetadataProfileJob(props.datasetId, job.id);
    if (result.error) {
      jobsError.value = metadataProfileRequestError(result.error, '画像任务取消失败');
      return;
    }
    updateJob(result.data);
    window.$message?.success('已提交取消请求');
  } catch (reason) {
    jobsError.value = metadataProfileRequestError(reason, '画像任务取消失败');
  } finally {
    jobSubmitting.value = false;
  }
}

async function resumeJob(job: MetadataProfileJobView) {
  if (!props.datasetId || jobSubmitting.value) return;
  jobSubmitting.value = true;
  jobsError.value = '';
  try {
    const result = await resumeMetadataProfileJob(props.datasetId, job.id);
    if (result.error) {
      jobsError.value = metadataProfileRequestError(result.error, '画像任务恢复失败');
      return;
    }
    updateJob(result.data);
    await refreshJob(result.data.id, false);
    window.$message?.success('画像任务已恢复');
  } catch (reason) {
    jobsError.value = metadataProfileRequestError(reason, '画像任务恢复失败');
  } finally {
    jobSubmitting.value = false;
  }
}

async function loadStats() {
  if (!props.datasetId) return;
  const datasetId = props.datasetId;
  statsLoading.value = true;
  statsError.value = '';
  try {
    const result = await fetchMetadataTableProfileStats(datasetId);
    if (props.datasetId !== datasetId) return;
    if (result.error) statsError.value = metadataProfileRequestError(result.error, '画像统计加载失败');
    else stats.value = result.data;
  } catch (reason) {
    if (props.datasetId === datasetId) statsError.value = metadataProfileRequestError(reason, '画像统计加载失败');
  } finally {
    if (props.datasetId === datasetId) statsLoading.value = false;
  }
}

function sortParams() {
  const [sortBy, sortOrder] = filters.sort.split('_') as ['default' | 'confidence' | 'name' | 'term' | 'created', 'asc' | 'desc'];
  return { sortBy, sortOrder };
}

async function loadProfiles() {
  if (!props.datasetId) return;
  const datasetId = props.datasetId;
  profilesLoading.value = true;
  profilesError.value = '';
  try {
    const result = await fetchMetadataTableProfiles(datasetId, {
      page: filters.page,
      pageSize: filters.pageSize,
      query: filters.query.trim() || undefined,
      tag: filters.tag || undefined,
      ignored: filters.ignored === 'all' ? undefined : filters.ignored === 'ignored',
      classification: filters.classification || undefined,
      status: filters.status || undefined,
      ...sortParams()
    });
    if (props.datasetId !== datasetId) return;
    if (result.error) {
      profilesError.value = metadataProfileRequestError(result.error, '表画像加载失败');
      return;
    }
    profiles.value = result.data.items;
    profileIndex.value = {
      ...profileIndex.value,
      ...Object.fromEntries(result.data.items.map(item => [item.tableId, item]))
    };
    profilesTotal.value = result.data.total;
    profilesPages.value = result.data.pages;
    filters.page = result.data.page;
    filters.pageSize = result.data.pageSize;
  } catch (reason) {
    if (props.datasetId === datasetId) profilesError.value = metadataProfileRequestError(reason, '表画像加载失败');
  } finally {
    if (props.datasetId === datasetId) profilesLoading.value = false;
  }
}

function applyFilters() {
  filters.page = 1;
  void loadProfiles();
}

function clearFilters() {
  Object.assign(filters, {
    page: 1,
    query: '',
    tag: null,
    ignored: 'all',
    classification: null,
    status: null,
    sort: 'default_desc'
  });
  void loadProfiles();
}

function changePage(page: number) {
  filters.page = page;
  void loadProfiles();
}

function changePageSize(pageSize: number) {
  filters.pageSize = pageSize;
  filters.page = 1;
  void loadProfiles();
}

async function toggleIgnore(profile: MetadataTableProfileSummaryView) {
  if (!props.datasetId || profile.profileId === null || profile.revisionNo === null || ignoreSubmittingIds.value.has(profile.tableId)) return;
  const pending = new Set(ignoreSubmittingIds.value);
  pending.add(profile.tableId);
  ignoreSubmittingIds.value = pending;
  profilesError.value = '';
  try {
    const result = await updateMetadataTableProfileIgnore(props.datasetId, profile.tableId, {
      revisionNo: profile.revisionNo,
      ignored: !profile.ignored
    });
    if (result.error) {
      profilesError.value = metadataProfileRequestError(result.error, '忽略状态更新失败');
      return;
    }
    profileIndex.value = { ...profileIndex.value, [profile.tableId]: result.data.summary };
    if (result.data.summary.ignored) {
      checkedProfileIds.value = checkedProfileIds.value.filter(key => String(key) !== profile.tableId);
    }
    if (detail.value?.summary.tableId === profile.tableId) detail.value = result.data;
    window.$message?.success(profile.ignored ? '已恢复表画像' : '已忽略表画像');
    await Promise.all([loadProfiles(), loadStats()]);
  } catch (reason) {
    profilesError.value = metadataProfileRequestError(reason, '忽略状态更新失败');
  } finally {
    const next = new Set(ignoreSubmittingIds.value);
    next.delete(profile.tableId);
    ignoreSubmittingIds.value = next;
  }
}

async function openDetail(profile: MetadataTableProfileSummaryView) {
  if (profile.profileId === null) return;
  const datasetId = props.datasetId;
  detailVisible.value = true;
  detailLoading.value = true;
  relatedLoading.value = true;
  detailError.value = '';
  relatedError.value = '';
  detail.value = null;
  relatedTables.value = [];
  try {
    const [detailResult, relatedResult] = await Promise.all([
      fetchMetadataTableProfile(datasetId, profile.tableId),
      fetchMetadataRelatedTables(datasetId, profile.tableId, 15)
    ]);
    if (props.datasetId !== datasetId) return;
    if (detailResult.error) detailError.value = metadataProfileRequestError(detailResult.error, '表画像详情加载失败');
    else detail.value = detailResult.data;
    if (relatedResult.error) {
      relatedError.value = metadataProfileRequestError(relatedResult.error, '关联表推荐加载失败');
      relatedTables.value = detailResult.error ? [] : detailResult.data.related || [];
    } else relatedTables.value = relatedResult.data;
  } catch (reason) {
    if (props.datasetId === datasetId) detailError.value = metadataProfileRequestError(reason, '表画像详情加载失败');
  } finally {
    if (props.datasetId === datasetId) {
      detailLoading.value = false;
      relatedLoading.value = false;
    }
  }
}

async function createImportPreview() {
  if (!props.datasetId || !selectedProfilesImportable.value) return;
  const datasetId = props.datasetId;
  previewVisible.value = true;
  previewLoading.value = true;
  previewError.value = '';
  preview.value = null;
  checkedPreviewItemIds.value = [];
  try {
    const result = await createMetadataSmartImportPreview(datasetId, {
      tableIds: selectedTableIds.value,
      profileJobId: selectedProfileJobId.value!
    });
    if (props.datasetId !== datasetId) return;
    if (result.error) {
      previewError.value = metadataProfileRequestError(result.error, '智能导入预览生成失败');
      return;
    }
    preview.value = result.data;
    rememberPreview(result.data.id);
    checkedPreviewItemIds.value = result.data.items.filter(item => item.status === 'available').map(item => item.id);
    activePreviewItem.value = result.data.items[0] || null;
  } catch (reason) {
    if (props.datasetId === datasetId) previewError.value = metadataProfileRequestError(reason, '智能导入预览生成失败');
  } finally {
    if (props.datasetId === datasetId) previewLoading.value = false;
  }
}

async function restoreImportPreview() {
  const previewId = resumablePreviewId.value;
  if (!props.datasetId || !previewId) return;
  const datasetId = props.datasetId;
  previewVisible.value = true;
  previewLoading.value = true;
  previewError.value = '';
  preview.value = null;
  try {
    const result = await fetchMetadataSmartImportPreview(datasetId, previewId);
    if (props.datasetId !== datasetId) return;
    if (result.error) {
      previewError.value = metadataProfileRequestError(result.error, '智能导入预览恢复失败');
      return;
    }
    preview.value = result.data;
    checkedPreviewItemIds.value = result.data.status === 'draft'
      ? result.data.items.filter(item => item.status === 'available').map(item => item.id)
      : [];
    activePreviewItem.value = result.data.items[0] || null;
    if (result.data.status !== 'draft') rememberPreview(null);
  } catch (reason) {
    if (props.datasetId === datasetId) previewError.value = metadataProfileRequestError(reason, '智能导入预览恢复失败');
  } finally {
    if (props.datasetId === datasetId) previewLoading.value = false;
  }
}

function toggleAllPreviewItems(checked: boolean) {
  checkedPreviewItemIds.value = checked ? availablePreviewItems.value.map(item => item.id) : [];
}

async function applyImportPreview() {
  if (!props.datasetId || !preview.value || !checkedPreviewItemIds.value.length) return;
  const datasetId = props.datasetId;
  previewApplying.value = true;
  previewError.value = '';
  try {
    const result = await applyMetadataSmartImportPreview(datasetId, preview.value.id, {
      revisionNo: preview.value.revisionNo,
      itemIds: checkedPreviewItemIds.value
    });
    if (props.datasetId !== datasetId) return;
    if (result.error) {
      previewError.value = metadataProfileRequestError(result.error, '智能导入应用失败');
      return;
    }
    preview.value = result.data.preview;
    activePreviewItem.value = result.data.preview.items.find(item => item.id === activePreviewItem.value?.id)
      || result.data.preview.items[0]
      || null;
    checkedPreviewItemIds.value = [];
    rememberPreview(null);
    emit('applied');
    window.$message?.success(`已应用 ${result.data.appliedItems.length} 项元数据变更`);
  } catch (reason) {
    if (props.datasetId === datasetId) previewError.value = metadataProfileRequestError(reason, '智能导入应用失败');
  } finally {
    if (props.datasetId === datasetId) previewApplying.value = false;
  }
}

function confidenceType(score: number | string | null) {
  const normalized = score === null ? Number.NaN : Number(score);
  if (!Number.isFinite(normalized)) return 'default';
  if (normalized >= 80) return 'success';
  if (normalized >= 60) return 'warning';
  return 'error';
}

function formattedTime(value?: string | null) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-';
}

function governanceStatusText(value: string) {
  return ({ active: '启用', inactive: '停用', available: '可应用', applied: '已应用' } as Record<string, string>)[value] || value;
}

function previewStatusText(value: MetadataSmartImportPreviewView['status']) {
  return ({ draft: '待应用', applied: '已应用', expired: '已过期' } as const)[value];
}

function proposalChangeText(expected: unknown, proposed: unknown) {
  const before = formatMetadataProfileValue(expected);
  const after = formatMetadataProfileValue(proposed);
  return before === after ? after : `${before} -> ${after}`;
}

function shortHash(value: string) {
  return value.length > 16 ? `${value.slice(0, 16)}...` : value;
}

function previewStorageKey() {
  return `agent:metadata-profile-preview:${props.datasetId}`;
}

function rememberPreview(previewId: string | null) {
  resumablePreviewId.value = previewId;
  try {
    if (previewId) window.localStorage.setItem(previewStorageKey(), previewId);
    else window.localStorage.removeItem(previewStorageKey());
  } catch {
    // Browser storage is optional; the durable preview remains available by its server id.
  }
}

function loadRememberedPreviewId() {
  try {
    resumablePreviewId.value = window.localStorage.getItem(previewStorageKey());
  } catch {
    resumablePreviewId.value = null;
  }
}

function ignoreDecisionText(value: MetadataTableProfileSummaryView['ignoreDecision']) {
  if (!value) return '尚无决策';
  return ({
    auto_include: '自动保留',
    auto_ignore: '自动忽略',
    manual_include: '人工保留',
    manual_ignore: '人工忽略'
  } as const)[value];
}

const jobColumns: DataTableColumns<MetadataProfileJobView> = [
  { title: '模式', key: 'mode', width: 90, render: row => (row.mode === 'full' ? '全量' : '增量') },
  { title: '状态', key: 'status', width: 100, render: row => h(NTag, { size: 'small', type: metadataProfileJobStatusType(row.status) }, () => metadataProfileJobStatusText(row.status)) },
  { title: '进度', key: 'progressPercent', minWidth: 170, render: row => h(NProgress, { type: 'line', percentage: metadataProfileProgress(row.progressPercent), height: 8, status: row.status === 'error' ? 'error' : row.status === 'done' ? 'success' : 'default' }) },
  { title: '完成/总数', key: 'completedTables', width: 105, render: row => `${row.completedTables}/${row.totalTables}` },
  { title: '失败', key: 'failedTables', width: 70 },
  { title: '创建时间', key: 'createdAt', width: 165, render: row => formattedTime(row.createdAt) },
  {
    title: '操作', key: 'actions', width: 185, fixed: 'right', render: row => h(NSpace, { size: 4 }, () => [
      h(NButton, { size: 'tiny', quaternary: true, onClick: () => selectJob(row) }, () => '详情'),
      isMetadataProfileJobActive(row)
        ? h(NButton, { size: 'tiny', quaternary: true, type: 'error', loading: jobSubmitting.value, onClick: () => cancelJob(row) }, () => '取消')
        : null,
      canResumeMetadataProfileJob(row)
        ? h(NButton, { size: 'tiny', quaternary: true, type: 'primary', loading: jobSubmitting.value, onClick: () => resumeJob(row) }, () => '恢复')
        : null
    ])
  }
];

const jobTableColumns: DataTableColumns<MetadataProfileJobTableView> = [
  { title: '表', key: 'tableName', minWidth: 190, ellipsis: { tooltip: true }, render: row => row.tableName ? `${row.schemaName || '-'}.${row.tableName}` : `已失效表 #${row.tableId}` },
  { title: '状态', key: 'status', width: 100, render: row => h(NTag, { size: 'small', type: row.status === 'success' ? 'success' : row.status === 'failed' ? 'error' : 'warning' }, () => ({ pending: '等待中', running: '执行中', success: '完成', failed: '失败' } as const)[row.status]) },
  { title: '尝试次数', key: 'attemptNo', width: 90 },
  { title: '错误', key: 'errorMessage', minWidth: 220, ellipsis: { tooltip: true }, render: row => row.errorMessage || '-' },
  { title: '完成时间', key: 'finishedAt', width: 165, render: row => formattedTime(row.finishedAt) }
];

const profileColumns: DataTableColumns<MetadataTableProfileSummaryView> = [
  { type: 'selection', disabled: row => row.ignored === true },
  {
    title: '表画像', key: 'tableName', minWidth: 220, render: row => h('div', { class: 'profile-primary-cell' }, [
      h('strong', row.displayName || row.term || row.tableName),
      h('span', `${row.schemaName}.${row.tableName}${row.description ? ` / ${row.description}` : ''}`)
    ])
  },
  { title: '类型', key: 'tableType', width: 90 },
  { title: '状态', key: 'status', width: 90, render: row => h(NTag, { size: 'small', type: row.status === 'success' ? 'success' : row.status === 'failed' ? 'error' : 'warning' }, () => ({ pending: '等待中', running: '画像中', success: '完成', failed: '失败' } as const)[row.status]) },
  {
    title: '分类', key: 'temporaryClassification', width: 100, render: row => h(NTag, {
      size: 'small', type: metadataProfileClassificationType(row.temporaryClassification)
    }, () => metadataProfileClassificationText(row.temporaryClassification))
  },
  {
    title: '标签', key: 'tags', minWidth: 170, render: row => row.tags.length
      ? h(NSpace, { size: 4, wrap: true }, () => row.tags.slice(0, 3).map(tag => h(NTag, { size: 'small', bordered: false }, () => tag)))
      : '-'
  },
  { title: '字段', key: 'columnCount', width: 70, render: row => row.columnCount ?? '-' },
  {
    title: '置信度', key: 'confidenceScore', width: 140, render: row => h(NProgress, {
      type: 'line', percentage: metadataProfileProgress(row.confidenceScore ?? 0), height: 7, status: confidenceType(row.confidenceScore)
    })
  },
  {
    title: '收录', key: 'ignored', width: 100, render: row => h(NTag, {
      size: 'small', type: row.ignored === true ? 'default' : row.ignoreDecision ? 'success' : 'default'
    }, () => ignoreDecisionText(row.ignoreDecision))
  },
  { title: '更新时间', key: 'updatedAt', width: 165, render: row => formattedTime(row.updatedAt) },
  {
    title: '操作', key: 'actions', width: 150, fixed: 'right', render: row => h(NSpace, { size: 4 }, () => [
      h(NButton, { size: 'tiny', quaternary: true, disabled: row.profileId === null, onClick: () => openDetail(row) }, () => '详情'),
      h(NButton, {
        size: 'tiny',
        quaternary: true,
        type: row.ignored ? 'primary' : 'warning',
        disabled: row.profileId === null || row.revisionNo === null,
        loading: ignoreSubmittingIds.value.has(row.tableId),
        onClick: () => toggleIgnore(row)
      }, () => (row.ignored ? '恢复' : '忽略'))
    ])
  }
];

const columnProfileColumns: DataTableColumns<MetadataColumnProfileView> = [
  {
    title: '字段', key: 'physicalName', minWidth: 180, render: row => h('div', { class: 'profile-primary-cell' }, [
      h('strong', row.displayName || row.term || row.physicalName),
      row.displayName || row.term ? h('span', row.physicalName) : null
    ])
  },
  { title: '类型', key: 'dataType', width: 125 },
  {
    title: '属性', key: 'attributes', minWidth: 150, render: row => h(NSpace, { size: 4, wrap: true }, () => [
      row.primary ? h(NTag, { size: 'small', type: 'info' }, () => '主键') : null,
      row.sensitive ? h(NTag, { size: 'small', type: 'warning' }, () => '敏感') : null,
      row.nonNullSampleCount === 0 ? h(NTag, { size: 'small' }, () => '样例全空') : null
    ])
  },
  { title: '非空样例', key: 'nonNullSampleCount', width: 90 },
  { title: '去重样例', key: 'distinctSampleCount', width: 90 },
  { title: '示例值', key: 'examples', minWidth: 220, ellipsis: { tooltip: true }, render: row => row.examples.length ? row.examples.join(' / ') : '-' },
  { title: '画像说明', key: 'description', minWidth: 200, ellipsis: { tooltip: true }, render: row => row.description || '-' }
];

const relatedColumns: DataTableColumns<MetadataRelatedTableView> = [
  { title: '关联表', key: 'targetTableName', minWidth: 180, render: row => `${row.sourceTableName || `#${row.sourceTableId}`} -> ${row.targetTableName || `#${row.targetTableId}`}` },
  { title: '关联字段', key: 'targetColumnName', minWidth: 180, render: row => `${row.sourceColumnName || `#${row.sourceColumnId}`} -> ${row.targetColumnName || `#${row.targetColumnId}`}` },
  { title: '置信度', key: 'confidenceScore', width: 130, render: row => h(NProgress, { type: 'line', percentage: metadataProfileProgress(row.confidenceScore), height: 7, status: confidenceType(row.confidenceScore) }) },
  { title: '推荐依据', key: 'reason', minWidth: 200, ellipsis: { tooltip: true }, render: row => row.reason || '-' },
  { title: '关联建议', key: 'joinCondition', minWidth: 220, ellipsis: { tooltip: true }, render: row => `${row.joinType.toUpperCase()} / ${row.joinCondition}` }
];

type SampleTableRow = Record<string, MetadataProfileSampleValueView | number>;
const sampleRows = computed<SampleTableRow[]>(() => (detail.value?.samples || []).slice(0, 3).map(row => Object.fromEntries([
  ['__rowKey', row.rowNo],
  ...row.values.map(value => [value.columnId, value])
])) as SampleTableRow[]);
const sampleColumns = computed<DataTableColumns<SampleTableRow>>(() => {
  const values = detail.value?.samples.flatMap(row => row.values) || [];
  const columns = new Map<string, MetadataProfileSampleValueView>();
  values.forEach(value => columns.set(value.columnId, value));
  return [...columns.entries()].map(([columnId, definition]) => ({
    title: definition.displayName ? `${definition.displayName} (${definition.columnName})` : definition.columnName,
    key: columnId,
    minWidth: 130,
    ellipsis: { tooltip: true },
    render: row => {
      const cell = row[columnId];
      if (!cell || typeof cell === 'number') return '-';
      return h(NSpace, { size: 4, align: 'center', wrap: false }, () => [
        h('span', formatMetadataProfileValue(cell.value)),
        cell.redacted ? h(NTag, { size: 'small', type: 'warning', bordered: false }, () => '已脱敏') : null,
        cell.truncated ? h(NTag, { size: 'small', bordered: false }, () => '已截断') : null
      ]);
    }
  }));
});

const previewItemColumns: DataTableColumns<MetadataSmartImportItemView> = [
  { title: '类型', key: 'itemType', width: 90, render: row => (row.itemType === 'table' ? '表元数据' : '表关系') },
  { title: '变更项', key: 'title', minWidth: 180, render: row => smartImportItemTitle(row) },
  { title: '建议内容', key: 'description', minWidth: 280, ellipsis: { tooltip: true }, render: row => smartImportItemDescription(row) },
  { title: '状态', key: 'status', width: 100, render: row => h(NTag, { size: 'small', type: row.status === 'applied' ? 'success' : row.status === 'available' ? 'info' : 'default' }, () => ({ available: '可应用', applied: '已应用', skipped: '已跳过' } as const)[row.status]) },
  { title: '错误', key: 'errorMessage', minWidth: 180, ellipsis: { tooltip: true }, render: row => row.errorMessage || '-' },
  { title: '操作', key: 'actions', width: 80, fixed: 'right', render: row => h(NButton, { size: 'tiny', quaternary: true, onClick: () => { activePreviewItem.value = row; } }, () => '查看') }
];
const previewColumns = computed<DataTableColumns<MetadataSmartImportItemView>>(() => [
  { type: 'selection', disabled: row => row.status !== 'available' },
  ...previewItemColumns
]);
const previewColumnColumns: DataTableColumns<MetadataColumnImportProposalView> = [
  { title: '字段 ID', key: 'columnId', width: 110 },
  { title: '展示名', key: 'displayName', minWidth: 160, render: row => proposalChangeText(row.expected.displayName, row.displayName) },
  { title: '说明', key: 'description', minWidth: 240, ellipsis: { tooltip: true }, render: row => proposalChangeText(row.expected.description, row.description) },
  { title: '敏感', key: 'sensitive', width: 105, render: row => proposalChangeText(row.expected.sensitive ? '是' : '否', row.sensitive ? '是' : '否') },
  { title: '状态', key: 'status', width: 115, render: row => proposalChangeText(governanceStatusText(row.expected.status), governanceStatusText(row.status)) }
];

async function resetDataset() {
  stopJobPolling();
  jobs.value = [];
  selectedJobId.value = null;
  selectedJobDetail.value = null;
  stats.value = null;
  profiles.value = [];
  profileIndex.value = {};
  checkedProfileIds.value = [];
  detailVisible.value = false;
  previewVisible.value = false;
  activePreviewItem.value = null;
  loadRememberedPreviewId();
  filters.page = 1;
  await Promise.all([loadJobs(), loadStats(), loadProfiles()]);
}

watch(() => props.datasetId, () => void resetDataset(), { immediate: true });
watch(() => [activeJob.value?.id, activeJob.value?.status], syncJobPolling);
onBeforeUnmount(stopJobPolling);
</script>

<template>
  <div class="metadata-profile-console">
    <section class="profile-section task-section">
      <div class="section-toolbar">
        <div>
          <h3>持久画像任务</h3>
        </div>
        <NSpace wrap>
          <NButton size="small" secondary :loading="jobsLoading" @click="loadJobs">
            <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
            刷新状态
          </NButton>
          <NButton size="small" type="primary" :loading="jobSubmitting" :disabled="Boolean(activeJob)" @click="startProfile('incremental')">
            <template #icon><SvgIcon icon="lucide:scan-search" /></template>
            增量画像
          </NButton>
          <NButton size="small" secondary :loading="jobSubmitting" :disabled="Boolean(activeJob)" @click="confirmFullProfile">
            <template #icon><SvgIcon icon="lucide:database-backup" /></template>
            全量画像
          </NButton>
        </NSpace>
      </div>

      <NAlert v-if="jobsError" type="error" :bordered="false" closable @close="jobsError = ''">{{ jobsError }}</NAlert>
      <NSpin :show="jobsLoading || jobDetailLoading">
        <div v-if="selectedJob" class="current-job" data-testid="current-profile-job">
          <div class="job-heading">
            <NSpace align="center" wrap>
              <strong>{{ selectedJob.mode === 'full' ? '全量画像' : '增量画像' }}</strong>
              <NTag size="small" :type="metadataProfileJobStatusType(selectedJob.status)">{{ metadataProfileJobStatusText(selectedJob.status) }}</NTag>
              <span class="muted">任务 {{ selectedJob.id }}</span>
            </NSpace>
            <NSpace>
              <NButton v-if="isMetadataProfileJobActive(selectedJob)" size="tiny" type="error" secondary :loading="jobSubmitting" @click="cancelJob(selectedJob)">
                <template #icon><SvgIcon icon="lucide:square" /></template>
                取消
              </NButton>
              <NButton v-if="canResumeMetadataProfileJob(selectedJob)" size="tiny" type="primary" secondary :loading="jobSubmitting" @click="resumeJob(selectedJob)">
                <template #icon><SvgIcon icon="lucide:play" /></template>
                恢复
              </NButton>
            </NSpace>
          </div>
          <NProgress
            type="line"
            :percentage="metadataProfileProgress(selectedJob.progressPercent)"
            :status="selectedJob.status === 'error' ? 'error' : selectedJob.status === 'done' ? 'success' : 'default'"
            :processing="isMetadataProfileJobActive(selectedJob)"
          />
          <div class="job-facts">
            <span>完成 {{ selectedJob.completedTables }} / {{ selectedJob.totalTables }}</span>
            <span>失败 {{ selectedJob.failedTables }}</span>
            <span>任务尝试 {{ selectedJob.attemptNo }} / {{ selectedJob.maxAttempts }}</span>
            <span>开始 {{ formattedTime(selectedJob.startedAt) }}</span>
            <span>更新 {{ formattedTime(selectedJob.updatedAt) }}</span>
          </div>
          <NAlert v-if="selectedJob.cancelRequested && isMetadataProfileJobActive(selectedJob)" type="warning" :bordered="false">任务正在结束当前只读采集后取消。</NAlert>
          <NAlert v-if="selectedJob.errorMessage" type="error" :bordered="false">{{ selectedJob.errorMessage }}</NAlert>
          <NDataTable
            v-if="selectedJobTables.length"
            class="job-table"
            size="small"
            :columns="jobTableColumns"
            :data="selectedJobTables"
            :row-key="row => row.id"
            :scroll-x="850"
            :max-height="240"
          />
        </div>
        <NEmpty v-else-if="!jobsError" description="尚未创建画像任务" />
      </NSpin>

      <NCollapse v-if="jobs.length" class="job-history">
        <NCollapseItem name="history" title="任务历史">
          <NDataTable size="small" :columns="jobColumns" :data="jobs" :row-key="row => row.id" :scroll-x="900" :max-height="300" />
        </NCollapseItem>
      </NCollapse>
    </section>

    <section class="profile-section">
      <div class="section-toolbar">
        <div>
          <h3>画像概览</h3>
        </div>
        <NButton size="small" secondary :loading="statsLoading" @click="loadStats">
          <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
          刷新统计
        </NButton>
      </div>
      <NAlert v-if="statsError" type="error" :bordered="false">{{ statsError }}</NAlert>
      <NSpin :show="statsLoading">
        <div v-if="stats" class="profile-stats">
          <div><span>画像总数</span><strong>{{ stats.totalProfiles }}</strong></div>
          <div><span>业务表 / 视图</span><strong>{{ stats.tableCount }} / {{ stats.viewCount }}</strong></div>
          <div><span>临时类表</span><strong>{{ stats.temporaryCount }}</strong></div>
          <div><span>已忽略</span><strong>{{ stats.ignoredCount }}</strong></div>
          <div><span>平均置信度</span><strong>{{ metadataProfileProgress(stats.averageConfidence) }}%</strong></div>
          <div><span>最近画像</span><strong>{{ formattedTime(stats.lastProfiledAt) }}</strong></div>
        </div>
        <NEmpty v-else-if="!statsLoading && !statsError" description="暂无画像统计" />
      </NSpin>
      <div v-if="stats?.tags.length" class="profile-tag-cloud">
        <span>标签</span>
        <NTag
          v-for="tag in stats.tags"
          :key="tag.name"
          size="small"
          checkable
          :checked="filters.tag === tag.name"
          @update:checked="checked => { filters.tag = checked ? tag.name : null; applyFilters(); }"
        >{{ tag.name }} · {{ tag.count }}</NTag>
      </div>
    </section>

    <section class="profile-section profiles-section">
      <div class="section-toolbar">
        <div>
          <h3>表画像</h3>
        </div>
        <NSpace wrap>
          <NButton v-if="resumablePreviewId" size="small" secondary :loading="previewLoading" @click="restoreImportPreview">
            <template #icon><SvgIcon icon="lucide:history" /></template>
            继续导入预览
          </NButton>
          <NButton size="small" type="primary" :disabled="!selectedProfilesImportable" :loading="previewLoading" @click="createImportPreview">
            <template #icon><SvgIcon icon="lucide:list-checks" /></template>
            智能导入预览{{ selectedTableIds.length ? ` (${selectedTableIds.length})` : '' }}
          </NButton>
        </NSpace>
      </div>
      <p v-if="selectedTableIds.length && !selectedProfilesImportable" class="selection-note">智能导入只能同时应用同一已完成画像任务中未忽略的表；请调整选择或重新创建包含这些表的画像任务。</p>

      <div class="profile-filters">
        <NInput v-model:value="filters.query" clearable placeholder="搜索表名、展示名、术语或说明" data-testid="profile-query" @keyup.enter="applyFilters">
          <template #prefix><SvgIcon icon="lucide:search" /></template>
        </NInput>
        <NSelect v-model:value="filters.tag" clearable :options="tagOptions" placeholder="全部标签" @update:value="applyFilters" />
        <NSelect v-model:value="filters.classification" clearable :options="classificationOptions" placeholder="全部分类" @update:value="applyFilters" />
        <NSelect v-model:value="filters.status" clearable :options="profileStatusOptions" placeholder="全部状态" @update:value="applyFilters" />
        <NSelect v-model:value="filters.ignored" :options="ignoredOptions" @update:value="applyFilters" />
        <NSelect v-model:value="filters.sort" :options="sortOptions" @update:value="applyFilters" />
        <NSpace :wrap="false">
          <NButton secondary @click="applyFilters"><template #icon><SvgIcon icon="lucide:search" /></template>查询</NButton>
          <NButton quaternary @click="clearFilters"><template #icon><SvgIcon icon="lucide:rotate-ccw" /></template>重置</NButton>
        </NSpace>
      </div>

      <NAlert v-if="profilesError" type="error" :bordered="false" closable @close="profilesError = ''">{{ profilesError }}</NAlert>
      <NDataTable
        v-if="profiles.length || profilesLoading"
        v-model:checked-row-keys="checkedProfileIds"
        size="small"
        :columns="profileColumns"
        :data="profiles"
        :loading="profilesLoading"
        :row-key="row => row.tableId"
        :scroll-x="1300"
        :max-height="520"
      />
      <NEmpty v-else-if="!profilesError" description="暂无匹配的表画像；可先启动增量或全量画像任务" />
      <div class="profile-pagination">
        <span class="muted">共 {{ profilesTotal }} 张表，已选择 {{ selectedTableIds.length }} 张</span>
        <NPagination
          :page="filters.page"
          :page-size="filters.pageSize"
          :page-count="Math.max(1, profilesPages)"
          :item-count="profilesTotal"
          show-size-picker
          :page-sizes="[20, 50, 100]"
          @update:page="changePage"
          @update:page-size="changePageSize"
        />
      </div>
    </section>

    <NModal v-model:show="detailVisible" preset="card" class="profile-detail-modal" style="width: min(1120px, calc(100vw - 24px))" :mask-closable="false">
      <template #header>
        <div class="modal-heading">
          <span>{{ detail?.summary.displayName || detail?.summary.tableName || '表画像详情' }}</span>
          <NTag v-if="detail" size="small" :type="metadataProfileClassificationType(detail.summary.temporaryClassification)">{{ metadataProfileClassificationText(detail.summary.temporaryClassification) }}</NTag>
        </div>
      </template>
      <NAlert v-if="detailError" type="error" :bordered="false">{{ detailError }}</NAlert>
      <NSpin :show="detailLoading">
        <template v-if="detail">
          <NDescriptions bordered size="small" :column="3" label-placement="left">
            <NDescriptionsItem label="物理表">{{ detail.summary.schemaName }}.{{ detail.summary.tableName }}</NDescriptionsItem>
            <NDescriptionsItem label="类型">{{ detail.summary.tableType }}</NDescriptionsItem>
            <NDescriptionsItem label="置信度">{{ detail.summary.confidenceScore ?? '-' }}{{ detail.summary.confidenceScore === null ? '' : '%' }}</NDescriptionsItem>
            <NDescriptionsItem label="字段数">{{ detail.summary.columnCount ?? '-' }}</NDescriptionsItem>
            <NDescriptionsItem label="估算行数">{{ detail.rowCountEstimate ?? '-' }}</NDescriptionsItem>
            <NDescriptionsItem label="收录决策">{{ ignoreDecisionText(detail.summary.ignoreDecision) }}</NDescriptionsItem>
            <NDescriptionsItem label="业务术语" :span="3">{{ detail.summary.term || '-' }}</NDescriptionsItem>
            <NDescriptionsItem label="画像说明" :span="3">{{ detail.summary.description || '-' }}</NDescriptionsItem>
            <NDescriptionsItem label="标签" :span="3">
              <NSpace v-if="detail.summary.tags.length" size="small" wrap><NTag v-for="tag in detail.summary.tags" :key="tag" size="small">{{ tag }}</NTag></NSpace>
              <span v-else>-</span>
            </NDescriptionsItem>
            <NDescriptionsItem label="置信依据" :span="3">{{ detail.summary.confidenceReason || '-' }}</NDescriptionsItem>
          </NDescriptions>

          <NTabs type="line" animated class="detail-tabs">
            <NTabPane name="columns" tab="字段画像">
              <NDataTable v-if="detail.columns.length" size="small" :columns="columnProfileColumns" :data="detail.columns" :row-key="row => row.columnId" :scroll-x="900" :max-height="430" />
              <NEmpty v-else description="暂无字段画像" />
            </NTabPane>
            <NTabPane name="samples" tab="脱敏样例">
              <NAlert :type="detail.sampleRedacted ? 'warning' : 'info'" :bordered="false" class="detail-alert">
                服务端仅返回最多 3 行；{{ detail.sampleRedacted ? '本次样例包含已脱敏字段。' : '本次样例未命中敏感字段。' }}
              </NAlert>
              <NDataTable v-if="sampleRows.length" size="small" :columns="sampleColumns" :data="sampleRows" :row-key="row => row.__rowKey as number" :scroll-x="Math.max(700, sampleColumns.length * 140)" :max-height="360" />
              <NEmpty v-else description="暂无可展示的脱敏样例" />
            </NTabPane>
            <NTabPane name="ddl" tab="DDL">
              <NCode v-if="detail.ddl" :code="detail.ddl" language="sql" word-wrap show-line-numbers />
              <NEmpty v-else description="暂无 DDL" />
            </NTabPane>
            <NTabPane name="related" tab="相关表">
              <NAlert v-if="relatedError" type="error" :bordered="false" class="detail-alert">{{ relatedError }}</NAlert>
              <NDataTable v-if="relatedTables.length || relatedLoading" size="small" :loading="relatedLoading" :columns="relatedColumns" :data="relatedTables" :row-key="row => row.id" :scroll-x="1000" />
              <NEmpty v-else-if="!relatedError" description="暂无相关表推荐" />
            </NTabPane>
          </NTabs>
        </template>
        <NEmpty v-else-if="!detailLoading && !detailError" description="表画像不存在" />
      </NSpin>
    </NModal>

    <NModal v-model:show="previewVisible" preset="card" class="profile-import-modal" style="width: min(1040px, calc(100vw - 24px))" :mask-closable="false" title="智能导入预览">
      <NAlert type="info" :bordered="false" class="detail-alert">预览不会修改数据集。仅勾选并应用的表元数据和关系建议会写入真实治理数据，并受版本冲突保护。</NAlert>
      <NAlert v-if="previewError" type="error" :bordered="false" class="detail-alert">{{ previewError }}</NAlert>
      <NSpin :show="previewLoading">
        <template v-if="preview">
          <div class="preview-summary">
            <span>状态：{{ previewStatusText(preview.status) }}</span>
            <span>数据集版本：{{ preview.datasetRevision }}</span>
            <span>有效期至：{{ formattedTime(preview.expiresAt) }}</span>
          </div>
          <div class="preview-selection">
            <NCheckbox
              :checked="Boolean(availablePreviewItems.length) && checkedPreviewItemIds.length === availablePreviewItems.length"
              :indeterminate="checkedPreviewItemIds.length > 0 && checkedPreviewItemIds.length < availablePreviewItems.length"
              :disabled="preview.status !== 'draft'"
              @update:checked="toggleAllPreviewItems"
            >选择全部可应用项</NCheckbox>
            <span class="muted">已选 {{ checkedPreviewItemIds.length }} / {{ availablePreviewItems.length }}</span>
          </div>
          <NDataTable
            v-if="preview.items.length"
            size="small"
            :columns="previewColumns"
            :data="preview.items"
            :checked-row-keys="checkedPreviewItemIds"
            :row-key="row => row.id"
            :scroll-x="980"
            :max-height="440"
            @update:checked-row-keys="keys => checkedPreviewItemIds = keys.map(String)"
          />
          <NEmpty v-else description="当前选择没有可导入的元数据变更" />
          <div v-if="activePreviewItem" class="preview-detail">
            <div class="preview-detail-heading">
              <strong>{{ smartImportItemTitle(activePreviewItem) }}</strong>
              <NTag size="small">{{ activePreviewItem.itemType === 'table' ? '表元数据' : '表关系' }}</NTag>
            </div>
            <template v-if="activePreviewItem.tableProposal">
              <NDescriptions bordered size="small" :column="4">
                <NDescriptionsItem label="物理表">{{ activePreviewItem.tableProposal.schemaName }}.{{ activePreviewItem.tableProposal.physicalName }}</NDescriptionsItem>
                <NDescriptionsItem label="展示名">{{ proposalChangeText(activePreviewItem.tableProposal.expected.displayName, activePreviewItem.tableProposal.displayName) }}</NDescriptionsItem>
                <NDescriptionsItem label="画像版本">v{{ activePreviewItem.tableProposal.profileRevision }}</NDescriptionsItem>
                <NDescriptionsItem label="状态">{{ proposalChangeText(governanceStatusText(activePreviewItem.tableProposal.expected.status), governanceStatusText(activePreviewItem.tableProposal.status)) }}</NDescriptionsItem>
                <NDescriptionsItem label="说明" :span="4">{{ proposalChangeText(activePreviewItem.tableProposal.expected.description, activePreviewItem.tableProposal.description) }}</NDescriptionsItem>
                <NDescriptionsItem label="并发基线" :span="4">
                  <code :title="`目录 ${activePreviewItem.tableProposal.expected.stateHash} / 结构 ${activePreviewItem.tableProposal.sourceHash}`">
                    目录 {{ shortHash(activePreviewItem.tableProposal.expected.stateHash) }} / 结构 {{ shortHash(activePreviewItem.tableProposal.sourceHash) }}
                  </code>
                </NDescriptionsItem>
              </NDescriptions>
              <NDataTable
                v-if="activePreviewItem.tableProposal.columnUpdates.length"
                class="preview-column-table"
                size="small"
                :columns="previewColumnColumns"
                :data="activePreviewItem.tableProposal.columnUpdates"
                :row-key="row => row.columnId"
                :scroll-x="760"
                :max-height="220"
              />
              <NEmpty v-else description="该表没有字段级更新" />
            </template>
            <NDescriptions v-else-if="activePreviewItem.relationshipProposal" bordered size="small" :column="2">
              <NDescriptionsItem label="源对象">表 {{ activePreviewItem.relationshipProposal.sourceTableId }} / 字段 {{ activePreviewItem.relationshipProposal.sourceColumnId }}</NDescriptionsItem>
              <NDescriptionsItem label="目标对象">表 {{ activePreviewItem.relationshipProposal.targetTableId }} / 字段 {{ activePreviewItem.relationshipProposal.targetColumnId }}</NDescriptionsItem>
              <NDescriptionsItem label="JOIN 类型">{{ activePreviewItem.relationshipProposal.joinType.toUpperCase() }}</NDescriptionsItem>
              <NDescriptionsItem label="推荐 ID">{{ activePreviewItem.relationshipProposal.recommendationId }}</NDescriptionsItem>
              <NDescriptionsItem label="关联条件" :span="2"><NCode :code="activePreviewItem.relationshipProposal.joinCondition" language="sql" word-wrap /></NDescriptionsItem>
              <NDescriptionsItem label="说明" :span="2">{{ activePreviewItem.relationshipProposal.description || '-' }}</NDescriptionsItem>
            </NDescriptions>
          </div>
        </template>
        <NEmpty v-else-if="!previewLoading && !previewError" description="暂无智能导入预览" />
      </NSpin>
      <template #footer>
        <div class="modal-footer">
          <span class="muted">应用后将刷新目录与字段元数据。</span>
          <NSpace>
            <NButton @click="previewVisible = false">关闭</NButton>
            <NButton type="primary" :loading="previewApplying" :disabled="!preview || preview.status !== 'draft' || !checkedPreviewItemIds.length" @click="applyImportPreview">
              <template #icon><SvgIcon icon="lucide:check-check" /></template>
              应用所选项
            </NButton>
          </NSpace>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style scoped lang="scss">
.metadata-profile-console { min-width: 0; }
.profile-section { padding: 4px 0 18px; border-bottom: 1px solid var(--n-border-color); }
.profile-section + .profile-section { padding-top: 18px; }
.profiles-section { border-bottom: 0; }
.section-toolbar { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
.section-toolbar h3 { margin: 0 0 3px; font-size: 15px; }
.current-job { display: grid; gap: 10px; padding: 12px; border: 1px solid var(--n-border-color); border-radius: 6px; background: var(--n-action-color); }
.job-heading, .modal-heading, .modal-footer, .preview-selection { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.job-facts, .preview-summary { display: flex; flex-wrap: wrap; gap: 8px 18px; color: var(--n-text-color-2); font-size: 12px; }
.job-table { margin-top: 2px; }
.job-history { margin-top: 10px; }
.profile-stats { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); border: 1px solid var(--n-border-color); border-radius: 6px; overflow: hidden; }
.profile-stats > div { display: grid; min-width: 0; gap: 5px; padding: 11px 12px; border-right: 1px solid var(--n-border-color); }
.profile-stats > div:last-child { border-right: 0; }
.profile-stats span, .muted { color: var(--n-text-color-3); font-size: 12px; }
.profile-stats strong { overflow: hidden; font-size: 17px; text-overflow: ellipsis; white-space: nowrap; }
.profile-tag-cloud { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.profile-tag-cloud > span { margin-right: 4px; color: var(--n-text-color-3); font-size: 12px; }
.selection-note { margin: -2px 0 10px; color: var(--n-warning-color); font-size: 12px; }
.profile-filters { display: grid; grid-template-columns: minmax(220px, 1.5fr) repeat(5, minmax(120px, 0.8fr)) auto; gap: 8px; margin-bottom: 12px; }
.profile-pagination { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 12px; }
.detail-tabs { margin-top: 14px; }
.detail-alert { margin-bottom: 10px; }
.preview-summary { margin: 4px 0 12px; padding: 9px 10px; border: 1px solid var(--n-border-color); border-radius: 6px; }
.preview-selection { margin-bottom: 10px; }
.preview-detail { margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--n-border-color); }
.preview-detail-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 9px; }
.preview-column-table { margin-top: 9px; }
.preview-detail code { overflow-wrap: anywhere; font-size: 11px; }
:deep(.profile-primary-cell) { display: flex; min-width: 0; flex-direction: column; gap: 3px; }
:deep(.profile-primary-cell strong), :deep(.profile-primary-cell span) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
:deep(.profile-primary-cell span) { color: var(--n-text-color-3); font-size: 11px; }
:deep(.n-code) { max-height: 430px; overflow: auto; }
:deep(.profile-detail-modal .n-card__content), :deep(.profile-import-modal .n-card__content) { max-height: calc(100vh - 180px); overflow: auto; }
@media (max-width: 1000px) {
  .profile-stats { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .profile-stats > div:nth-child(3) { border-right: 0; }
  .profile-stats > div:nth-child(-n + 3) { border-bottom: 1px solid var(--n-border-color); }
  .profile-filters { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 700px) {
  .section-toolbar, .job-heading, .modal-footer, .profile-pagination { align-items: stretch; flex-direction: column; }
  .profile-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .profile-stats > div { border-bottom: 1px solid var(--n-border-color); }
  .profile-stats > div:nth-child(2n) { border-right: 0; }
  .profile-stats > div:nth-last-child(-n + 2) { border-bottom: 0; }
  .profile-filters { grid-template-columns: 1fr; }
}
</style>
