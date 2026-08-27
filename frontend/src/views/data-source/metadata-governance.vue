<script setup lang="ts">
import { computed, h, reactive, ref } from 'vue';
import type { DataTableColumns } from 'naive-ui';
import { NButton, NSpace, NTag } from 'naive-ui';
import { downloadDatasetMetadataYaml, updateDataColumn, updateDataTable } from '@/service/api';
import type { DataColumnView, DataTableView, UpdateDataColumnPayload, UpdateDataTablePayload } from '@/service/api';
import {
  filterMetadataCatalog,
  metadataCatalogStats,
  metadataColumnRows,
  metadataGovernanceIssues,
  type MetadataColumnFilter,
  type MetadataColumnRow,
  type MetadataGovernanceIssue,
  type MetadataTableStatusFilter
} from './metadata-catalog';

type GovernanceStatus = 'active' | 'inactive';
type MetadataView = 'tables' | 'columns' | 'quality';

const props = withDefaults(
  defineProps<{
    datasetId: string;
    metadata: DataTableView[];
    loading?: boolean;
  }>(),
  {
    loading: false
  }
);

const emit = defineEmits<{
  (event: 'refresh'): void;
}>();

const activeView = ref<MetadataView>('tables');
const keyword = ref('');
const tableStatus = ref<MetadataTableStatusFilter>('all');
const columnFilter = ref<MetadataColumnFilter>('all');
const tableVisible = ref(false);
const columnVisible = ref(false);
const submitting = ref(false);
const exportLoading = ref(false);
const actionError = ref<string | null>(null);
const editingTable = ref<DataTableView | null>(null);
const editingColumn = ref<{ table: DataTableView; column: DataColumnView } | null>(null);

const tableForm = reactive<UpdateDataTablePayload>({
  displayName: '',
  description: '',
  status: 'active'
});
const columnForm = reactive<UpdateDataColumnPayload>({
  displayName: '',
  description: '',
  sensitive: false,
  status: 'active'
});

const viewOptions: Array<{ value: MetadataView; label: string; icon: string }> = [
  { value: 'tables', label: '表目录', icon: 'lucide:table-properties' },
  { value: 'columns', label: '字段清单', icon: 'lucide:columns-3' },
  { value: 'quality', label: '治理质量', icon: 'lucide:list-checks' }
];
const statusOptions = [
  { label: '启用', value: 'active' },
  { label: '停用', value: 'inactive' }
];
const tableStatusOptions = [
  { label: '全部表状态', value: 'all' },
  { label: '仅启用表', value: 'active' },
  { label: '仅停用表', value: 'inactive' }
];
const columnFilterOptions = [
  { label: '全部字段', value: 'all' },
  { label: '敏感字段', value: 'sensitive' },
  { label: '主键字段', value: 'primary' },
  { label: '停用字段', value: 'inactive' },
  { label: '待完善字段', value: 'missing' }
];

const visibleMetadata = computed(() =>
  filterMetadataCatalog(props.metadata, {
    keyword: keyword.value,
    tableStatus: tableStatus.value,
    columnFilter: columnFilter.value
  })
);
const visibleColumns = computed(() => metadataColumnRows(visibleMetadata.value));
const stats = computed(() => metadataCatalogStats(props.metadata));
const issues = computed(() => metadataGovernanceIssues(props.metadata));
const hasActiveFilters = computed(
  () => Boolean(keyword.value.trim()) || tableStatus.value !== 'all' || columnFilter.value !== 'all'
);

function status(value: string | null | undefined): GovernanceStatus {
  return value === 'inactive' ? 'inactive' : 'active';
}

function statusLabel(value: string | null | undefined) {
  if (value === 'inactive') return '停用';
  if (value === 'active') return '启用';
  return value || '未知';
}

function statusType(value: string | null | undefined) {
  if (value === 'inactive') return 'warning';
  if (value === 'active') return 'success';
  return 'default';
}

function issueType(level: MetadataGovernanceIssue['level']) {
  if (level === 'error') return 'error';
  if (level === 'warning') return 'warning';
  return 'info';
}

function issueLabel(level: MetadataGovernanceIssue['level']) {
  if (level === 'error') return '同步异常';
  if (level === 'warning') return '优先完善';
  return '建议完善';
}

function openTable(table: DataTableView) {
  actionError.value = null;
  editingTable.value = table;
  tableForm.displayName = table.displayName || table.physicalName;
  tableForm.description = table.description || '';
  tableForm.status = status(table.status);
  tableVisible.value = true;
}

function openColumn(table: DataTableView, column: DataColumnView) {
  actionError.value = null;
  editingColumn.value = { table, column };
  columnForm.displayName = column.displayName || column.physicalName;
  columnForm.description = column.description || '';
  columnForm.sensitive = column.sensitive;
  columnForm.status = status(column.status);
  columnVisible.value = true;
}

function openIssue(issue: MetadataGovernanceIssue) {
  if (issue.column) openColumn(issue.table, issue.column);
  else openTable(issue.table);
}

function closeTable() {
  if (!submitting.value) tableVisible.value = false;
}

function closeColumn() {
  if (!submitting.value) columnVisible.value = false;
}

function resetFilters() {
  keyword.value = '';
  tableStatus.value = 'all';
  columnFilter.value = 'all';
}

function resultStatus(result: unknown) {
  const candidate = result as {
    response?: { status?: number };
    error?: { response?: { status?: number } };
  };
  return candidate.response?.status ?? candidate.error?.response?.status ?? null;
}

function resultMessage(result: unknown, fallback: string) {
  const candidate = result as { error?: unknown };
  if (!candidate.error) return null;
  const httpStatus = resultStatus(result);
  if (httpStatus === 403) return '没有治理此数据集元数据的权限';
  if (httpStatus === 409) return '元数据已被其他人修改，请刷新后重试';
  if (candidate.error instanceof Error && candidate.error.message) return candidate.error.message;
  return fallback;
}

function validDisplayName(value: string) {
  const normalized = value.trim();
  if (!normalized) {
    actionError.value = '显示名称不能为空';
    return false;
  }
  if (normalized.length > 255) {
    actionError.value = '显示名称不能超过 255 个字符';
    return false;
  }
  return true;
}

function normalizedDescription(value: string) {
  const normalized = value.trim();
  if (normalized.length > 4000) {
    actionError.value = '描述不能超过 4000 个字符';
    return null;
  }
  return normalized;
}

async function saveTable() {
  if (!editingTable.value || !props.datasetId || !validDisplayName(tableForm.displayName)) return;
  const description = normalizedDescription(tableForm.description || '');
  if (description === null) return;
  actionError.value = null;
  submitting.value = true;
  try {
    const payload: UpdateDataTablePayload = {
      displayName: tableForm.displayName.trim(),
      description: description || undefined,
      status: tableForm.status
    };
    const result = await updateDataTable(props.datasetId, editingTable.value.id, payload);
    const failure = resultMessage(result, '数据表治理保存失败');
    if (failure) {
      actionError.value = failure;
      if (resultStatus(result) === 409) emit('refresh');
      return;
    }
    tableVisible.value = false;
    window.$message?.success('数据表治理已保存');
    emit('refresh');
  } finally {
    submitting.value = false;
  }
}

async function saveColumn() {
  if (!editingColumn.value || !props.datasetId || !validDisplayName(columnForm.displayName)) return;
  const description = normalizedDescription(columnForm.description || '');
  if (description === null) return;
  actionError.value = null;
  submitting.value = true;
  try {
    const payload: UpdateDataColumnPayload = {
      displayName: columnForm.displayName.trim(),
      description: description || undefined,
      sensitive: columnForm.sensitive,
      status: columnForm.status
    };
    const result = await updateDataColumn(props.datasetId, editingColumn.value.column.id, payload);
    const failure = resultMessage(result, '数据列治理保存失败');
    if (failure) {
      actionError.value = failure;
      if (resultStatus(result) === 409) emit('refresh');
      return;
    }
    columnVisible.value = false;
    window.$message?.success('数据列治理已保存');
    emit('refresh');
  } finally {
    submitting.value = false;
  }
}

async function downloadCatalog() {
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
    actionError.value = resultMessage({ error }, 'YAML 导出失败');
  } finally {
    exportLoading.value = false;
  }
}

function columnColumns(table: DataTableView): DataTableColumns<DataColumnView> {
  return [
    {
      title: '字段',
      key: 'physicalName',
      minWidth: 190,
      render: row =>
        h('div', { class: 'primary-cell' }, [
          h('strong', row.displayName || row.physicalName),
          h('span', row.physicalName)
        ])
    },
    { title: '类型', key: 'dataType', width: 130 },
    {
      title: '业务说明',
      key: 'description',
      minWidth: 210,
      ellipsis: { tooltip: true },
      render: row => row.description || '待完善'
    },
    {
      title: '分类',
      key: 'classification',
      width: 140,
      render: row =>
        h(NSpace, { size: 4 }, () => [
          row.primary ? h(NTag, { size: 'small', type: 'info' }, () => '主键') : null,
          h(NTag, { size: 'small', type: row.sensitive ? 'warning' : 'default' }, () =>
            row.sensitive ? '敏感' : '普通'
          )
        ])
    },
    {
      title: '状态',
      key: 'status',
      width: 90,
      render: row => h(NTag, { size: 'small', type: statusType(row.status) }, () => statusLabel(row.status))
    },
    {
      title: '操作',
      key: 'actions',
      width: 88,
      fixed: 'right',
      render: row =>
        h(
          NButton,
          { size: 'small', quaternary: true, onClick: () => openColumn(table, row) },
          () => '治理'
        )
    }
  ];
}

const inventoryColumns: DataTableColumns<MetadataColumnRow> = [
  {
    title: '数据表',
    key: 'tableName',
    minWidth: 180,
    render: row =>
      h('div', { class: 'primary-cell' }, [h('strong', row.tableName), h('span', row.qualifiedTableName)])
  },
  {
    title: '字段',
    key: 'physicalName',
    minWidth: 190,
    render: row =>
      h('div', { class: 'primary-cell' }, [
        h('strong', row.displayName || row.physicalName),
        h('span', row.physicalName)
      ])
  },
  { title: '类型', key: 'dataType', width: 130 },
  {
    title: '业务说明',
    key: 'description',
    minWidth: 220,
    ellipsis: { tooltip: true },
    render: row => row.description || '待完善'
  },
  {
    title: '分类',
    key: 'sensitive',
    width: 145,
    render: row =>
      h(NSpace, { size: 4 }, () => [
        row.primary ? h(NTag, { size: 'small', type: 'info' }, () => '主键') : null,
        h(NTag, { size: 'small', type: row.sensitive ? 'warning' : 'default' }, () =>
          row.sensitive ? '敏感' : '普通'
        )
      ])
  },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render: row => h(NTag, { size: 'small', type: statusType(row.status) }, () => statusLabel(row.status))
  },
  {
    title: '操作',
    key: 'actions',
    width: 88,
    fixed: 'right',
    render: row => h(NButton, { size: 'small', quaternary: true, onClick: () => openColumn(row.table, row) }, () => '治理')
  }
];
</script>

<template>
  <section class="metadata-governance">
    <div class="summary-grid">
      <article><span>数据表</span><strong>{{ stats.tableCount }}</strong><small>{{ stats.schemaCount }} 个 Schema</small></article>
      <article><span>字段</span><strong>{{ stats.columnCount }}</strong><small>{{ stats.primaryCount }} 个主键字段</small></article>
      <article><span>敏感字段</span><strong>{{ stats.sensitiveCount }}</strong><small>{{ stats.inactiveColumnCount }} 个停用字段</small></article>
      <article><span>治理问题</span><strong>{{ issues.length }}</strong><small>{{ stats.missingMetadataCount }} 个同步缺失项</small></article>
    </div>

    <nav class="view-switch app-tab-list app-tab-list--line" role="tablist" aria-label="元数据视图">
      <button
        v-for="view in viewOptions"
        :key="view.value"
        :data-testid="`metadata-view-${view.value}`"
        type="button"
        role="tab"
        :aria-selected="activeView === view.value"
        class="app-tab"
        :class="{ active: activeView === view.value }"
        @click="activeView = view.value"
      >
        <SvgIcon :icon="view.icon" />
        {{ view.label }}
      </button>
    </nav>

    <div class="governance-toolbar">
      <NInput
        v-model:value="keyword"
        clearable
        placeholder="搜索 Schema、表、字段、类型或描述"
        data-testid="metadata-keyword"
      />
      <NSelect
        v-model:value="tableStatus"
        :options="tableStatusOptions"
        data-testid="metadata-table-status"
      />
      <NSelect
        v-model:value="columnFilter"
        :options="columnFilterOptions"
        data-testid="metadata-column-filter"
      />
      <NButton v-if="hasActiveFilters" quaternary @click="resetFilters">
        <template #icon><SvgIcon icon="lucide:x" /></template>
        清除筛选
      </NButton>
      <NButton secondary title="导出规范元数据 YAML" :loading="exportLoading" @click="downloadCatalog">
        <template #icon><SvgIcon icon="lucide:download" /></template>
        导出 YAML
      </NButton>
    </div>

    <NAlert v-if="actionError" type="error" :bordered="false" closable @close="actionError = null">
      {{ actionError }}
    </NAlert>

    <NSpin :show="loading">
      <template v-if="activeView === 'tables'">
        <div class="result-summary">
          当前显示 {{ visibleMetadata.length }} / {{ stats.tableCount }} 张表，{{ visibleColumns.length }} / {{ stats.columnCount }} 个字段
        </div>
        <NEmpty
          v-if="!visibleMetadata.length"
          description="暂无匹配的元数据，请先同步或调整搜索条件"
        />
        <NCollapse v-else>
          <NCollapseItem v-for="table in visibleMetadata" :key="table.id" :name="table.id">
            <template #header>
              <div class="table-heading">
                <div class="primary-cell">
                  <strong>{{ table.displayName || table.physicalName }}</strong>
                  <span>{{ table.physicalSchema }}.{{ table.physicalName }} · {{ table.columns.length }} 个匹配字段</span>
                </div>
                <NSpace :size="5">
                  <NTag v-if="!table.metadataPresent" size="small" type="error">同步缺失</NTag>
                  <NTag size="small" :type="statusType(table.status)">{{ statusLabel(table.status) }}</NTag>
                </NSpace>
              </div>
            </template>
            <NDescriptions :column="2" bordered size="small">
              <NDescriptionsItem label="显示名称">{{ table.displayName || '待完善' }}</NDescriptionsItem>
              <NDescriptionsItem label="物理类型">{{ table.tableType }}</NDescriptionsItem>
              <NDescriptionsItem label="物理标识">{{ table.tableKey }}</NDescriptionsItem>
              <NDescriptionsItem label="元数据状态">{{ table.metadataPresent ? '已同步' : '物理表缺失' }}</NDescriptionsItem>
              <NDescriptionsItem label="业务描述" :span="2">{{ table.description || '暂无描述' }}</NDescriptionsItem>
            </NDescriptions>
            <div class="table-actions">
              <NButton size="small" type="primary" secondary @click="openTable(table)">
                <template #icon><SvgIcon icon="lucide:pencil" /></template>
                编辑表治理
              </NButton>
            </div>
            <NDataTable
              class="column-table"
              :data="table.columns"
              :columns="columnColumns(table)"
              :row-key="row => row.id"
              :scroll-x="970"
            />
          </NCollapseItem>
        </NCollapse>
      </template>

      <template v-else-if="activeView === 'columns'">
        <div class="result-summary">跨表字段清单，共 {{ visibleColumns.length }} 个匹配字段</div>
        <NDataTable
          v-if="visibleColumns.length"
          :data="visibleColumns"
          :columns="inventoryColumns"
          :row-key="row => row.rowKey"
          :scroll-x="1150"
          max-height="620"
        />
        <NEmpty v-else description="没有符合筛选条件的字段" />
      </template>

      <template v-else>
        <div class="quality-layout">
          <div class="coverage-grid">
            <article>
              <div><span>表业务名称</span><strong>{{ stats.tableNameCoverage }}%</strong></div>
              <div class="coverage-track"><span :style="{ width: `${stats.tableNameCoverage}%` }"></span></div>
              <small>{{ stats.namedTableCount }} / {{ stats.tableCount }} 张表已命名</small>
            </article>
            <article>
              <div><span>表业务描述</span><strong>{{ stats.tableDescriptionCoverage }}%</strong></div>
              <div class="coverage-track"><span :style="{ width: `${stats.tableDescriptionCoverage}%` }"></span></div>
              <small>{{ stats.describedTableCount }} / {{ stats.tableCount }} 张表已描述</small>
            </article>
            <article>
              <div><span>字段业务名称</span><strong>{{ stats.columnNameCoverage }}%</strong></div>
              <div class="coverage-track"><span :style="{ width: `${stats.columnNameCoverage}%` }"></span></div>
              <small>{{ stats.namedColumnCount }} / {{ stats.columnCount }} 个字段已命名</small>
            </article>
            <article>
              <div><span>字段业务描述</span><strong>{{ stats.columnDescriptionCoverage }}%</strong></div>
              <div class="coverage-track"><span :style="{ width: `${stats.columnDescriptionCoverage}%` }"></span></div>
              <small>{{ stats.describedColumnCount }} / {{ stats.columnCount }} 个字段已描述</small>
            </article>
          </div>

          <div class="quality-facts">
            <div>
              <h3>字段类型分布</h3>
              <div class="type-list">
                <NTag v-for="item in stats.dataTypes" :key="item.type" size="small" :bordered="false">
                  {{ item.type }} · {{ item.count }}
                </NTag>
                <span v-if="!stats.dataTypes.length" class="muted">暂无字段类型</span>
              </div>
            </div>
            <div>
              <h3>状态概览</h3>
              <div class="type-list">
                <NTag size="small" type="warning" :bordered="false">{{ stats.inactiveTableCount }} 张停用表</NTag>
                <NTag size="small" type="warning" :bordered="false">{{ stats.inactiveColumnCount }} 个停用字段</NTag>
                <NTag size="small" type="error" :bordered="false">{{ stats.missingMetadataCount }} 个同步缺失项</NTag>
              </div>
            </div>
          </div>

          <div class="issue-list">
            <div class="quality-heading">
              <div><h3>待治理项</h3><p>按同步异常、敏感字段说明和业务语义缺失排序。</p></div>
              <NTag size="small" :bordered="false">{{ issues.length }} 项</NTag>
            </div>
            <button v-for="issue in issues" :key="issue.key" type="button" @click="openIssue(issue)">
              <NTag size="small" :type="issueType(issue.level)" :bordered="false">{{ issueLabel(issue.level) }}</NTag>
              <span><strong>{{ issue.title }}</strong><small>{{ issue.detail }}</small></span>
              <SvgIcon icon="lucide:chevron-right" />
            </button>
            <NEmpty v-if="!issues.length" description="当前目录没有待完善的治理项" />
          </div>
        </div>
      </template>
    </NSpin>

    <NModal
      v-model:show="tableVisible"
      preset="card"
      title="编辑数据表治理"
      :mask-closable="false"
      style="width: min(620px, calc(100vw - 32px))"
    >
      <NForm label-placement="top">
        <NFormItem label="物理表">
          <NInput :value="editingTable ? `${editingTable.physicalSchema}.${editingTable.physicalName}` : ''" disabled />
        </NFormItem>
        <NFormItem label="显示名称">
          <NInput v-model:value="tableForm.displayName" maxlength="255" data-testid="table-display-name" />
        </NFormItem>
        <NFormItem label="业务描述">
          <NInput
            v-model:value="tableForm.description"
            type="textarea"
            :autosize="{ minRows: 4, maxRows: 8 }"
            maxlength="4000"
            data-testid="table-description"
          />
        </NFormItem>
        <NFormItem label="查询状态">
          <NSelect v-model:value="tableForm.status" :options="statusOptions" data-testid="table-status" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="closeTable">取消</NButton>
          <NButton type="primary" :loading="submitting" @click="saveTable">保存</NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal
      v-model:show="columnVisible"
      preset="card"
      title="编辑数据列治理"
      :mask-closable="false"
      style="width: min(620px, calc(100vw - 32px))"
    >
      <NForm label-placement="top">
        <NFormItem label="物理字段">
          <NInput
            :value="editingColumn ? `${editingColumn.table.physicalName}.${editingColumn.column.physicalName}` : ''"
            disabled
          />
        </NFormItem>
        <NFormItem label="显示名称">
          <NInput v-model:value="columnForm.displayName" maxlength="255" data-testid="column-display-name" />
        </NFormItem>
        <NFormItem label="业务描述">
          <NInput
            v-model:value="columnForm.description"
            type="textarea"
            :autosize="{ minRows: 4, maxRows: 8 }"
            maxlength="4000"
            data-testid="column-description"
          />
        </NFormItem>
        <NFormItem label="查询状态">
          <NSelect v-model:value="columnForm.status" :options="statusOptions" data-testid="column-status" />
        </NFormItem>
        <NFormItem label="敏感字段">
          <NSwitch v-model:value="columnForm.sensitive">
            <template #checked>敏感</template>
            <template #unchecked>普通</template>
          </NSwitch>
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="closeColumn">取消</NButton>
          <NButton type="primary" :loading="submitting" @click="saveColumn">保存</NButton>
        </NSpace>
      </template>
    </NModal>
  </section>
</template>

<style scoped lang="scss">
.metadata-governance { display: grid; gap: 14px; }
.summary-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.summary-grid article { min-height: 92px; padding: 13px; border: 1px solid var(--n-border-color); border-radius: 7px; background: var(--n-color); }
.summary-grid span, .summary-grid small { display: block; color: var(--n-text-color-3); font-size: 11px; }
.summary-grid strong { display: block; margin: 7px 0 3px; font-size: 23px; }
.view-switch { margin-bottom: 2px; }
.view-switch button { min-width: 112px; }
.governance-toolbar { display: grid; grid-template-columns: minmax(220px, 1fr) 150px 150px auto auto; align-items: center; gap: 8px; }
.result-summary { margin-bottom: 10px; color: var(--n-text-color-3); font-size: 12px; }
.table-heading { display: flex; width: 100%; align-items: center; justify-content: space-between; gap: 12px; }
.primary-cell { display: grid; min-width: 0; gap: 3px; }
.primary-cell strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.primary-cell span { overflow: hidden; color: var(--n-text-color-3); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.table-actions { display: flex; justify-content: flex-end; margin: 12px 0; }
.column-table { margin-top: 8px; }
.quality-layout { display: grid; gap: 14px; }
.coverage-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 9px; }
.coverage-grid article, .quality-facts, .issue-list { padding: 13px; border: 1px solid var(--n-border-color); border-radius: 7px; background: var(--n-color); }
.coverage-grid article > div:first-child { display: flex; align-items: center; justify-content: space-between; gap: 8px; font-size: 12px; }
.coverage-grid article > div strong { color: var(--n-primary-color); font-size: 15px; }
.coverage-grid small { color: var(--n-text-color-3); font-size: 10px; }
.coverage-track { height: 5px; margin: 9px 0 6px; overflow: hidden; border-radius: 3px; background: var(--n-action-color); }
.coverage-track span { display: block; height: 100%; background: var(--n-primary-color); }
.quality-facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }
.quality-facts h3, .quality-heading h3 { margin: 0 0 8px; font-size: 13px; }
.type-list { display: flex; flex-wrap: wrap; gap: 5px; }
.muted, .quality-heading p { margin: 0; color: var(--n-text-color-3); font-size: 11px; }
.quality-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 7px; }
.issue-list button { display: grid; width: 100%; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 10px; padding: 9px 2px; border: 0; border-bottom: 1px solid var(--n-divider-color); color: var(--n-text-color); text-align: left; background: transparent; cursor: pointer; }
.issue-list button:last-of-type { border-bottom: 0; }
.issue-list button:hover { color: var(--n-primary-color); }
.issue-list button > span { display: grid; min-width: 0; gap: 2px; }
.issue-list button strong, .issue-list button small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.issue-list button strong { font-size: 12px; }
.issue-list button small { color: var(--n-text-color-3); font-size: 10px; }
@media (max-width: 900px) {
  .summary-grid, .coverage-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .governance-toolbar { grid-template-columns: 1fr 1fr; }
  .governance-toolbar :deep(.n-input) { grid-column: 1 / -1; }
}
@media (max-width: 640px) {
  .summary-grid, .coverage-grid, .quality-facts, .governance-toolbar { grid-template-columns: 1fr; }
  .view-switch { overflow-x: auto; }
  .view-switch button { min-width: 104px; }
  .table-heading { align-items: flex-start; flex-direction: column; }
}
</style>
