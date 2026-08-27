<script setup lang="ts">
import { computed, h, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import dayjs from 'dayjs';
import type { DataTableColumns, FormInst, FormRules, SelectOption } from 'naive-ui';
import { NButton, NTag } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  createPortalChatBIBrief,
  createPortalChatBIDrilldown,
  createPortalChatBIMonitor,
  downloadDataQueryExport,
  fetchDatasets,
  fetchPortalChatBIQueries,
  fetchPortalChatBIQuery,
  fetchPortalChatBIResultStack,
  fetchPortalChatBITaskPlan,
  fetchPortalChatBITaskPlanEvents,
  streamPortalChatBIQuery,
  updatePortalChatBIPresentation,
  type DatasetView,
  type DataQueryExportFormat,
  type PortalChatBIAggregation,
  type PortalChatBIBrief,
  type PortalChatBIChartType,
  type PortalChatBIMonitor,
  type PortalChatBIQuery,
  type PortalChatBIQueryRequest,
  type PortalChatBIResultStackItem,
  type PortalChatBITask,
  type PortalChatBITaskPlan,
  type PortalChatBIStreamEvent
} from '@/service/api';
import { parseChatBIPortalHandoff } from './portal-handoff';
import ResultPresentation from './result-presentation.vue';

type ResultRow = { index: number; cells: unknown[] };
type ScheduleType = 'daily' | 'weekly' | 'monthly';
type ResultMode = 'table' | 'chart' | 'pivot';
type PortalIdentifier = string | number;

const route = useRoute();
const loading = ref(false);
const historyLoading = ref(false);
const querySubmitting = ref(false);
const queryExporting = ref(false);
const queryExportOptions = [
  { label: '导出 CSV', key: 'csv' },
  { label: '导出 Excel', key: 'xlsx' }
];
const rerunningQueryId = ref<string | number | null>(null);
const queryError = ref('');
const loadError = ref('');
const datasets = ref<DatasetView[]>([]);
const history = ref<PortalChatBIQuery[]>([]);
const activeQuery = ref<PortalChatBIQuery | null>(null);
const resultStack = ref<PortalChatBIResultStackItem[]>([]);
const activeTaskPlan = ref<PortalChatBITaskPlan | null>(null);
const taskPlanCursor = ref(0);
const repairProgress = ref<Array<Record<string, unknown>>>([]);
const federationProgress = ref<Array<Record<string, unknown>>>([]);
const resultMode = ref<ResultMode>('table');
const currentConversationId = ref<PortalIdentifier | null>(null);
const queryFormRef = ref<FormInst | null>(null);
const queryForm = reactive({ datasetIds: [] as PortalIdentifier[], question: '' });
let detailRequestSequence = 0;
let appliedPortalHandoff = '';
let queryStreamController: AbortController | null = null;

const briefVisible = ref(false);
const briefSubmitting = ref(false);
const briefResult = ref<PortalChatBIBrief | null>(null);
const briefForm = reactive({ title: '', exportWord: true, polishWithLlm: false });

const monitorVisible = ref(false);
const monitorSubmitting = ref(false);
const monitorResult = ref<PortalChatBIMonitor | null>(null);
const monitorForm = reactive({
  title: '',
  scheduleType: 'daily' as ScheduleType,
  timeValue: '09:00',
  weekday: 0,
  monthday: 1,
  notifyOnSuccess: false
});

const presentationVisible = ref(false);
const presentationSubmitting = ref(false);
const presentationForm = reactive({
  chartType: 'bar' as PortalChatBIChartType,
  chartDimension: null as string | null,
  chartMeasures: [] as string[],
  chartAggregation: 'sum' as PortalChatBIAggregation,
  pivotRows: [] as string[],
  pivotColumn: null as string | null,
  pivotValue: null as string | null,
  pivotAggregation: 'sum' as PortalChatBIAggregation
});

const drilldownVisible = ref(false);
const drilldownSubmitting = ref(false);
const drilldownForm = reactive({ dimension: '', value: null as unknown, question: '' });

const queryRules: FormRules = {
  datasetIds: [
    {
      type: 'array',
      required: true,
      min: 1,
      max: 5,
      message: '请选择 1 到 5 个数据集',
      trigger: ['change', 'blur']
    }
  ],
  question: [
    { required: true, message: '请输入要分析的问题', trigger: ['input', 'blur'] },
    { min: 2, max: 4000, message: '问题长度应为 2 到 4000 个字符', trigger: ['input', 'blur'] }
  ]
};

const datasetOptions = computed<SelectOption[]>(() =>
  datasets.value
    .filter(item => item.status === 'active')
    .map(item => ({
      label: item.name,
      value: item.id,
      disabled:
        queryForm.datasetIds.length >= 5 &&
        !queryForm.datasetIds.some(selected => String(selected) === String(item.id))
    }))
);
const activeDatasets = computed(() =>
  queryForm.datasetIds
    .map(id => datasets.value.find(item => String(item.id) === String(id)))
    .filter((item): item is DatasetView => Boolean(item))
);
const activeResultId = computed(() => {
  const value = activeQuery.value?.result_id;
  return value === null || value === undefined ? null : String(value);
});
const activeQueryId = computed(() => activeQuery.value?.query_id ?? null);
const resultModeOptions = [
  { label: '表格', value: 'table' },
  { label: '图表', value: 'chart' },
  { label: '透视', value: 'pivot' }
] as const;
const chartTypeOptions: SelectOption[] = [
  { label: '不显示图表', value: 'none' },
  { label: '柱状图', value: 'bar' },
  { label: '折线图', value: 'line' },
  { label: '饼图', value: 'pie' }
];
const aggregationOptions: SelectOption[] = [
  { label: '求和', value: 'sum' },
  { label: '平均值', value: 'avg' },
  { label: '最小值', value: 'min' },
  { label: '最大值', value: 'max' },
  { label: '计数', value: 'count' }
];
const resultFieldOptions = computed<SelectOption[]>(() =>
  (activeQuery.value?.columns || []).map(column => ({ label: column, value: column }))
);
const numericResultFields = computed(() => {
  const rows = activeQuery.value?.rows || [];
  return (activeQuery.value?.columns || []).filter((_column, index) => {
    let seen = false;
    for (const row of rows.slice(0, 200)) {
      const value = row[index];
      if (value === null || value === undefined || value === '') continue;
      seen = true;
      if (typeof value !== 'number' && !Number.isFinite(Number(value))) return false;
    }
    return seen;
  });
});
const numericFieldOptions = computed<SelectOption[]>(() =>
  numericResultFields.value.map(column => ({ label: column, value: column }))
);
const resultStackOptions = computed<SelectOption[]>(() =>
  resultStack.value.map((item, index) => ({
    label: `${index + 1}. ${item.question}`,
    value: String(item.query_id)
  }))
);
const defaultDrilldownDimension = computed(
  () =>
    activeQuery.value?.presentation?.chart.dimension ||
    activeQuery.value?.presentation?.pivot.row_dimensions?.[0] ||
    activeQuery.value?.columns?.[0] ||
    ''
);
const resultRows = computed<ResultRow[]>(() =>
  (activeQuery.value?.rows || []).map((cells, index) => ({ index: index + 1, cells }))
);
const resultColumns = computed<DataTableColumns<ResultRow>>(() => {
  const columns: DataTableColumns<ResultRow> = [
    { title: '#', key: 'index', width: 56, fixed: 'left', render: row => row.index }
  ];
  for (const [index, title] of (activeQuery.value?.columns || []).entries()) {
    columns.push({
      title,
      key: `column-${index}`,
      minWidth: 140,
      ellipsis: { tooltip: true },
      render: row => formatCell(row.cells[index])
    });
  }
  const dimension = defaultDrilldownDimension.value;
  const dimensionIndex = (activeQuery.value?.columns || []).indexOf(dimension);
  if (activeQuery.value?.status === 'succeeded' && dimension && dimensionIndex >= 0) {
    columns.push({
      title: '操作',
      key: 'drilldown',
      width: 84,
      fixed: 'right',
      render: row =>
        h(
          NButton,
          {
            size: 'tiny',
            quaternary: true,
            type: 'primary',
            onClick: () => openDrilldown(dimension, row.cells[dimensionIndex])
          },
          { default: () => '下钻' }
        )
    });
  }
  return columns;
});
const resultScrollX = computed(() => Math.max(720, resultColumns.value.length * 160 + 56));
const questionPlaceholder = computed(() =>
  activeQuery.value?.status === 'clarify' ? '补充说明后继续分析' : '例如：过去 30 天各区域销售额和同比变化如何？'
);
const fromDataPortal = computed(() => Boolean(parseChatBIPortalHandoff(route.query as Record<string, unknown>)));
const weekdayOptions: SelectOption[] = [
  { label: '星期一', value: 0 },
  { label: '星期二', value: 1 },
  { label: '星期三', value: 2 },
  { label: '星期四', value: 3 },
  { label: '星期五', value: 4 },
  { label: '星期六', value: 5 },
  { label: '星期日', value: 6 }
];

async function loadWorkspace() {
  loading.value = true;
  loadError.value = '';
  const [datasetResult, historyResult] = await Promise.all([fetchDatasets(), fetchPortalChatBIQueries(30)]);

  if (datasetResult.error) {
    loadError.value = requestError(datasetResult.error, '数据集加载失败');
  } else {
    datasets.value = datasetResult.data;
    const available = datasetResult.data.find(item => item.status === 'active');
    if (!queryForm.datasetIds.length && available) queryForm.datasetIds = [available.id];
  }

  if (historyResult.error) {
    loadError.value ||= requestError(historyResult.error, '查询历史加载失败');
  } else {
    history.value = historyResult.data;
    const latest = historyResult.data.find(item => item.query_id !== null && item.query_id !== undefined);
    if (!activeQuery.value && latest) await openHistory(latest, false);
  }
  applyPortalHandoff();
  loading.value = false;
}

function applyPortalHandoff() {
  const handoff = parseChatBIPortalHandoff(route.query as Record<string, unknown>);
  if (!handoff) return;
  const signature = `${handoff.datasetId || ''}\u0000${handoff.question}`;
  if (signature === appliedPortalHandoff) return;
  appliedPortalHandoff = signature;
  queryForm.question = handoff.question;
  if (handoff.datasetId) {
    const matched = datasets.value.find(item => item.status === 'active' && String(item.id) === handoff.datasetId);
    if (matched) queryForm.datasetIds = [matched.id];
  }
  currentConversationId.value = null;
  resultStack.value = [];
}

async function refreshHistory() {
  historyLoading.value = true;
  const result = await fetchPortalChatBIQueries(30);
  if (!result.error) history.value = result.data;
  historyLoading.value = false;
}

async function refreshResultStack(conversationId = currentConversationId.value) {
  if (conversationId === null || conversationId === undefined) {
    resultStack.value = [];
    return;
  }
  const result = await fetchPortalChatBIResultStack(conversationId, 10);
  if (!result.error) resultStack.value = result.data;
}

async function submitQuestion() {
  try {
    await queryFormRef.value?.validate();
  } catch {
    return;
  }
  if (!queryForm.datasetIds.length) return;
  await executeQuestion(queryForm.question, queryForm.datasetIds, currentConversationId.value || undefined);
}

function normalizeDatasetIds(values: Array<PortalIdentifier | null | undefined>) {
  const result: PortalIdentifier[] = [];
  for (const value of values) {
    if (value === null || value === undefined || result.some(item => String(item) === String(value))) continue;
    result.push(value);
  }
  return result.slice(0, 5);
}

function queryDatasetIds(query: PortalChatBIQuery | null | undefined) {
  if (!query) return [];
  return normalizeDatasetIds([...(query.dataset_ids || []), query.dataset_id]);
}

function sameDatasetSelection(left: PortalIdentifier[], right: PortalIdentifier[]) {
  const a = normalizeDatasetIds(left);
  const b = normalizeDatasetIds(right);
  return a.length === b.length && a.every((value, index) => String(value) === String(b[index]));
}

async function executeQuestion(
  question: string,
  datasetIds: PortalIdentifier[],
  conversationId?: PortalIdentifier
) {
  const normalizedQuestion = question.trim();
  if (!normalizedQuestion) return;
  const selectedDatasetIds = normalizeDatasetIds(datasetIds);
  const primaryDatasetId = selectedDatasetIds[0];
  if (primaryDatasetId === undefined) return;
  const sequence = ++detailRequestSequence;
  querySubmitting.value = true;
  queryError.value = '';
  activeTaskPlan.value = null;
  taskPlanCursor.value = 0;
  repairProgress.value = [];
  federationProgress.value = [];
  queryStreamController?.abort();
  const controller = new AbortController();
  queryStreamController = controller;
  const requestData: PortalChatBIQueryRequest = {
    dataset_id: primaryDatasetId,
    ...(selectedDatasetIds.length > 1 ? { dataset_ids: selectedDatasetIds } : {}),
    question: normalizedQuestion,
    ...(conversationId ? { conversation_id: conversationId } : {}),
    ...(activeQuery.value?.status === 'succeeded' &&
    conversationId &&
    String(activeQuery.value.conversation_id) === String(conversationId) &&
    sameDatasetSelection(queryDatasetIds(activeQuery.value), selectedDatasetIds) &&
    activeQuery.value.query_id
      ? { parent_result_id: activeQuery.value.query_id }
      : {})
  };

  const streamState: { result: PortalChatBIQuery | null; error: string } = { result: null, error: '' };
  try {
    await streamPortalChatBIQuery(
      requestData,
      event => {
        if (sequence !== detailRequestSequence) return;
        consumeChatBIStreamEvent(
          event,
          result => {
            streamState.result = result;
          },
          message => {
            streamState.error = message;
          }
        );
      },
      controller.signal
    );
  } catch (error) {
    if (!(error instanceof DOMException && error.name === 'AbortError')) {
      streamState.error = error instanceof Error ? error.message : '分析请求失败';
    }
  } finally {
    if (queryStreamController === controller) queryStreamController = null;
  }

  if (sequence !== detailRequestSequence) return;
  const streamedResult = streamState.result;
  if (!streamedResult) {
    const planKey = (activeTaskPlan.value as PortalChatBITaskPlan | null)?.plan_id;
    if (planKey) {
      await replayTaskPlanEvents(planKey, taskPlanCursor.value);
      const recovered = await fetchPortalChatBITaskPlan(planKey);
      if (!recovered.error && recovered.data) {
        activeTaskPlan.value = recovered.data;
      }
    }
    queryError.value = streamState.error || '分析请求未返回结果';
    querySubmitting.value = false;
    await refreshHistory();
    return;
  }

  const completedQuery: PortalChatBIQuery = {
    ...streamedResult,
    task_plan: streamedResult.task_plan || activeTaskPlan.value || undefined,
    repair_attempts: streamedResult.repair_attempts || repairProgress.value
  };
  activeQuery.value = completedQuery;
  activeTaskPlan.value = completedQuery.task_plan || activeTaskPlan.value;
  if (completedQuery.repair_attempts?.length) repairProgress.value = completedQuery.repair_attempts;
  queryForm.datasetIds = queryDatasetIds(completedQuery);
  queryForm.question = '';
  currentConversationId.value = completedQuery.conversation_id;
  resultMode.value = 'table';
  restoreFederationProgress(completedQuery);
  querySubmitting.value = false;
  await Promise.all([refreshHistory(), refreshResultStack(completedQuery.conversation_id)]);
}

function consumeChatBIStreamEvent(
  event: PortalChatBIStreamEvent,
  onResult: (result: PortalChatBIQuery) => void,
  onError: (message: string) => void
) {
  const data = event.data && typeof event.data === 'object' ? event.data : {};
  if (event.type === 'chatbi_task_plan' || event.type === 'chatbi_task_status') {
    const cursor = Number(event.cursor);
    if (Number.isFinite(cursor) && cursor > taskPlanCursor.value) {
      taskPlanCursor.value = cursor;
    }
  }
  if (event.type === 'chatbi_task_plan') {
    activeTaskPlan.value = mergeTaskPlan(data);
    return;
  }
  if (event.type === 'chatbi_task_status') {
    activeTaskPlan.value = mergeTaskStatus(data);
    return;
  }
  if (event.type === 'chatbi_federated_status') {
    mergeFederationStatus(data);
    return;
  }
  if (event.type === 'log' && event.category === 'sql_repair') {
    const repair = event.repair && typeof event.repair === 'object' ? event.repair : null;
    if (repair) {
      const repairRecord = repair as Record<string, unknown>;
      const repairId = String(repairRecord.repair_id || event.id || repairProgress.value.length);
      const current = repairProgress.value.filter(item => String(item.repair_id || '') !== repairId);
      repairProgress.value = [...current, repairRecord];
    }
    return;
  }
  if (event.type === 'chatbi_result') {
    const result = parseStreamQuery(data);
    if (result) onResult(result);
    else onError('ChatBI 结果事件格式不完整');
    return;
  }
  if (event.type === 'error') {
    const message = typeof data.message === 'string' ? data.message : '分析请求失败';
    onError(message);
  }
}

async function replayTaskPlanEvents(planKey: string, afterCursor = 0) {
  let cursor = Math.max(0, Number(afterCursor) || 0);
  for (let page = 0; page < 10; page += 1) {
    const result = await fetchPortalChatBITaskPlanEvents(planKey, cursor, 200);
    if (result.error || !result.data) return;
    const events = Array.isArray(result.data.events) ? result.data.events : [];
    for (const event of events) {
      consumeChatBIStreamEvent(event, () => undefined, message => {
        queryError.value = message;
      });
    }
    const next = Number(result.data.next_cursor);
    if (!Number.isFinite(next) || next <= cursor) break;
    cursor = next;
    taskPlanCursor.value = Math.max(taskPlanCursor.value, cursor);
    if (!result.data.has_more) break;
  }
}

function parseStreamQuery(data: Record<string, unknown>): PortalChatBIQuery | null {
  const conversationId = data.conversation_id;
  const datasetId = data.dataset_id;
  if (
    typeof data.status !== 'string' ||
    (typeof conversationId !== 'string' && typeof conversationId !== 'number') ||
    typeof data.trace_id !== 'string' ||
    (typeof datasetId !== 'string' && typeof datasetId !== 'number') ||
    typeof data.question !== 'string' ||
    typeof data.created_at !== 'string'
  ) {
    return null;
  }
  return data as unknown as PortalChatBIQuery;
}

function mergeFederationStatus(data: Record<string, unknown>) {
  const stage = typeof data.stage === 'string' ? data.stage : 'federated';
  const sequence = typeof data.sequence === 'number' || typeof data.sequence === 'string' ? data.sequence : '';
  const key = `${stage}:${sequence}`;
  const current = federationProgress.value.filter(item => String(item._key || '') !== key);
  federationProgress.value = [...current, { ...data, _key: key }];
}

function restoreFederationProgress(query: PortalChatBIQuery | null | undefined) {
  const federation = query?.federation;
  if (!federation || typeof federation !== 'object') {
    federationProgress.value = [];
    return;
  }
  const sources = Array.isArray(federation.sources) ? federation.sources : [];
  federationProgress.value = sources.map((source, index) => ({
    ...(source && typeof source === 'object' ? source : {}),
    stage: 'source',
    sequence: index + 1,
    _key: `source:${index + 1}`
  }));
}

function mergeTaskPlan(data: Record<string, unknown>): PortalChatBITaskPlan | null {
  const planId = data.plan_id || activeTaskPlan.value?.plan_id;
  if (!planId) return activeTaskPlan.value;
  const tasks = Array.isArray(data.tasks) ? (data.tasks as PortalChatBITask[]) : activeTaskPlan.value?.tasks || [];
  return {
    ...activeTaskPlan.value,
    plan_id: String(planId),
    status: typeof data.status === 'string' ? data.status : activeTaskPlan.value?.status || 'pending',
    dataset_id: (data.dataset_id as PortalIdentifier | undefined) ?? activeTaskPlan.value?.dataset_id,
    dataset_ids: Array.isArray(data.dataset_ids)
      ? (data.dataset_ids as Array<PortalIdentifier>)
      : activeTaskPlan.value?.dataset_ids,
    conversation_id:
      (data.conversation_id as PortalIdentifier | null | undefined) ?? activeTaskPlan.value?.conversation_id,
    question: typeof data.question === 'string' ? data.question : activeTaskPlan.value?.question,
    tasks
  };
}

function mergeTaskStatus(data: Record<string, unknown>): PortalChatBITaskPlan | null {
  const current = activeTaskPlan.value;
  const planId = data.plan_id || current?.plan_id;
  if (!planId) return current;
  const fullTasks = Array.isArray(data.tasks) ? (data.tasks as PortalChatBITask[]) : null;
  if (fullTasks) {
    return {
      ...current,
      plan_id: String(planId),
      status: typeof data.status === 'string' ? data.status : current?.status || 'running',
      tasks: fullTasks
    };
  }
  const taskId = typeof data.task_id === 'string' ? data.task_id : '';
  const tasks = (current?.tasks || []).map(task =>
    task.task_id === taskId
      ? {
          ...task,
          status: typeof data.status === 'string' ? data.status : task.status,
          result_query_id: (data.result_query_id as PortalIdentifier | null | undefined) ?? task.result_query_id,
          error: typeof data.error === 'string' ? data.error : task.error
        }
      : task
  );
  const taskStatus = typeof data.status === 'string' ? data.status : '';
  return {
    ...(current as PortalChatBITaskPlan),
    plan_id: String(planId),
    status: taskStatus === 'running' ? 'running' : current?.status || 'pending',
    tasks
  };
}

async function openHistory(item: PortalChatBIQuery, showLoading = true) {
  if (item.query_id === null || item.query_id === undefined) return;
  await openQuery(item.query_id, showLoading);
}

async function openQuery(queryId: PortalIdentifier, showLoading = true) {
  const sequence = ++detailRequestSequence;
  if (showLoading) querySubmitting.value = true;
  queryError.value = '';
  const result = await fetchPortalChatBIQuery(queryId);
  if (sequence !== detailRequestSequence) return;
  if (result.error) {
    queryError.value = requestError(result.error, '查询详情加载失败');
    querySubmitting.value = false;
    return;
  }
  activeQuery.value = result.data;
  activeTaskPlan.value = result.data.task_plan || null;
  taskPlanCursor.value = 0;
  repairProgress.value = result.data.repair_attempts || [];
  queryForm.datasetIds = queryDatasetIds(result.data);
  currentConversationId.value = result.data.conversation_id;
  resultMode.value = 'table';
  restoreFederationProgress(result.data);
  if (activeTaskPlan.value?.plan_id) {
    await replayTaskPlanEvents(activeTaskPlan.value.plan_id);
  }
  querySubmitting.value = false;
  await refreshResultStack(result.data.conversation_id);
}

async function selectResultStack(value: string | number | null) {
  if (value === null || value === undefined || value === '') return;
  await openQuery(value);
}

async function rerun(item: PortalChatBIQuery) {
  rerunningQueryId.value = item.query_id || item.trace_id;
  currentConversationId.value = null;
  const datasetIds = queryDatasetIds(item);
  queryForm.datasetIds = datasetIds;
  await executeQuestion(item.question, datasetIds);
  rerunningQueryId.value = null;
}

async function exportCurrentQuery(format: DataQueryExportFormat) {
  const queryId = activeQuery.value?.query_id;
  if (queryId === null || queryId === undefined) return;
  queryExporting.value = true;
  try {
    const { blob, fileName } = await downloadDataQueryExport(String(queryId), format);
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

function handleDatasetChange(value: PortalIdentifier[]) {
  queryForm.datasetIds = normalizeDatasetIds(value);
  if (!sameDatasetSelection(queryDatasetIds(activeQuery.value), queryForm.datasetIds)) {
    currentConversationId.value = null;
  }
  federationProgress.value = [];
}

function clearConversationContext() {
  currentConversationId.value = null;
}

function openBrief() {
  if (!activeResultId.value) {
    window.$message?.error('当前分析没有可用于生成简报的结果');
    return;
  }
  briefForm.title = activeQuery.value?.title || '';
  briefForm.exportWord = true;
  briefForm.polishWithLlm = false;
  briefResult.value = null;
  briefVisible.value = true;
}

async function createBrief() {
  if (!activeResultId.value) return;
  briefResult.value = null;
  briefSubmitting.value = true;
  const result = await createPortalChatBIBrief({
    result_id: activeResultId.value,
    title: briefForm.title.trim() || undefined,
    export_word: briefForm.exportWord,
    polish_with_llm: briefForm.polishWithLlm
  });
  if (!result.error) {
    briefResult.value = result.data;
    window.$message?.success('业务简报已生成');
  }
  briefSubmitting.value = false;
}

function openMonitor() {
  if (!activeResultId.value) {
    window.$message?.error('当前分析没有可用于创建监控的结果');
    return;
  }
  monitorForm.title = activeQuery.value?.title || '';
  monitorResult.value = null;
  monitorVisible.value = true;
}

async function createMonitor() {
  if (!activeResultId.value) return;
  monitorResult.value = null;
  monitorSubmitting.value = true;
  const result = await createPortalChatBIMonitor({
    result_id: activeResultId.value,
    title: monitorForm.title.trim() || undefined,
    schedule_type: monitorForm.scheduleType,
    time_value: monitorForm.timeValue,
    weekday: monitorForm.weekday,
    monthday: monitorForm.monthday,
    notify_on_success: monitorForm.notifyOnSuccess
  });
  if (!result.error) {
    monitorResult.value = result.data;
    window.$message?.success(result.data.created ? '查询监控已创建' : '已复用现有查询监控');
  }
  monitorSubmitting.value = false;
}

function openPresentation() {
  const presentation = activeQuery.value?.presentation;
  if (!presentation) {
    window.$message?.error('当前结果没有可编辑的展示配置');
    return;
  }
  presentationForm.chartType = presentation.chart.type;
  presentationForm.chartDimension = presentation.chart.dimension;
  presentationForm.chartMeasures = [...presentation.chart.measures];
  presentationForm.chartAggregation = presentation.chart.aggregation;
  presentationForm.pivotRows = [...presentation.pivot.row_dimensions];
  presentationForm.pivotColumn = presentation.pivot.column_dimension;
  presentationForm.pivotValue = presentation.pivot.value_column;
  presentationForm.pivotAggregation = presentation.pivot.aggregation;
  presentationVisible.value = true;
}

async function savePresentation() {
  const queryId = activeQuery.value?.query_id;
  const presentation = activeQuery.value?.presentation;
  if (queryId === null || queryId === undefined || !presentation || !activeQuery.value) return;
  presentationSubmitting.value = true;
  queryError.value = '';
  const result = await updatePortalChatBIPresentation(queryId, {
    expected_revision: presentation.revision,
    chart: {
      type: presentationForm.chartType,
      dimension: presentationForm.chartDimension,
      measures: presentationForm.chartMeasures,
      aggregation: presentationForm.chartAggregation
    },
    pivot: {
      row_dimensions: presentationForm.pivotRows,
      column_dimension: presentationForm.pivotColumn,
      value_column: presentationForm.pivotValue,
      aggregation: presentationForm.pivotAggregation
    }
  });
  if (result.error) {
    queryError.value = requestError(result.error, '展示配置保存失败');
  } else {
    activeQuery.value = { ...activeQuery.value, presentation: result.data };
    presentationVisible.value = false;
    window.$message?.success('展示配置已保存');
  }
  presentationSubmitting.value = false;
}

function openDrilldown(dimension: string, value: unknown) {
  if (!activeQueryId.value) return;
  drilldownForm.dimension = dimension;
  drilldownForm.value = value;
  drilldownForm.question = '';
  drilldownVisible.value = true;
}

async function submitDrilldown() {
  const queryId = activeQuery.value?.query_id;
  if (queryId === null || queryId === undefined || !drilldownForm.dimension) return;
  drilldownSubmitting.value = true;
  queryError.value = '';
  const result = await createPortalChatBIDrilldown(queryId, {
    dimension: drilldownForm.dimension,
    value: drilldownForm.value,
    question: drilldownForm.question.trim() || undefined
  });
  if (result.error) {
    queryError.value = requestError(result.error, '下钻分析失败');
  } else {
    activeQuery.value = result.data;
    queryForm.datasetIds = queryDatasetIds(result.data);
    currentConversationId.value = result.data.conversation_id;
    restoreFederationProgress(result.data);
    resultMode.value = 'table';
    drilldownVisible.value = false;
    await Promise.all([refreshHistory(), refreshResultStack(result.data.conversation_id)]);
    window.$message?.success('下钻分析已完成');
  }
  drilldownSubmitting.value = false;
}

function formatCell(value: unknown) {
  if (value === null || value === undefined || value === '') return '-';
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value);
    } catch {
      return String(value);
    }
  }
  return String(value);
}

function formatTime(value?: string | null) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-';
}

function formatBytes(value?: number | null) {
  if (!value) return '0 B';
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

function statusType(status: PortalChatBIQuery['status']) {
  if (status === 'succeeded') return 'success';
  if (status === 'failed' || status === 'rejected') return 'error';
  return 'warning';
}

function statusLabel(status: PortalChatBIQuery['status']) {
  if (status === 'succeeded') return '已完成';
  if (status === 'clarify') return '待补充';
  if (status === 'failed') return '失败';
  if (status === 'rejected') return '已拒绝';
  return status;
}

function progressStatusType(status: string) {
  if (status === 'succeeded' || status === 'success') return 'success';
  if (status === 'failed' || status === 'error' || status === 'skipped') return 'error';
  if (status === 'running') return 'info';
  return 'warning';
}

function progressStatusLabel(status: string) {
  if (status === 'pending') return '等待中';
  if (status === 'running') return '执行中';
  if (status === 'succeeded' || status === 'success') return '已完成';
  if (status === 'failed' || status === 'error') return '失败';
  if (status === 'skipped') return '已跳过';
  if (status === 'clarification_required') return '待补充';
  return status;
}

function federationStageLabel(stage: unknown) {
  if (stage === 'source') return '子查询';
  if (stage === 'memory_join') return '内存联接';
  if (stage === 'plan') return '联邦计划';
  return '联邦执行';
}

function federationDetail(item: Record<string, unknown>) {
  if (typeof item.error === 'string' && item.error) return item.error;
  if (typeof item.dataset_name === 'string' && item.dataset_name) {
    return `${item.dataset_name}${item.temp_table ? ` · ${item.temp_table}` : ''}`;
  }
  if (typeof item.status === 'string') return item.status;
  return '正在准备联邦查询';
}

function taskOperationLabel(operation: string) {
  if (operation === 'query') return '查询';
  if (operation === 'analyze') return '分析';
  if (operation === 'present') return '呈现';
  return operation;
}

function repairStatus(repair: Record<string, unknown>) {
  return typeof repair.status === 'string' ? repair.status : 'running';
}

function repairAttemptLabel(repair: Record<string, unknown>, index: number) {
  const attempt = typeof repair.attempt === 'number' ? repair.attempt : index + 1;
  const maximum = typeof repair.max_attempts === 'number' ? repair.max_attempts : '-';
  return `第 ${attempt}/${maximum} 次`;
}

function repairDetails(repair: Record<string, unknown>) {
  if (typeof repair.reason === 'string' && repair.reason) return repair.reason;
  if (typeof repair.error_summary === 'string' && repair.error_summary) return repair.error_summary;
  return 'SQL 自动修复处理中';
}

function queryDatasetName(query: PortalChatBIQuery) {
  if (query.dataset_names?.length) return query.dataset_names.join('、');
  if (query.dataset_name && !query.dataset_ids?.length) return query.dataset_name;
  const names = queryDatasetIds(query).map(
    id => datasets.value.find(item => String(item.id) === String(id))?.name || `数据集 #${id}`
  );
  return names.join('、') || `数据集 #${query.dataset_id}`;
}

function requestError(error: { response?: { data?: { message?: string; msg?: string } } } | null, fallback: string) {
  return error?.response?.data?.message || error?.response?.data?.msg || fallback;
}

watch(
  () => [route.query.source, route.query.question, route.query.dataset_id],
  () => applyPortalHandoff()
);

onMounted(loadWorkspace);
onBeforeUnmount(() => {
  detailRequestSequence += 1;
  queryStreamController?.abort();
  queryStreamController = null;
});
</script>

<template>
  <div class="chatbi-page h-full overflow-auto p-16px">
    <header class="mb-12px flex flex-wrap items-center justify-between gap-12px">
      <div class="min-w-0">
        <div class="flex items-center gap-8px">
          <SvgIcon icon="lucide:chart-spline" class="text-20px text-primary" />
          <h1 class="m-0 text-22px font-600">ChatBI</h1>
          <NTag size="small" :bordered="false">自然语言分析</NTag>
        </div>
        <div class="mt-2px max-w-680px truncate text-12px op-60">
          {{ activeDatasets.length ? activeDatasets.map(item => item.name).join('、') : '请选择可用数据集' }}
        </div>
      </div>
      <NSpace :wrap="false">
        <NButton quaternary :loading="loading" title="刷新" @click="loadWorkspace">
          <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
        </NButton>
        <NButton secondary @click="$router.push('/saved-reports')">
          <template #icon><SvgIcon icon="lucide:file-chart-column" /></template>
          报表
        </NButton>
      </NSpace>
    </header>

    <NAlert v-if="loadError" type="error" :show-icon="true" class="mb-12px">{{ loadError }}</NAlert>

    <NCard :bordered="false" size="small" class="query-composer">
      <NForm ref="queryFormRef" :model="queryForm" :rules="queryRules" label-placement="top">
        <NGrid :cols="24" :x-gap="12" :y-gap="4" responsive="screen" item-responsive>
          <NFormItemGi span="24 m:9" label="数据集" path="datasetIds">
            <NSelect
              v-model:value="queryForm.datasetIds"
              :options="datasetOptions"
              multiple
              filterable
              clearable
              max-tag-count="responsive"
              placeholder="选择 1-5 个数据集（多选可联邦分析）"
              :disabled="querySubmitting"
              @update:value="handleDatasetChange"
            />
          </NFormItemGi>
          <NFormItemGi span="24 m:11" path="question">
            <template #label>
              <div class="flex items-center gap-8px">
                <span>问题</span>
                <NTag v-if="fromDataPortal" size="small" type="info" :bordered="false">来自数据门户</NTag>
                <NTag
                  v-if="currentConversationId"
                  size="small"
                  closable
                  :bordered="false"
                  @close="clearConversationContext"
                >
                  连续追问 #{{ currentConversationId }}
                </NTag>
              </div>
            </template>
            <NInput
              v-model:value="queryForm.question"
              type="textarea"
              :autosize="{ minRows: 2, maxRows: 4 }"
              maxlength="4000"
              show-count
              :placeholder="questionPlaceholder"
              :disabled="querySubmitting"
              @keydown.ctrl.enter.prevent="submitQuestion"
              @keydown.meta.enter.prevent="submitQuestion"
            />
          </NFormItemGi>
          <NFormItemGi span="24 m:4" label=" ">
            <NButton
              type="primary"
              block
              class="query-button"
              :loading="querySubmitting"
              :disabled="!datasetOptions.length"
              @click="submitQuestion"
            >
              <template #icon><SvgIcon icon="lucide:send" /></template>
              开始分析
            </NButton>
          </NFormItemGi>
        </NGrid>
      </NForm>
      <NAlert v-if="queryForm.datasetIds.length > 1" type="info" :show-icon="true" class="mt-8px">
        已选择 {{ queryForm.datasetIds.length }} 个数据集，将先分别执行受治理子查询，再在内存中联接；每个数据集仍会单独复核权限。
      </NAlert>
      <NAlert v-if="!loading && !datasetOptions.length" type="warning" :show-icon="true">
        当前没有可用于查询的活动数据集
      </NAlert>
    </NCard>

    <div class="workspace-grid mt-12px">
      <NCard :bordered="false" size="small" class="result-panel">
        <template #header>
          <div class="flex min-w-0 items-center gap-8px">
            <span class="truncate">{{ activeQuery?.title || activeQuery?.question || '分析结果' }}</span>
            <NTag v-if="activeQuery" size="small" :bordered="false" :type="statusType(activeQuery.status)">
              {{ statusLabel(activeQuery.status) }}
            </NTag>
          </div>
        </template>
        <template v-if="activeQuery?.status === 'succeeded'" #header-extra>
          <NSpace :wrap="false" size="small">
            <NDropdown :options="queryExportOptions" trigger="click" @select="exportCurrentQuery">
              <NButton
                size="small"
                quaternary
                title="导出查询结果"
                :loading="queryExporting"
                :disabled="!activeResultId"
              >
                <template #icon><SvgIcon icon="lucide:download" /></template>
              </NButton>
            </NDropdown>
            <NButton size="small" secondary :disabled="!activeResultId" @click="openBrief">
              <template #icon><SvgIcon icon="lucide:file-text" /></template>
              简报
            </NButton>
            <NButton size="small" secondary :disabled="!activeResultId" @click="openMonitor">
              <template #icon><SvgIcon icon="lucide:bell-plus" /></template>
              监控
            </NButton>
            <NButton
              size="small"
              quaternary
              title="编辑图表和透视配置"
              :disabled="!activeQuery?.presentation"
              @click="openPresentation"
            >
              <template #icon><SvgIcon icon="lucide:sliders-horizontal" /></template>
            </NButton>
          </NSpace>
        </template>

        <NAlert v-if="queryError" type="error" :show-icon="true" class="mb-12px">{{ queryError }}</NAlert>
        <NCollapse v-if="federationProgress.length" class="mb-12px">
          <NCollapseItem name="federation">
            <template #header>
              <div class="flex items-center gap-8px">
                <SvgIcon icon="lucide:network" class="text-primary" />
                <span>联邦查询进度</span>
                <NTag size="tiny" :bordered="false" type="info">{{ federationProgress.length }}</NTag>
              </div>
            </template>
            <div class="grid gap-8px">
              <div
                v-for="(item, index) in federationProgress"
                :key="String(item._key || index)"
                class="progress-row flex min-w-0 items-center gap-8px"
              >
                <NTag size="tiny" :bordered="false" :type="progressStatusType(String(item.status || 'pending'))">
                  {{ progressStatusLabel(String(item.status || 'pending')) }}
                </NTag>
                <span class="shrink-0 text-12px font-500">{{ federationStageLabel(item.stage) }}</span>
                <span class="min-w-0 truncate text-12px op-70">{{ federationDetail(item) }}</span>
              </div>
            </div>
          </NCollapseItem>
        </NCollapse>
        <div v-if="activeTaskPlan" class="progress-section mb-12px">
          <div class="flex items-center justify-between gap-8px">
            <div class="flex min-w-0 items-center gap-8px">
              <SvgIcon icon="lucide:list-checks" class="shrink-0 text-primary" />
              <span class="truncate text-13px font-600">任务计划</span>
            </div>
            <NTag size="small" :bordered="false" :type="progressStatusType(activeTaskPlan.status)">
              {{ progressStatusLabel(activeTaskPlan.status) }}
            </NTag>
          </div>
          <div class="mt-8px grid gap-6px">
            <div
              v-for="task in activeTaskPlan.tasks"
              :key="task.task_id"
              class="progress-row flex min-w-0 items-center gap-8px"
            >
              <NTag size="tiny" :bordered="false" :type="progressStatusType(task.status)">
                {{ progressStatusLabel(task.status) }}
              </NTag>
              <span class="shrink-0 text-12px font-500">{{ taskOperationLabel(task.operation) }}</span>
              <span class="min-w-0 truncate text-12px op-70" :title="task.query">{{ task.query }}</span>
            </div>
          </div>
        </div>
        <NCollapse v-if="repairProgress.length" class="mb-12px">
          <NCollapseItem name="sql-repair">
            <template #header>
              <div class="flex items-center gap-8px">
                <SvgIcon icon="lucide:wrench" class="text-warning" />
                <span>SQL 自动修复</span>
                <NTag size="tiny" type="warning" :bordered="false">{{ repairProgress.length }}</NTag>
              </div>
            </template>
            <div class="grid gap-8px">
              <div
                v-for="(repair, index) in repairProgress"
                :key="String(repair.repair_id || index)"
                class="repair-row"
              >
                <div class="flex flex-wrap items-center gap-8px">
                  <span class="text-12px font-500">{{ repairAttemptLabel(repair, index) }}</span>
                  <NTag size="tiny" :bordered="false" :type="progressStatusType(repairStatus(repair))">
                    {{ progressStatusLabel(repairStatus(repair)) }}
                  </NTag>
                </div>
                <div class="mt-4px text-12px op-70">{{ repairDetails(repair) }}</div>
              </div>
            </div>
          </NCollapseItem>
        </NCollapse>
        <div v-if="querySubmitting" class="result-loading">
          <NSkeleton text :repeat="3" />
          <NSkeleton class="mt-16px" height="240px" />
        </div>
        <template v-else-if="activeQuery">
          <div class="result-meta flex flex-wrap items-center gap-x-16px gap-y-6px text-12px op-60">
            <span>{{ queryDatasetName(activeQuery) }}</span>
            <span>{{ activeQuery.row_count || 0 }} 行</span>
            <span>{{ formatBytes(activeQuery.result_bytes) }}</span>
            <span>{{ activeQuery.elapsed_ms || 0 }} ms</span>
            <span>{{ formatTime(activeQuery.created_at) }}</span>
            <NTag v-if="activeQuery.truncated" size="small" type="warning" :bordered="false">结果已截断</NTag>
          </div>
          <div v-if="resultStack.length" class="mt-10px flex flex-wrap items-center gap-8px">
            <span class="text-12px op-60">结果栈</span>
            <NSelect
              :value="activeQueryId ? String(activeQueryId) : null"
              :options="resultStackOptions"
              size="small"
              filterable
              class="result-stack-select"
              @update:value="selectResultStack"
            />
          </div>
          <div class="question-text mt-12px">{{ activeQuery.question }}</div>

          <NAlert
            v-if="activeQuery.status === 'failed' || activeQuery.status === 'rejected'"
            type="error"
            :show-icon="true"
            class="mt-12px"
            title="查询未完成"
          >
            {{ activeQuery.error || activeQuery.analysis || '查询执行失败，请检查数据集状态后重试' }}
          </NAlert>
          <NAlert
            v-else-if="activeQuery.status === 'clarify'"
            type="warning"
            :show-icon="true"
            class="mt-12px"
            title="需要补充信息"
          >
            {{ activeQuery.clarification || activeQuery.analysis }}
          </NAlert>
          <template v-else>
            <div v-if="activeQuery.analysis" class="analysis-text mt-16px whitespace-pre-wrap">
              {{ activeQuery.analysis }}
            </div>
            <NRadioGroup
              v-if="activeQuery.presentation"
              v-model:value="resultMode"
              class="mt-16px"
              size="small"
              name="result-mode"
            >
              <NRadioButton v-for="option in resultModeOptions" :key="option.value" :value="option.value">
                {{ option.label }}
              </NRadioButton>
            </NRadioGroup>
            <div v-if="resultMode === 'table' || !activeQuery.presentation" class="mt-12px overflow-hidden">
              <NDataTable
                v-if="resultColumns.length > 1"
                :columns="resultColumns"
                :data="resultRows"
                :scroll-x="resultScrollX"
                :max-height="420"
                :row-key="row => row.index"
                :single-line="false"
                size="small"
              />
              <NEmpty v-else description="查询成功，但没有返回表格字段" />
            </div>
            <ResultPresentation
              v-else-if="activeQuery.presentation"
              :mode="resultMode"
              :presentation="activeQuery.presentation"
              class="mt-12px"
              @drilldown="payload => openDrilldown(payload.dimension, payload.value)"
            />
            <NCollapse v-if="activeQuery.evidence" class="mt-12px">
              <NCollapseItem name="evidence" title="数据证据">
                <div class="evidence-grid text-12px">
                  <span>状态</span>
                  <span>{{ activeQuery.evidence.status }}</span>
                  <span>来源</span>
                  <span>{{ activeQuery.evidence.source_ref || '-' }}</span>
                  <span>观察时间</span>
                  <span>{{ formatTime(activeQuery.evidence.observed_at) }}</span>
                  <span>结果哈希</span>
                  <span class="break-all">{{ activeQuery.evidence.result_hash || '-' }}</span>
                </div>
              </NCollapseItem>
            </NCollapse>
            <NCollapse v-if="activeQuery.sql" class="mt-12px">
              <NCollapseItem name="sql" title="只读 SQL">
                <NCode :code="activeQuery.sql" language="sql" word-wrap />
              </NCollapseItem>
            </NCollapse>
          </template>
        </template>
        <NEmpty v-else description="选择数据集并输入问题后开始分析" class="result-empty" />
      </NCard>

      <NCard :bordered="false" size="small" class="history-panel" title="查询历史">
        <template #header-extra>
          <NButton quaternary size="small" :loading="historyLoading" title="刷新历史" @click="refreshHistory">
            <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
          </NButton>
        </template>
        <NScrollbar class="history-scroll">
          <NList v-if="history.length" :show-divider="false">
            <NListItem v-for="item in history" :key="String(item.query_id || item.trace_id)" class="history-item">
              <button
                type="button"
                class="history-open"
                :disabled="querySubmitting || item.query_id === null || item.query_id === undefined"
                @click="openHistory(item)"
              >
                <span class="line-clamp-2 font-500">{{ item.title || item.question }}</span>
                <span class="mt-4px block truncate text-12px op-60">{{ queryDatasetName(item) }}</span>
                <span class="mt-6px flex items-center justify-between gap-8px text-12px op-60">
                  <span>{{ formatTime(item.created_at) }}</span>
                  <NTag size="tiny" :bordered="false" :type="statusType(item.status)">
                    {{ statusLabel(item.status) }}
                  </NTag>
                </span>
              </button>
              <template #suffix>
                <NButton
                  quaternary
                  size="small"
                  title="重新查询"
                  :loading="rerunningQueryId === (item.query_id || item.trace_id)"
                  :disabled="querySubmitting"
                  @click.stop="rerun(item)"
                >
                  <template #icon><SvgIcon icon="lucide:rotate-cw" /></template>
                </NButton>
              </template>
            </NListItem>
          </NList>
          <NEmpty v-else description="暂无查询历史" class="py-48px" />
        </NScrollbar>
      </NCard>
    </div>

    <NModal
      v-model:show="briefVisible"
      preset="card"
      title="生成业务简报"
      :mask-closable="!briefSubmitting"
      style="width: min(600px, calc(100vw - 32px))"
    >
      <NForm :model="briefForm" label-placement="top">
        <NFormItem label="简报标题">
          <NInput v-model:value="briefForm.title" maxlength="255" placeholder="留空则使用分析标题" />
        </NFormItem>
        <NFormItem label="输出">
          <NSwitch v-model:value="briefForm.exportWord" />
          <span class="ml-8px">生成可下载文件</span>
        </NFormItem>
        <NFormItem label="内容处理">
          <NSwitch v-model:value="briefForm.polishWithLlm" />
          <span class="ml-8px">模型润色</span>
        </NFormItem>
      </NForm>
      <NAlert v-if="briefResult" type="success" :show-icon="true" title="简报已生成">
        {{ briefResult.title }}
        <div v-if="briefResult.artifact?.download_url" class="mt-8px">
          <NButton
            tag="a"
            text
            type="primary"
            :href="String(briefResult.artifact.download_url)"
            target="_blank"
            rel="noopener noreferrer"
          >
            下载文件
          </NButton>
        </div>
      </NAlert>
      <template #footer>
        <NSpace justify="end">
          <NButton :disabled="briefSubmitting" @click="briefVisible = false">关闭</NButton>
          <NButton type="primary" :loading="briefSubmitting" @click="createBrief">生成简报</NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal
      v-model:show="monitorVisible"
      preset="card"
      title="创建查询监控"
      :mask-closable="!monitorSubmitting"
      style="width: min(640px, calc(100vw - 32px))"
    >
      <NForm :model="monitorForm" label-placement="top">
        <NFormItem label="监控名称">
          <NInput v-model:value="monitorForm.title" maxlength="255" placeholder="留空则使用分析标题" />
        </NFormItem>
        <NFormItem label="执行周期">
          <NRadioGroup v-model:value="monitorForm.scheduleType">
            <NRadioButton value="daily">每天</NRadioButton>
            <NRadioButton value="weekly">每周</NRadioButton>
            <NRadioButton value="monthly">每月</NRadioButton>
          </NRadioGroup>
        </NFormItem>
        <NGrid :cols="2" :x-gap="12" responsive="screen" item-responsive>
          <NFormItemGi span="2 m:1" label="执行时间">
            <NTimePicker
              v-model:formatted-value="monitorForm.timeValue"
              value-format="HH:mm"
              format="HH:mm"
              :clearable="false"
            />
          </NFormItemGi>
          <NFormItemGi v-if="monitorForm.scheduleType === 'weekly'" span="2 m:1" label="星期">
            <NSelect v-model:value="monitorForm.weekday" :options="weekdayOptions" />
          </NFormItemGi>
          <NFormItemGi v-if="monitorForm.scheduleType === 'monthly'" span="2 m:1" label="日期">
            <NInputNumber v-model:value="monitorForm.monthday" :min="1" :max="28" />
          </NFormItemGi>
        </NGrid>
        <NFormItem label="通知">
          <NSwitch v-model:value="monitorForm.notifyOnSuccess" />
          <span class="ml-8px">执行成功时发送站内通知</span>
        </NFormItem>
      </NForm>
      <NAlert v-if="monitorResult" type="success" :show-icon="true" title="监控已就绪">
        报表 #{{ monitorResult.report_id }}，下次执行 {{ formatTime(monitorResult.next_run_at) }}
      </NAlert>
      <template #footer>
        <NSpace justify="end">
          <NButton :disabled="monitorSubmitting" @click="monitorVisible = false">关闭</NButton>
          <NButton type="primary" :loading="monitorSubmitting" @click="createMonitor">创建监控</NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal
      v-model:show="presentationVisible"
      preset="card"
      title="图表和透视配置"
      :mask-closable="!presentationSubmitting"
      style="width: min(720px, calc(100vw - 32px))"
    >
      <NForm :model="presentationForm" label-placement="top">
        <NDivider title-placement="left">图表</NDivider>
        <NGrid :cols="2" :x-gap="12" :y-gap="4" responsive="screen" item-responsive>
          <NFormItemGi span="2 m:1" label="类型">
            <NSelect v-model:value="presentationForm.chartType" :options="chartTypeOptions" />
          </NFormItemGi>
          <NFormItemGi span="2 m:1" label="维度">
            <NSelect
              v-model:value="presentationForm.chartDimension"
              :options="resultFieldOptions"
              clearable
              filterable
              placeholder="按行号展示"
            />
          </NFormItemGi>
          <NFormItemGi span="2" label="指标">
            <NSelect
              v-model:value="presentationForm.chartMeasures"
              :options="numericFieldOptions"
              multiple
              filterable
              max-tag-count="responsive"
              placeholder="选择数值字段"
            />
          </NFormItemGi>
          <NFormItemGi span="2 m:1" label="聚合方式">
            <NSelect v-model:value="presentationForm.chartAggregation" :options="aggregationOptions" />
          </NFormItemGi>
        </NGrid>
        <NDivider title-placement="left">透视表</NDivider>
        <NGrid :cols="2" :x-gap="12" :y-gap="4" responsive="screen" item-responsive>
          <NFormItemGi span="2" label="行维度">
            <NSelect
              v-model:value="presentationForm.pivotRows"
              :options="resultFieldOptions"
              multiple
              filterable
              max-tag-count="responsive"
            />
          </NFormItemGi>
          <NFormItemGi span="2 m:1" label="列维度">
            <NSelect v-model:value="presentationForm.pivotColumn" :options="resultFieldOptions" clearable filterable />
          </NFormItemGi>
          <NFormItemGi span="2 m:1" label="值字段">
            <NSelect v-model:value="presentationForm.pivotValue" :options="numericFieldOptions" clearable filterable />
          </NFormItemGi>
          <NFormItemGi span="2 m:1" label="聚合方式">
            <NSelect v-model:value="presentationForm.pivotAggregation" :options="aggregationOptions" />
          </NFormItemGi>
        </NGrid>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton :disabled="presentationSubmitting" @click="presentationVisible = false">取消</NButton>
          <NButton type="primary" :loading="presentationSubmitting" @click="savePresentation">保存配置</NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal
      v-model:show="drilldownVisible"
      preset="card"
      title="下钻分析"
      :mask-closable="!drilldownSubmitting"
      style="width: min(560px, calc(100vw - 32px))"
    >
      <NForm :model="drilldownForm" label-placement="top">
        <NFormItem label="维度">
          <NInput v-model:value="drilldownForm.dimension" readonly />
        </NFormItem>
        <NFormItem label="当前值">
          <NInput :value="formatCell(drilldownForm.value)" readonly />
        </NFormItem>
        <NFormItem label="补充问题">
          <NInput
            v-model:value="drilldownForm.question"
            type="textarea"
            maxlength="2000"
            show-count
            placeholder="可选：指定下钻时关注的业务问题"
          />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton :disabled="drilldownSubmitting" @click="drilldownVisible = false">取消</NButton>
          <NButton type="primary" :loading="drilldownSubmitting" @click="submitDrilldown">开始下钻</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.chatbi-page {
  min-width: 0;
}

.query-composer,
.result-panel,
.history-panel {
  border-radius: 6px;
}

.query-button {
  min-height: 54px;
}

.workspace-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 12px;
  align-items: start;
}

.result-panel {
  min-width: 0;
  min-height: 520px;
}

.result-panel :deep(.n-card-header) {
  flex-wrap: wrap;
  gap: 8px;
}

.result-panel :deep(.n-card-header__main) {
  min-width: 0;
  flex: 1 1 220px;
}

.history-panel {
  min-width: 0;
}

.history-scroll {
  max-height: 612px;
}

.history-item {
  border-bottom: 1px solid var(--n-border-color);
  padding: 10px 0;
}

.history-item:last-child {
  border-bottom: 0;
}

.history-open {
  display: block;
  width: 100%;
  min-width: 0;
  border: 0;
  background: transparent;
  padding: 0;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.history-open:disabled {
  cursor: default;
}

.history-open:focus-visible {
  outline: 2px solid var(--n-primary-color);
  outline-offset: 3px;
}

.result-loading {
  padding: 16px 0;
}

.progress-section {
  border: 1px solid var(--n-border-color);
  border-radius: 6px;
  padding: 10px 12px;
}

.progress-row,
.repair-row {
  min-height: 28px;
}

.repair-row + .repair-row {
  border-top: 1px solid var(--n-border-color);
  padding-top: 8px;
}

.result-empty {
  padding: 120px 0;
}

.question-text {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.6;
}

.analysis-text {
  border-left: 3px solid var(--n-primary-color);
  padding-left: 12px;
  line-height: 1.75;
}

.result-stack-select {
  width: min(420px, 100%);
}

.evidence-grid {
  display: grid;
  grid-template-columns: 96px minmax(0, 1fr);
  gap: 8px 12px;
}

.evidence-grid > span:nth-child(odd) {
  opacity: 0.6;
}

@media (max-width: 1023px) {
  .workspace-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .result-panel {
    min-height: 440px;
  }

  .history-scroll {
    max-height: 420px;
  }
}

@media (max-width: 639px) {
  .chatbi-page {
    padding: 12px;
  }

  .result-empty {
    padding: 72px 0;
  }
}
</style>
