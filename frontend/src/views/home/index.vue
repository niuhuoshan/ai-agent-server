<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue';
import type { DataTableColumns } from 'naive-ui';
import { NButton } from 'naive-ui';
import { useRouter } from 'vue-router';
import { useAppStore } from '@/store/modules/app';
import { fetchPortalWorkbenchHome, type PortalWorkbenchHome, type PortalWorkbenchItem } from '@/service/api';

const appStore = useAppStore();
const router = useRouter();

const gap = computed(() => (appStore.isMobile ? 0 : 16));
const loading = ref(false);
const workbench = ref<PortalWorkbenchHome | null>(null);
const recentColumns: DataTableColumns<PortalWorkbenchItem> = [
  { title: '任务', key: 'title' },
  { title: '状态', key: 'status' },
  { title: '时间', key: 'occurred_at', render: row => formatTime(row.occurred_at) },
  {
    title: '',
    key: 'action',
    width: 48,
    render: row => h(NButton, { text: true, type: 'primary', onClick: () => openWorkbenchItem(row) }, { default: () => '查看' })
  }
];

async function loadWorkbench() {
  loading.value = true;
  const result = await fetchPortalWorkbenchHome();
  if (!result.error) workbench.value = result.data;
  loading.value = false;
}

function formatTime(value?: string | null) {
  if (!value) return '-';
  return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function itemType(item: PortalWorkbenchItem) {
  if (item.severity === 'critical' || item.status === 'failed') return 'error';
  if (item.status === 'running') return 'warning';
  return 'info';
}

function targetValue(item: PortalWorkbenchItem, key: string) {
  const value = item.target?.[key];
  return value === null || value === undefined ? undefined : String(value);
}

async function openWorkbenchItem(item: PortalWorkbenchItem) {
  const action = item.action || '';
  if (action === 'open_scenario') {
    await router.push({ path: '/scenario-templates', query: { template_id: targetValue(item, 'template_id') } });
    return;
  }
  if (action === 'open_agent') {
    await router.push({ path: '/workspace', query: { agent_id: targetValue(item, 'agent_id') } });
    return;
  }
  if (action === 'open_conversation') {
    await router.push({ path: '/workspace', query: { conversation_id: targetValue(item, 'conversation_id') } });
    return;
  }
  if (action === 'open_report' || action === 'open_digest') {
    await router.push({ path: '/saved-reports', query: { report_id: targetValue(item, 'report_id') || targetValue(item, 'resource_id') } });
    return;
  }
  if (action === 'open_task' || action === 'open_task_log') {
    await router.push({
      path: '/task-center',
      query: {
        task_id: targetValue(item, 'task_id') || targetValue(item, 'resource_id'),
        ...(action === 'open_task_log' ? { panel: 'runs' } : {})
      }
    });
    return;
  }
  if (action === 'open_notification') {
    await router.push({ path: '/personal-center', query: { tab: 'notifications' } });
  }
}

async function openResource(tab: string) {
  const destinations: Record<string, { path: string; query?: Record<string, string> }> = {
    memory: { path: '/memory' },
    tokens: { path: '/token-stats' },
    data: { path: '/data-portal' },
    skills: { path: '/resource-center', query: { tab: 'skills' } },
    mcp: { path: '/resource-center', query: { tab: 'connectors', type: 'mcp' } },
    tasks: { path: '/task-center' },
    notifications: { path: '/personal-center', query: { tab: 'notifications' } }
  };
  const target = destinations[tab];
  if (target) await router.push(target);
}

onMounted(loadWorkbench);
</script>

<template>
  <NSpin :show="loading">
    <NSpace vertical :size="16">
      <div class="flex flex-wrap items-end justify-between gap-12px">
        <div>
          <div class="text-12px op-60">个人工作台</div>
          <h1 class="m-0 mt-4px text-24px font-600">{{ workbench?.mode === 'new_user' ? '从今天开始' : '继续处理工作' }}</h1>
        </div>
        <div class="text-12px op-60">{{ workbench ? `更新于 ${formatTime(workbench.generated_at)}` : '正在加载真实业务数据' }}</div>
      </div>

      <NGrid :x-gap="gap" :y-gap="16" responsive="screen" item-responsive>
        <NGi v-for="resource in (workbench?.personal_resources || [])" :key="resource.key" span="24 s:12 m:6">
          <NCard :bordered="false" size="small" class="card-wrapper resource-card" hoverable @click="openResource(resource.tab)">
            <div class="flex items-center justify-between">
              <div>
                <div class="text-12px op-60">{{ resource.label }}</div>
                <div class="mt-8px text-26px font-600">{{ resource.value }}</div>
              </div>
              <NTag size="small" :type="resource.status === 'ok' ? 'success' : 'default'">{{ resource.unit }}</NTag>
            </div>
          </NCard>
        </NGi>
      </NGrid>

      <NGrid :x-gap="gap" :y-gap="16" responsive="screen" item-responsive>
        <NGi span="24 s:24 m:14">
          <NCard :bordered="false" class="card-wrapper" title="需要关注">
            <template #header-extra><NButton text size="small" @click="loadWorkbench"><SvgIcon icon="lucide:refresh-cw" />刷新</NButton></template>
            <NSpace v-if="workbench?.attention?.length" vertical :size="8">
              <button v-for="item in workbench.attention" :key="item.id" type="button" class="work-item" @click="openWorkbenchItem(item)">
                <div class="flex items-start gap-8px">
                  <NTag size="small" :type="itemType(item)">{{ item.severity || '提示' }}</NTag>
                  <div class="min-w-0 flex-1">
                    <div class="font-500">{{ item.title }}</div>
                    <div class="mt-2px text-12px op-60">{{ item.subtitle || '待处理事项' }} · {{ formatTime(item.occurred_at) }}</div>
                  </div>
                </div>
              </button>
            </NSpace>
            <NEmpty v-else description="暂无待处理事项" />
          </NCard>
        </NGi>
        <NGi span="24 s:24 m:10">
          <NCard :bordered="false" class="card-wrapper" title="常用 Agent">
            <NSpace v-if="workbench?.favorite_agents?.length" vertical :size="8">
              <div v-for="agent in workbench.favorite_agents" :key="agent.id" class="flex items-center justify-between border-b border-color/10 pb-8px last:border-0">
                <div class="min-w-0">
                  <div class="truncate font-500">{{ agent.title }}</div>
                  <div class="text-12px op-60">{{ agent.subtitle || '可用智能体' }}</div>
                </div>
                <NButton text type="primary" size="small" title="打开 Agent" @click="openWorkbenchItem(agent)"><SvgIcon icon="lucide:arrow-up-right" /></NButton>
              </div>
            </NSpace>
            <NEmpty v-else description="暂无可用 Agent" />
          </NCard>
        </NGi>
      </NGrid>

      <NCard v-if="workbench?.recommended_scenarios?.length" :bordered="false" class="card-wrapper" title="推荐场景">
        <template #header-extra>
          <NButton text type="primary" size="small" @click="router.push('/scenario-templates')">全部场景</NButton>
        </template>
        <NGrid cols="1 s:2 l:3" responsive="screen" :x-gap="12" :y-gap="12">
          <NGi v-for="scenario in workbench.recommended_scenarios" :key="scenario.id">
            <button type="button" class="scenario-item" @click="openWorkbenchItem(scenario)">
              <div class="min-w-0 flex-1">
                <div class="truncate font-500">{{ scenario.title }}</div>
                <div class="mt-4px line-clamp-2 text-12px op-60">{{ scenario.subtitle || '可交付企业场景' }}</div>
              </div>
              <SvgIcon icon="lucide:arrow-up-right" class="shrink-0 text-16px text-primary" />
            </button>
          </NGi>
        </NGrid>
      </NCard>

      <NGrid :x-gap="gap" :y-gap="16" responsive="screen" item-responsive>
        <NGi span="24 s:24 m:14">
          <NCard :bordered="false" class="card-wrapper" title="最近任务">
            <NDataTable v-if="workbench?.recent_tasks?.length" :columns="recentColumns" :data="workbench.recent_tasks" :bordered="false" :single-line="false" />
            <NEmpty v-else description="暂无任务记录" />
          </NCard>
        </NGi>
        <NGi span="24 s:24 m:10">
          <NCard :bordered="false" class="card-wrapper" title="数据门户">
            <div class="flex items-center justify-between">
              <div>
                <div class="text-12px op-60">最近报表与数据分析</div>
                <div class="mt-8px text-24px font-600">{{ workbench?.latest_results?.length || 0 }}</div>
              </div>
              <NButton type="primary" secondary @click="$router.push('/saved-reports')"><SvgIcon icon="lucide:bar-chart-3" />打开报表</NButton>
            </div>
            <div v-if="workbench?.source_status" class="mt-16px flex flex-wrap gap-6px">
              <NTag v-for="(status, key) in workbench.source_status" :key="key" size="small" :type="status === 'ok' ? 'success' : 'default'">{{ key }}: {{ status }}</NTag>
            </div>
          </NCard>
        </NGi>
      </NGrid>
    </NSpace>
  </NSpin>
</template>

<style scoped>
.work-item {
  display: block;
  width: 100%;
  border-bottom: 1px solid var(--n-border-color);
  border-top: 0;
  border-right: 0;
  border-left: 0;
  background: transparent;
  padding: 8px 0;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.resource-card {
  min-height: 94px;
  cursor: pointer;
}

.scenario-item {
  display: flex;
  width: 100%;
  min-height: 78px;
  align-items: flex-start;
  gap: 10px;
  border: 1px solid var(--n-border-color);
  border-radius: 6px;
  background: transparent;
  padding: 12px;
  color: inherit;
  text-align: left;
  cursor: pointer;
}
</style>
