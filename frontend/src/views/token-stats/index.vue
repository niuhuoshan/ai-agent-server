<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue';
import { NAlert, NButton, NCard, NDataTable, NDatePicker, NEmpty, NGrid, NGi, NSelect, NSpace, NSpin, NTag } from 'naive-ui';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { useEcharts } from '@/hooks/common/echarts';
import { useAuthStore } from '@/store/modules/auth';
import {
  fetchTokenAgents,
  fetchTokenRecords,
  fetchTokenTrends,
  fetchTokenUsers,
  type DashboardPeriod,
  type TokenAgentRow,
  type TokenRecord,
  type TokenTrend,
  type TokenUserRow
} from '@/service/api';

const authStore = useAuthStore();
const isAdmin = computed(() => authStore.userInfo.roles.some(role => ['platform_admin', 'superadmin'].includes(role)));
const period = ref<DashboardPeriod>('week');
const loading = ref(false);
const trendData = ref<TokenTrend[]>([]);
const agentData = ref<TokenAgentRow[]>([]);
const userData = ref<TokenUserRow[]>([]);
const records = ref<TokenRecord[]>([]);
const recordsTotal = ref(0);
const recordsPage = ref(1);
const recordsSize = ref(20);
const dateRange = ref<[number, number] | null>(null);
const coverage = ref('');
const unavailableSources = ref<string[]>([]);
const loadError = ref('');
const recordsError = ref('');

const periodOptions: SelectOption[] = [
  { label: '今日', value: 'today' },
  { label: '最近 7 天', value: 'week' },
  { label: '最近 30 天', value: 'month' }
];

const { domRef, updateOptions } = useEcharts(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['输入 Token', '输出 Token', '交互次数'], top: 0 },
  grid: { left: 16, right: 16, bottom: 24, top: 40, containLabel: true },
  xAxis: { type: 'category', boundaryGap: false, data: [] as string[] },
  yAxis: [
    { type: 'value', name: 'Token' },
    { type: 'value', name: '次数' }
  ],
  series: [
    { type: 'line', name: '输入 Token', smooth: true, areaStyle: {}, data: [] as number[], color: '#2080f0' },
    { type: 'line', name: '输出 Token', smooth: true, areaStyle: {}, data: [] as number[], color: '#d03050' },
    { type: 'line', name: '交互次数', smooth: true, yAxisIndex: 1, data: [] as number[], color: '#18a058' }
  ]
}));

const summary = computed(() => {
  const rows = agentData.value.length ? agentData.value : trendData.value;
  const prompt = rows.reduce((sum, row) => sum + Number(row.prompt_tokens || 0), 0);
  const completion = rows.reduce((sum, row) => sum + Number(row.completion_tokens || 0), 0);
  const rawTotal = rows.reduce((sum, row) => sum + Number(row.total_tokens || 0), 0);
  const calls = rows.reduce((sum, row) => sum + Number(row.calls || 0), 0);
  const total = prompt + completion || rawTotal;
  return { prompt, completion, total, calls, average: calls ? Math.round(total / calls) : 0 };
});

const agentColumns: DataTableColumns<TokenAgentRow> = [
  { title: '智能体', key: 'name', ellipsis: { tooltip: true } },
  { title: '交互', key: 'calls' },
  { title: '输入', key: 'prompt_tokens', render: row => formatTokens(row.prompt_tokens) },
  { title: '输出', key: 'completion_tokens', render: row => formatTokens(row.completion_tokens) },
  { title: '合计', key: 'total_tokens', render: row => formatTokens(row.total_tokens) },
  { title: '均次', key: 'average', render: row => formatTokens(row.calls ? Math.round(row.total_tokens / row.calls) : 0) }
];

const userColumns: DataTableColumns<TokenUserRow> = [
  { title: '账号', key: 'username' },
  { title: '姓名', key: 'real_name', render: row => row.real_name || '-' },
  { title: '交互', key: 'calls' },
  { title: '输入', key: 'prompt_tokens', render: row => formatTokens(row.prompt_tokens) },
  { title: '输出', key: 'completion_tokens', render: row => formatTokens(row.completion_tokens) },
  { title: '合计', key: 'total_tokens', render: row => formatTokens(row.total_tokens) },
  { title: '占比', key: 'ratio', render: row => `${row.ratio || 0}%` }
];

const recordColumns: DataTableColumns<TokenRecord> = [
  { title: '时间', key: 'created_at', render: row => formatTime(row.created_at) },
  { title: '用户', key: 'real_name', render: row => row.real_name || row.username || '-' },
  { title: '智能体', key: 'agent_name', render: row => row.agent_name || '-' },
  { title: '模型', key: 'model_name', render: row => row.model_name || '-' },
  { title: '输入', key: 'prompt_tokens', render: row => formatTokens(row.prompt_tokens) },
  { title: '输出', key: 'completion_tokens', render: row => formatTokens(row.completion_tokens) },
  { title: '合计', key: 'total_tokens', render: row => formatTokens(row.total_tokens) },
  { title: '状态', key: 'status', render: row => renderStatus(row.status) }
];

function renderStatus(value?: string) {
  const type = value === 'success' || value === 'succeeded' || value === 'completed' ? 'success' : value === 'failed' || value === 'error' ? 'error' : 'default';
  return h(NTag, { size: 'small', type }, { default: () => statusLabel(value) });
}

function statusLabel(value?: string) {
  return ({ success: '成功', succeeded: '成功', completed: '完成', failed: '失败', error: '错误', running: '运行中' } as Record<string, string>)[value || ''] || value || '-';
}

function formatTime(value?: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN') : '-';
}

function formatTokens(value: number | null | undefined) {
  const amount = Number(value || 0);
  if (amount >= 1_000_000) return `${(amount / 1_000_000).toFixed(1)}M`;
  if (amount >= 1_000) return `${(amount / 1_000).toFixed(1)}K`;
  return amount.toLocaleString('zh-CN');
}

function periodDays() {
  return period.value === 'today' ? 1 : period.value === 'month' ? 30 : 7;
}

function dateParams() {
  if (!dateRange.value) return { days: periodDays() };
  const [from, to] = dateRange.value;
  const toDate = new Date(to);
  const fromDate = new Date(from);
  const asDate = (date: Date) => {
    const year = date.getFullYear();
    const month = `${date.getMonth() + 1}`.padStart(2, '0');
    const day = `${date.getDate()}`.padStart(2, '0');
    return `${year}-${month}-${day}`;
  };
  return { startDate: asDate(fromDate), endDate: asDate(toDate) };
}

async function loadRecords() {
  recordsError.value = '';
  try {
    const result = await fetchTokenRecords({ ...dateParams(), page: recordsPage.value, size: recordsSize.value });
    if (result.error) {
      records.value = [];
      recordsTotal.value = 0;
      coverage.value = '';
      unavailableSources.value = [];
      recordsError.value = requestError(result.error, 'Token 明细加载失败');
      return;
    }
    records.value = result.data?.items || [];
    recordsTotal.value = result.data?.total || 0;
    coverage.value = result.data?.coverage || '';
    unavailableSources.value = result.data?.unavailable_sources || [];
  } catch (error) {
    records.value = [];
    recordsTotal.value = 0;
    coverage.value = '';
    unavailableSources.value = [];
    recordsError.value = requestError(error, 'Token 明细加载失败');
  }
}

async function refresh() {
  loading.value = true;
  recordsPage.value = 1;
  loadError.value = '';
  try {
    const [trendResult, agentResult, userResult] = await Promise.all([
      fetchTokenTrends(dateParams()),
      fetchTokenAgents(period.value),
      isAdmin.value ? fetchTokenUsers(period.value) : null
    ]);
    if (trendResult.error) {
      trendData.value = [];
      loadError.value = requestError(trendResult.error, 'Token 趋势加载失败');
    } else {
      trendData.value = trendResult.data || [];
    }
    if (agentResult.error) {
      agentData.value = [];
      loadError.value ||= requestError(agentResult.error, '智能体 Token 统计加载失败');
    } else {
      agentData.value = agentResult.data || [];
    }
    if (!userResult) {
      userData.value = [];
    } else if (userResult.error) {
      userData.value = [];
      loadError.value ||= requestError(userResult.error, '用户 Token 统计加载失败');
    } else {
      userData.value = userResult.data || [];
    }
    await loadRecords();
  } catch (error) {
    trendData.value = [];
    agentData.value = [];
    userData.value = [];
    loadError.value = requestError(error, 'Token 统计加载失败');
  } finally {
    loading.value = false;
  }
}

function requestError(error: unknown, fallback: string) {
  const response = (error as { response?: { data?: { message?: string; msg?: string } } } | null)?.response;
  return response?.data?.message || response?.data?.msg || (error instanceof Error && error.message.trim() ? error.message : fallback);
}

function updateChart() {
  updateOptions(options => {
    options.xAxis.data = trendData.value.map(item => item.date.slice(5));
    options.series[0].data = trendData.value.map(item => item.prompt_tokens || 0);
    options.series[1].data = trendData.value.map(item => item.completion_tokens || 0);
    options.series[2].data = trendData.value.map(item => item.calls || 0);
    return options;
  });
}

watch(trendData, updateChart, { deep: true });
watch(dateRange, refresh);
onMounted(refresh);
</script>

<template>
  <NSpin :show="loading">
    <NSpace vertical :size="16" class="pb-16px">
      <div class="flex flex-wrap items-end justify-between gap-12px">
        <div>
          <div class="text-12px op-60">资源审计</div>
          <h1 class="m-0 mt-4px text-24px font-600">Token 统计分析</h1>
          <div class="mt-4px text-12px op-60">统一统计对话消息与任务运行的模型输入、输出和调用频次</div>
        </div>
        <NSpace align="center">
          <NSelect v-model:value="period" :options="periodOptions" style="width: 130px" @update:value="refresh" />
          <NDatePicker v-model:value="dateRange" type="daterange" clearable format="yyyy-MM-dd" :is-date-disabled="(timestamp: number) => timestamp > Date.now()" style="width: 250px" />
          <NButton secondary :loading="loading" @click="refresh"><template #icon><SvgIcon icon="lucide:refresh-cw" /></template>刷新</NButton>
        </NSpace>
      </div>

      <NAlert v-if="loadError" type="error" :show-icon="true">{{ loadError }}</NAlert>
      <NAlert v-if="recordsError" type="error" :show-icon="true">{{ recordsError }}</NAlert>
      <NAlert v-if="unavailableSources.length" type="info" :show-icon="false">当前统计覆盖：{{ coverage || '对话消息与任务运行' }}；未覆盖 {{ unavailableSources.join('、') }}。</NAlert>

      <NGrid :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
        <NGi span="24 s:12 m:6"><NCard :bordered="false" size="small" title="Token 消耗"><div class="text-26px font-600">{{ formatTokens(summary.total) }}</div><div class="text-12px op-60">输入 {{ formatTokens(summary.prompt) }} · 输出 {{ formatTokens(summary.completion) }}</div></NCard></NGi>
        <NGi span="24 s:12 m:6"><NCard :bordered="false" size="small" title="调用记录"><div class="text-26px font-600">{{ summary.calls.toLocaleString('zh-CN') }}</div><div class="text-12px op-60">对话回复与任务运行记录</div></NCard></NGi>
        <NGi span="24 s:12 m:6"><NCard :bordered="false" size="small" title="平均单次"><div class="text-26px font-600">{{ formatTokens(summary.average) }}</div><div class="text-12px op-60">Token / 记录</div></NCard></NGi>
        <NGi span="24 s:12 m:6"><NCard :bordered="false" size="small" title="活跃智能体"><div class="text-26px font-600">{{ agentData.length }}</div><div class="text-12px op-60">当前统计周期内有记录</div></NCard></NGi>
      </NGrid>

      <NCard :bordered="false" title="输入 / 输出趋势">
        <div ref="domRef" class="h-320px w-full" />
        <NEmpty v-if="!trendData.length" description="暂无趋势数据" />
      </NCard>

      <NGrid :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
        <NGi span="24 s:24 m:12"><NCard :bordered="false" title="智能体消耗排行"><NDataTable v-if="agentData.length" :columns="agentColumns" :data="agentData" :bordered="false" :single-line="false" /><NEmpty v-else description="暂无智能体分布" /></NCard></NGi>
        <NGi span="24 s:24 m:12"><NCard v-if="isAdmin" :bordered="false" title="用户消耗排行"><NDataTable v-if="userData.length" :columns="userColumns" :data="userData" :bordered="false" :single-line="false" /><NEmpty v-else description="暂无用户统计" /></NCard><NCard v-else :bordered="false" title="用户消耗排行"><NEmpty description="仅平台管理员可查看企业用户排行" /></NCard></NGi>
      </NGrid>

      <NCard :bordered="false" title="Token 明细">
        <NDataTable :columns="recordColumns" :data="records" :loading="loading" :bordered="false" :single-line="false" :pagination="{ page: recordsPage, pageSize: recordsSize, itemCount: recordsTotal, showSizePicker: true, pageSizes: [20, 50, 100], onChange: (page: number) => { recordsPage = page; loadRecords(); }, onUpdatePageSize: (size: number) => { recordsSize = size; recordsPage = 1; loadRecords(); } }" />
        <NEmpty v-if="!records.length && !loading && !recordsError" description="暂无 Token 明细" />
      </NCard>
    </NSpace>
  </NSpin>
</template>
