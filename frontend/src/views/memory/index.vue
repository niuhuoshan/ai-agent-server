<script setup lang="ts">
import { computed, h, onMounted, reactive, ref, watch } from 'vue';
import type { DataTableColumns, FormInst, FormRules, SelectOption } from 'naive-ui';
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NDescriptions,
  NDescriptionsItem,
  NForm,
  NFormItem,
  NInput,
  NModal,
  NSelect,
  NSpace,
  NSpin,
  NTabs,
  NTabPane,
  NTag,
  useDialog
} from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { useAuthStore } from '@/store/modules/auth';
import MemoryOperations from './modules/memory-operations.vue';
import {
  clearPortalSessionMemory,
  clearPortalSessionMemoryForUser,
  consolidatePortalMemory,
  deletePortalDailySummary,
  deletePortalDailySummaryForUser,
  deletePortalLongTermMemory,
  deletePortalMemorySummary,
  deletePortalMemorySummaryForUser,
  fetchPortalDailySummaries,
  fetchPortalDailySummariesForUser,
  fetchPortalDailySummaryDetail,
  fetchPortalDailySummaryDetailForUser,
  fetchPortalLongTermMemory,
  fetchPortalMemoryCapabilities,
  fetchPortalMemorySummaries,
  fetchPortalMemorySummariesForUser,
  fetchPortalMemorySummaryDetail,
  fetchPortalMemorySummaryDetailForUser,
  fetchSystemUsers,
  rebuildPortalDailySummary,
  rebuildPortalDailySummaryForUser,
  savePortalLongTermMemory,
  type PortalDailySummary,
  type PortalDailySummaryDetail,
  type PortalMemoryCapabilities,
  type PortalMemorySummary,
  type SystemUserView
} from '@/service/api';

const dialog = useDialog();
const authStore = useAuthStore();
const isAdmin = computed(() => authStore.userInfo.roles.some(role => ['platform_admin', 'superadmin'].includes(role)));
const selectedUserId = ref(authStore.userInfo.userId);
const users = ref<SystemUserView[]>([]);
const isOwnView = computed(() => selectedUserId.value === authStore.userInfo.userId);
const userOptions = computed<SelectOption[]>(() =>
  users.value.map(user => ({
    label: `${user.nickName || user.userName} (${user.userName})`,
    value: user.userId
  }))
);
const selectedUserLabel = computed(() => {
  if (isOwnView.value) return '当前用户';
  const user = users.value.find(item => item.userId === selectedUserId.value);
  return user ? user.nickName || user.userName : `用户 #${selectedUserId.value}`;
});
const loading = ref(false);
const activeTab = ref('sessions');
const summaries = ref<PortalMemorySummary[]>([]);
const daily = ref<PortalDailySummary[]>([]);
const ltm = ref<Record<string, string>>({});
const capabilities = ref<PortalMemoryCapabilities | null>(null);
const search = ref('');
const detailVisible = ref(false);
const detailLoading = ref(false);
const detail = ref<{
  summary: PortalMemorySummary | null;
  history: Array<Record<string, unknown>>;
  has_history: boolean;
} | null>(null);
const detailTitle = ref('');
const dailyDetailVisible = ref(false);
const dailyDetailLoading = ref(false);
const dailyDetail = ref<PortalDailySummaryDetail | null>(null);
const dailyDetailTitle = ref('');
const dailyActionDay = ref<string | null>(null);
const consolidating = ref(false);
const ltmVisible = ref(false);
const ltmSubmitting = ref(false);
const ltmFormRef = ref<FormInst | null>(null);
const ltmEditingKey = ref<string | null>(null);
const ltmForm = reactive({ key: '', value: '' });
const memoryEnabled = computed(() => capabilities.value?.enabled !== false);
const summariesEnabled = computed(() => capabilities.value?.session_summaries.available !== false);
const ltmRules: FormRules = {
  key: [{ required: true, message: '请输入记忆键', trigger: ['input', 'blur'] }],
  value: [{ required: true, message: '请输入记忆内容', trigger: ['input', 'blur'] }]
};

const summaryColumns: DataTableColumns<PortalMemorySummary> = [
  { title: '会话', key: 'conversation_id', minWidth: 230, ellipsis: { tooltip: true } },
  {
    title: '摘要',
    key: 'summary',
    minWidth: 320,
    ellipsis: { tooltip: true },
    render: row => row.summary || row.content || '-'
  },
  { title: '更新时间', key: 'updated_at', width: 190, render: row => row.updated_at || row.created_at || '-' },
  {
    title: '操作',
    key: 'actions',
    width: 170,
    render: row =>
      h(NSpace, { size: 6 }, () => [
        h(NButton, { size: 'small', onClick: () => openDetail(row) }, () => '查看'),
        h(NButton, { size: 'small', type: 'error', tertiary: true, onClick: () => removeSummary(row) }, () => '清除')
      ])
  }
];
const dailyColumns: DataTableColumns<PortalDailySummary> = [
  { title: '日期', key: 'date', width: 160 },
  { title: '会话数', key: 'session_count', width: 100 },
  {
    title: '状态',
    key: 'stored',
    width: 110,
    render: row =>
      h(
        NTag,
        {
          size: 'small',
          type: row.stored ? 'success' : 'default',
          bordered: false
        },
        () => (row.stored ? '已保存' : '实时汇总')
      )
  },
  { title: '摘要', key: 'summary', minWidth: 300, ellipsis: { tooltip: true }, render: row => row.summary || '-' },
  {
    title: '操作',
    key: 'actions',
    width: 250,
    render: row =>
      h(NSpace, { size: 6 }, () => [
        h(NButton, { size: 'small', onClick: () => openDailyDetail(row) }, () => '查看'),
        h(
          NButton,
          {
            size: 'small',
            type: 'primary',
            secondary: true,
            loading: dailyActionDay.value === row.date,
            disabled: capabilities.value?.daily_summaries.rebuild === false,
            onClick: () => rebuildDaily(row)
          },
          () => (row.stored ? '重建' : '生成')
        ),
        h(
          NButton,
          {
            size: 'small',
            type: 'error',
            tertiary: true,
            disabled: !row.stored,
            onClick: () => removeDaily(row)
          },
          () => '删除'
        )
      ])
  }
];
const dailySessionColumns: DataTableColumns<PortalMemorySummary> = [
  { title: '会话', key: 'conversation_id', width: 190, ellipsis: { tooltip: true } },
  {
    title: '摘要',
    key: 'summary',
    minWidth: 320,
    ellipsis: { tooltip: true },
    render: row => row.summary || row.content || '-'
  },
  { title: '更新时间', key: 'updated_at', width: 180, render: row => formatTime(row.updated_at || row.created_at) }
];
const ltmRows = computed(() => Object.entries(ltm.value).map(([key, value]) => ({ key, value })));
const ltmColumns: DataTableColumns<{ key: string; value: string }> = [
  { title: '键', key: 'key', width: 180 },
  { title: '内容', key: 'value', minWidth: 350, ellipsis: { tooltip: true } },
  {
    title: '操作',
    key: 'actions',
    width: 170,
    render: row =>
      h(NSpace, { size: 6 }, () => [
        h(NButton, { size: 'small', onClick: () => openLtm(row.key, row.value) }, () => '编辑'),
        h(NButton, { size: 'small', type: 'error', tertiary: true, onClick: () => removeLtm(row.key) }, () => '删除')
      ])
  }
];

async function load() {
  loading.value = true;
  const params = { keyword: search.value.trim() || undefined, limit: 200 };
  const target = selectedUserId.value;
  const [summaryResult, dailyResult] = await Promise.all([
    isOwnView.value ? fetchPortalMemorySummaries(params) : fetchPortalMemorySummariesForUser(target, params),
    isOwnView.value ? fetchPortalDailySummaries(params) : fetchPortalDailySummariesForUser(target, params)
  ]);
  summaries.value = summaryResult.error ? [] : summaryResult.data;
  daily.value = dailyResult.error ? [] : dailyResult.data;
  if (isOwnView.value) {
    const ltmResult = await fetchPortalLongTermMemory();
    if (!ltmResult.error) ltm.value = ltmResult.data;
  } else {
    ltm.value = {};
  }
  loading.value = false;
}

async function loadCapabilities() {
  const result = await fetchPortalMemoryCapabilities();
  if (!result.error) capabilities.value = result.data;
}

async function loadUsers() {
  if (!isAdmin.value) return;
  const result = await fetchSystemUsers(undefined, 1, 200);
  if (result.error) return;
  users.value = result.data.rows;
}

async function openDetail(row: PortalMemorySummary) {
  if (!row.conversation_id) return;
  detailTitle.value = row.conversation_id;
  detailVisible.value = true;
  detailLoading.value = true;
  const result = isOwnView.value
    ? await fetchPortalMemorySummaryDetail(row.conversation_id)
    : await fetchPortalMemorySummaryDetailForUser(selectedUserId.value, row.conversation_id);
  if (!result.error) detail.value = result.data;
  detailLoading.value = false;
}

function removeSummary(row: PortalMemorySummary) {
  if (!row.conversation_id) return;
  dialog.warning({
    title: '清除会话记忆',
    content: '只清除会话摘要，保留会话正文留痕。确认继续？',
    positiveText: '清除',
    negativeText: '取消',
    onPositiveClick: async () => {
      const result = isOwnView.value
        ? await deletePortalMemorySummary(row.conversation_id as string)
        : await deletePortalMemorySummaryForUser(selectedUserId.value, row.conversation_id as string);
      if (!result.error) {
        window.$message?.success('会话摘要已清除');
        await load();
      }
    }
  });
}

function clearAll() {
  dialog.warning({
    title: '清除全部会话记忆',
    content: '此操作会清除当前用户全部会话摘要和每日摘要，不能恢复。',
    positiveText: '确认清除',
    negativeText: '取消',
    onPositiveClick: async () => {
      const result = isOwnView.value
        ? await clearPortalSessionMemory()
        : await clearPortalSessionMemoryForUser(selectedUserId.value);
      if (!result.error) {
        window.$message?.success(`${selectedUserLabel.value}的会话记忆已清除`);
        await load();
      }
    }
  });
}

async function openDailyDetail(row: PortalDailySummary) {
  dailyDetailTitle.value = row.date;
  dailyDetailVisible.value = true;
  dailyDetailLoading.value = true;
  const result = isOwnView.value
    ? await fetchPortalDailySummaryDetail(row.date)
    : await fetchPortalDailySummaryDetailForUser(selectedUserId.value, row.date);
  if (!result.error) dailyDetail.value = result.data;
  dailyDetailLoading.value = false;
}

async function rebuildDaily(row: PortalDailySummary) {
  if (!summariesEnabled.value) return;
  dailyActionDay.value = row.date;
  const result = isOwnView.value
    ? await rebuildPortalDailySummary(row.date)
    : await rebuildPortalDailySummaryForUser(selectedUserId.value, row.date);
  dailyActionDay.value = null;
  if (result.error) return;
  window.$message?.success(row.stored ? '每日摘要已重建' : '每日摘要已生成');
  await load();
  await openDailyDetail(row);
}

async function consolidateAll() {
  if (!isOwnView.value || !summariesEnabled.value) return;
  consolidating.value = true;
  const result = await consolidatePortalMemory();
  consolidating.value = false;
  if (result.error) return;
  window.$message?.success(
    `已整理 ${result.data.days_processed} 天，新增 ${result.data.daily_summaries_created} 条，更新 ${result.data.daily_summaries_updated} 条`
  );
  await load();
}

function removeDaily(row: PortalDailySummary) {
  if (!row.stored) return;
  dialog.warning({
    title: '删除每日摘要',
    content: `确认删除 ${row.date} 的已保存摘要？会话摘要不会被删除。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      const result = isOwnView.value
        ? await deletePortalDailySummary(row.date)
        : await deletePortalDailySummaryForUser(selectedUserId.value, row.date);
      if (!result.error) {
        window.$message?.success('每日摘要已删除');
        await load();
      }
    }
  });
}

function openLtm(key = '', value = '') {
  ltmEditingKey.value = key || null;
  Object.assign(ltmForm, { key, value });
  ltmVisible.value = true;
}

async function saveLtm() {
  if (!memoryEnabled.value) return;
  try {
    await ltmFormRef.value?.validate();
  } catch {
    return;
  }
  ltmSubmitting.value = true;
  if (ltmEditingKey.value && ltmEditingKey.value !== ltmForm.key) {
    const removed = await deletePortalLongTermMemory(ltmEditingKey.value);
    if (removed.error) {
      ltmSubmitting.value = false;
      return;
    }
  }
  const result = await savePortalLongTermMemory(ltmForm.key.trim(), ltmForm.value.trim());
  if (!result.error) {
    ltmVisible.value = false;
    window.$message?.success('长期偏好已保存');
    await load();
  }
  ltmSubmitting.value = false;
}

function removeLtm(key: string) {
  dialog.warning({
    title: '删除长期偏好',
    content: `确认删除“${key}”？`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      const result = await deletePortalLongTermMemory(key);
      if (!result.error) {
        window.$message?.success('长期偏好已删除');
        await load();
      }
    }
  });
}

function formatTime(value?: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN') : '-';
}

watch(selectedUserId, async () => {
  if (!isOwnView.value && activeTab.value === 'ltm') activeTab.value = 'sessions';
  detailVisible.value = false;
  dailyDetailVisible.value = false;
  await load();
});

watch(activeTab, value => {
  if (value === 'operations' && !isOwnView.value) {
    selectedUserId.value = authStore.userInfo.userId;
  }
});

onMounted(async () => {
  await Promise.all([loadUsers(), loadCapabilities()]);
  await load();
});
</script>

<template>
  <div class="h-full flex-col-stretch gap-12px overflow-hidden p-16px">
    <NCard :bordered="false" size="small" class="shrink-0">
      <template #header>
        <div class="flex flex-wrap items-center gap-8px">
          <SvgIcon icon="lucide:brain" />
          <span>个人记忆</span>
          <NTag size="small" :bordered="false">{{ selectedUserLabel }}</NTag>
          <NTag v-if="capabilities?.relational_store.available" size="small" type="success" :bordered="false">
            关系型存储可用
          </NTag>
          <NTag v-if="!memoryEnabled" size="small" type="error" :bordered="false">
            记忆服务已关闭
          </NTag>
          <NTag
            v-if="capabilities && !capabilities.intelligent_consolidation.available"
            size="small"
            type="warning"
            :bordered="false"
            :title="capabilities.intelligent_consolidation.reason"
          >
            智能整理未配置
          </NTag>
        </div>
      </template>
      <template #header-extra>
        <NSpace wrap>
          <NSelect
            v-if="isAdmin && activeTab !== 'operations'"
            v-model:value="selectedUserId"
            :options="userOptions"
            filterable
            placeholder="选择用户"
            style="width: 220px"
          />
          <NInput
            v-model:value="search"
            clearable
            placeholder="搜索记忆内容"
            style="width: 220px"
            @keyup.enter="load"
          />
          <NButton secondary :loading="loading" @click="load">
            <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
            刷新
          </NButton>
          <NButton type="error" secondary @click="clearAll">
            <template #icon><SvgIcon icon="lucide:trash-2" /></template>
            清除会话摘要
          </NButton>
        </NSpace>
      </template>
    </NCard>
    <NAlert v-if="!memoryEnabled" type="warning" :show-icon="false" class="shrink-0">
      记忆服务已由平台配置关闭，运行时不会读取或写入个人记忆；历史记录仍可按权限查看和清理。
    </NAlert>
    <NAlert v-else-if="!summariesEnabled" type="info" :show-icon="false" class="shrink-0">
      会话与每日摘要已关闭，已有记忆仍保留，可继续查看和清理。
    </NAlert>
    <NCard :bordered="false" class="min-h-0 flex-1" content-class="h-full flex-col-stretch">
      <NTabs v-model:value="activeTab" type="line" class="min-h-0 flex-col-stretch">
        <NTabPane name="sessions" tab="会话摘要" display-directive="show">
          <NDataTable
            :columns="summaryColumns"
            :data="summaries"
            :loading="loading"
            :single-line="false"
            flex-height
            class="h-full"
          />
        </NTabPane>
        <NTabPane name="daily" tab="每日摘要" display-directive="show">
          <div v-if="isOwnView && capabilities?.relational_consolidation?.available" class="mb-12px flex justify-end">
            <NButton secondary :loading="consolidating" @click="consolidateAll">
              <template #icon><SvgIcon icon="lucide:layers-3" /></template>
              整理全部
            </NButton>
          </div>
          <NAlert
            v-if="capabilities && !capabilities.intelligent_consolidation.available"
            type="info"
            :show-icon="false"
            class="mb-12px"
          >
            {{ capabilities.intelligent_consolidation.reason }}；当前可使用关系型会话摘要生成每日汇总。
          </NAlert>
          <NDataTable
            :columns="dailyColumns"
            :data="daily"
            :loading="loading"
            :single-line="false"
            flex-height
            class="h-full"
          />
        </NTabPane>
        <NTabPane v-if="isOwnView" name="ltm" tab="长期偏好" display-directive="show">
          <div class="mb-12px">
            <NButton type="primary" :disabled="!memoryEnabled" @click="openLtm()">
              <template #icon><SvgIcon icon="lucide:plus" /></template>
              新增偏好
            </NButton>
          </div>
          <NDataTable
            :columns="ltmColumns"
            :data="ltmRows"
            :loading="loading"
            :single-line="false"
            flex-height
            class="h-full"
          />
        </NTabPane>
        <NTabPane v-if="isAdmin" name="operations" tab="运维与检索" display-directive="show">
          <MemoryOperations />
        </NTabPane>
      </NTabs>
    </NCard>
    <NModal
      v-model:show="detailVisible"
      preset="card"
      :title="`会话记忆 · ${detailTitle}`"
      style="width: min(780px, 94vw)"
    >
      <NSpin :show="detailLoading">
        <NDescriptions v-if="detail" :column="1" bordered label-placement="left">
          <NDescriptionsItem label="摘要">
            {{ detail.summary?.summary || detail.summary?.content || '暂无摘要' }}
          </NDescriptionsItem>
          <NDescriptionsItem label="历史消息">
            <div class="max-h-360px overflow-y-auto whitespace-pre-wrap">
              {{
                detail.history.map(item => `${String(item.role || '')}: ${String(item.content || '')}`).join('\n\n') ||
                '暂无历史消息'
              }}
            </div>
          </NDescriptionsItem>
        </NDescriptions>
      </NSpin>
    </NModal>
    <NModal
      v-model:show="dailyDetailVisible"
      preset="card"
      :title="`每日摘要 · ${dailyDetailTitle}`"
      style="width: min(900px, 94vw)"
    >
      <NSpin :show="dailyDetailLoading">
        <template v-if="dailyDetail">
          <NDescriptions :column="2" bordered label-placement="left">
            <NDescriptionsItem label="状态">
              <NTag size="small" :type="dailyDetail.summary.stored ? 'success' : 'default'" :bordered="false">
                {{ dailyDetail.summary.stored ? '已保存' : '实时汇总' }}
              </NTag>
            </NDescriptionsItem>
            <NDescriptionsItem label="会话数">{{ dailyDetail.summary.session_count }}</NDescriptionsItem>
            <NDescriptionsItem label="摘要" :span="2">
              <div class="whitespace-pre-wrap">{{ dailyDetail.summary.summary || '暂无摘要' }}</div>
            </NDescriptionsItem>
          </NDescriptions>
          <div class="mb-8px mt-16px font-600">会话摘要</div>
          <NDataTable
            :columns="dailySessionColumns"
            :data="dailyDetail.sessions"
            :single-line="false"
            :max-height="360"
          />
        </template>
      </NSpin>
    </NModal>
    <NModal
      v-model:show="ltmVisible"
      preset="card"
      :title="ltmEditingKey ? '编辑长期偏好' : '新增长期偏好'"
      style="width: min(560px, 94vw)"
    >
      <NForm ref="ltmFormRef" :model="ltmForm" :rules="ltmRules" label-placement="top">
        <NFormItem label="键" path="key"><NInput v-model:value="ltmForm.key" maxlength="128" /></NFormItem>
        <NFormItem label="内容" path="value">
          <NInput
            v-model:value="ltmForm.value"
            type="textarea"
            maxlength="4000"
            :autosize="{ minRows: 4, maxRows: 10 }"
          />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="ltmVisible = false">取消</NButton>
          <NButton type="primary" :loading="ltmSubmitting" @click="saveLtm">保存</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>
