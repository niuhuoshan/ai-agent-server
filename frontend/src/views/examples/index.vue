<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import type { DataTableColumns, FormInst } from 'naive-ui';
import { NButton, NCard, NDataTable, NDivider, NForm, NFormItem, NInput, NList, NListItem, NModal, NSelect, NSpace, NTag, useDialog } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  auditPortalExample,
  deletePortalExample,
  enhancePortalExample,
  fetchPortalExampleHistory,
  fetchDatasets,
  fetchPortalExamples,
  syncPortalExamples,
  syncPortalExample,
  updatePortalExample,
  type DatasetView,
  type PortalExample,
  type PortalExampleRevision
} from '@/service/api';

const dialog = useDialog();
const loading = ref(false);
const submitting = ref(false);
const enhancingId = ref<string | number | null>(null);
const examples = ref<PortalExample[]>([]);
const total = ref(0);
const datasets = ref<DatasetView[]>([]);
const page = ref(1);
const size = ref(20);
const search = ref('');
const status = ref<string | null>(null);
const category = ref<string | null>(null);
const detailVisible = ref(false);
const detail = ref<PortalExample | null>(null);
const history = ref<PortalExampleRevision[]>([]);
const formRef = ref<FormInst | null>(null);
const form = reactive({ userQuery: '', refinedQuery: '', contextSummary: '', sqlText: '', category: 'data_query' });
const statusOptions = [{ label: '待审核', value: 'pending' }, { label: '已通过', value: 'approved' }, { label: '已拒绝', value: 'rejected' }, { label: '已废弃', value: 'deprecated' }];
const categoryOptions = [{ label: '通用', value: 'general' }, { label: '知识问答', value: 'knowledge' }, { label: '数据查询', value: 'data_query' }];
const datasetMap = computed(() => new Map(datasets.value.map(item => [String(item.id), item.name])));

const columns: DataTableColumns<PortalExample> = [
  { title: '用户问题', key: 'user_query', minWidth: 250, ellipsis: { tooltip: true } },
  { title: '数据集', key: 'dataset_id', width: 150, render: row => row.dataset_id ? `${datasetMap.value.get(String(row.dataset_id)) || '数据集'} (#${row.dataset_id})` : '-' },
  { title: '分类', key: 'category', width: 110 },
  { title: '智能增强', key: 'enhance_status', width: 110, render: row => h(NTag, { size: 'small', type: enhanceStatusType(row.enhance_status) }, () => enhanceStatusText(row.enhance_status)) },
  { title: '审核状态', key: 'review_status', width: 110, render: row => h(NTag, { size: 'small', type: statusType(row.review_status) }, () => statusText(row.review_status)) },
  { title: '本地索引', key: 'local_sync_status', width: 110, render: row => row.local_sync_status || '-' },
  { title: '时间', key: 'created_at', width: 180, render: row => row.created_at || '-' },
  { title: '操作', key: 'actions', width: 350, fixed: 'right', render: row => h(NSpace, { size: 6 }, () => [
    h(NButton, { size: 'small', onClick: () => openDetail(row) }, () => '查看/编辑'),
    h(NButton, { size: 'small', secondary: true, loading: enhancingId.value === row.id, onClick: () => enhance(row) }, () => '智能增强'),
    h(NButton, { size: 'small', type: 'success', secondary: true, disabled: row.review_status !== 'pending', onClick: () => audit(row, 'approved') }, () => '通过'),
    h(NButton, { size: 'small', type: 'warning', secondary: true, disabled: row.review_status !== 'pending', onClick: () => audit(row, 'rejected') }, () => '拒绝'),
    h(NButton, { size: 'small', tertiary: true, onClick: () => sync(row) }, () => '同步')
  ]) }
];

function statusType(value: string) { return value === 'approved' ? 'success' : value === 'rejected' ? 'error' : value === 'pending' ? 'warning' : 'default'; }
function statusText(value: string) { return ({ pending: '待审核', approved: '已通过', rejected: '已拒绝', deprecated: '已废弃' } as Record<string, string>)[value] || value; }
function enhanceStatusType(value?: string) { return value === 'succeeded' ? 'success' : value === 'failed' ? 'error' : value === 'running' || value === 'pending' ? 'warning' : 'default'; }
function enhanceStatusText(value?: string) { return ({ not_requested: '未增强', pending: '等待中', running: '生成中', succeeded: '已完成', failed: '失败' } as Record<string, string>)[value || ''] || value || '-'; }

async function load() {
  loading.value = true;
  const [result, datasetResult] = await Promise.all([
    fetchPortalExamples({ page: page.value, size: size.value, search: search.value.trim() || undefined, status: status.value || undefined, category: category.value || undefined }),
    datasets.value.length ? Promise.resolve({ error: null, data: datasets.value }) : fetchDatasets()
  ]);
  if (!result.error) { examples.value = result.data.items; total.value = result.data.total; }
  if (!datasetResult.error) datasets.value = datasetResult.data;
  loading.value = false;
}

function openDetail(row: PortalExample) {
  detail.value = row;
  history.value = [];
  Object.assign(form, { userQuery: row.user_query, refinedQuery: row.refined_query || '', contextSummary: row.context_summary || '', sqlText: row.sql_text, category: row.category });
  detailVisible.value = true;
  void loadHistory(row.id);
}

async function loadHistory(id: string | number) {
  const result = await fetchPortalExampleHistory(id);
  if (!result.error) history.value = result.data;
}

async function save() {
  if (!detail.value) return;
  submitting.value = true;
  const result = await updatePortalExample(detail.value.id, { user_query: form.userQuery, refined_query: form.refinedQuery || null, context_summary: form.contextSummary || null, sql_text: form.sqlText, category: form.category });
  if (!result.error) { detailVisible.value = false; window.$message?.success('案例已更新'); await load(); }
  submitting.value = false;
}

async function audit(row: PortalExample, next: 'approved' | 'rejected') {
  const result = await auditPortalExample(row.id, next);
  if (!result.error) { window.$message?.success(next === 'approved' ? '案例已通过审核' : '案例已拒绝'); await load(); }
}

async function sync(row: PortalExample) {
  const result = await syncPortalExample(row.id);
  if (!result.error) { window.$message?.success('案例已同步到本地检索索引'); await load(); }
}

async function enhance(row: PortalExample) {
  enhancingId.value = row.id;
  const result = await enhancePortalExample(row.id);
  if (!result.error) {
    if (detail.value?.id === row.id) {
      detail.value = result.data;
      Object.assign(form, {
        userQuery: result.data.user_query,
        refinedQuery: result.data.refined_query || '',
        contextSummary: result.data.context_summary || '',
        sqlText: result.data.sql_text,
        category: result.data.category
      });
    }
    window.$message?.success('案例智能增强已完成');
    await load();
  }
  enhancingId.value = null;
}

function syncAll() {
  dialog.info({ title: '批量同步案例', content: '只同步审核通过或已废弃且通过只读 SQL 校验的案例。确认继续？', positiveText: '开始同步', negativeText: '取消', onPositiveClick: async () => {
    const result = await syncPortalExamples();
    if (!result.error) { window.$message?.success(`同步完成：${String(result.data.synced || 0)} 条`); await load(); }
  } });
}

function remove(row: PortalExample) {
  dialog.warning({ title: '删除案例', content: '删除后不可恢复，确认继续？', positiveText: '删除', negativeText: '取消', onPositiveClick: async () => {
    const result = await deletePortalExample(row.id);
    if (!result.error) { window.$message?.success('案例已删除'); await load(); }
  } });
}

onMounted(load);
</script>

<template>
  <div class="h-full flex-col-stretch gap-12px overflow-hidden p-16px">
    <NCard :bordered="false" size="small" class="shrink-0">
      <template #header><div class="flex items-center gap-8px"><SvgIcon icon="lucide:book-open-check" /><span>ChatBI 案例集</span><NTag size="small" :bordered="false">本地关系索引</NTag></div></template>
      <template #header-extra><NSpace><NInput v-model:value="search" clearable placeholder="搜索问题或 SQL" style="width: 220px" @keyup.enter="page = 1; load()" /><NSelect v-model:value="status" clearable placeholder="审核状态" :options="statusOptions" style="width: 120px" @update:value="page = 1; load()" /><NSelect v-model:value="category" clearable placeholder="案例分类" :options="categoryOptions" style="width: 120px" @update:value="page = 1; load()" /><NButton secondary :loading="loading" @click="load"><template #icon><SvgIcon icon="lucide:refresh-cw" /></template>刷新</NButton><NButton type="primary" secondary @click="syncAll"><template #icon><SvgIcon icon="lucide:refresh-cw" /></template>批量同步</NButton></NSpace></template>
    </NCard>
    <NCard :bordered="false" class="min-h-0 flex-1" content-class="h-full">
      <NDataTable :columns="columns" :data="examples" :loading="loading" :single-line="false" flex-height class="h-full" :pagination="{ page, pageSize: size, itemCount: total, showSizePicker: true, pageSizes: [20, 50, 100], onChange: (next: number) => { page = next; load(); }, onUpdatePageSize: (next: number) => { size = next; page = 1; load(); } }" />
    </NCard>
    <NModal v-model:show="detailVisible" preset="card" :title="detail ? `案例 #${detail.id}` : '案例'" style="width: min(800px, 94vw)">
      <NForm ref="formRef" :model="form" label-placement="top"><NFormItem label="用户问题"><NInput v-model:value="form.userQuery" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }" /></NFormItem><NFormItem label="增强问题"><NInput v-model:value="form.refinedQuery" /></NFormItem><NFormItem label="上下文摘要"><NInput v-model:value="form.contextSummary" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }" /></NFormItem><NFormItem label="只读 SQL"><NInput v-model:value="form.sqlText" type="textarea" :autosize="{ minRows: 5, maxRows: 14 }" /></NFormItem><NFormItem label="分类"><NSelect v-model:value="form.category" :options="categoryOptions" /></NFormItem></NForm>
      <NDivider>变更历史</NDivider>
      <NList v-if="history.length" bordered>
        <NListItem v-for="item in history" :key="String(item.id)">
          <div class="w-full flex items-center justify-between gap-12px">
            <div class="min-w-0"><div class="font-medium">{{ item.action }} · {{ item.reason || '案例状态变更' }}</div><div class="text-12px op-60">版本 {{ item.revision_no || '-' }} · {{ item.created_at || '-' }} · {{ item.content_hash || '-' }}</div></div>
            <NTag size="small" :type="statusType(item.review_status)">{{ statusText(item.review_status) }}</NTag>
          </div>
        </NListItem>
      </NList>
      <div v-else class="py-12px text-12px op-60">暂无持久变更历史</div>
      <template #footer><NSpace justify="space-between"><NButton v-if="detail" type="error" tertiary @click="remove(detail)">删除案例</NButton><NSpace><NButton v-if="detail" secondary :loading="enhancingId === detail.id" @click="enhance(detail)"><template #icon><SvgIcon icon="lucide:sparkles" /></template>智能增强</NButton><NButton @click="detailVisible = false">取消</NButton><NButton type="primary" :loading="submitting" @click="save">保存</NButton></NSpace></NSpace></template>
    </NModal>
  </div>
</template>
