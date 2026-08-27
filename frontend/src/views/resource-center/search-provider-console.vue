<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import { NButton, NTag } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  executeWebSearch,
  fetchSearchProviders,
  type SearchProviderView,
  type WebSearchHit,
  type WebSearchResult
} from '@/service/api/search';
import {
  searchCircuitText,
  searchCircuitType,
  searchFailureRate,
  searchProviderLabel
} from './web-search';

const loading = ref(false);
const searching = ref(false);
const errorMessage = ref<string | null>(null);
const providers = ref<SearchProviderView[]>([]);
const providerId = ref<string | null>(null);
const query = ref('');
const maxResults = ref(5);
const result = ref<WebSearchResult | null>(null);

const selectedProvider = computed(() => providers.value.find(item => item.connectorId === providerId.value) || null);
const providerOptions = computed<SelectOption[]>(() => providers.value.map(item => ({
  label: searchProviderLabel(item),
  value: item.connectorId,
  disabled: !item.manageable || item.circuitState === 'open'
})));

const resultColumns: DataTableColumns<WebSearchHit> = [
  { title: '#', key: 'rank', width: 54 },
  {
    title: '来源',
    key: 'title',
    minWidth: 260,
    render: row => h('div', { class: 'search-hit' }, [
      h('a', {
        href: row.url,
        target: '_blank',
        rel: 'noopener noreferrer',
        class: 'search-hit__title'
      }, row.title),
      h('span', { class: 'search-hit__host' }, row.source)
    ])
  },
  { title: '摘要', key: 'snippet', minWidth: 360, ellipsis: { tooltip: true } },
  { title: '时间', key: 'publishedAt', width: 150, render: row => row.publishedAt || '-' }
];

async function loadProviders() {
  loading.value = true;
  errorMessage.value = null;
  const response = await fetchSearchProviders();
  loading.value = false;
  if (response.error) {
    errorMessage.value = response.error instanceof Error ? response.error.message : '搜索 Provider 加载失败';
    return;
  }
  providers.value = response.data || [];
  if (!providerId.value || !providers.value.some(item => item.connectorId === providerId.value)) {
    providerId.value = providers.value.find(item => item.manageable && item.circuitState !== 'open')?.connectorId || null;
  }
}

async function runSearch() {
  const provider = selectedProvider.value;
  if (!provider || !query.value.trim()) {
    window.$message?.warning('请选择可维护的搜索 Provider 并填写测试关键词');
    return;
  }
  searching.value = true;
  errorMessage.value = null;
  result.value = null;
  const response = await executeWebSearch({
    connectorId: provider.connectorId,
    query: query.value.trim(),
    maxResults: Math.min(maxResults.value, provider.maxResults)
  });
  searching.value = false;
  if (response.error) {
    errorMessage.value = response.error instanceof Error ? response.error.message : '联网搜索测试失败';
    await loadProviders();
    return;
  }
  result.value = response.data || null;
  window.$message?.success(`真实检索完成，返回 ${response.data?.resultCount || 0} 条引用`);
  await loadProviders();
}

onMounted(loadProviders);
defineExpose({ refresh: loadProviders });
</script>

<template>
  <section class="search-console">
    <header class="search-console__header">
      <div>
        <h3>联网搜索运行验证</h3>
        <p>调用当前部署的真实 Provider；查询正文不进入运维审计，仅保存摘要。</p>
      </div>
      <NButton quaternary circle :loading="loading" title="刷新 Provider 状态" @click="loadProviders">
        <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
      </NButton>
    </header>

    <NAlert v-if="errorMessage" type="error" closable @close="errorMessage = null">
      {{ errorMessage }}
    </NAlert>
    <NEmpty v-if="!loading && providers.length === 0" description="尚未配置已启用的搜索 Provider" />

    <template v-else>
      <div class="search-console__form">
        <NSelect
          v-model:value="providerId"
          :options="providerOptions"
          :loading="loading"
          placeholder="选择搜索 Provider"
          filterable
        />
        <NInput v-model:value="query" maxlength="2000" clearable placeholder="输入真实联网检索关键词" @keyup.enter="runSearch" />
        <NInputNumber v-model:value="maxResults" :min="1" :max="selectedProvider?.maxResults || 20" />
        <NButton type="primary" :loading="searching" :disabled="!selectedProvider || !query.trim()" @click="runSearch">
          <template #icon><SvgIcon icon="lucide:search" /></template>
          执行检索
        </NButton>
      </div>

      <div v-if="selectedProvider" class="search-console__status">
        <NTag :type="searchCircuitType(selectedProvider.circuitState)" size="small">
          {{ searchCircuitText(selectedProvider.circuitState) }}
        </NTag>
        <span>{{ selectedProvider.engine.toUpperCase() }}</span>
        <span>请求 {{ selectedProvider.totalRequests }}</span>
        <span>失败率 {{ searchFailureRate(selectedProvider) }}%</span>
        <span>限流 {{ selectedProvider.rateLimitPerMinute }}/分钟</span>
        <span v-if="selectedProvider.lastLatencyMs !== null && selectedProvider.lastLatencyMs !== undefined">
          最近 {{ selectedProvider.lastLatencyMs }} ms
        </span>
        <span v-if="selectedProvider.lastError" class="search-console__error">{{ selectedProvider.lastError }}</span>
      </div>

      <div v-if="result" class="search-console__result">
        <div class="search-console__result-meta">
          <strong>{{ result.connectorName }}</strong>
          <span>{{ result.resultCount }} 条引用</span>
          <span>{{ result.latencyMs }} ms</span>
        </div>
        <NDataTable
          :columns="resultColumns"
          :data="result.results"
          :row-key="row => `${row.rank}-${row.url}`"
          :scroll-x="900"
          :max-height="420"
        />
      </div>
    </template>
  </section>
</template>

<style scoped>
.search-console {
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid rgb(var(--border-color));
}

.search-console__header,
.search-console__result-meta,
.search-console__status {
  display: flex;
  align-items: center;
  gap: 12px;
}

.search-console__header {
  justify-content: space-between;
  margin-bottom: 14px;
}

.search-console__header h3 {
  margin: 0;
  font-size: 16px;
}

.search-console__header p {
  margin: 4px 0 0;
  color: var(--n-text-color-3);
  font-size: 12px;
}

.search-console__form {
  display: grid;
  grid-template-columns: minmax(220px, 0.8fr) minmax(280px, 1.5fr) 100px auto;
  gap: 10px;
  align-items: center;
}

.search-console__status {
  flex-wrap: wrap;
  min-height: 36px;
  margin-top: 10px;
  color: var(--n-text-color-2);
  font-size: 12px;
}

.search-console__error {
  color: var(--n-color-error);
}

.search-console__result {
  margin-top: 14px;
}

.search-console__result-meta {
  margin-bottom: 8px;
  font-size: 12px;
}

:deep(.search-hit) {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

:deep(.search-hit__title) {
  overflow: hidden;
  color: var(--n-color-target);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:deep(.search-hit__host) {
  color: var(--n-text-color-3);
  font-size: 11px;
}

@media (max-width: 900px) {
  .search-console__form {
    grid-template-columns: 1fr;
  }
}
</style>
