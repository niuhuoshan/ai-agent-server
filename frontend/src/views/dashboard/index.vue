<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue';
import { NAlert, NButton, NCard, NDataTable, NDescriptions, NDescriptionsItem, NEmpty, NGrid, NGi, NProgress, NSelect, NSpace, NSpin, NTag } from 'naive-ui';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { useEcharts } from '@/hooks/common/echarts';
import { useAuthStore } from '@/store/modules/auth';
import {
  fetchDashboardActivities,
  fetchDashboardAgentStats,
  fetchDashboardApiTrends,
  fetchDashboardApiTrends24h,
  fetchDashboardOnlineUsers,
  fetchDashboardStats,
  fetchTokenAgents,
  type DashboardActivities,
  type DashboardAgentPerformance,
  type DashboardAgentStats,
  type DashboardApiCall,
  type DashboardApiHourTrend,
  type DashboardApiTrend,
  type DashboardOnlineUser,
  type DashboardOnlineUsers,
  type DashboardPeriod,
  type DashboardStats,
  type TokenAgentRow
} from '@/service/api';

const authStore = useAuthStore();
const period = ref<DashboardPeriod>('today');
const loading = ref(false);
const stats = ref<DashboardStats | null>(null);
const agentStats = ref<DashboardAgentStats | null>(null);
const activities = ref<DashboardActivities | null>(null);
const agentTokens = ref<TokenAgentRow[]>([]);
const onlineUsers = ref<DashboardOnlineUsers | null>(null);
const apiTrends = ref<DashboardApiTrend[]>([]);
const apiTrends24h = ref<DashboardApiHourTrend[]>([]);
const loadError = ref('');

const periodOptions: SelectOption[] = [
  { label: '今日', value: 'today' },
  { label: '最近 7 天', value: 'week' },
  { label: '最近 30 天', value: 'month' }
];

const isAdmin = computed(() => authStore.userInfo.roles.some(role => ['platform_admin', 'superadmin'].includes(role)));
const scopeLabel = computed(() => (stats.value?.scope === 'enterprise' ? '企业视角' : '个人视角'));
const execution = computed(() => stats.value?.execution_runs);
const health = computed(() => agentStats.value?.health_stats);
const apiSummary = computed(() => stats.value?.api_calls);

const { domRef: dailyApiChartRef, updateOptions: updateDailyApiChart } = useEcharts(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['调用', '成功', '错误'], top: 0 },
  grid: { left: 16, right: 16, bottom: 24, top: 40, containLabel: true },
  xAxis: { type: 'category', boundaryGap: false, data: [] as string[] },
  yAxis: { type: 'value', minInterval: 1 },
  series: [
    { type: 'line', name: '调用', smooth: true, data: [] as number[], color: '#2080f0' },
    { type: 'line', name: '成功', smooth: true, data: [] as number[], color: '#18a058' },
    { type: 'line', name: '错误', smooth: true, data: [] as number[], color: '#d03050' }
  ]
}));

const { domRef: hourlyApiChartRef, updateOptions: updateHourlyApiChart } = useEcharts(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['调用', '成功'], top: 0 },
  grid: { left: 16, right: 16, bottom: 24, top: 40, containLabel: true },
  xAxis: { type: 'category', boundaryGap: false, data: [] as string[] },
  yAxis: { type: 'value', minInterval: 1 },
  series: [
    { type: 'line', name: '调用', smooth: true, areaStyle: {}, data: [] as number[], color: '#2080f0' },
    { type: 'line', name: '成功', smooth: true, data: [] as number[], color: '#18a058' }
  ]
}));

const performanceColumns: DataTableColumns<DashboardAgentPerformance> = [
  { title: '智能体', key: 'name', ellipsis: { tooltip: true } },
  { title: '调用次数', key: 'calls' },
  { title: '成功率', key: 'success_rate', render: row => `${row.success_rate}%` },
  { title: '平均延迟', key: 'avg_latency', render: row => `${row.avg_latency} ms` },
  { title: '版本', key: 'version', render: row => (row.version ? `v${row.version}` : '-') }
];

const runColumns: DataTableColumns<NonNullable<DashboardActivities['recent_runs']>[number]> = [
  { title: '任务', key: 'task_title', ellipsis: { tooltip: true } },
  { title: 'Agent', key: 'agent_name', ellipsis: { tooltip: true } },
  { title: '状态', key: 'status', render: row => statusTag(row.status) },
  { title: '时间', key: 'created_at', render: row => formatTime(row.created_at) }
];

const tokenColumns: DataTableColumns<TokenAgentRow> = [
  { title: '智能体', key: 'name' },
  { title: '调用', key: 'calls' },
  { title: '输入', key: 'prompt_tokens', render: row => formatTokens(row.prompt_tokens) },
  { title: '输出', key: 'completion_tokens', render: row => formatTokens(row.completion_tokens) },
  { title: '合计', key: 'total_tokens', render: row => formatTokens(row.total_tokens) }
];

const userColumns: DataTableColumns<NonNullable<DashboardActivities['recent_users']>[number]> = [
  { title: '用户', key: 'real_name', render: row => row.real_name || row.user_name || '-' },
  { title: '最近活跃', key: 'last_active', render: row => formatTime(row.last_active) }
];

const callColumns: DataTableColumns<DashboardApiCall> = [
  { title: '接口', key: 'endpoint', ellipsis: { tooltip: true } },
  { title: '方法', key: 'method', width: 80 },
  { title: '用户', key: 'user_name', render: row => row.user_name || '-' },
  { title: '状态码', key: 'status_code', width: 90, render: row => apiStatusTag(row.status_code, row.outcome) },
  { title: '耗时', key: 'process_time_ms', width: 100, render: row => `${formatNumber(row.process_time_ms)} ms` },
  { title: '时间', key: 'created_at', width: 150, render: row => formatTime(row.created_at) }
];

const onlineColumns: DataTableColumns<DashboardOnlineUser> = [
  { title: '用户', key: 'real_name', render: row => row.real_name || row.user_name || '-' },
  { title: '账号', key: 'user_name', render: row => row.user_name || '-' },
  { title: '角色', key: 'role', ellipsis: { tooltip: true }, render: row => row.role || '-' },
  { title: '客户端', key: 'device_type', render: row => row.device_type || row.client_key || '-' },
  { title: '登录时间', key: 'login_time', render: row => formatTime(row.login_time) }
];

function statusTag(status?: string) {
  const type = status === 'succeeded' || status === 'completed' ? 'success' : status === 'failed' ? 'error' : status === 'running' ? 'warning' : 'default';
  return hTag(type, statusLabel(status));
}

function hTag(type: 'success' | 'error' | 'warning' | 'default', text: string) {
  return h(NTag, { type, size: 'small' }, { default: () => text });
}

function apiStatusTag(status?: number | null, outcome?: string | null) {
  const type = status && status >= 400 ? 'error' : outcome === 'succeeded' ? 'success' : outcome === 'accepted' ? 'warning' : 'default';
  return hTag(type, status ? String(status) : outcome || '-');
}

function statusLabel(status?: string) {
  return ({ succeeded: '成功', completed: '完成', failed: '失败', running: '运行中', cancelled: '已取消', waiting: '等待中' } as Record<string, string>)[status || ''] || status || '-';
}

function formatTime(value?: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '-';
}

function numberValue(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

function formatNumber(value: unknown) {
  return numberValue(value).toLocaleString('zh-CN');
}

function formatTokens(value: unknown) {
  const amount = numberValue(value);
  if (amount >= 1_000_000) return `${(amount / 1_000_000).toFixed(1)}M`;
  if (amount >= 1_000) return `${(amount / 1_000).toFixed(1)}K`;
  return formatNumber(amount);
}

function metricText(value: unknown, unit = '') {
  return `${formatNumber(value)}${unit}`;
}

function healthType(rate?: number) {
  if (!rate) return 'default';
  return rate >= 95 ? 'success' : rate >= 80 ? 'warning' : 'error';
}

async function refresh() {
  loading.value = true;
  loadError.value = '';
  const [statsResult, agentResult, activityResult, tokenResult, onlineResult, trendsResult, trends24hResult] = await Promise.all([
    fetchDashboardStats(period.value, isAdmin.value),
    fetchDashboardAgentStats(period.value),
    fetchDashboardActivities(10),
    fetchTokenAgents(period.value),
    fetchDashboardOnlineUsers(),
    fetchDashboardApiTrends(period.value === 'today' ? 1 : period.value === 'month' ? 30 : 7),
    fetchDashboardApiTrends24h()
  ]);
  if (!statsResult.error) stats.value = statsResult.data;
  if (!agentResult.error) agentStats.value = agentResult.data;
  if (!activityResult.error) activities.value = activityResult.data;
  if (!tokenResult.error) agentTokens.value = tokenResult.data || [];
  if (!onlineResult.error) onlineUsers.value = onlineResult.data;
  else loadError.value ||= '在线会话统计加载失败';
  if (!trendsResult.error) apiTrends.value = trendsResult.data || [];
  else loadError.value ||= 'API 日趋势加载失败';
  if (!trends24hResult.error) apiTrends24h.value = trends24hResult.data || [];
  else loadError.value ||= 'API 小时趋势加载失败';
  updateCharts();
  loading.value = false;
}

function updateCharts() {
  updateDailyApiChart(options => {
    options.xAxis.data = apiTrends.value.map(item => item.date.slice(5));
    options.series[0].data = apiTrends.value.map(item => item.total_calls);
    options.series[1].data = apiTrends.value.map(item => item.success_calls);
    options.series[2].data = apiTrends.value.map(item => item.error_calls);
    return options;
  });
  updateHourlyApiChart(options => {
    options.xAxis.data = apiTrends24h.value.map(item => item.hour);
    options.series[0].data = apiTrends24h.value.map(item => item.total_calls);
    options.series[1].data = apiTrends24h.value.map(item => item.success_calls);
    return options;
  });
}

onMounted(refresh);
</script>

<template>
  <NSpin :show="loading">
    <NSpace vertical :size="16" class="pb-16px">
      <div class="flex flex-wrap items-end justify-between gap-12px">
        <div>
          <div class="text-12px op-60">{{ scopeLabel }}</div>
          <h1 class="m-0 mt-4px text-24px font-600">{{ isAdmin ? '系统概览' : '我的概览' }}</h1>
          <div class="mt-4px text-12px op-60">运行健康、智能体表现与资源消耗</div>
        </div>
        <NSpace align="center">
          <NSelect v-model:value="period" :options="periodOptions" style="width: 130px" @update:value="refresh" />
          <NButton secondary :loading="loading" @click="refresh"><template #icon><SvgIcon icon="lucide:refresh-cw" /></template>刷新</NButton>
        </NSpace>
      </div>

      <NAlert v-if="loadError" type="error">{{ loadError }}</NAlert>
      <NAlert v-if="onlineUsers?.status === 'unavailable'" type="warning">{{ onlineUsers.reason || '在线会话数据暂不可用' }}</NAlert>

      <NGrid :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
        <NGi span="24 s:12 m:6"><NCard :bordered="false" size="small" title="执行次数"><div class="text-26px font-600">{{ formatNumber(execution?.total) }}</div><div class="text-12px op-60">{{ execution?.source || '任务运行' }}</div></NCard></NGi>
        <NGi span="24 s:12 m:6"><NCard :bordered="false" size="small" title="执行成功率"><div class="flex items-end gap-8px"><div class="text-26px font-600">{{ execution?.success_rate || 0 }}%</div><NTag size="small" :type="healthType(execution?.success_rate)">{{ execution?.success || 0 }} 次成功</NTag></div><div class="text-12px op-60">失败 {{ execution?.errors || 0 }} · 取消 {{ execution?.cancelled || 0 }}</div></NCard></NGi>
        <NGi span="24 s:12 m:6"><NCard :bordered="false" size="small" title="Token 消耗"><div class="text-26px font-600">{{ formatTokens(stats?.total_tokens) }}</div><div class="text-12px op-60">输入 {{ formatTokens(stats?.prompt_tokens) }} · 输出 {{ formatTokens(stats?.completion_tokens) }}</div></NCard></NGi>
        <NGi span="24 s:12 m:6"><NCard :bordered="false" size="small" title="平均执行延迟"><div class="text-26px font-600">{{ metricText(execution?.avg_latency_ms, ' ms') }}</div><div class="text-12px op-60">统计周期：{{ period === 'today' ? '今日' : period === 'week' ? '最近 7 天' : '最近 30 天' }}</div></NCard></NGi>
      </NGrid>

      <NGrid :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
        <NGi span="24 s:12 m:6"><NCard :bordered="false" size="small" title="开放 API 调用"><div class="text-26px font-600">{{ formatNumber(apiSummary?.total) }}</div><div class="text-12px op-60">成功 {{ formatNumber(apiSummary?.success) }} · 错误 {{ formatNumber(apiSummary?.errors) }}</div></NCard></NGi>
        <NGi span="24 s:12 m:6"><NCard :bordered="false" size="small" title="API 成功率"><div class="text-26px font-600">{{ stats?.success_rate || 0 }}%</div><div class="text-12px op-60">错误率 {{ stats?.error_rate || 0 }}%</div></NCard></NGi>
        <NGi span="24 s:12 m:6"><NCard :bordered="false" size="small" title="API 平均响应"><div class="text-26px font-600">{{ metricText(stats?.avg_response_time, ' ms') }}</div><div class="text-12px op-60">基于开放 API 审计事实</div></NCard></NGi>
        <NGi span="24 s:12 m:6"><NCard :bordered="false" size="small" title="在线会话"><div class="text-26px font-600">{{ formatNumber(onlineUsers?.count) }}</div><div class="text-12px op-60">{{ formatNumber(onlineUsers?.user_count) }} 位用户</div></NCard></NGi>
      </NGrid>

      <NGrid :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
        <NGi span="24 m:12"><NCard :bordered="false" title="API 日趋势"><div ref="dailyApiChartRef" class="h-280px w-full" /></NCard></NGi>
        <NGi span="24 m:12"><NCard :bordered="false" title="最近 24 小时 API 趋势"><div ref="hourlyApiChartRef" class="h-280px w-full" /></NCard></NGi>
      </NGrid>

      <NGrid :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
        <NGi span="24 s:24 m:10">
          <NCard :bordered="false" title="智能体健康">
            <NSpace vertical :size="12">
              <div class="flex items-center justify-between"><span>步骤成功率</span><NTag :type="healthType(health?.success_rate)">{{ health?.success_rate || 0 }}%</NTag></div>
              <NProgress type="line" :percentage="health?.success_rate || 0" :status="health?.success_rate && health.success_rate < 80 ? 'error' : 'success'" :show-indicator="false" />
              <NDescriptions :column="2" bordered size="small">
                <NDescriptionsItem label="执行步骤">{{ formatNumber(health?.total_steps) }}</NDescriptionsItem>
                <NDescriptionsItem label="工具调用">{{ formatNumber(health?.total_tool_calls) }}</NDescriptionsItem>
                <NDescriptionsItem label="平均延迟">{{ formatNumber(health?.avg_latency) }} ms</NDescriptionsItem>
                <NDescriptionsItem label="活跃 Agent">{{ agentTokens.length }}</NDescriptionsItem>
              </NDescriptions>
            </NSpace>
          </NCard>
        </NGi>
        <NGi span="24 s:24 m:14">
          <NCard :bordered="false" title="智能体表现">
            <NDataTable v-if="agentStats?.agent_performance?.length" :columns="performanceColumns" :data="agentStats.agent_performance" :bordered="false" :single-line="false" />
            <NEmpty v-else description="暂无智能体执行数据" />
          </NCard>
        </NGi>
      </NGrid>

      <NGrid :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
        <NGi span="24 s:24 m:10">
          <NCard :bordered="false" title="工具使用排行">
            <NSpace v-if="agentStats?.tool_usage?.length" vertical :size="8">
              <div v-for="tool in agentStats.tool_usage.slice(0, 8)" :key="String(tool.tool_id || tool.name)" class="flex items-center gap-8px">
                <div class="w-100px truncate text-13px">{{ tool.name }}</div>
                <NProgress class="flex-1" type="line" :percentage="Math.min(100, (tool.value / Math.max(...(agentStats?.tool_usage || []).map(item => item.value), 1)) * 100)" :show-indicator="false" />
                <div class="w-52px text-right text-12px op-60">{{ formatNumber(tool.value) }}</div>
              </div>
            </NSpace>
            <NEmpty v-else description="暂无工具调用" />
          </NCard>
        </NGi>
        <NGi span="24 s:24 m:14">
          <NCard :bordered="false" title="Token 智能体分布">
            <NDataTable v-if="agentTokens.length" :columns="tokenColumns" :data="agentTokens" :bordered="false" :single-line="false" />
            <NEmpty v-else description="暂无 Token 分布" />
          </NCard>
        </NGi>
      </NGrid>

      <NGrid :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
        <NGi span="24 s:24 m:14"><NCard :bordered="false" title="最近任务运行"><NDataTable v-if="activities?.recent_runs?.length" :columns="runColumns" :data="activities.recent_runs" :bordered="false" :single-line="false" /><NEmpty v-else description="暂无最近运行" /></NCard></NGi>
        <NGi span="24 s:24 m:10"><NCard :bordered="false" title="最近活跃用户"><NDataTable v-if="activities?.recent_users?.length" :columns="userColumns" :data="activities.recent_users" :bordered="false" :single-line="false" /><NEmpty v-else description="暂无用户活动" /></NCard></NGi>
      </NGrid>

      <NGrid :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
        <NGi span="24 m:14"><NCard :bordered="false" title="最近 API 调用"><NDataTable v-if="activities?.recent_calls?.length" :columns="callColumns" :data="activities.recent_calls" :bordered="false" :single-line="false" :scroll-x="760" /><NEmpty v-else description="暂无 API 调用" /></NCard></NGi>
        <NGi span="24 m:10"><NCard :bordered="false" title="当前在线用户"><NDataTable v-if="isAdmin && onlineUsers?.users?.length" :columns="onlineColumns" :data="onlineUsers.users" :bordered="false" :single-line="false" :scroll-x="620" /><NEmpty v-else :description="isAdmin ? '暂无在线用户' : `当前 ${onlineUsers?.count || 0} 个在线会话，详细名单仅管理员可见`" /></NCard></NGi>
      </NGrid>

      <NCard v-if="activities?.recent_run_errors?.length" :bordered="false" title="最近运行错误">
        <NSpace vertical :size="8"><div v-for="error in activities.recent_run_errors" :key="`${error.run_id}-${error.time}`" class="border-b border-color/10 pb-8px last:border-0"><div class="flex items-center gap-8px"><NTag type="error" size="small">失败</NTag><span class="font-500">{{ error.agent || 'Agent' }} / {{ error.step || '运行步骤' }}</span><span class="ml-auto text-12px op-60">{{ formatTime(error.time) }}</span></div><div class="mt-4px text-12px op-60">{{ error.message || '未提供错误信息' }}</div></div></NSpace>
      </NCard>

      <NCard v-if="activities?.recent_errors?.length" :bordered="false" title="最近 API 错误"><NDataTable :columns="callColumns" :data="activities.recent_errors" :bordered="false" :single-line="false" :scroll-x="760" /></NCard>
    </NSpace>
  </NSpin>
</template>
