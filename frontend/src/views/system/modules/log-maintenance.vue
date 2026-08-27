<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue';
import dayjs from 'dayjs';
import type { DataTableColumns } from 'naive-ui';
import { NTag } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  executeLogCleanup,
  fetchLogMaintenanceRuns,
  fetchLogPartitionStatus,
  fetchLogRetentionConfig,
  previewLogCleanup,
  updateLogRetentionConfig
} from '@/service/api';
import type {
  LogCleanupPreview,
  LogCleanupResult,
  LogMaintenanceRun,
  LogPartition,
  LogPartitionStatus,
  LogRetentionConfig,
  LogTableStorage
} from '@/service/api';
import {
  formatStorageBytes,
  logStorageModeLabel,
  maintenanceStatusLabel,
  maintenanceStatusType,
  numericSummary,
  requestErrorStatus,
  requestErrorText
} from '../log-maintenance';

type PartitionRow = LogPartition & Pick<LogTableStorage, 'tableName' | 'displayName' | 'storageMode'>;

const loading = ref(false);
const saving = ref(false);
const previewing = ref(false);
const cleaning = ref(false);
const loadError = ref('');
const config = ref<LogRetentionConfig | null>(null);
const storage = ref<LogPartitionStatus | null>(null);
const runs = ref<LogMaintenanceRun[]>([]);
const retentionDays = ref<number | null>(null);
const changeReason = ref('');
const preview = ref<LogCleanupPreview | null>(null);
const previewVisible = ref(false);
const acknowledged = ref(false);
const cleanupError = ref('');
const result = ref<LogCleanupResult | null>(null);
const resultVisible = ref(false);

const configDirty = computed(() =>
  Boolean(config.value && retentionDays.value !== null && retentionDays.value !== config.value.retentionDays)
);
const expiredRows = computed(() => storage.value?.tables.reduce((total, table) => total + table.expiredRows, 0) || 0);
const totalBytes = computed(() => storage.value?.tables.reduce((total, table) => total + table.sizeBytes, 0) || 0);
const partitionedTables = computed(
  () => storage.value?.tables.filter(table => table.storageMode === 'partitioned').length || 0
);
const partitionRows = computed<PartitionRow[]>(
  () =>
    storage.value?.tables.flatMap(table =>
      table.partitions.map(partition => ({
        ...partition,
        tableName: table.tableName,
        displayName: table.displayName,
        storageMode: table.storageMode
      }))
    ) || []
);

function formatTime(value: string | null | undefined, fallback = '-') {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : fallback;
}

function resetConfiguration() {
  if (!config.value) return;
  retentionDays.value = config.value.retentionDays;
  changeReason.value = '';
}

async function load() {
  loading.value = true;
  loadError.value = '';
  const [configResult, storageResult, runsResult] = await Promise.all([
    fetchLogRetentionConfig(),
    fetchLogPartitionStatus(),
    fetchLogMaintenanceRuns(20)
  ]);
  const errors: string[] = [];
  if (configResult.error) {
    errors.push(requestErrorText(configResult.error, '保留策略加载失败'));
  } else {
    config.value = configResult.data;
    retentionDays.value = configResult.data.retentionDays;
    changeReason.value = '';
  }
  if (storageResult.error) {
    errors.push(requestErrorText(storageResult.error, '日志存储状态加载失败'));
  } else {
    storage.value = storageResult.data;
  }
  if (runsResult.error) {
    errors.push(requestErrorText(runsResult.error, '维护记录加载失败'));
  } else {
    runs.value = runsResult.data;
  }
  loadError.value = errors.join('；');
  loading.value = false;
}

async function saveConfiguration() {
  if (!config.value || retentionDays.value === null) return;
  if (
    !Number.isInteger(retentionDays.value) ||
    retentionDays.value < config.value.minRetentionDays ||
    retentionDays.value > config.value.maxRetentionDays
  ) {
    window.$message?.warning(`保留天数需为 ${config.value.minRetentionDays}-${config.value.maxRetentionDays} 的整数`);
    return;
  }
  if (changeReason.value.trim().length < 2) {
    window.$message?.warning('请填写至少 2 个字符的变更原因');
    return;
  }
  saving.value = true;
  const response = await updateLogRetentionConfig({
    retentionDays: retentionDays.value,
    expectedRevision: config.value.revisionNo,
    changeReason: changeReason.value.trim()
  });
  if (response.error) {
    const message = requestErrorText(response.error, '日志保留策略保存失败');
    window.$message?.error(message);
    if (requestErrorStatus(response.error) === 409) await load();
  } else {
    config.value = response.data;
    retentionDays.value = response.data.retentionDays;
    changeReason.value = '';
    window.$message?.success('日志保留策略已保存');
    const statusResult = await fetchLogPartitionStatus();
    if (!statusResult.error) storage.value = statusResult.data;
  }
  saving.value = false;
}

async function openCleanupPreview() {
  previewing.value = true;
  cleanupError.value = '';
  acknowledged.value = false;
  const response = await previewLogCleanup();
  if (response.error) {
    window.$message?.error(requestErrorText(response.error, '日志清理预览失败'));
  } else {
    preview.value = response.data;
    previewVisible.value = true;
  }
  previewing.value = false;
}

async function confirmCleanup() {
  if (!preview.value || !acknowledged.value) return;
  cleaning.value = true;
  cleanupError.value = '';
  const response = await executeLogCleanup(preview.value.confirmationToken);
  if (response.error) {
    cleanupError.value = requestErrorText(response.error, '日志清理执行失败');
  } else {
    result.value = response.data;
    previewVisible.value = false;
    resultVisible.value = true;
    window.$message?.success(response.data.message);
    await load();
  }
  cleaning.value = false;
}

function storageModeType(mode: string) {
  return mode === 'partitioned' ? 'success' : 'warning';
}

const partitionColumns: DataTableColumns<PartitionRow> = [
  {
    title: '日志表',
    key: 'displayName',
    minWidth: 170,
    render: row => h('div', { class: 'table-name-cell' }, [h('strong', row.displayName), h('small', row.tableName)])
  },
  {
    title: '存储模式',
    key: 'storageMode',
    width: 120,
    render: row =>
      h(NTag, { size: 'small', type: storageModeType(row.storageMode) }, () => logStorageModeLabel(row.storageMode))
  },
  { title: '物理关系', key: 'partitionName', minWidth: 230, ellipsis: { tooltip: true } },
  { title: '边界', key: 'boundExpression', minWidth: 260, ellipsis: { tooltip: true } },
  { title: '估算行数', key: 'estimatedRows', width: 120, render: row => row.estimatedRows.toLocaleString() },
  { title: '占用空间', key: 'sizeBytes', width: 120, render: row => formatStorageBytes(row.sizeBytes) },
  { title: '最早记录', key: 'oldestAt', width: 165, render: row => formatTime(row.oldestAt) },
  {
    title: '过期记录',
    key: 'expiredRows',
    width: 120,
    render: row =>
      h(NTag, { size: 'small', type: row.expiredRows > 0 ? 'warning' : 'default' }, () =>
        row.expiredRows.toLocaleString()
      )
  },
  {
    title: '清理方式',
    key: 'cleanupMode',
    width: 130,
    render: row =>
      row.removableCandidate
        ? h(NTag, { size: 'small', type: 'error' }, () => '删除整月分区')
        : row.expiredRows > 0
          ? '微批删除'
          : '-'
  }
];

const runColumns: DataTableColumns<LogMaintenanceRun> = [
  { title: '运行 ID', key: 'runId', width: 165, ellipsis: { tooltip: true } },
  {
    title: '触发',
    key: 'triggerType',
    width: 95,
    render: row => (row.triggerType === 'scheduled' ? '定时' : '手动')
  },
  {
    title: '状态',
    key: 'status',
    width: 115,
    render: row =>
      h(NTag, { size: 'small', type: maintenanceStatusType(row.status) }, () => maintenanceStatusLabel(row.status))
  },
  { title: '保留期', key: 'retentionDays', width: 90, render: row => `${row.retentionDays} 天` },
  {
    title: '清理结果',
    key: 'summary',
    minWidth: 230,
    render: row => {
      if (row.errorMessage) return h('span', { class: 'run-error' }, row.errorMessage);
      const deleted = numericSummary(row.summary, 'deletedRows');
      const dropped = numericSummary(row.summary, 'droppedRows');
      return `微批 ${deleted.toLocaleString()} 行，分区 ${dropped.toLocaleString()} 行`;
    }
  },
  { title: '开始时间', key: 'startedAt', width: 165, render: row => formatTime(row.startedAt, '尚未执行') },
  { title: '完成时间', key: 'finishedAt', width: 165, render: row => formatTime(row.finishedAt) }
];

onMounted(load);
</script>

<template>
  <section class="logs-pane">
    <div class="pane-toolbar">
      <div>
        <h3>日志留存</h3>
        <NSpace v-if="config" size="small" align="center">
          <NTag size="small" type="info">策略 v{{ config.revisionNo }}</NTag>
          <span>自动维护：{{ config.automaticSchedule }}</span>
        </NSpace>
      </div>
      <NSpace>
        <NButton secondary :loading="loading" @click="load">
          <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
          刷新
        </NButton>
        <NButton
          type="error"
          secondary
          :loading="previewing"
          :disabled="!storage || configDirty"
          @click="openCleanupPreview"
        >
          <template #icon><SvgIcon icon="lucide:trash-2" /></template>
          清理过期日志
        </NButton>
      </NSpace>
    </div>

    <NAlert v-if="loadError" type="error" :bordered="false" class="status-alert">
      <NSpace justify="space-between" align="center">
        <span>{{ loadError }}</span>
        <NButton size="small" @click="load">重试</NButton>
      </NSpace>
    </NAlert>

    <NSpin :show="loading">
      <section class="policy-section">
        <div class="section-heading">
          <div>
            <h4>保留策略</h4>
            <span v-if="config">更新于 {{ formatTime(config.updatedAt) }}</span>
          </div>
          <NTag v-if="config" size="small">{{ config.minRetentionDays }}-{{ config.maxRetentionDays }} 天</NTag>
        </div>
        <NGrid :cols="12" :x-gap="16" :y-gap="8" responsive="screen" item-responsive>
          <NFormItemGi span="12 s:4 m:3" label="在线保留天数">
            <NInputNumber
              v-model:value="retentionDays"
              :min="config?.minRetentionDays || 1"
              :max="config?.maxRetentionDays || 3650"
              :precision="0"
              class="w-full"
            >
              <template #suffix>天</template>
            </NInputNumber>
          </NFormItemGi>
          <NFormItemGi span="12 s:8 m:7" label="变更原因">
            <NInput
              v-model:value="changeReason"
              maxlength="500"
              show-count
              placeholder="例如：调整企业审计合规留存周期"
            />
          </NFormItemGi>
          <NFormItemGi span="12 m:2" label=" ">
            <NSpace :wrap="false" class="policy-actions">
              <NButton :disabled="!configDirty || saving" @click="resetConfiguration">撤销</NButton>
              <NButton
                type="primary"
                :disabled="!configDirty || changeReason.trim().length < 2"
                :loading="saving"
                @click="saveConfiguration"
              >
                <template #icon><SvgIcon icon="lucide:save" /></template>
                保存
              </NButton>
            </NSpace>
          </NFormItemGi>
        </NGrid>
      </section>

      <section v-if="storage" class="storage-section">
        <div class="metric-grid">
          <div>
            <span>数据库</span>
            <strong>{{ storage.databaseType }}</strong>
          </div>
          <div>
            <span>物理占用</span>
            <strong>{{ formatStorageBytes(totalBytes) }}</strong>
          </div>
          <div>
            <span>当前过期记录</span>
            <strong :class="{ warning: expiredRows > 0 }">{{ expiredRows.toLocaleString() }}</strong>
          </div>
          <div>
            <span>分区表</span>
            <strong>{{ partitionedTables }} / {{ storage.tables.length }}</strong>
          </div>
        </div>

        <NAlert v-if="partitionedTables < storage.tables.length" type="warning" :bordered="false" class="status-alert">
          普通表使用每表每轮最多
          {{ storage.maxRowsPerTablePerRun.toLocaleString() }} 行的微批删除；按月分区表会优先删除完全过期的物理分区。
        </NAlert>
        <NAlert v-else type="success" :bordered="false" class="status-alert">
          月分区已启用，系统会预建当前月及未来 {{ storage.futureMonthsPrepared }} 个月分区。
        </NAlert>

        <div class="section-heading">
          <div>
            <h4>物理存储</h4>
            <span>截止线 {{ formatTime(storage.cutoffAt) }}</span>
          </div>
          <span>检查于 {{ formatTime(storage.checkedAt) }}</span>
        </div>
        <NDataTable
          :columns="partitionColumns"
          :data="partitionRows"
          :row-key="row => `${row.tableName}:${row.partitionName}`"
          :scroll-x="1450"
          :pagination="partitionRows.length > 20 ? { pageSize: 20 } : false"
        />
        <NEmpty v-if="partitionRows.length === 0" description="未读取到日志物理关系" />
      </section>

      <section class="history-section">
        <div class="section-heading">
          <div>
            <h4>维护记录</h4>
            <span>手动确认与每日自动维护</span>
          </div>
        </div>
        <NDataTable
          :columns="runColumns"
          :data="runs"
          :row-key="row => row.runId"
          :scroll-x="1040"
          :pagination="runs.length > 10 ? { pageSize: 10 } : false"
        />
        <NEmpty v-if="!loading && runs.length === 0" description="暂无日志维护记录" />
      </section>
    </NSpin>

    <NModal
      v-model:show="previewVisible"
      preset="card"
      title="确认清理过期日志"
      style="width: min(760px, calc(100vw - 32px))"
      :mask-closable="!cleaning"
      :closable="!cleaning"
    >
      <template v-if="preview">
        <NDescriptions :column="2" bordered label-placement="top" class="preview-summary">
          <NDescriptionsItem label="保留期">{{ preview.retentionDays }} 天</NDescriptionsItem>
          <NDescriptionsItem label="过期截止线">{{ formatTime(preview.cutoffAt) }}</NDescriptionsItem>
          <NDescriptionsItem label="过期记录">{{ preview.expiredRows.toLocaleString() }} 行</NDescriptionsItem>
          <NDescriptionsItem label="可删除整月分区">{{ preview.removablePartitions }} 个</NDescriptionsItem>
          <NDescriptionsItem label="确认有效期">{{ formatTime(preview.confirmationExpiresAt) }}</NDescriptionsItem>
          <NDescriptionsItem label="运行 ID">{{ preview.runId }}</NDescriptionsItem>
        </NDescriptions>
        <NAlert v-if="preview.expiredRows === 0" type="info" :bordered="false" class="status-alert">
          当前没有超过保留期的日志，仍会执行未来分区检查。
        </NAlert>
        <NAlert v-if="preview.mayRequireMultipleRuns" type="warning" :bordered="false" class="status-alert">
          记录数量超过单轮安全上限，本次完成后会保留“部分完成”状态。
        </NAlert>
        <ul class="warning-list">
          <li v-for="warning in preview.warnings" :key="warning">{{ warning }}</li>
        </ul>
        <NAlert v-if="cleanupError" type="error" :bordered="false" class="status-alert">{{ cleanupError }}</NAlert>
        <NCheckbox v-model:checked="acknowledged" :disabled="cleaning">
          我已确认清理范围，并知悉过期日志和物理分区不可恢复
        </NCheckbox>
      </template>
      <template #footer>
        <NSpace justify="end">
          <NButton :disabled="cleaning" @click="previewVisible = false">取消</NButton>
          <NButton type="error" :disabled="!acknowledged || !preview" :loading="cleaning" @click="confirmCleanup">
            <template #icon><SvgIcon icon="lucide:trash-2" /></template>
            确认不可逆清理
          </NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal
      v-model:show="resultVisible"
      preset="card"
      title="日志维护结果"
      style="width: min(760px, calc(100vw - 32px))"
    >
      <template v-if="result">
        <NAlert :type="result.remainingExpiredRows ? 'warning' : 'success'" :bordered="false" class="status-alert">
          {{ result.message }}
        </NAlert>
        <NDescriptions :column="2" bordered label-placement="top">
          <NDescriptionsItem label="运行 ID">{{ result.runId }}</NDescriptionsItem>
          <NDescriptionsItem label="状态">
            <NTag size="small" :type="maintenanceStatusType(result.status)">
              {{ maintenanceStatusLabel(result.status) }}
            </NTag>
          </NDescriptionsItem>
          <NDescriptionsItem label="微批删除">{{ result.deletedRows.toLocaleString() }} 行</NDescriptionsItem>
          <NDescriptionsItem label="分区回收">{{ result.droppedRows.toLocaleString() }} 行</NDescriptionsItem>
          <NDescriptionsItem label="新建分区">{{ result.createdPartitions.length }} 个</NDescriptionsItem>
          <NDescriptionsItem label="删除分区">{{ result.droppedPartitions.length }} 个</NDescriptionsItem>
        </NDescriptions>
        <div v-if="result.createdPartitions.length || result.droppedPartitions.length" class="partition-result">
          <NSpace v-if="result.createdPartitions.length" size="small">
            <span>新建</span>
            <NTag v-for="name in result.createdPartitions" :key="name" size="small" type="success">{{ name }}</NTag>
          </NSpace>
          <NSpace v-if="result.droppedPartitions.length" size="small">
            <span>删除</span>
            <NTag v-for="name in result.droppedPartitions" :key="name" size="small" type="error">{{ name }}</NTag>
          </NSpace>
        </div>
      </template>
      <template #footer>
        <NSpace justify="end"><NButton type="primary" @click="resultVisible = false">完成</NButton></NSpace>
      </template>
    </NModal>
  </section>
</template>

<style scoped lang="scss">
.logs-pane {
  padding-top: 8px;
}
.pane-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.pane-toolbar h3 {
  margin: 0 0 4px;
  font-size: 16px;
}
.pane-toolbar span,
.section-heading span {
  color: var(--n-text-color-3);
  font-size: 13px;
}
.status-alert {
  margin: 12px 0;
}
.policy-section,
.storage-section,
.history-section {
  padding: 18px 0;
  border-top: 1px solid var(--n-border-color);
}
.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}
.section-heading h4 {
  margin: 0 0 3px;
  font-size: 15px;
}
.policy-actions {
  width: 100%;
}
.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border: 1px solid var(--n-border-color);
  margin-bottom: 12px;
}
.metric-grid > div {
  min-width: 0;
  padding: 14px 16px;
  border-right: 1px solid var(--n-border-color);
}
.metric-grid > div:last-child {
  border-right: 0;
}
.metric-grid span {
  display: block;
  color: var(--n-text-color-3);
  font-size: 12px;
}
.metric-grid strong {
  display: block;
  margin-top: 5px;
  font-size: 20px;
  font-weight: 600;
}
.metric-grid strong.warning {
  color: var(--n-warning-color);
}
:deep(.table-name-cell) {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
:deep(.table-name-cell small) {
  color: var(--n-text-color-3);
}
:deep(.run-error) {
  color: var(--n-error-color);
}
.preview-summary {
  margin-bottom: 12px;
}
.warning-list {
  margin: 12px 0;
  padding-left: 20px;
  color: var(--n-text-color-2);
  line-height: 1.8;
}
.partition-result {
  display: grid;
  gap: 10px;
  margin-top: 14px;
}
@media (max-width: 860px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .metric-grid > div:nth-child(2) {
    border-right: 0;
  }
  .metric-grid > div:nth-child(-n + 2) {
    border-bottom: 1px solid var(--n-border-color);
  }
}
@media (max-width: 640px) {
  .pane-toolbar {
    align-items: stretch;
    flex-direction: column;
  }
  .pane-toolbar > :deep(.n-space) {
    justify-content: flex-end;
  }
  .section-heading {
    align-items: flex-start;
    flex-direction: column;
  }
  .metric-grid {
    grid-template-columns: 1fr;
  }
  .metric-grid > div {
    border-right: 0;
    border-bottom: 1px solid var(--n-border-color);
  }
  .metric-grid > div:last-child {
    border-bottom: 0;
  }
}
</style>
