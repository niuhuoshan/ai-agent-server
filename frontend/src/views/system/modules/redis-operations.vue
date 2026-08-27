<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue';
import type { DataTableColumns, DataTableRowKey } from 'naive-ui';
import { NButton, NCode, NSpace, NTag, useDialog } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  deleteRedisKey,
  deleteRedisKeys,
  fetchRedisKeyDetail,
  fetchRedisKeys,
  flushRedisCaches
} from '@/service/api';
import type { RedisKeyDetailView, RedisKeyView } from '@/service/api';
import { formatRedisTtl, redisTypeLabel, redisValueRows } from '../redis-operations';

const dialog = useDialog();
const pattern = ref('*');
const keys = ref<RedisKeyView[]>([]);
const totalCount = ref(0);
const truncated = ref(false);
const loading = ref(false);
const loadError = ref('');
const checkedKeys = ref<DataTableRowKey[]>([]);
const detailVisible = ref(false);
const detailLoading = ref(false);
const detail = ref<RedisKeyDetailView | null>(null);

const detailRows = computed(() => redisValueRows(detail.value?.value));
const detailColumns: DataTableColumns<{ key: string; label: string; value: string }> = [
  { title: '字段 / 序号', key: 'label', width: 180, ellipsis: { tooltip: true } },
  {
    title: '值', key: 'value', minWidth: 360,
    render: row => h(NCode, { code: row.value, language: row.value.startsWith('{') || row.value.startsWith('[') ? 'json' : 'text', wordWrap: true })
  }
];

function typeTag(type: string) {
  if (type === 'string') return 'success';
  if (type === 'hash' || type === 'json') return 'info';
  if (type === 'list' || type === 'set' || type === 'zset') return 'warning';
  return 'default';
}

const columns: DataTableColumns<RedisKeyView> = [
  { type: 'selection' },
  { title: '键名', key: 'name', minWidth: 320, ellipsis: { tooltip: true } },
  {
    title: '类型', key: 'type', width: 120,
    render: row => h(NTag, { size: 'small', type: typeTag(row.type) }, () => redisTypeLabel(row.type))
  },
  { title: 'TTL', key: 'ttlSeconds', width: 150, render: row => formatRedisTtl(row.ttlSeconds) },
  {
    title: '操作', key: 'actions', width: 150,
    render: row => h(NSpace, { size: 4 }, () => [
      h(NButton, { size: 'small', quaternary: true, onClick: () => openDetail(row) }, () => '详情'),
      h(NButton, { size: 'small', quaternary: true, type: 'error', onClick: () => confirmDelete(row) }, () => '删除')
    ])
  }
];

function errorText(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback;
}

async function load() {
  loading.value = true;
  loadError.value = '';
  const result = await fetchRedisKeys(pattern.value.trim() || '*');
  if (result.error) {
    loadError.value = errorText(result.error, 'Redis 键加载失败');
    keys.value = [];
    totalCount.value = 0;
  } else {
    keys.value = result.data.keys;
    totalCount.value = result.data.totalCount;
    truncated.value = result.data.truncated;
    checkedKeys.value = checkedKeys.value.filter(key => keys.value.some(item => item.name === key));
  }
  loading.value = false;
}

async function openDetail(row: RedisKeyView) {
  detailVisible.value = true;
  detailLoading.value = true;
  detail.value = null;
  const result = await fetchRedisKeyDetail(row.name);
  if (result.error) {
    window.$message?.error(errorText(result.error, 'Redis 键详情读取失败'));
    detailVisible.value = false;
  } else {
    detail.value = result.data;
  }
  detailLoading.value = false;
}

function confirmDelete(row: RedisKeyView) {
  dialog.warning({
    title: '删除 Redis 键',
    content: `确认删除“${row.name}”？依赖该缓存的功能可能需要重新加载。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      const result = await deleteRedisKey(row.name);
      if (result.error) {
        window.$message?.error(errorText(result.error, 'Redis 键删除失败'));
        return;
      }
      window.$message?.success(result.data.message);
      await load();
    }
  });
}

function confirmDeleteSelected() {
  if (checkedKeys.value.length === 0) return;
  dialog.warning({
    title: '批量删除 Redis 键',
    content: `将删除选中的 ${checkedKeys.value.length} 个键。此操作不能撤销。`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      const result = await deleteRedisKeys(checkedKeys.value.map(String));
      if (result.error) {
        window.$message?.error(errorText(result.error, 'Redis 批量删除失败'));
        return;
      }
      window.$message?.success(result.data.message);
      checkedKeys.value = [];
      await load();
    }
  });
}

function confirmFlush() {
  dialog.warning({
    title: '清理非会话缓存',
    content: '将删除当前 Redis 数据库中的非会话键，并保留 conversation:* 会话历史。缓存会按实际访问重新生成。',
    positiveText: '确认清理',
    negativeText: '取消',
    onPositiveClick: async () => {
      const result = await flushRedisCaches();
      if (result.error) {
        window.$message?.error(errorText(result.error, 'Redis 清理失败'));
        return;
      }
      window.$message?.success(result.data.message);
      checkedKeys.value = [];
      await load();
    }
  });
}

onMounted(load);
</script>

<template>
  <section class="redis-pane">
    <div class="redis-toolbar">
      <div>
        <h3>Redis 运维</h3>
        <p>查看当前数据库中的键、TTL 和受限值预览；危险操作会记录管理员审计。</p>
      </div>
      <NSpace>
        <NButton :disabled="checkedKeys.length === 0" secondary type="error" @click="confirmDeleteSelected">
          <template #icon><SvgIcon icon="lucide:trash-2" /></template>
          删除选中
        </NButton>
        <NButton secondary type="warning" @click="confirmFlush">
          <template #icon><SvgIcon icon="lucide:eraser" /></template>
          清理缓存
        </NButton>
      </NSpace>
    </div>

    <div class="scan-toolbar">
      <NInput v-model:value="pattern" clearable placeholder="Redis SCAN 模式，例如 cache:*" @keyup.enter="load">
        <template #prefix><SvgIcon icon="lucide:search" /></template>
      </NInput>
      <NButton type="primary" :loading="loading" @click="load">
        <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
        扫描
      </NButton>
    </div>

    <NAlert v-if="loadError" type="error" :bordered="false" class="status-alert">
      <NSpace justify="space-between" align="center">
        <span>{{ loadError }}</span>
        <NButton size="small" @click="load">重试</NButton>
      </NSpace>
    </NAlert>
    <NAlert v-else-if="truncated" type="warning" :bordered="false" class="status-alert">
      当前扫描结果已达到安全上限，仅展示前 {{ keys.length }} 个键。请使用更精确的匹配模式。
    </NAlert>

    <div class="table-summary">
      <span>当前数据库共 {{ totalCount.toLocaleString() }} 个键</span>
      <span>本次返回 {{ keys.length.toLocaleString() }} 个</span>
    </div>
    <NDataTable
      v-model:checked-row-keys="checkedKeys"
      :columns="columns"
      :data="keys"
      :loading="loading"
      :row-key="row => row.name"
      :scroll-x="780"
      :pagination="{ pageSize: 20, showSizePicker: true, pageSizes: [20, 50, 100] }"
    />

    <NModal v-model:show="detailVisible" preset="card" title="Redis 键详情" class="detail-modal" style="width: min(860px, calc(100vw - 32px))">
      <NSpin :show="detailLoading">
        <NEmpty v-if="!detail && !detailLoading" description="该键当前不可用" />
        <template v-else-if="detail">
          <NDescriptions :column="3" bordered label-placement="top" class="detail-summary">
            <NDescriptionsItem label="键名">{{ detail.name }}</NDescriptionsItem>
            <NDescriptionsItem label="类型"><NTag size="small" :type="typeTag(detail.type)">{{ redisTypeLabel(detail.type) }}</NTag></NDescriptionsItem>
            <NDescriptionsItem label="TTL">{{ formatRedisTtl(detail.ttlSeconds) }}</NDescriptionsItem>
          </NDescriptions>
          <NAlert v-if="detail.valueTruncated" type="warning" :bordered="false" class="status-alert">值预览已按大小或条目数量截断。</NAlert>
          <NDataTable :columns="detailColumns" :data="detailRows" :row-key="row => row.key" :scroll-x="560" :max-height="480" />
        </template>
      </NSpin>
    </NModal>
  </section>
</template>

<style scoped lang="scss">
.redis-pane { padding-top: 8px; }
.redis-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.redis-toolbar h3 { margin: 0 0 4px; font-size: 16px; }
.redis-toolbar p { margin: 0; color: var(--n-text-color-3); font-size: 13px; }
.scan-toolbar { display: grid; grid-template-columns: minmax(220px, 520px) auto; gap: 10px; margin-bottom: 12px; }
.status-alert { margin-bottom: 12px; }
.table-summary { display: flex; gap: 18px; margin-bottom: 10px; color: var(--n-text-color-3); font-size: 13px; }
.detail-summary { margin-bottom: 12px; }
@media (max-width: 720px) {
  .redis-toolbar { align-items: stretch; flex-direction: column; }
  .scan-toolbar { grid-template-columns: 1fr; }
  .table-summary { flex-direction: column; gap: 2px; }
}
</style>
