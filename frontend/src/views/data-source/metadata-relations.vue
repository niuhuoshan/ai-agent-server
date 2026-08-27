<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import {
  createDatasetRelation,
  deleteDatasetRelation,
  fetchDatasetRelations,
  updateDatasetRelation,
  type CreateDataRelationPayload,
  type DataRelationView,
  type DataTableView,
  type UpdateDataRelationPayload
} from '@/service/api';

const props = defineProps<{ datasetId: string; metadata: DataTableView[] }>();

const loading = ref(false);
const submitting = ref(false);
const relations = ref<DataRelationView[]>([]);
const keyword = ref('');
const error = ref('');
const modalError = ref('');
const editorVisible = ref(false);
const deleteVisible = ref(false);
const editing = ref<DataRelationView | null>(null);
const deleting = ref<DataRelationView | null>(null);
const form = reactive({
  sourceTableId: null as string | null,
  targetTableId: null as string | null,
  joinType: 'left' as DataRelationView['joinType'],
  joinCondition: '',
  description: '',
  status: 'active' as DataRelationView['status']
});

const joinTypeOptions = [
  { label: 'LEFT JOIN', value: 'left' },
  { label: 'INNER JOIN', value: 'inner' },
  { label: 'RIGHT JOIN', value: 'right' },
  { label: 'FULL JOIN', value: 'full' }
];
const statusOptions = [
  { label: '启用', value: 'active' },
  { label: '停用', value: 'inactive' }
];
const tableOptions = computed(() =>
  props.metadata.map(table => ({
    label: `${table.displayName || table.physicalName} / ${table.physicalSchema}.${table.physicalName}`,
    value: table.id,
    disabled: table.status === 'inactive' || !table.metadataPresent
  }))
);
const visibleRelations = computed(() => {
  const needle = keyword.value.trim().toLocaleLowerCase();
  if (!needle) return relations.value;
  return relations.value.filter(relation =>
    [
      tableName(relation.sourceTableId),
      tableName(relation.targetTableId),
      relation.joinType,
      relation.joinCondition,
      relation.description || '',
      relation.status
    ]
      .join(' ')
      .toLocaleLowerCase()
      .includes(needle)
  );
});

function table(id: string | null | undefined) {
  return props.metadata.find(item => String(item.id) === String(id));
}

function tableName(id: string | null | undefined) {
  const item = table(id);
  return item ? item.displayName || item.physicalName : `表 #${id || '-'}`;
}

function qualifiedTableName(id: string | null | undefined) {
  const item = table(id);
  return item ? `${item.physicalSchema}.${item.physicalName}` : `#${id || '-'}`;
}

function requestError(result: unknown, fallback: string) {
  const value = result as {
    error?: { response?: { status?: number; data?: { msg?: string; message?: string } }; message?: string };
  };
  if (!value.error) return '';
  const status = value.error.response?.status;
  if (status === 403) return '没有维护此数据集关系的权限';
  if (status === 409) return '关系已变化或存在重复定义，请刷新后重试';
  return value.error.response?.data?.msg || value.error.response?.data?.message || value.error.message || fallback;
}

async function load() {
  if (!props.datasetId) return;
  loading.value = true;
  error.value = '';
  try {
    const result = await fetchDatasetRelations(props.datasetId);
    const failure = requestError(result, '关系列表加载失败');
    if (failure) error.value = failure;
    else if (result.data) relations.value = result.data;
    else error.value = '关系列表返回为空';
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '关系列表加载失败';
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editing.value = null;
  const enabledTables = props.metadata.filter(item => item.status === 'active' && item.metadataPresent);
  Object.assign(form, {
    sourceTableId: enabledTables[0]?.id || null,
    targetTableId: enabledTables[1]?.id || enabledTables[0]?.id || null,
    joinType: 'left',
    joinCondition: '',
    description: '',
    status: 'active'
  });
  modalError.value = '';
  editorVisible.value = true;
}

function openEdit(relation: DataRelationView) {
  editing.value = relation;
  Object.assign(form, {
    sourceTableId: relation.sourceTableId,
    targetTableId: relation.targetTableId,
    joinType: relation.joinType,
    joinCondition: relation.joinCondition,
    description: relation.description || '',
    status: relation.status
  });
  modalError.value = '';
  editorVisible.value = true;
}

function validate() {
  if (!form.sourceTableId || !form.targetTableId) {
    modalError.value = '请选择源表和目标表';
    return false;
  }
  if (!table(form.sourceTableId) || !table(form.targetTableId)) {
    modalError.value = '关联表已不在当前授权数据集内，请刷新元数据';
    return false;
  }
  if (!form.joinCondition.trim()) {
    modalError.value = '关联条件不能为空';
    return false;
  }
  return true;
}

async function save() {
  if (!validate() || !form.sourceTableId || !form.targetTableId) return;
  const common: CreateDataRelationPayload = {
    sourceTableId: form.sourceTableId,
    targetTableId: form.targetTableId,
    joinType: form.joinType,
    joinCondition: form.joinCondition.trim(),
    description: form.description.trim() || undefined,
    status: form.status
  };
  submitting.value = true;
  modalError.value = '';
  try {
    const result = editing.value
      ? await updateDatasetRelation(props.datasetId, editing.value.id, {
          ...common,
          revisionNo: editing.value.revisionNo
        } satisfies UpdateDataRelationPayload)
      : await createDatasetRelation(props.datasetId, common);
    const failure = requestError(result, '关系保存失败');
    if (failure) {
      modalError.value = failure;
      return;
    }
    editorVisible.value = false;
    window.$message?.success(editing.value ? '关系已更新' : '关系已创建');
    await load();
  } catch (reason) {
    modalError.value = reason instanceof Error ? reason.message : '关系保存失败';
  } finally {
    submitting.value = false;
  }
}

function confirmDelete(relation: DataRelationView) {
  deleting.value = relation;
  modalError.value = '';
  deleteVisible.value = true;
}

async function remove() {
  if (!deleting.value) return;
  submitting.value = true;
  modalError.value = '';
  try {
    const result = await deleteDatasetRelation(props.datasetId, deleting.value.id);
    const failure = requestError(result, '关系删除失败');
    if (failure) {
      modalError.value = failure;
      return;
    }
    deleteVisible.value = false;
    window.$message?.success('关系已删除');
    await load();
  } catch (reason) {
    modalError.value = reason instanceof Error ? reason.message : '关系删除失败';
  } finally {
    submitting.value = false;
  }
}

watch(() => props.datasetId, () => void load(), { immediate: true });
</script>

<template>
  <section class="metadata-semantic-section">
    <div class="semantic-toolbar">
      <NInput v-model:value="keyword" clearable placeholder="搜索关联表、条件或说明" data-testid="relation-search" />
      <div class="toolbar-actions">
        <NButton secondary :loading="loading" title="刷新关系" @click="load">
          <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
        </NButton>
        <NButton type="primary" :disabled="!metadata.length" @click="openCreate">
          <template #icon><SvgIcon icon="lucide:plus" /></template>
          新建关系
        </NButton>
      </div>
    </div>

    <NAlert v-if="error" type="error" :bordered="false">{{ error }}</NAlert>
    <div v-if="visibleRelations.length" class="relation-list">
      <article v-for="relation in visibleRelations" :key="relation.id">
        <div class="relation-flow">
          <span><strong>{{ tableName(relation.sourceTableId) }}</strong><small>{{ qualifiedTableName(relation.sourceTableId) }}</small></span>
          <div><NTag size="small" :type="relation.status === 'active' ? 'success' : 'warning'" :bordered="false">{{ relation.joinType.toUpperCase() }}</NTag><SvgIcon icon="lucide:arrow-right" /></div>
          <span><strong>{{ tableName(relation.targetTableId) }}</strong><small>{{ qualifiedTableName(relation.targetTableId) }}</small></span>
        </div>
        <div class="relation-detail"><code>{{ relation.joinCondition }}</code><p>{{ relation.description || '暂无关系说明' }}</p></div>
        <div class="row-actions">
          <NButton size="small" quaternary @click="openEdit(relation)"><template #icon><SvgIcon icon="lucide:pencil" /></template>编辑</NButton>
          <NButton size="small" type="error" quaternary @click="confirmDelete(relation)"><template #icon><SvgIcon icon="lucide:trash-2" /></template>删除</NButton>
        </div>
      </article>
    </div>
    <NEmpty v-else-if="!loading" description="暂无表关系，请建立 ChatBI 可复用的关联路径" />

    <NModal v-model:show="editorVisible" preset="card" :title="editing ? '编辑关系' : '新建关系'" :mask-closable="false" style="width: min(720px, calc(100vw - 32px))">
      <NAlert v-if="modalError" type="error" :bordered="false" class="mb-12px">{{ modalError }}</NAlert>
      <NForm label-placement="top">
        <div class="form-grid">
          <NFormItem label="源表"><NSelect v-model:value="form.sourceTableId" :options="tableOptions" filterable data-testid="relation-source" /></NFormItem>
          <NFormItem label="目标表"><NSelect v-model:value="form.targetTableId" :options="tableOptions" filterable data-testid="relation-target" /></NFormItem>
          <NFormItem label="连接类型"><NSelect v-model:value="form.joinType" :options="joinTypeOptions" data-testid="relation-join-type" /></NFormItem>
          <NFormItem label="状态"><NSelect v-model:value="form.status" :options="statusOptions" data-testid="relation-status" /></NFormItem>
          <NFormItem label="关联条件" class="full-field"><NInput v-model:value="form.joinCondition" type="textarea" :autosize="{ minRows: 3, maxRows: 7 }" maxlength="2000" placeholder="orders.customer_id = customers.id" data-testid="relation-condition" /></NFormItem>
          <NFormItem label="关系说明" class="full-field"><NInput v-model:value="form.description" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }" maxlength="2000" data-testid="relation-description" /></NFormItem>
        </div>
      </NForm>
      <template #footer><NSpace justify="end"><NButton @click="editorVisible = false">取消</NButton><NButton type="primary" :loading="submitting" @click="save">保存</NButton></NSpace></template>
    </NModal>

    <NModal v-model:show="deleteVisible" preset="dialog" title="删除关系" :mask-closable="false">
      <NAlert v-if="modalError" type="error" :bordered="false" class="mb-12px">{{ modalError }}</NAlert>
      确认删除“{{ tableName(deleting?.sourceTableId) }} → {{ tableName(deleting?.targetTableId) }}”关系？
      <template #action><NSpace justify="end"><NButton @click="deleteVisible = false">取消</NButton><NButton type="error" :loading="submitting" @click="remove">删除</NButton></NSpace></template>
    </NModal>
  </section>
</template>

<style scoped lang="scss">
.metadata-semantic-section { display: grid; gap: 12px; }
.semantic-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.semantic-toolbar :deep(.n-input) { max-width: 420px; }
.toolbar-actions, .row-actions { display: flex; align-items: center; gap: 6px; }
.relation-list { display: grid; gap: 9px; }
.relation-list article { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(260px, .8fr) auto; align-items: center; gap: 14px; padding: 13px; border: 1px solid var(--n-border-color); border-radius: 7px; background: var(--n-color); }
.relation-flow { display: grid; grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr); align-items: center; gap: 8px; }
.relation-flow > span { display: grid; min-width: 0; gap: 3px; }
.relation-flow strong, .relation-flow small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.relation-flow strong { font-size: 13px; }
.relation-flow small { color: var(--n-text-color-3); font-size: 10px; }
.relation-flow > div { display: grid; justify-items: center; gap: 3px; color: var(--n-text-color-3); }
.relation-detail { display: grid; min-width: 0; gap: 5px; }
.relation-detail code { overflow: hidden; color: var(--n-primary-color); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.relation-detail p { margin: 0; color: var(--n-text-color-3); font-size: 11px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 12px; }
.full-field { grid-column: 1 / -1; }
@media (max-width: 900px) {
  .relation-list article { grid-template-columns: 1fr auto; }
  .relation-detail { grid-column: 1 / -1; grid-row: 2; }
}
@media (max-width: 640px) {
  .semantic-toolbar { align-items: stretch; flex-direction: column; }
  .semantic-toolbar :deep(.n-input) { max-width: none; }
  .relation-list article, .form-grid { grid-template-columns: 1fr; }
  .row-actions { justify-content: flex-end; }
  .full-field { grid-column: auto; }
}
</style>
