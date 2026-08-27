<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue';
import dayjs from 'dayjs';
import type { DataTableColumns, DataTableRowKey, SelectOption, UploadFileInfo } from 'naive-ui';
import { NButton, NTag, useDialog } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  applyMetadataImportPreview,
  createMetadataImportPreview,
  downloadDatasetMetadataYaml,
  fetchMetadataImportPreview
} from '@/service/api';
import type {
  MetadataImportApplyView,
  MetadataImportItemView,
  MetadataImportPreviewView,
  MetadataImportSourceType
} from '@/service/api';
import {
  METADATA_IMPORT_MAX_CHARACTERS,
  METADATA_IMPORT_MAX_FILE_BYTES,
  filterMetadataImportItems,
  metadataImportActionLabel,
  metadataImportActionType,
  metadataImportAvailableItems,
  metadataImportContentError,
  metadataImportDiagnosticMessage,
  metadataImportDiagnosticType,
  metadataImportFileAccept,
  metadataImportItemDetail,
  metadataImportItemStatusLabel,
  metadataImportItemStatusType,
  metadataImportItemTitle,
  metadataImportItemTypeLabel,
  metadataImportPreviewExpired,
  metadataImportPreviewStatusLabel,
  metadataImportPreviewStatusType,
  metadataImportRequestError,
  metadataImportSelectedSummary,
  metadataImportSourceLabel,
  metadataImportSourcePlaceholder,
  type MetadataImportActionFilter,
  type MetadataImportItemFilter
} from './metadata-import-state';

const props = defineProps<{
  datasetId: string;
  datasetName?: string;
}>();

const emit = defineEmits<{
  (event: 'applied', result: MetadataImportApplyView): void;
}>();

const dialog = useDialog();
const sourceType = ref<MetadataImportSourceType>('ddl');
const sourceContent = ref('');
const sourceFileName = ref('');
const previewLoading = ref(false);
const applyLoading = ref(false);
const exportLoading = ref(false);
const actionError = ref<string | null>(null);
const preview = ref<MetadataImportPreviewView | null>(null);
const selectedRowKeys = ref<DataTableRowKey[]>([]);
const keyword = ref('');
const itemTypeFilter = ref<MetadataImportItemFilter>('all');
const actionFilter = ref<MetadataImportActionFilter>('all');
const detailItem = ref<MetadataImportItemView | null>(null);
const sourceFingerprintAtPreview = ref<string | null>(null);

const sourceTypeOptions = [
  { value: 'ddl' as const, label: 'DDL', icon: 'lucide:file-code-2' },
  { value: 'yaml' as const, label: 'YAML', icon: 'lucide:file-text' }
];
const itemTypeOptions: SelectOption[] = [
  { label: '全部资源', value: 'all' },
  { label: '数据表', value: 'table' },
  { label: '业务指标', value: 'metric' },
  { label: '表关系', value: 'relationship' }
];
const actionOptions: SelectOption[] = [
  { label: '全部变更', value: 'all' },
  { label: '仅新增', value: 'create' },
  { label: '仅更新', value: 'update' }
];

const sourceError = computed(() => metadataImportContentError(sourceContent.value, sourceType.value));
const sourceCharacters = computed(() => sourceContent.value.length);
const availableItems = computed(() => metadataImportAvailableItems(preview.value));
const filteredItems = computed(() => filterMetadataImportItems(
  preview.value?.items || [],
  keyword.value,
  itemTypeFilter.value,
  actionFilter.value
));
const selectedIds = computed(() => new Set(selectedRowKeys.value.map(String)));
const selectedSummary = computed(() => metadataImportSelectedSummary(preview.value?.items || [], selectedIds.value));
const unselectedAvailableCount = computed(() => Math.max(0, availableItems.value.length - selectedSummary.value.total));
const sourceFingerprint = computed(() => `${sourceType.value}\n${sourceContent.value}`);
const sourceChangedSincePreview = computed(() =>
  sourceFingerprintAtPreview.value !== null && sourceFingerprintAtPreview.value !== sourceFingerprint.value
);
const previewExpired = computed(() => Boolean(preview.value && metadataImportPreviewExpired(preview.value)));
const previewApplicable = computed(() =>
  preview.value?.status === 'draft' && !previewExpired.value && !sourceChangedSincePreview.value
);
const canApply = computed(() => previewApplicable.value && selectedSummary.value.total > 0 && !applyLoading.value);
const proposalCode = computed(() => JSON.stringify(detailItem.value?.proposal || {}, null, 2));
const visibleAvailableIds = computed(() => filteredItems.value
  .filter(item => item.status === 'available')
  .map(item => String(item.id)));
const allVisibleSelected = computed(() =>
  visibleAvailableIds.value.length > 0 && visibleAvailableIds.value.every(id => selectedIds.value.has(id))
);
const previewTypeCounts = computed(() => ({
  tables: preview.value?.items.filter(item => item.itemType === 'table').length || 0,
  metrics: preview.value?.items.filter(item => item.itemType === 'metric').length || 0,
  relationships: preview.value?.items.filter(item => item.itemType === 'relationship').length || 0
}));

function previewStorageKey() {
  return `agent:metadata-import-preview:${props.datasetId}`;
}

function resetPreviewSelection(value: MetadataImportPreviewView) {
  selectedRowKeys.value = metadataImportAvailableItems(value).map(item => String(item.id));
}

function adoptPreview(value: MetadataImportPreviewView, sourceFingerprintValue: string | null) {
  preview.value = value;
  sourceType.value = value.sourceType;
  sourceFingerprintAtPreview.value = sourceFingerprintValue;
  resetPreviewSelection(value);
  window.localStorage.setItem(previewStorageKey(), String(value.id));
}

function clearPreview() {
  preview.value = null;
  selectedRowKeys.value = [];
  sourceFingerprintAtPreview.value = null;
  actionError.value = null;
  window.localStorage.removeItem(previewStorageKey());
}

async function restorePreview() {
  const previewId = window.localStorage.getItem(previewStorageKey());
  if (!previewId || !/^\d+$/.test(previewId)) {
    if (previewId) window.localStorage.removeItem(previewStorageKey());
    return;
  }
  await loadPreview(previewId);
}

async function loadPreview(previewId: string) {
  previewLoading.value = true;
  actionError.value = null;
  try {
    const result = await fetchMetadataImportPreview(props.datasetId, previewId);
    if (result.error) {
      actionError.value = metadataImportRequestError(result.error, '导入预览加载失败');
      return;
    }
    adoptPreview(result.data, null);
  } catch (error) {
    actionError.value = metadataImportRequestError(error, '导入预览加载失败');
  } finally {
    previewLoading.value = false;
  }
}

async function createPreview() {
  if (sourceError.value) return;
  previewLoading.value = true;
  actionError.value = null;
  try {
    const fingerprint = sourceFingerprint.value;
    const result = await createMetadataImportPreview(props.datasetId, {
      format: sourceType.value,
      content: sourceContent.value
    });
    if (result.error) {
      actionError.value = metadataImportRequestError(result.error, '导入预览生成失败');
      return;
    }
    adoptPreview(result.data, fingerprint);
    window.$message?.success(`已解析 ${result.data.tableCount} 张表、${result.data.columnCount} 个字段`);
  } catch (error) {
    actionError.value = metadataImportRequestError(error, '导入预览生成失败');
  } finally {
    previewLoading.value = false;
  }
}

async function handleSourceFile(options: { file: UploadFileInfo }) {
  const file = options.file.file;
  if (!file) return;
  actionError.value = null;
  if (file.size > METADATA_IMPORT_MAX_FILE_BYTES) {
    actionError.value = '导入文件不能超过 8 MB';
    return;
  }
  try {
    const content = await file.text();
    const contentError = metadataImportContentError(content, sourceType.value);
    if (contentError) {
      actionError.value = contentError;
      return;
    }
    sourceContent.value = content;
    sourceFileName.value = file.name;
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : '导入文件读取失败';
  }
}

async function exportYaml() {
  exportLoading.value = true;
  actionError.value = null;
  try {
    const { blob, fileName } = await downloadDatasetMetadataYaml(props.datasetId);
    const url = URL.createObjectURL(blob);
    try {
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = fileName;
      anchor.click();
    } finally {
      URL.revokeObjectURL(url);
    }
    window.$message?.success('规范 YAML 已导出');
  } catch (error) {
    actionError.value = metadataImportRequestError(error, 'YAML 导出失败');
  } finally {
    exportLoading.value = false;
  }
}

function selectVisible() {
  const next = new Set(selectedRowKeys.value.map(String));
  for (const id of visibleAvailableIds.value) next.add(id);
  selectedRowKeys.value = [...next];
}

function clearVisibleSelection() {
  const visible = new Set(visibleAvailableIds.value);
  selectedRowKeys.value = selectedRowKeys.value.filter(key => !visible.has(String(key)));
}

function updateSelectedRowKeys(keys: DataTableRowKey[]) {
  const available = new Set(availableItems.value.map(item => String(item.id)));
  selectedRowKeys.value = keys.filter(key => available.has(String(key)));
}

async function applySelection() {
  if (!preview.value || !canApply.value) return;
  applyLoading.value = true;
  actionError.value = null;
  try {
    const result = await applyMetadataImportPreview(props.datasetId, String(preview.value.id), {
      revisionNo: preview.value.revisionNo,
      itemIds: selectedRowKeys.value.map(key => String(key))
    });
    if (result.error) {
      const failure = metadataImportRequestError(result.error, '元数据导入失败');
      if ((result.error as { response?: { status?: number } }).response?.status === 409) {
        await loadPreview(String(preview.value.id));
      }
      actionError.value = failure;
      return;
    }
    const appliedIds = new Set(result.data.appliedItemIds.map(String));
    const skippedIds = new Set(result.data.skippedItemIds.map(String));
    preview.value = {
      ...preview.value,
      status: result.data.status,
      datasetRevision: result.data.datasetRevision,
      revisionNo: result.data.revisionNo,
      appliedAt: result.data.appliedAt,
      items: preview.value.items.map(item => ({
        ...item,
        status: appliedIds.has(String(item.id))
          ? 'applied'
          : skippedIds.has(String(item.id)) ? 'skipped' : item.status
      }))
    };
    selectedRowKeys.value = [];
    window.$message?.success(`已原子应用 ${appliedIds.size} 项元数据变更`);
    emit('applied', result.data);
  } catch (error) {
    actionError.value = metadataImportRequestError(error, '元数据导入失败');
  } finally {
    applyLoading.value = false;
  }
}

function confirmApply() {
  if (!canApply.value) return;
  const summary = selectedSummary.value;
  dialog.warning({
    title: '应用元数据导入',
    content: `将一次性应用 ${summary.total} 项变更：${summary.tables} 张表、${summary.metrics} 个指标、${summary.relationships} 条关系；其余 ${unselectedAvailableCount.value} 项将标记为跳过。`,
    positiveText: '确认应用',
    negativeText: '取消',
    onPositiveClick: applySelection
  });
}

function closeDetail(show: boolean) {
  if (!show) detailItem.value = null;
}

const itemColumns: DataTableColumns<MetadataImportItemView> = [
  { type: 'selection', disabled: row => row.status !== 'available' },
  {
    title: '资源',
    key: 'resourceKey',
    minWidth: 260,
    render: row => h('div', { class: 'import-primary-cell' }, [
      h('strong', metadataImportItemTitle(row)),
      h('span', `${row.resourceKey} / ${metadataImportItemDetail(row)}`)
    ])
  },
  {
    title: '类型',
    key: 'itemType',
    width: 105,
    render: row => metadataImportItemTypeLabel(row.itemType)
  },
  {
    title: '变更',
    key: 'action',
    width: 90,
    render: row => h(NTag, { size: 'small', type: metadataImportActionType(row.action) }, () => metadataImportActionLabel(row.action))
  },
  {
    title: '状态',
    key: 'status',
    width: 105,
    render: row => h(NTag, { size: 'small', type: metadataImportItemStatusType(row.status) }, () => metadataImportItemStatusLabel(row.status))
  },
  {
    title: '操作',
    key: 'actions',
    width: 82,
    fixed: 'right',
    render: row => h(NButton, { size: 'small', quaternary: true, onClick: () => { detailItem.value = row; } }, () => '查看')
  }
];

watch(sourceType, () => {
  sourceFileName.value = '';
});

watch(() => props.datasetId, async (next, previous) => {
  if (!next || next === previous) return;
  sourceContent.value = '';
  sourceFileName.value = '';
  preview.value = null;
  selectedRowKeys.value = [];
  sourceFingerprintAtPreview.value = null;
  await restorePreview();
});

onMounted(restorePreview);
</script>

<template>
  <section class="metadata-import-workbench">
    <div class="workbench-toolbar">
      <div>
        <h3>元数据导入导出</h3>
        <span>{{ datasetName || datasetId }}</span>
      </div>
      <NButton secondary :loading="exportLoading" data-testid="metadata-export-yaml" @click="exportYaml">
        <template #icon><SvgIcon icon="lucide:download" /></template>
        导出 YAML
      </NButton>
    </div>

    <NAlert v-if="actionError" type="error" :bordered="false" closable @close="actionError = null">
      {{ actionError }}
    </NAlert>

    <section class="source-editor" aria-label="导入源">
      <div class="source-editor-heading">
        <NRadioGroup v-model:value="sourceType" size="small">
          <NRadioButton v-for="option in sourceTypeOptions" :key="option.value" :value="option.value">
            <span class="mode-label"><SvgIcon :icon="option.icon" />{{ option.label }}</span>
          </NRadioButton>
        </NRadioGroup>
        <NUpload
          :accept="metadataImportFileAccept(sourceType)"
          :default-upload="false"
          :show-file-list="false"
          @change="handleSourceFile"
        >
          <NButton size="small" secondary>
            <template #icon><SvgIcon icon="lucide:file-input" /></template>
            载入文件
          </NButton>
        </NUpload>
      </div>
      <NInput
        v-model:value="sourceContent"
        type="textarea"
        :autosize="{ minRows: 8, maxRows: 16 }"
        :placeholder="metadataImportSourcePlaceholder(sourceType)"
        data-testid="metadata-import-content"
      />
      <div class="source-editor-footer">
        <span>{{ sourceFileName || metadataImportSourceLabel(sourceType) }} · {{ sourceCharacters.toLocaleString('zh-CN') }} / {{ METADATA_IMPORT_MAX_CHARACTERS.toLocaleString('zh-CN') }} 字符</span>
        <NButton type="primary" :loading="previewLoading" :disabled="Boolean(sourceError)" data-testid="metadata-create-preview" @click="createPreview">
          <template #icon><SvgIcon icon="lucide:scan-search" /></template>
          生成预览
        </NButton>
      </div>
      <NAlert v-if="sourceContent && sourceError" type="warning" :bordered="false">{{ sourceError }}</NAlert>
    </section>

    <NSpin :show="previewLoading">
      <template v-if="preview">
        <section class="preview-summary" aria-label="导入预览摘要">
          <div class="preview-title">
            <div>
              <span>预览 {{ preview.id }}</span>
              <strong>{{ preview.tableCount }} 张表 · {{ preview.columnCount }} 个字段</strong>
            </div>
            <NTag :type="metadataImportPreviewStatusType(preview.status)">
              {{ metadataImportPreviewStatusLabel(preview.status) }}
            </NTag>
          </div>
          <div class="preview-facts">
            <div><span>资源项</span><strong>{{ preview.items.length }}</strong></div>
            <div><span>数据表</span><strong>{{ previewTypeCounts.tables }}</strong></div>
            <div><span>业务指标</span><strong>{{ previewTypeCounts.metrics }}</strong></div>
            <div><span>表关系</span><strong>{{ previewTypeCounts.relationships }}</strong></div>
            <div><span>过期时间</span><strong>{{ dayjs(preview.expiresAt).format('MM-DD HH:mm') }}</strong></div>
          </div>
        </section>

        <NAlert v-if="sourceChangedSincePreview" type="warning" :bordered="false">
          当前内容已在预览生成后修改，请重新生成预览。
        </NAlert>
        <NAlert v-else-if="previewExpired" type="warning" :bordered="false">
          该预览已过期，请重新生成预览。
        </NAlert>
        <div v-if="preview.diagnostics.length" class="diagnostic-list">
          <NAlert
            v-for="(diagnostic, index) in preview.diagnostics"
            :key="`${index}:${metadataImportDiagnosticMessage(diagnostic)}`"
            :type="metadataImportDiagnosticType(diagnostic)"
            :bordered="false"
          >
            {{ metadataImportDiagnosticMessage(diagnostic) }}
          </NAlert>
        </div>

        <div class="preview-toolbar">
          <NInput v-model:value="keyword" clearable placeholder="搜索资源标识或预览内容" />
          <NSelect v-model:value="itemTypeFilter" :options="itemTypeOptions" />
          <NSelect v-model:value="actionFilter" :options="actionOptions" />
          <NButton size="small" secondary :disabled="!visibleAvailableIds.length" @click="allVisibleSelected ? clearVisibleSelection() : selectVisible()">
            {{ allVisibleSelected ? '取消筛选结果' : '选择筛选结果' }}
          </NButton>
          <NButton v-if="preview.status !== 'draft'" size="small" quaternary @click="clearPreview">
            新建导入
          </NButton>
        </div>

        <NDataTable
          :columns="itemColumns"
          :data="filteredItems"
          :row-key="row => String(row.id)"
          :checked-row-keys="selectedRowKeys"
          :scroll-x="880"
          max-height="520"
          data-testid="metadata-import-items"
          @update:checked-row-keys="updateSelectedRowKeys"
        />
        <NEmpty v-if="!filteredItems.length" description="没有符合条件的导入项" />

        <footer class="apply-bar">
          <div>
            <strong>已选择 {{ selectedSummary.total }} 项</strong>
            <span>{{ selectedSummary.creates }} 项新增 · {{ selectedSummary.updates }} 项更新 · {{ unselectedAvailableCount }} 项跳过</span>
          </div>
          <NButton
            type="primary"
            :loading="applyLoading"
            :disabled="!canApply"
            data-testid="metadata-apply-preview"
            @click="confirmApply"
          >
            <template #icon><SvgIcon icon="lucide:check-check" /></template>
            原子应用
          </NButton>
        </footer>
      </template>
      <NEmpty v-else-if="!previewLoading" description="暂无导入预览" />
    </NSpin>

    <NModal
      :show="Boolean(detailItem)"
      preset="card"
      title="导入项详情"
      style="width: min(760px, calc(100vw - 32px))"
      @update:show="closeDetail"
    >
      <template v-if="detailItem">
        <NDescriptions :column="2" bordered size="small">
          <NDescriptionsItem label="资源标识" :span="2">{{ detailItem.resourceKey }}</NDescriptionsItem>
          <NDescriptionsItem label="资源类型">{{ metadataImportItemTypeLabel(detailItem.itemType) }}</NDescriptionsItem>
          <NDescriptionsItem label="变更类型">{{ metadataImportActionLabel(detailItem.action) }}</NDescriptionsItem>
          <NDescriptionsItem label="当前摘要">{{ detailItem.currentHash || '新资源' }}</NDescriptionsItem>
          <NDescriptionsItem label="内容摘要">{{ detailItem.contentHash }}</NDescriptionsItem>
        </NDescriptions>
        <NCode class="proposal-code" :code="proposalCode" language="json" word-wrap />
      </template>
    </NModal>
  </section>
</template>

<style scoped lang="scss">
.metadata-import-workbench { display: grid; min-width: 0; gap: 14px; }
.workbench-toolbar, .source-editor-heading, .source-editor-footer, .preview-title, .preview-toolbar, .apply-bar { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.workbench-toolbar h3 { margin: 0 0 3px; font-size: 16px; }
.workbench-toolbar span, .source-editor-footer span, .apply-bar span, .preview-title span { display: block; color: var(--n-text-color-3); font-size: 12px; }
.workbench-toolbar > div, .source-editor-footer span, .apply-bar > div, .preview-title > div { min-width: 0; }
.workbench-toolbar span, .source-editor-footer span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.source-editor { display: grid; gap: 10px; padding: 14px 0; border-block: 1px solid var(--n-border-color); }
.mode-label { display: inline-flex; align-items: center; gap: 6px; }
.mode-label :deep(svg) { width: 15px; height: 15px; }
.preview-summary { display: grid; gap: 11px; padding: 12px; border-left: 3px solid var(--n-primary-color); background: var(--n-action-color); }
.preview-title strong { display: block; margin-top: 3px; font-size: 15px; }
.preview-facts { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 8px; }
.preview-facts > div { min-width: 0; }
.preview-facts span { display: block; color: var(--n-text-color-3); font-size: 11px; }
.preview-facts strong { display: block; margin-top: 3px; overflow: hidden; font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }
.diagnostic-list { display: grid; gap: 6px; }
.preview-toolbar { display: grid; grid-template-columns: minmax(210px, 1fr) 145px 130px auto auto; }
.apply-bar { position: sticky; bottom: 0; z-index: 2; padding: 12px 0; border-top: 1px solid var(--n-border-color); background: var(--n-color); }
.apply-bar strong { display: block; }
.proposal-code { display: block; max-height: 480px; margin-top: 14px; padding: 12px; overflow: auto; background: var(--n-code-color); }
:deep(.import-primary-cell) { display: grid; min-width: 0; gap: 3px; }
:deep(.import-primary-cell strong), :deep(.import-primary-cell span) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
:deep(.import-primary-cell span) { color: var(--n-text-color-3); font-size: 12px; }
@media (max-width: 760px) {
  .workbench-toolbar, .source-editor-heading, .source-editor-footer, .apply-bar { align-items: stretch; flex-direction: column; }
  .workbench-toolbar > *, .source-editor-heading > *, .source-editor-footer > *, .apply-bar > * { width: 100%; }
  .preview-facts { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .preview-toolbar { grid-template-columns: 1fr 1fr; }
  .preview-toolbar > :first-child { grid-column: 1 / -1; }
}
</style>
