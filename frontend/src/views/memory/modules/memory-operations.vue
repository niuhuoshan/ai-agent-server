<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import type { DataTableColumns } from 'naive-ui';
import {
  NAlert,
  NButton,
  NDataTable,
  NDescriptions,
  NDescriptionsItem,
  NEmpty,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NSelect,
  NSpace,
  NSpin,
  NSwitch,
  NTag
} from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  fetchPortalMemoryConfig,
  fetchPortalMemoryIndexStatus,
  fetchModels,
  testPortalMemoryEmbedding,
  testPortalMemoryRedisVector,
  testPortalMemorySearch,
  updatePortalMemoryConfig,
  verifyPortalMemoryIndex,
  type PortalMemoryConfig,
  type ModelView,
  type PortalMemoryIndexStatus,
  type PortalMemorySearchResult
} from '@/service/api';

type ProbeStatus = 'idle' | 'checking' | 'available' | 'unavailable';

interface ProviderProbe {
  status: ProbeStatus;
  message: string;
}

interface MemoryConfigForm {
  enabled: boolean;
  summaryEnabled: boolean;
  embeddingEnabled: boolean;
  embeddingModelId: string | null;
  embeddingDimension: number | null;
  searchKnnTopK: number;
  vectorWeight: number;
  consolidationThreshold: number;
  baseHalfLifeDays: number;
  summaryTtlDays: number;
}

const loading = ref(false);
const saving = ref(false);
const verifying = ref(false);
const searching = ref(false);
const searchExecuted = ref(false);
const config = ref<PortalMemoryConfig | null>(null);
const indexStatus = ref<PortalMemoryIndexStatus | null>(null);
const loadError = ref('');
const searchError = ref('');
const searchResults = ref<PortalMemorySearchResult[]>([]);
const embeddingModels = ref<ModelView[]>([]);
const form = reactive<MemoryConfigForm>({
  enabled: true,
  summaryEnabled: true,
  embeddingEnabled: false,
  embeddingModelId: null,
  embeddingDimension: null,
  searchKnnTopK: 5,
  vectorWeight: 0.7,
  consolidationThreshold: 0.82,
  baseHalfLifeDays: 7,
  summaryTtlDays: 30
});
const searchForm = reactive({ query: '', limit: 20 });
const redisProbe = reactive<ProviderProbe>({ status: 'idle', message: '' });
const embeddingProbe = reactive<ProviderProbe>({ status: 'idle', message: '' });

const modelOptions = computed(() =>
  embeddingModels.value.map(model => ({
    label: `${model.displayName} (${model.modelName})`,
    value: model.id
  }))
);
const configChanged = computed(() => {
  if (!config.value) return false;
  return (
    config.value.enabled !== form.enabled ||
    config.value.summary_enabled !== form.summaryEnabled ||
    config.value.embedding_enabled !== form.embeddingEnabled ||
    String(config.value.embedding_model_id || '') !== String(form.embeddingModelId || '') ||
    (config.value.embedding_dimension ?? null) !== form.embeddingDimension ||
    config.value.search_knn_top_k !== form.searchKnnTopK ||
    config.value.vector_weight !== form.vectorWeight ||
    config.value.consolidation_threshold !== form.consolidationThreshold ||
    config.value.base_half_life_days !== form.baseHalfLifeDays ||
    config.value.summary_ttl_days !== form.summaryTtlDays
  );
});
const indexLabel = computed(() => {
  if (!indexStatus.value) return '未检查';
  if (!indexStatus.value.available) return '不可用';
  if (indexStatus.value.rebuild_required) return '需要维护';
  return '正常';
});
const indexTagType = computed(() => {
  if (!indexStatus.value) return 'default';
  if (!indexStatus.value.available) return 'error';
  return indexStatus.value.rebuild_required ? 'warning' : 'success';
});

const searchColumns: DataTableColumns<PortalMemorySearchResult> = [
  { title: '记忆键', key: 'memory_key', width: 180, ellipsis: { tooltip: true } },
  {
    title: '类型',
    key: 'memory_type',
    width: 110,
    render: row => h(NTag, { size: 'small', bordered: false }, () => row.memory_type || '-')
  },
  { title: '内容', key: 'content', minWidth: 340, ellipsis: { tooltip: true } },
  {
    title: '得分',
    key: 'score',
    width: 90,
    render: row => (typeof row.score === 'number' ? row.score.toFixed(3) : '-')
  },
  { title: '更新时间', key: 'updated_at', width: 190, render: row => formatTime(row.updated_at) }
];

function operationError(error: unknown, fallback: string) {
  if (!error || typeof error !== 'object') return fallback;
  const response = Reflect.get(error, 'response');
  if (response && typeof response === 'object') {
    const data = Reflect.get(response, 'data');
    if (data && typeof data === 'object') {
      const message = Reflect.get(data, 'message') || Reflect.get(data, 'msg');
      if (typeof message === 'string' && message.trim()) return message;
    }
  }
  const message = Reflect.get(error, 'message');
  return typeof message === 'string' && message.trim() ? message : fallback;
}

function formatTime(value?: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN') : '-';
}

function probeLabel(status: ProbeStatus) {
  if (status === 'checking') return '检测中';
  if (status === 'available') return '可用';
  if (status === 'unavailable') return '不可用';
  return '未检测';
}

function probeType(status: ProbeStatus) {
  if (status === 'checking') return 'info';
  if (status === 'available') return 'success';
  if (status === 'unavailable') return 'error';
  return 'default';
}

async function loadOperations() {
  loading.value = true;
  loadError.value = '';
  const [configResult, indexResult, modelResult] = await Promise.all([
    fetchPortalMemoryConfig(),
    fetchPortalMemoryIndexStatus(),
    fetchModels('embedding')
  ]);
  if (configResult.error) {
    loadError.value = operationError(configResult.error, '记忆配置加载失败');
  } else {
    config.value = configResult.data;
    Object.assign(form, {
      enabled: configResult.data.enabled,
      summaryEnabled: configResult.data.summary_enabled,
      embeddingEnabled: configResult.data.embedding_enabled,
      embeddingModelId: configResult.data.embedding_model_id
        ? String(configResult.data.embedding_model_id)
        : null,
      embeddingDimension: configResult.data.embedding_dimension ?? null,
      searchKnnTopK: configResult.data.search_knn_top_k,
      vectorWeight: configResult.data.vector_weight,
      consolidationThreshold: configResult.data.consolidation_threshold,
      baseHalfLifeDays: configResult.data.base_half_life_days,
      summaryTtlDays: configResult.data.summary_ttl_days
    });
  }
  if (indexResult.error) {
    const message = operationError(indexResult.error, '索引状态加载失败');
    loadError.value = loadError.value ? `${loadError.value}；${message}` : message;
  } else {
    indexStatus.value = indexResult.data;
  }
  if (!modelResult.error) embeddingModels.value = modelResult.data;
  loading.value = false;
}

async function saveConfiguration() {
  if (form.embeddingEnabled && (!form.embeddingModelId || !form.embeddingDimension)) {
    window.$message?.warning('启用向量记忆时必须选择 Embedding 模型并填写维度');
    return;
  }
  saving.value = true;
  const result = await updatePortalMemoryConfig({
    enabled: form.enabled,
    summary_enabled: form.summaryEnabled,
    embedding_enabled: form.embeddingEnabled,
    embedding_model_id: form.embeddingEnabled ? form.embeddingModelId : null,
    embedding_dimension: form.embeddingEnabled ? form.embeddingDimension : null,
    search_knn_top_k: form.searchKnnTopK,
    vector_weight: form.vectorWeight,
    consolidation_threshold: form.consolidationThreshold,
    base_half_life_days: form.baseHalfLifeDays,
    summary_ttl_days: form.summaryTtlDays,
    expected_revision: config.value?.revision
  });
  saving.value = false;
  if (result.error) return;
  config.value = result.data;
  await loadOperations();
  window.$message?.success('记忆运行配置已保存');
}

async function runIndexVerification() {
  verifying.value = true;
  const result = await verifyPortalMemoryIndex();
  verifying.value = false;
  if (result.error) return;
  window.$message?.success(result.data.message || '向量索引重建完成');
  const refreshed = await fetchPortalMemoryIndexStatus();
  if (!refreshed.error) indexStatus.value = refreshed.data;
}

async function runSearchTest() {
  const query = searchForm.query.trim();
  if (!query) {
    window.$message?.warning('请输入检索内容');
    return;
  }
  searching.value = true;
  searchExecuted.value = true;
  searchError.value = '';
  const result = await testPortalMemorySearch(query, searchForm.limit);
  searching.value = false;
  if (result.error) {
    searchResults.value = [];
    searchError.value = operationError(result.error, '记忆检索测试失败');
    return;
  }
  searchResults.value = result.data;
}

async function runProviderProbe(kind: 'redis' | 'embedding') {
  const probe = kind === 'redis' ? redisProbe : embeddingProbe;
  probe.status = 'checking';
  probe.message = '';
  const result = kind === 'redis' ? await testPortalMemoryRedisVector() : await testPortalMemoryEmbedding();
  if (result.error) {
    probe.status = 'unavailable';
    probe.message = operationError(
      result.error,
      kind === 'redis' ? 'PostgreSQL pgvector 不可用' : 'Embedding Provider 不可用'
    );
    return;
  }
  probe.status = 'available';
  probe.message =
    result.data.message ||
    (kind === 'redis'
      ? `${result.data.provider || 'pgvector'} 可用，已索引 ${result.data.embedded_document_count ?? 0} 条`
      : `Embedding 可用，维度 ${result.data.dimensions ?? '-'}`);
}

onMounted(loadOperations);
</script>

<template>
  <NSpin :show="loading">
    <div class="flex flex-col gap-20px">
      <section>
        <div class="mb-12px flex flex-wrap items-center justify-between gap-8px">
          <h3 class="m-0 text-16px font-600">检索配置与索引</h3>
          <NSpace>
            <NButton secondary :loading="loading" @click="loadOperations">
              <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
              刷新
            </NButton>
            <NButton type="primary" secondary :loading="verifying" @click="runIndexVerification">
              <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
              重建向量
            </NButton>
          </NSpace>
        </div>
        <NAlert v-if="loadError" type="error" class="mb-12px">{{ loadError }}</NAlert>
        <NDescriptions bordered label-placement="left" :column="2">
          <NDescriptionsItem label="检索引擎">{{ indexStatus?.provider || config?.provider || '-' }}</NDescriptionsItem>
          <NDescriptionsItem label="索引状态">
            <NTag size="small" :type="indexTagType" :bordered="false">{{ indexLabel }}</NTag>
          </NDescriptionsItem>
          <NDescriptionsItem label="记忆文档">{{ indexStatus?.document_count ?? '-' }}</NDescriptionsItem>
          <NDescriptionsItem label="向量文档">
            {{ indexStatus?.vector?.embedded_document_count ?? 0 }}
          </NDescriptionsItem>
          <NDescriptionsItem label="检查时间">{{ formatTime(indexStatus?.checked_at) }}</NDescriptionsItem>
          <NDescriptionsItem label="作用域">{{ indexStatus?.owner_scoped ? '按用户隔离' : '-' }}</NDescriptionsItem>
          <NDescriptionsItem label="维护方式">
            {{ indexStatus?.automatically_maintained ? '写入时自动维护' : '手工维护' }}
          </NDescriptionsItem>
          <NDescriptionsItem label="整理模式">{{ config?.consolidation_mode || '-' }}</NDescriptionsItem>
          <NDescriptionsItem label="配置版本">{{ config?.revision ?? '默认配置' }}</NDescriptionsItem>
        </NDescriptions>
        <NAlert v-if="indexStatus?.message" type="info" class="mt-12px">{{ indexStatus.message }}</NAlert>
        <NForm label-placement="top" class="mt-16px grid grid-cols-1 gap-x-16px md:grid-cols-2 xl:grid-cols-4">
          <NFormItem label="记忆服务">
            <NSwitch v-model:value="form.enabled" />
          </NFormItem>
          <NFormItem label="摘要生成">
            <NSwitch v-model:value="form.summaryEnabled" />
          </NFormItem>
          <NFormItem label="向量记忆">
            <NSwitch v-model:value="form.embeddingEnabled" />
          </NFormItem>
          <NFormItem label="Embedding 模型">
            <NSelect
              v-model:value="form.embeddingModelId"
              :options="modelOptions"
              :disabled="!form.embeddingEnabled"
              clearable
              placeholder="选择已启用模型"
            />
          </NFormItem>
          <NFormItem label="向量维度">
            <NInputNumber
              v-model:value="form.embeddingDimension"
              :disabled="!form.embeddingEnabled"
              :min="1"
              :max="8192"
              :precision="0"
              class="w-full"
            />
          </NFormItem>
          <NFormItem label="默认召回数量">
            <NInputNumber
              v-model:value="form.searchKnnTopK"
              data-testid="default-search-limit"
              :min="1"
              :max="200"
              :precision="0"
              class="w-full"
            />
          </NFormItem>
          <NFormItem label="向量权重">
            <NInputNumber
              v-model:value="form.vectorWeight"
              :min="0"
              :max="1"
              :step="0.05"
              :precision="2"
              class="w-full"
            />
          </NFormItem>
          <NFormItem label="合并阈值">
            <NInputNumber
              v-model:value="form.consolidationThreshold"
              :min="0"
              :max="1"
              :step="0.01"
              :precision="2"
              class="w-full"
            />
          </NFormItem>
          <NFormItem label="基础半衰期（天）">
            <NInputNumber
              v-model:value="form.baseHalfLifeDays"
              :min="0.01"
              :max="3650"
              :step="1"
              :precision="2"
              class="w-full"
            />
          </NFormItem>
          <NFormItem label="摘要保留（天）">
            <NInputNumber
              v-model:value="form.summaryTtlDays"
              :min="1"
              :max="3650"
              :precision="0"
              class="w-full"
            />
          </NFormItem>
          <NFormItem label="保存">
            <NButton type="primary" :loading="saving" :disabled="!configChanged" @click="saveConfiguration">
              <template #icon><SvgIcon icon="lucide:save" /></template>
              保存配置
            </NButton>
          </NFormItem>
        </NForm>
      </section>

      <section class="border-t border-gray-200 pt-20px dark:border-gray-700">
        <h3 class="mb-12px mt-0 text-16px font-600">所有者范围检索测试</h3>
        <NForm inline label-placement="left">
          <NFormItem label="检索内容">
            <NInput
              v-model:value="searchForm.query"
              data-testid="memory-search-query"
              clearable
              placeholder="输入记忆内容"
              style="width: min(420px, 72vw)"
              @keyup.enter="runSearchTest"
            />
          </NFormItem>
          <NFormItem label="数量">
            <NInputNumber
              v-model:value="searchForm.limit"
              data-testid="memory-search-limit"
              :min="1"
              :max="200"
              :precision="0"
              style="width: 120px"
            />
          </NFormItem>
          <NFormItem>
            <NButton type="primary" :loading="searching" @click="runSearchTest">
              <template #icon><SvgIcon icon="lucide:search" /></template>
              检索
            </NButton>
          </NFormItem>
        </NForm>
        <NAlert v-if="searchError" type="error" class="mb-12px">{{ searchError }}</NAlert>
        <NDataTable
          v-if="searchResults.length"
          :columns="searchColumns"
          :data="searchResults"
          :loading="searching"
          :single-line="false"
          :max-height="320"
        />
        <NEmpty v-else-if="searchExecuted && !searching && !searchError" description="没有匹配的记忆" />
      </section>

      <section class="border-t border-gray-200 pt-20px dark:border-gray-700">
        <h3 class="mb-12px mt-0 text-16px font-600">Provider 诊断</h3>
        <div class="grid grid-cols-1 gap-16px lg:grid-cols-2">
          <div class="border border-gray-200 p-16px dark:border-gray-700">
            <div class="flex items-center justify-between gap-12px">
              <div class="font-600">PostgreSQL pgvector</div>
              <NTag size="small" :type="probeType(redisProbe.status)" :bordered="false">
                {{ probeLabel(redisProbe.status) }}
              </NTag>
            </div>
            <NAlert
              v-if="redisProbe.message"
              :type="redisProbe.status === 'available' ? 'success' : 'warning'"
              class="my-12px"
            >
              {{ redisProbe.message }}
            </NAlert>
            <NButton secondary :loading="redisProbe.status === 'checking'" @click="runProviderProbe('redis')">
              <template #icon><SvgIcon icon="lucide:activity" /></template>
              检测向量存储
            </NButton>
          </div>
          <div class="border border-gray-200 p-16px dark:border-gray-700">
            <div class="flex items-center justify-between gap-12px">
              <div class="font-600">Embedding</div>
              <NTag size="small" :type="probeType(embeddingProbe.status)" :bordered="false">
                {{ probeLabel(embeddingProbe.status) }}
              </NTag>
            </div>
            <NAlert
              v-if="embeddingProbe.message"
              :type="embeddingProbe.status === 'available' ? 'success' : 'warning'"
              class="my-12px"
            >
              {{ embeddingProbe.message }}
            </NAlert>
            <NButton secondary :loading="embeddingProbe.status === 'checking'" @click="runProviderProbe('embedding')">
              <template #icon><SvgIcon icon="lucide:scan-search" /></template>
              检测 Embedding
            </NButton>
          </div>
        </div>
      </section>
    </div>
  </NSpin>
</template>
