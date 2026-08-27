<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import {
  createDatasetMetric,
  deleteDatasetMetric,
  fetchDatasetMetrics,
  updateDatasetMetric,
  type CreateDataMetricPayload,
  type DataMetricView,
  type UpdateDataMetricPayload
} from '@/service/api';

const props = defineProps<{ datasetId: string }>();

const loading = ref(false);
const submitting = ref(false);
const metrics = ref<DataMetricView[]>([]);
const keyword = ref('');
const error = ref('');
const modalError = ref('');
const editorVisible = ref(false);
const deleteVisible = ref(false);
const editing = ref<DataMetricView | null>(null);
const deleting = ref<DataMetricView | null>(null);
const form = reactive({
  metricKey: '',
  name: '',
  description: '',
  calculationLogic: '',
  unit: '',
  status: 'active' as DataMetricView['status']
});

const statusOptions = [
  { label: '启用', value: 'active' },
  { label: '停用', value: 'inactive' }
];
const visibleMetrics = computed(() => {
  const needle = keyword.value.trim().toLocaleLowerCase();
  if (!needle) return metrics.value;
  return metrics.value.filter(metric =>
    [metric.metricKey, metric.name, metric.description || '', metric.calculationLogic, metric.unit || '', metric.status]
      .join(' ')
      .toLocaleLowerCase()
      .includes(needle)
  );
});

function requestError(result: unknown, fallback: string) {
  const value = result as {
    error?: { response?: { status?: number; data?: { msg?: string; message?: string } }; message?: string };
  };
  if (!value.error) return '';
  const status = value.error.response?.status;
  if (status === 403) return '没有维护此数据集指标的权限';
  if (status === 409) return '指标已被其他人修改，已刷新最新数据';
  return value.error.response?.data?.msg || value.error.response?.data?.message || value.error.message || fallback;
}

async function load() {
  if (!props.datasetId) return;
  loading.value = true;
  error.value = '';
  try {
    const result = await fetchDatasetMetrics(props.datasetId);
    const failure = requestError(result, '指标列表加载失败');
    if (failure) error.value = failure;
    else if (result.data) metrics.value = result.data;
    else error.value = '指标列表返回为空';
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '指标列表加载失败';
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editing.value = null;
  Object.assign(form, {
    metricKey: '',
    name: '',
    description: '',
    calculationLogic: '',
    unit: '',
    status: 'active'
  });
  modalError.value = '';
  editorVisible.value = true;
}

function openEdit(metric: DataMetricView) {
  editing.value = metric;
  Object.assign(form, {
    metricKey: metric.metricKey,
    name: metric.name,
    description: metric.description || '',
    calculationLogic: metric.calculationLogic,
    unit: metric.unit || '',
    status: metric.status
  });
  modalError.value = '';
  editorVisible.value = true;
}

function validate() {
  if (!/^[a-z0-9][a-z0-9._-]{0,127}$/.test(form.metricKey.trim())) {
    modalError.value = '指标标识仅支持小写字母、数字、点、下划线和短横线';
    return false;
  }
  if (!form.name.trim()) {
    modalError.value = '指标名称不能为空';
    return false;
  }
  if (!form.calculationLogic.trim()) {
    modalError.value = '计算逻辑不能为空';
    return false;
  }
  return true;
}

async function save() {
  if (!validate()) return;
  const common = {
    name: form.name.trim(),
    description: form.description.trim() || undefined,
    calculationLogic: form.calculationLogic.trim(),
    unit: form.unit.trim() || undefined,
    status: form.status
  };
  submitting.value = true;
  modalError.value = '';
  try {
    const result = editing.value
      ? await updateDatasetMetric(props.datasetId, editing.value.id, {
          ...common,
          versionNo: editing.value.versionNo
        } satisfies UpdateDataMetricPayload)
      : await createDatasetMetric(props.datasetId, {
          ...common,
          metricKey: form.metricKey.trim()
        } satisfies CreateDataMetricPayload);
    const failure = requestError(result, '指标保存失败');
    if (failure) {
      modalError.value = failure;
      if ((result as { error?: { response?: { status?: number } } }).error?.response?.status === 409) await load();
      return;
    }
    editorVisible.value = false;
    window.$message?.success(editing.value ? '指标已更新' : '指标已创建');
    await load();
  } catch (reason) {
    modalError.value = reason instanceof Error ? reason.message : '指标保存失败';
  } finally {
    submitting.value = false;
  }
}

function confirmDelete(metric: DataMetricView) {
  deleting.value = metric;
  modalError.value = '';
  deleteVisible.value = true;
}

async function remove() {
  if (!deleting.value) return;
  submitting.value = true;
  modalError.value = '';
  try {
    const result = await deleteDatasetMetric(props.datasetId, deleting.value.id);
    const failure = requestError(result, '指标删除失败');
    if (failure) {
      modalError.value = failure;
      return;
    }
    deleteVisible.value = false;
    window.$message?.success('指标已删除');
    await load();
  } catch (reason) {
    modalError.value = reason instanceof Error ? reason.message : '指标删除失败';
  } finally {
    submitting.value = false;
  }
}

watch(() => props.datasetId, () => void load(), { immediate: true });
</script>

<template>
  <section class="metadata-semantic-section">
    <div class="semantic-toolbar">
      <NInput v-model:value="keyword" clearable placeholder="搜索指标名称、标识、单位或计算逻辑" data-testid="metric-search" />
      <div class="toolbar-actions">
        <NButton secondary :loading="loading" title="刷新指标" @click="load">
          <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
        </NButton>
        <NButton type="primary" @click="openCreate">
          <template #icon><SvgIcon icon="lucide:plus" /></template>
          新建指标
        </NButton>
      </div>
    </div>

    <NAlert v-if="error" type="error" :bordered="false">{{ error }}</NAlert>
    <div v-if="visibleMetrics.length" class="semantic-list">
      <article v-for="metric in visibleMetrics" :key="metric.id">
        <div class="semantic-icon"><SvgIcon icon="lucide:sigma" /></div>
        <div class="semantic-copy">
          <div class="semantic-title">
            <h3>{{ metric.name }}</h3>
            <NTag size="small" :type="metric.status === 'active' ? 'success' : 'warning'" :bordered="false">
              {{ metric.status === 'active' ? '启用' : '停用' }}
            </NTag>
            <NTag v-if="metric.unit" size="small" :bordered="false">{{ metric.unit }}</NTag>
          </div>
          <code>{{ metric.metricKey }}</code>
          <p>{{ metric.description || '暂无指标说明' }}</p>
          <pre>{{ metric.calculationLogic }}</pre>
        </div>
        <div class="row-actions">
          <NButton size="small" quaternary @click="openEdit(metric)">
            <template #icon><SvgIcon icon="lucide:pencil" /></template>
            编辑
          </NButton>
          <NButton size="small" type="error" quaternary @click="confirmDelete(metric)">
            <template #icon><SvgIcon icon="lucide:trash-2" /></template>
            删除
          </NButton>
        </div>
      </article>
    </div>
    <NEmpty v-else-if="!loading" description="暂无指标，请先建立可复用的业务计算口径" />

    <NModal
      v-model:show="editorVisible"
      preset="card"
      :title="editing ? '编辑指标' : '新建指标'"
      :mask-closable="false"
      style="width: min(680px, calc(100vw - 32px))"
    >
      <NAlert v-if="modalError" type="error" :bordered="false" class="mb-12px">{{ modalError }}</NAlert>
      <NForm label-placement="top">
        <div class="form-grid">
          <NFormItem label="指标标识">
            <NInput v-model:value="form.metricKey" :disabled="Boolean(editing)" maxlength="128" placeholder="gross_sales" data-testid="metric-key" />
          </NFormItem>
          <NFormItem label="指标名称">
            <NInput v-model:value="form.name" maxlength="255" placeholder="销售额" data-testid="metric-name" />
          </NFormItem>
          <NFormItem label="单位">
            <NInput v-model:value="form.unit" maxlength="64" placeholder="元、%、人" data-testid="metric-unit" />
          </NFormItem>
          <NFormItem label="状态">
            <NSelect v-model:value="form.status" :options="statusOptions" data-testid="metric-status" />
          </NFormItem>
          <NFormItem label="指标说明" class="full-field">
            <NInput v-model:value="form.description" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }" maxlength="4000" data-testid="metric-description" />
          </NFormItem>
          <NFormItem label="计算逻辑" class="full-field">
            <NInput v-model:value="form.calculationLogic" type="textarea" :autosize="{ minRows: 4, maxRows: 10 }" maxlength="8000" placeholder="SUM(orders.total_amount)" data-testid="metric-logic" />
          </NFormItem>
        </div>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="editorVisible = false">取消</NButton>
          <NButton type="primary" :loading="submitting" @click="save">保存</NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal v-model:show="deleteVisible" preset="dialog" title="删除指标" :mask-closable="false">
      <NAlert v-if="modalError" type="error" :bordered="false" class="mb-12px">{{ modalError }}</NAlert>
      确认删除“{{ deleting?.name }}”？引用保护或版本冲突时后端会拒绝。
      <template #action>
        <NSpace justify="end">
          <NButton @click="deleteVisible = false">取消</NButton>
          <NButton type="error" :loading="submitting" @click="remove">删除</NButton>
        </NSpace>
      </template>
    </NModal>
  </section>
</template>

<style scoped lang="scss">
.metadata-semantic-section { display: grid; gap: 12px; }
.semantic-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.semantic-toolbar :deep(.n-input) { max-width: 420px; }
.toolbar-actions, .row-actions, .semantic-title { display: flex; align-items: center; gap: 6px; }
.semantic-list { display: grid; gap: 9px; }
.semantic-list article { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: start; gap: 11px; padding: 13px; border: 1px solid var(--n-border-color); border-radius: 7px; background: var(--n-color); }
.semantic-icon { display: grid; width: 32px; height: 32px; place-items: center; color: var(--n-primary-color); border-radius: 6px; background: var(--n-action-color); }
.semantic-copy { display: grid; min-width: 0; gap: 5px; }
.semantic-title { flex-wrap: wrap; }
.semantic-title h3 { margin: 0; font-size: 14px; }
.semantic-copy code { color: var(--n-primary-color); font-size: 11px; }
.semantic-copy p { margin: 0; color: var(--n-text-color-3); font-size: 12px; }
.semantic-copy pre { max-height: 100px; margin: 2px 0 0; padding: 8px; overflow: auto; border-radius: 5px; color: var(--n-text-color-2); background: var(--n-action-color); font-size: 11px; white-space: pre-wrap; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 12px; }
.full-field { grid-column: 1 / -1; }
@media (max-width: 640px) {
  .semantic-toolbar { align-items: stretch; flex-direction: column; }
  .semantic-toolbar :deep(.n-input) { max-width: none; }
  .semantic-list article { grid-template-columns: auto minmax(0, 1fr); }
  .row-actions { grid-column: 1 / -1; justify-content: flex-end; }
  .form-grid { grid-template-columns: 1fr; }
  .full-field { grid-column: auto; }
}
</style>
