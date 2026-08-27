<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import { NButton, NSpace, NTag } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  downloadAuditExport,
  fetchAuditConsoleEvents,
  fetchAuditEventDetail,
  fetchAuditFeatures,
  fetchAuditStatistics,
  fetchAuditTrace,
  fetchMetadataChangeDiff,
  fetchMetadataChangelog,
  fetchMetadataChangelogStats
} from '@/service/api';
import type {
  AuditEventDetailView,
  AuditEventView,
  AuditFeatureView,
  AuditStatisticsView,
  AuditTraceStepView,
  AuditTraceView,
  MetadataChangeDiffView,
  MetadataChangelogChangeView,
  MetadataChangelogStatsView
} from '@/service/api';

const activeView = ref<'audit' | 'changelog'>('audit');
const auditLoading = ref(false);
const auditEvents = ref<AuditEventView[]>([]);
const auditFeatures = ref<AuditFeatureView | null>(null);
const auditStatistics = ref<AuditStatisticsView | null>(null);
const auditError = ref('');
const auditHasMore = ref(false);
const auditFilters = reactive({
  actorType: null as string | null,
  actorId: '',
  action: null as string | null,
  resourceType: null as string | null,
  decision: null as string | null,
  dateRange: null as [number, number] | null
});

const detailVisible = ref(false);
const detailLoading = ref(false);
const detailError = ref('');
const detail = ref<AuditEventDetailView | null>(null);
const traceVisible = ref(false);
const traceLoading = ref(false);
const traceError = ref('');
const trace = ref<AuditTraceView | null>(null);

const changelogLoading = ref(false);
const changelog = ref<MetadataChangelogChangeView[]>([]);
const changelogStats = ref<MetadataChangelogStatsView | null>(null);
const changelogError = ref('');
const changelogPage = ref(1);
const changelogTotal = ref(0);
const changelogFilters = reactive({ resourceType: null as string | null, action: null as string | null });
const diffVisible = ref(false);
const diffLoading = ref(false);
const diffError = ref('');
const diff = ref<MetadataChangeDiffView | null>(null);

const actorOptions = computed<SelectOption[]>(() => (auditFeatures.value?.actorTypes || []).map(value => ({ label: value, value })));
const actionOptions = computed<SelectOption[]>(() => (auditFeatures.value?.actions || []).map(value => ({ label: value, value })));
const resourceOptions = computed<SelectOption[]>(() => (auditFeatures.value?.resourceTypes || []).map(value => ({ label: value, value })));
const decisionOptions = computed<SelectOption[]>(() => (auditFeatures.value?.decisions || []).map(value => ({ label: value, value })));
const changeResourceOptions = computed<SelectOption[]>(() => Array.from(new Set(changelog.value.map(value => value.resourceType))).map(value => ({ label: value, value })));
const changeActionOptions = computed<SelectOption[]>(() => Array.from(new Set(changelog.value.map(value => value.action))).map(value => ({ label: value, value })));

function icon(name: string) {
  return () => h(SvgIcon, { icon: name, class: 'text-16px' });
}

function formatTime(value: string | null | undefined) {
  return value ? new Date(value).toLocaleString() : '-';
}

function decisionType(value: string) {
  return ['allow', 'success'].includes(value) ? 'success' : ['deny', 'failure'].includes(value) ? 'error' : 'warning';
}

function queryParams() {
  const params: Record<string, string> = {};
  if (auditFilters.actorType) params.actorType = auditFilters.actorType;
  if (auditFilters.actorId.trim()) params.actorId = auditFilters.actorId.trim();
  if (auditFilters.action) params.action = auditFilters.action;
  if (auditFilters.resourceType) params.resourceType = auditFilters.resourceType;
  if (auditFilters.decision) params.decision = auditFilters.decision;
  if (auditFilters.dateRange) {
    params.createdFrom = new Date(auditFilters.dateRange[0]).toISOString();
    params.createdTo = new Date(auditFilters.dateRange[1]).toISOString();
  }
  return params;
}

async function loadAudit(append = false) {
  auditLoading.value = true;
  auditError.value = '';
  const params = queryParams();
  if (append) params.beforeId = auditEvents.value.at(-1)?.id || '';
  const [listResult, statsResult] = await Promise.all([
    fetchAuditConsoleEvents({ ...params, limit: 100 }),
    append ? Promise.resolve(null) : fetchAuditStatistics(params)
  ]);
  if (listResult.error) auditError.value = '审计日志加载失败';
  else {
    auditEvents.value = append ? [...auditEvents.value, ...listResult.data] : listResult.data;
    auditHasMore.value = listResult.data.length === 100;
  }
  if (statsResult && !statsResult.error) auditStatistics.value = statsResult.data;
  auditLoading.value = false;
}

async function loadFeatures() {
  const result = await fetchAuditFeatures();
  if (!result.error) auditFeatures.value = result.data;
}

function resetAudit() {
  Object.assign(auditFilters, { actorType: null, actorId: '', action: null, resourceType: null, decision: null, dateRange: null });
  void loadAudit();
}

async function openDetail(row: AuditEventView) {
  detailVisible.value = true;
  detailLoading.value = true;
  detailError.value = '';
  detail.value = null;
  const result = await fetchAuditEventDetail(row.id);
  if (result.error) detailError.value = '审计详情加载失败';
  else detail.value = result.data;
  detailLoading.value = false;
}

async function openTrace(traceId: string | null) {
  if (!traceId) return;
  traceVisible.value = true;
  traceLoading.value = true;
  traceError.value = '';
  trace.value = null;
  const result = await fetchAuditTrace(traceId);
  if (result.error) traceError.value = 'Trace 加载失败';
  else trace.value = result.data;
  traceLoading.value = false;
}

async function exportAudit(format: 'csv' | 'json') {
  try {
    const result = await downloadAuditExport(queryParams(), format);
    const url = URL.createObjectURL(result.blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = result.fileName;
    anchor.click();
    URL.revokeObjectURL(url);
  } catch (error) {
    window.$message?.error(error instanceof Error ? error.message : '审计导出失败');
  }
}

async function loadChangelog() {
  changelogLoading.value = true;
  changelogError.value = '';
  const result = await fetchMetadataChangelog({
    resourceType: changelogFilters.resourceType || undefined,
    action: changelogFilters.action || undefined,
    page: changelogPage.value,
    size: 50
  });
  if (result.error) changelogError.value = '元数据变更加载失败';
  else {
    changelog.value = result.data.items;
    changelogTotal.value = result.data.total;
  }
  const statsResult = await fetchMetadataChangelogStats(30);
  if (!statsResult.error) changelogStats.value = statsResult.data;
  changelogLoading.value = false;
}

async function openDiff(row: MetadataChangelogChangeView) {
  diffVisible.value = true;
  diffLoading.value = true;
  diffError.value = '';
  diff.value = null;
  const result = await fetchMetadataChangeDiff(row.id);
  if (result.error) diffError.value = '变更 Diff 加载失败';
  else diff.value = result.data;
  diffLoading.value = false;
}

function pretty(value: unknown) {
  if (value === null || value === undefined) return '-';
  if (typeof value === 'string') {
    try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; }
  }
  return JSON.stringify(value, null, 2);
}

const auditColumns: DataTableColumns<AuditEventView> = [
  { title: '主体', key: 'actorType', width: 150, render: row => `${row.actorType}${row.actorId ? ` #${row.actorId}` : ''}` },
  { title: '操作', key: 'action', width: 145 },
  { title: '资源', key: 'resourceType', minWidth: 180, render: row => row.resourceType ? `${row.resourceType}${row.resourceId ? ` #${row.resourceId}` : ''}` : '-' },
  { title: '任务 / 运行', key: 'run', width: 170, render: row => `${row.taskId ? `#${row.taskId}` : '-'} / ${row.runId ? `#${row.runId}` : '-'}` },
  { title: '决策', key: 'decision', width: 105, render: row => h(NTag, { type: decisionType(row.decision), size: 'small' }, () => row.decision) },
  { title: '时间', key: 'createdAt', width: 170, render: row => formatTime(row.createdAt) },
  {
    title: '操作', key: 'actions', width: 120, fixed: 'right',
    render: row => h(NSpace, { size: 4 }, () => [
      h(NButton, { size: 'small', quaternary: true, circle: true, title: '查看详情', onClick: () => openDetail(row), renderIcon: icon('lucide:eye') }),
      row.traceId ? h(NButton, { size: 'small', quaternary: true, circle: true, title: '查看 Trace', onClick: () => openTrace(row.traceId), renderIcon: icon('lucide:route') }) : null
    ])
  }
];

const changelogColumns: DataTableColumns<MetadataChangelogChangeView> = [
  { title: '资源', key: 'resource', minWidth: 210, render: row => `${row.resourceType} #${row.resourceId || row.datasetId}` },
  { title: '动作', key: 'action', width: 120 },
  { title: '操作者', key: 'actorId', width: 110, render: row => `#${row.actorId}` },
  { title: '时间', key: 'createdAt', width: 175, render: row => formatTime(row.createdAt) },
  { title: '前后哈希', key: 'hash', width: 190, render: row => `${row.beforeHash?.slice(0, 10) || '-'} -> ${row.afterHash?.slice(0, 10) || '-'}` },
  { title: '操作', key: 'actions', width: 100, render: row => h(NButton, { size: 'small', quaternary: true, circle: true, title: '查看 Diff', onClick: () => openDiff(row), renderIcon: icon('lucide:git-compare') }) }
];

onMounted(async () => {
  await Promise.all([loadFeatures(), loadAudit()]);
});
</script>

<template>
  <section class="audit-console">
    <NTabs v-model:value="activeView" type="line" animated>
      <NTabPane name="audit" tab="审计日志">
        <div class="toolbar">
          <NSelect v-model:value="auditFilters.actorType" clearable placeholder="主体类型" :options="actorOptions" class="control-sm" />
          <NInput v-model:value="auditFilters.actorId" clearable placeholder="主体 ID" class="control-sm" />
          <NSelect v-model:value="auditFilters.action" clearable filterable placeholder="操作" :options="actionOptions" class="control-md" />
          <NSelect v-model:value="auditFilters.resourceType" clearable filterable placeholder="资源类型" :options="resourceOptions" class="control-md" />
          <NSelect v-model:value="auditFilters.decision" clearable placeholder="决策" :options="decisionOptions" class="control-sm" />
          <NDatePicker v-model:value="auditFilters.dateRange" type="daterange" clearable class="date-range" />
          <NButton type="primary" :loading="auditLoading" @click="loadAudit()"><template #icon><SvgIcon icon="lucide:search" /></template>查询</NButton>
          <NButton quaternary @click="resetAudit">重置</NButton>
          <NDropdown :options="[{ label: '导出 CSV', key: 'csv' }, { label: '导出 JSON', key: 'json' }]" @select="exportAudit">
            <NButton secondary><template #icon><SvgIcon icon="lucide:download" /></template>导出</NButton>
          </NDropdown>
        </div>
        <NAlert v-if="auditError" type="error" :bordered="false" class="mb-12px">{{ auditError }}</NAlert>
        <NGrid v-if="auditStatistics" :cols="4" :x-gap="10" :y-gap="10" responsive="screen" item-responsive class="stats-grid">
          <NGi span="24 s:12 m:6"><NStatistic label="总事件" :value="auditStatistics.total" /></NGi>
          <NGi span="24 s:12 m:6"><NStatistic label="成功" :value="auditStatistics.successCount" /></NGi>
          <NGi span="24 s:12 m:6"><NStatistic label="拒绝 / 失败" :value="auditStatistics.denyCount + auditStatistics.failureCount" /></NGi>
          <NGi span="24 s:12 m:6"><NStatistic label="主体 / Trace" :value="`${auditStatistics.distinctActors} / ${auditStatistics.distinctTraces}`" /></NGi>
        </NGrid>
        <NDataTable :columns="auditColumns" :data="auditEvents" :loading="auditLoading" :row-key="row => row.id" :scroll-x="1180" :max-height="560" />
        <div v-if="auditHasMore" class="load-more"><NButton secondary :loading="auditLoading" @click="loadAudit(true)"><template #icon><SvgIcon icon="lucide:chevrons-down" /></template>加载更多</NButton></div>
      </NTabPane>
      <NTabPane name="changelog" tab="元数据变更">
        <div class="toolbar">
          <NSelect v-model:value="changelogFilters.resourceType" clearable filterable placeholder="资源类型" :options="changeResourceOptions" class="control-md" />
          <NSelect v-model:value="changelogFilters.action" clearable placeholder="动作" :options="changeActionOptions" class="control-sm" />
          <NButton type="primary" :loading="changelogLoading" @click="loadChangelog"><template #icon><SvgIcon icon="lucide:search" /></template>查询</NButton>
          <NButton quaternary @click="Object.assign(changelogFilters, { resourceType: null, action: null }); changelogPage = 1; loadChangelog()">重置</NButton>
        </div>
        <NAlert v-if="changelogError" type="error" :bordered="false" class="mb-12px">{{ changelogError }}</NAlert>
        <NDescriptions v-if="changelogStats" :column="4" bordered size="small" class="mb-12px">
          <NDescriptionsItem label="近 30 天变更">{{ changelogStats.total }}</NDescriptionsItem>
          <NDescriptionsItem v-for="entry in changelogStats.breakdown.slice(0, 3)" :key="`${entry.resourceType}-${entry.action}`" :label="`${entry.resourceType} / ${entry.action}`">{{ entry.count }}</NDescriptionsItem>
        </NDescriptions>
        <NDataTable :columns="changelogColumns" :data="changelog" :loading="changelogLoading" :row-key="row => row.id" :scroll-x="980" :max-height="560" />
        <div class="pagination-row"><NPagination v-model:page="changelogPage" :page-size="50" :item-count="changelogTotal" show-quick-jumper @update:page="loadChangelog" /></div>
      </NTabPane>
    </NTabs>

    <NDrawer v-model:show="detailVisible" :width="560">
      <NDrawerContent title="审计事件详情" closable>
        <NSpin :show="detailLoading"><NAlert v-if="detailError" type="error">{{ detailError }}</NAlert><template v-else-if="detail">
          <NDescriptions :column="1" bordered size="small">
            <NDescriptionsItem label="事件 ID">{{ detail.id }}</NDescriptionsItem><NDescriptionsItem label="Trace ID">{{ detail.traceId || '-' }}</NDescriptionsItem>
            <NDescriptionsItem label="主体">{{ detail.actorType }} #{{ detail.actorId || '-' }}</NDescriptionsItem><NDescriptionsItem label="动作">{{ detail.action }}</NDescriptionsItem>
            <NDescriptionsItem label="资源">{{ detail.resourceType || '-' }} #{{ detail.resourceId || '-' }}</NDescriptionsItem><NDescriptionsItem label="决策">{{ detail.decision }} / {{ detail.decisionReason || '-' }}</NDescriptionsItem>
            <NDescriptionsItem label="时间">{{ formatTime(detail.createdAt) }}</NDescriptionsItem><NDescriptionsItem label="IP / User-Agent">{{ detail.ipAddress || '-' }} / {{ detail.userAgent || '-' }}</NDescriptionsItem>
          </NDescriptions>
          <NDivider />
          <h4>请求摘要</h4><pre class="json-block">{{ detail.requestSummary || '-' }}</pre><h4>结果摘要</h4><pre class="json-block">{{ detail.resultSummary || '-' }}</pre>
          <h4>数据范围</h4><pre class="json-block">{{ pretty(detail.dataScope) }}</pre><h4>扩展元数据</h4><pre class="json-block">{{ pretty(detail.metadata) }}</pre>
        </template></NSpin>
      </NDrawerContent>
    </NDrawer>

    <NDrawer v-model:show="traceVisible" :width="720">
      <NDrawerContent title="Trace / Span 详情" closable>
        <NSpin :show="traceLoading"><NAlert v-if="traceError" type="error">{{ traceError }}</NAlert><template v-else-if="trace">
          <NSpace align="center" class="mb-12px"><NTag type="info">{{ trace.traceId }}</NTag><span>{{ trace.totalSteps }} 个语义步骤</span></NSpace>
          <NTimeline>
            <NTimelineItem v-for="step in trace.steps" :key="step.eventId" :type="step.eventStatus === 'success' ? 'success' : step.eventStatus === 'failed' ? 'error' : 'warning'" :title="step.summary || step.eventType" :time="formatTime(step.occurredAt)">
              <div class="trace-line"><span>{{ step.agentName || step.model || step.toolName || '-' }}</span><NTag size="small">{{ step.eventType }}</NTag><NTag v-if="step.sensitiveLevel" size="small" :bordered="false">{{ step.sensitiveLevel }}</NTag><span v-if="step.executionTimeMs != null">{{ Math.round(step.executionTimeMs) }} ms</span></div>
              <div class="trace-meta">cursor {{ step.cursor }}<template v-if="step.conversationId"> · conversation #{{ step.conversationId }}</template><template v-if="step.runId"> · run #{{ step.runId }}</template><template v-if="step.stepId"> · step #{{ step.stepId }}</template></div>
              <div v-if="step.spanId" class="trace-meta">span {{ step.spanId }}<template v-if="step.parentSpanId"> · parent {{ step.parentSpanId }}</template></div>
            </NTimelineItem>
          </NTimeline>
        </template></NSpin>
      </NDrawerContent>
    </NDrawer>

    <NDrawer v-model:show="diffVisible" :width="720">
      <NDrawerContent title="元数据变更 Diff" closable>
        <NSpin :show="diffLoading"><NAlert v-if="diffError" type="error">{{ diffError }}</NAlert><template v-else-if="diff">
          <NDescriptions :column="1" bordered size="small"><NDescriptionsItem label="记录">{{ diff.id }}</NDescriptionsItem><NDescriptionsItem label="资源">{{ diff.resourceType }} #{{ diff.resourceId || diff.datasetId }}</NDescriptionsItem><NDescriptionsItem label="摘要">{{ diff.summary }}</NDescriptionsItem></NDescriptions>
          <NDivider /><div v-for="change in diff.changes" :key="change.field" class="diff-row"><strong>{{ change.field }}</strong><div><pre class="json-block">{{ pretty(change.oldValue) }}</pre><span>→</span><pre class="json-block">{{ pretty(change.newValue) }}</pre></div></div>
        </template></NSpin>
      </NDrawerContent>
    </NDrawer>
  </section>
</template>

<style scoped>
.audit-console { padding-top: 4px; }
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 12px; }
.control-sm { width: 130px; } .control-md { width: 170px; } .date-range { width: 250px; }
.stats-grid { margin-bottom: 14px; padding: 10px 0; border-top: 1px solid var(--n-divider-color); border-bottom: 1px solid var(--n-divider-color); }
.load-more, .pagination-row { display: flex; justify-content: center; margin-top: 12px; }
.json-block { max-height: 220px; margin: 5px 0 12px; padding: 8px; overflow: auto; border-radius: 5px; background: var(--n-color-modal); white-space: pre-wrap; word-break: break-word; font-size: 12px; }
.trace-line { display: flex; align-items: center; gap: 8px; } .trace-meta { color: var(--n-text-color-3); font-size: 11px; }
.diff-row { padding: 8px 0; border-bottom: 1px solid var(--n-divider-color); } .diff-row > div { display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; gap: 8px; }
@media (max-width: 700px) { .control-sm, .control-md, .date-range { width: 100%; } .diff-row > div { grid-template-columns: 1fr; } }
</style>
