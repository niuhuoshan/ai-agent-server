<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue';
import dayjs from 'dayjs';
import type { DataTableColumns } from 'naive-ui';
import { NTag } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { fetchPermissionCopyRecords } from '@/service/api';
import type { PermissionCopyRecordView } from '@/service/api';

interface UserOption {
  userId: string;
  userName: string;
  nickName: string;
}

const props = withDefaults(defineProps<{ users?: UserOption[] }>(), { users: () => [] });

const loading = ref(false);
const records = ref<PermissionCopyRecordView[]>([]);
const errorMessage = ref('');

const userMap = computed(() => new Map(props.users.map(user => [user.userId, user])));

function userLabel(id: string | null | undefined) {
  if (!id) return '-';
  const user = userMap.value.get(id);
  return user ? `${user.nickName || user.userName} (@${user.userName})` : `用户 #${id}`;
}

function modeLabel(mode: PermissionCopyRecordView['copyMode']) {
  return ({
    copy_base: '复制基础权限',
    append_missing: '追加缺失权限',
    replace_base: '覆盖基础权限',
    save_template: '另存为权限模板'
  } as Record<string, string>)[mode] || mode;
}

function modeType(mode: PermissionCopyRecordView['copyMode']) {
  return mode === 'save_template' ? 'info' : mode === 'replace_base' ? 'warning' : 'default';
}

function diffNumber(record: PermissionCopyRecordView, key: string) {
  const value = record.diff?.[key];
  return typeof value === 'number' ? value : 0;
}

function excludedCount(record: PermissionCopyRecordView) {
  const rules = record.excluded?.rules;
  return Array.isArray(rules) ? rules.length : 0;
}

function formatTime(value: string) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-';
}

async function load() {
  loading.value = true;
  errorMessage.value = '';
  const result = await fetchPermissionCopyRecords(100);
  if (result.error) {
    errorMessage.value = result.error instanceof Error ? result.error.message : '权限复制历史加载失败';
  } else {
    records.value = result.data;
  }
  loading.value = false;
}

const columns: DataTableColumns<PermissionCopyRecordView> = [
  {
    title: '参考用户', key: 'sourceUserId', minWidth: 190,
    render: row => h('div', { class: 'primary-cell' }, [
      h('strong', userLabel(row.sourceUserId)),
      h('span', row.sourceProfileVersion ? `权限包 v${row.sourceProfileVersion}` : '用户有效基础权限')
    ])
  },
  {
    title: '目标用户', key: 'targetUserId', minWidth: 190,
    render: row => userLabel(row.targetUserId)
  },
  {
    title: '操作方式', key: 'copyMode', width: 150,
    render: row => h(NTag, { size: 'small', bordered: false, type: modeType(row.copyMode) }, () => modeLabel(row.copyMode))
  },
  { title: '新增', key: 'added', width: 80, render: row => diffNumber(row, 'addedRuleCount').toLocaleString() },
  { title: '保留', key: 'retained', width: 80, render: row => diffNumber(row, 'retainedRuleCount').toLocaleString() },
  { title: '排除', key: 'excluded', width: 80, render: row => excludedCount(row).toLocaleString() },
  { title: '执行人', key: 'createdBy', minWidth: 180, render: row => userLabel(row.createdBy) },
  { title: '时间', key: 'createdAt', width: 175, render: row => formatTime(row.createdAt) }
];

defineExpose({ load });
onMounted(load);
</script>

<template>
  <section class="copy-history-pane">
    <div class="pane-toolbar">
      <div>
        <h3>权限复制历史</h3>
        <p>记录参考用户、目标用户、权限差异和排除项，便于追溯基础权限复用。</p>
      </div>
      <NButton secondary :loading="loading" @click="load">
        <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
        刷新
      </NButton>
    </div>
    <NAlert v-if="errorMessage" type="error" :bordered="false" class="history-error">{{ errorMessage }}</NAlert>
    <NDataTable
      v-if="records.length"
      :columns="columns"
      :data="records"
      :loading="loading"
      :scroll-x="1100"
      :row-key="row => row.id"
    />
    <NEmpty v-else-if="!loading && !errorMessage" description="暂无权限复制记录" />
  </section>
</template>

<style scoped lang="scss">
.copy-history-pane { padding: 4px 0; }
.pane-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.pane-toolbar h3 { margin: 0 0 4px; font-size: 15px; }
.pane-toolbar p { margin: 0; color: var(--n-text-color-3); font-size: 13px; }
.history-error { margin-bottom: 16px; }
:deep(.primary-cell) { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
:deep(.primary-cell span) { color: var(--n-text-color-3); font-size: 12px; }
@media (max-width: 640px) { .pane-toolbar { align-items: stretch; flex-direction: column; } }
</style>
