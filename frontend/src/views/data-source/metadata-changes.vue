<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import dayjs from 'dayjs';
import { fetchDatasetMetadataChanges, type MetadataChangeView } from '@/service/api';

const props = defineProps<{ datasetId: string }>();

const loading = ref(false);
const error = ref('');
const changes = ref<MetadataChangeView[]>([]);
const resourceType = ref('all');
const action = ref('all');
const keyword = ref('');

const resourceOptions = [
  { label: '全部资源', value: 'all' },
  { label: '业务指标', value: 'metric' },
  { label: '表关系', value: 'relationship' },
  { label: '行策略', value: 'row_policy' }
];
const actionOptions = [
  { label: '全部动作', value: 'all' },
  { label: '创建', value: 'create' },
  { label: '更新', value: 'update' },
  { label: '归档', value: 'archive' }
];
const visibleChanges = computed(() => {
  const needle = keyword.value.trim().toLocaleLowerCase();
  return changes.value.filter(change => {
    if (resourceType.value !== 'all' && change.resourceType !== resourceType.value) return false;
    if (action.value !== 'all' && change.action !== action.value) return false;
    if (!needle) return true;
    return [
      change.resourceType,
      change.action,
      change.resourceId || '',
      change.actorId,
      change.beforeJson || '',
      change.afterJson || ''
    ]
      .join(' ')
      .toLocaleLowerCase()
      .includes(needle);
  });
});

function requestError(result: unknown, fallback: string) {
  const value = result as {
    error?: { response?: { status?: number; data?: { msg?: string; message?: string } }; message?: string };
  };
  if (!value.error) return '';
  if (value.error.response?.status === 403) return '没有查看此数据集变更记录的权限';
  return value.error.response?.data?.msg || value.error.response?.data?.message || value.error.message || fallback;
}

async function load() {
  if (!props.datasetId) return;
  loading.value = true;
  error.value = '';
  try {
    const result = await fetchDatasetMetadataChanges(props.datasetId, 200);
    const failure = requestError(result, '变更记录加载失败');
    if (failure) error.value = failure;
    else if (result.data) changes.value = result.data;
    else error.value = '变更记录返回为空';
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '变更记录加载失败';
  } finally {
    loading.value = false;
  }
}

function resourceLabel(value: string) {
  return ({ metric: '业务指标', relationship: '表关系', row_policy: '行策略' } as Record<string, string>)[value] || value;
}

function actionLabel(value: string) {
  return ({ create: '创建', update: '更新', archive: '归档' } as Record<string, string>)[value] || value;
}

function actionType(value: string) {
  if (value === 'create') return 'success';
  if (value === 'archive') return 'warning';
  return 'info';
}

function formattedJson(value: string | null) {
  if (!value) return '无';
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}

function shortHash(value: string | null) {
  return value ? value.slice(0, 12) : '-';
}

watch(() => props.datasetId, () => void load(), { immediate: true });
</script>

<template>
  <section class="metadata-changes">
    <div class="changes-toolbar">
      <NInput v-model:value="keyword" clearable placeholder="搜索资源、动作、操作者或变更内容" />
      <NSelect v-model:value="resourceType" :options="resourceOptions" />
      <NSelect v-model:value="action" :options="actionOptions" />
      <NButton secondary :loading="loading" @click="load"><template #icon><SvgIcon icon="lucide:refresh-cw" /></template>刷新</NButton>
    </div>
    <NAlert v-if="error" type="error" :bordered="false">{{ error }}</NAlert>
    <div v-if="visibleChanges.length" class="change-list">
      <details v-for="change in visibleChanges" :key="change.id">
        <summary>
          <span class="change-mark"><SvgIcon icon="lucide:history" /></span>
          <span class="change-copy"><strong>{{ resourceLabel(change.resourceType) }} #{{ change.resourceId || change.datasetId }}</strong><small>操作者 {{ change.actorId }} · {{ dayjs(change.createdAt).format('YYYY-MM-DD HH:mm:ss') }}</small></span>
          <NTag size="small" :type="actionType(change.action)" :bordered="false">{{ actionLabel(change.action) }}</NTag>
          <SvgIcon icon="lucide:chevron-down" />
        </summary>
        <div class="change-detail">
          <div><span>变更前 · {{ shortHash(change.beforeHash) }}</span><pre>{{ formattedJson(change.beforeJson) }}</pre></div>
          <div><span>变更后 · {{ shortHash(change.afterHash) }}</span><pre>{{ formattedJson(change.afterJson) }}</pre></div>
        </div>
      </details>
    </div>
    <NEmpty v-else-if="!loading" description="暂无符合条件的元数据变更记录" />
  </section>
</template>

<style scoped lang="scss">
.metadata-changes { display: grid; gap: 12px; }
.changes-toolbar { display: grid; grid-template-columns: minmax(240px, 1fr) 140px 120px auto; gap: 8px; }
.change-list { display: grid; gap: 7px; }
.change-list details { overflow: hidden; border: 1px solid var(--n-border-color); border-radius: 7px; background: var(--n-color); }
.change-list summary { display: grid; grid-template-columns: auto minmax(0, 1fr) auto auto; align-items: center; gap: 10px; padding: 11px; cursor: pointer; list-style: none; }
.change-list summary::-webkit-details-marker { display: none; }
.change-list summary > svg:last-child { color: var(--n-text-color-3); transition: transform .16s ease; }
.change-list details[open] summary > svg:last-child { transform: rotate(180deg); }
.change-mark { display: grid; width: 28px; height: 28px; place-items: center; color: var(--n-primary-color); border-radius: 5px; background: var(--n-action-color); }
.change-copy { display: grid; min-width: 0; gap: 3px; }
.change-copy strong, .change-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.change-copy strong { font-size: 12px; }
.change-copy small { color: var(--n-text-color-3); font-size: 10px; }
.change-detail { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; padding: 0 11px 11px; border-top: 1px solid var(--n-divider-color); }
.change-detail > div { min-width: 0; padding-top: 9px; }
.change-detail span { color: var(--n-text-color-3); font-size: 10px; }
.change-detail pre { max-height: 360px; margin: 5px 0 0; padding: 9px; overflow: auto; border-radius: 5px; color: var(--n-text-color-2); background: var(--n-action-color); font-size: 10px; white-space: pre-wrap; word-break: break-word; }
@media (max-width: 700px) {
  .changes-toolbar, .change-detail { grid-template-columns: 1fr; }
  .change-list summary { grid-template-columns: auto minmax(0, 1fr) auto; }
  .change-list summary > svg:last-child { display: none; }
}
</style>
