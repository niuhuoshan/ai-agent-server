<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import {
  NAlert,
  NButton,
  NCard,
  NDescriptions,
  NDescriptionsItem,
  NEmpty,
  NForm,
  NFormItem,
  NGrid,
  NGi,
  NInput,
  NSelect,
  NSpace,
  NSpin,
  NTabPane,
  NTabs,
  NTag,
  useDialog,
  useMessage
} from 'naive-ui';
import type { FormRules, SelectOption } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import NotificationSettings from './modules/notification-settings.vue';
import { notificationRequestError } from './modules/notification-settings-state';
import {
  deletePortalInboxNotification,
  deleteReadPortalInboxNotifications,
  clearBrowserProfiles,
  markAllPortalInboxNotificationsRead,
  markPortalInboxNotificationRead,
  fetchEffectiveUiPermissions,
  fetchPortalInboxNotifications,
  fetchPortalInboxUnreadCount,
  fetchPortalDataPortalHome,
  fetchPortalLongTermMemory,
  fetchPortalMemorySummaries,
  fetchPortalQuota,
  fetchUserProfile,
  updateUserPassword,
  updateUserProfile,
  type EffectiveUiPermissions,
  type PortalDataPortalHome,
  type PortalMemorySummary,
  type PortalInboxItem,
  type PortalQuotaStatus,
  type UserProfile
} from '@/service/api';

type PersonalTab = 'info' | 'permissions' | 'memory' | 'tokens' | 'notifications' | 'data' | 'skills' | 'mcp' | 'tasks';

const message = useMessage();
const dialog = useDialog();
const router = useRouter();
const activeTab = ref<PersonalTab>('info');
const loading = ref(false);
const profile = ref<UserProfile | null>(null);
const permissions = ref<EffectiveUiPermissions>({ buttons: [], routes: [] });
const summaries = ref<PortalMemorySummary[]>([]);
const longTermMemory = ref<Record<string, string>>({});
const quota = ref<PortalQuotaStatus | null>(null);
const dataPortal = ref<PortalDataPortalHome | null>(null);
const notifications = ref<PortalInboxItem[]>([]);
const unreadCount = ref(0);
const inboxError = ref('');
const deletingNotificationId = ref<string | null>(null);
const clearingRead = ref(false);
const markingRead = ref(false);
const profileForm = reactive({ nickName: '', email: '', phoneNumber: '', gender: '2' });
const passwordForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' });
const profileSaving = ref(false);
const passwordSaving = ref(false);
const clearingBrowserProfiles = ref(false);

const genderOptions: SelectOption[] = [
  { label: '男', value: '0' },
  { label: '女', value: '1' },
  { label: '未知', value: '2' }
];

const passwordRules: FormRules = {
  oldPassword: { required: true, message: '请输入当前密码', trigger: ['input', 'blur'] },
  newPassword: [
    { required: true, message: '请输入新密码', trigger: ['input', 'blur'] },
    { min: 6, message: '新密码至少 6 位', trigger: ['input', 'blur'] }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: ['input', 'blur'] },
    {
      validator: (_rule, value) => value === passwordForm.newPassword,
      message: '两次输入的新密码不一致',
      trigger: ['input', 'blur']
    }
  ]
};

const memoryCount = computed(() => summaries.value.length + Object.keys(longTermMemory.value).length);
const quotaPercent = computed(() => {
  if (quota.value?.limit_tokens == null) return 0;
  if (quota.value.limit_tokens <= 0) return 100;
  return Math.min(100, Math.round((quota.value.used_tokens / quota.value.limit_tokens) * 100));
});
const notificationRows = computed(() =>
  notifications.value.map(item => ({
    id: String(item.id),
    title: item.title || '系统通知',
    content: item.content || '',
    createdAt: item.created_at,
    read: Boolean(item.read_at)
  }))
);
const hasReadNotifications = computed(() => notificationRows.value.some(item => item.read));
const hasUnreadNotifications = computed(() => notificationRows.value.some(item => !item.read));

function formatTime(value: string) {
  return value && value !== 'undefined' ? new Date(value).toLocaleString('zh-CN') : '-';
}

function applyProfile(value: UserProfile) {
  profile.value = value;
  profileForm.nickName = value.user.nickName || '';
  profileForm.email = value.user.email || '';
  profileForm.phoneNumber = value.user.phoneNumber || '';
  profileForm.gender = value.user.gender || '2';
}

async function load() {
  loading.value = true;
  const [
    profileResult,
    permissionResult,
    summaryResult,
    ltmResult,
    quotaResult,
    dataResult,
    inboxResult,
    unreadResult
  ] = await Promise.all([
    fetchUserProfile(),
    fetchEffectiveUiPermissions(),
    fetchPortalMemorySummaries({ limit: 100 }),
    fetchPortalLongTermMemory(),
    fetchPortalQuota(),
    fetchPortalDataPortalHome(),
    fetchPortalInboxNotifications({ limit: 20 }),
    fetchPortalInboxUnreadCount()
  ]);
  if (!profileResult.error && profileResult.data) applyProfile(profileResult.data);
  if (!permissionResult.error && permissionResult.data) permissions.value = permissionResult.data;
  if (!summaryResult.error) summaries.value = summaryResult.data || [];
  if (!ltmResult.error) longTermMemory.value = ltmResult.data || {};
  if (!quotaResult.error) quota.value = quotaResult.data;
  if (!dataResult.error) dataPortal.value = dataResult.data;
  if (!inboxResult.error) notifications.value = inboxResult.data || [];
  if (!unreadResult.error) unreadCount.value = unreadResult.data?.count || 0;
  loading.value = false;
}

async function loadNotificationInbox() {
  inboxError.value = '';
  const [inboxResult, unreadResult] = await Promise.all([
    fetchPortalInboxNotifications({ limit: 20 }),
    fetchPortalInboxUnreadCount()
  ]);
  if (inboxResult.error) {
    inboxError.value = notificationRequestError(inboxResult.error, '个人通知加载失败');
  } else {
    notifications.value = inboxResult.data;
  }
  if (unreadResult.error) {
    inboxError.value ||= notificationRequestError(unreadResult.error, '未读数量加载失败');
  } else {
    unreadCount.value = unreadResult.data.count;
  }
}

function confirmDeleteNotification(item: (typeof notificationRows.value)[number]) {
  dialog.warning({
    title: '删除通知',
    content: `确认删除“${item.title}”？删除后无法恢复。`,
    positiveText: '删除',
    negativeText: '取消',
    async onPositiveClick() {
      deletingNotificationId.value = item.id;
      inboxError.value = '';
      const result = await deletePortalInboxNotification(item.id);
      if (result.error) {
        inboxError.value = notificationRequestError(result.error, '通知删除失败');
      } else {
        message.success('通知已删除');
        await loadNotificationInbox();
      }
      deletingNotificationId.value = null;
    }
  });
}

function confirmClearReadNotifications() {
  dialog.warning({
    title: '清理已读通知',
    content: '确认删除当前账号的全部已读通知？未读通知会保留。',
    positiveText: '清理',
    negativeText: '取消',
    async onPositiveClick() {
      clearingRead.value = true;
      inboxError.value = '';
      const result = await deleteReadPortalInboxNotifications();
      if (result.error) {
        inboxError.value = notificationRequestError(result.error, '已读通知清理失败');
      } else {
        message.success(`已清理 ${result.data.deleted} 条已读通知`);
        await loadNotificationInbox();
      }
      clearingRead.value = false;
    }
  });
}

async function markNotificationRead(item: (typeof notificationRows.value)[number]) {
  if (item.read || markingRead.value) return;
  markingRead.value = true;
  inboxError.value = '';
  const result = await markPortalInboxNotificationRead(item.id);
  if (result.error) {
    inboxError.value = notificationRequestError(result.error, '通知已读操作失败');
  } else {
    await loadNotificationInbox();
  }
  markingRead.value = false;
}

async function markAllNotificationsRead() {
  if (!hasUnreadNotifications.value || markingRead.value) return;
  markingRead.value = true;
  inboxError.value = '';
  const result = await markAllPortalInboxNotificationsRead();
  if (result.error) {
    inboxError.value = notificationRequestError(result.error, '全部已读操作失败');
  } else {
    message.success(`已标记 ${result.data.updated} 条通知为已读`);
    await loadNotificationInbox();
  }
  markingRead.value = false;
}

async function saveProfile() {
  profileSaving.value = true;
  const { error } = await updateUserProfile({ ...profileForm });
  if (!error) {
    message.success('个人资料已更新');
    const result = await fetchUserProfile();
    if (!result.error && result.data) applyProfile(result.data);
  }
  profileSaving.value = false;
}

async function savePassword() {
  if (
    !passwordForm.oldPassword ||
    !passwordForm.newPassword ||
    passwordForm.newPassword !== passwordForm.confirmPassword
  ) {
    message.error('请检查密码输入');
    return;
  }
  passwordSaving.value = true;
  const { error } = await updateUserPassword(passwordForm.oldPassword, passwordForm.newPassword);
  if (!error) {
    message.success('密码已更新，请使用新密码登录');
    passwordForm.oldPassword = '';
    passwordForm.newPassword = '';
    passwordForm.confirmPassword = '';
  }
  passwordSaving.value = false;
}

function confirmClearBrowserProfiles() {
  dialog.warning({
    title: '清除云端浏览器登录态',
    content: '这会关闭当前用户的浏览器会话并清除 Cookie、Storage 与登录缓存，不会影响本机 Chrome/Edge 数据。是否继续？',
    positiveText: '确认清除',
    negativeText: '取消',
    async onPositiveClick() {
      clearingBrowserProfiles.value = true;
      const result = await clearBrowserProfiles();
      if (result.error) message.error(result.error instanceof Error ? result.error.message : '浏览器缓存清除失败');
      else message.success('云端浏览器历史、登录态及缓存已清除');
      clearingBrowserProfiles.value = false;
    }
  });
}

function open(path: string) {
  router.push(path);
}

onMounted(load);
</script>

<template>
  <NSpin :show="loading">
    <NSpace vertical :size="16" class="pb-16px">
      <div class="flex flex-wrap items-end justify-between gap-12px">
        <div>
          <div class="text-12px op-60">账号与个人资源</div>
          <h1 class="m-0 mt-4px text-24px font-600">个人中心</h1>
          <div class="mt-4px text-12px op-60">资料、权限、记忆和个人工作入口</div>
        </div>
        <NButton secondary :loading="loading" @click="load">
          <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
          刷新
        </NButton>
      </div>

      <NCard :bordered="false" size="small">
        <div class="flex flex-wrap items-center gap-16px">
          <div
            class="h-48px w-48px flex items-center justify-center rounded-full bg-primary/10 text-20px font-600 text-primary"
          >
            {{ (profile?.user.nickName || profile?.user.userName || '?').slice(0, 1) }}
          </div>
          <div class="min-w-180px">
            <div class="text-18px font-600">{{ profile?.user.nickName || profile?.user.userName || '当前用户' }}</div>
            <div class="mt-4px text-12px op-60">
              {{ profile?.user.userName || '-' }} · {{ profile?.user.deptName || '未设置部门' }}
            </div>
          </div>
          <NTag size="small" type="success">登录有效</NTag>
          <div class="ml-auto text-right text-12px op-60">{{ profile?.roleGroup || '未分配角色' }}</div>
        </div>
      </NCard>

      <NTabs v-model:value="activeTab" type="line" animated>
        <NTabPane name="info" tab="基本资料">
          <NGrid :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
            <NGi span="24 s:24 m:14">
              <NCard :bordered="false" title="个人资料">
                <NForm label-placement="top">
                  <NGrid :cols="2" :x-gap="16">
                    <NFormItem label="登录账号"><NInput :value="profile?.user.userName || ''" disabled /></NFormItem>
                    <NFormItem label="昵称"><NInput v-model:value="profileForm.nickName" /></NFormItem>
                    <NFormItem label="邮箱"><NInput v-model:value="profileForm.email" /></NFormItem>
                    <NFormItem label="手机号码"><NInput v-model:value="profileForm.phoneNumber" /></NFormItem>
                    <NFormItem label="性别">
                      <NSelect v-model:value="profileForm.gender" :options="genderOptions" />
                    </NFormItem>
                  </NGrid>
                  <NButton type="primary" :loading="profileSaving" @click="saveProfile">保存资料</NButton>
                </NForm>
              </NCard>
            </NGi>
            <NGi span="24 s:24 m:10">
              <NCard :bordered="false" title="账号信息">
                <NDescriptions :column="1" bordered size="small">
                  <NDescriptionsItem label="部门">{{ profile?.user.deptName || '-' }}</NDescriptionsItem>
                  <NDescriptionsItem label="角色">{{ profile?.roleGroup || '-' }}</NDescriptionsItem>
                  <NDescriptionsItem label="岗位">{{ profile?.postGroup || '-' }}</NDescriptionsItem>
                  <NDescriptionsItem label="最近登录">
                    {{ formatTime(profile?.user.loginDate || '') }}
                  </NDescriptionsItem>
                  <NDescriptionsItem label="登录 IP">{{ profile?.user.loginIp || '-' }}</NDescriptionsItem>
                </NDescriptions>
              </NCard>
            </NGi>
          </NGrid>
          <NCard :bordered="false" title="修改密码" class="mt-16px">
            <NForm :model="passwordForm" :rules="passwordRules" label-placement="top">
              <NGrid :cols="3" :x-gap="16">
                <NFormItem label="当前密码" path="oldPassword">
                  <NInput v-model:value="passwordForm.oldPassword" type="password" show-password-on="click" />
                </NFormItem>
                <NFormItem label="新密码" path="newPassword">
                  <NInput v-model:value="passwordForm.newPassword" type="password" show-password-on="click" />
                </NFormItem>
                <NFormItem label="确认新密码" path="confirmPassword">
                  <NInput v-model:value="passwordForm.confirmPassword" type="password" show-password-on="click" />
                </NFormItem>
              </NGrid>
              <NButton secondary :loading="passwordSaving" @click="savePassword">更新密码</NButton>
            </NForm>
          </NCard>
          <NCard :bordered="false" title="云端浏览器缓存" class="mt-16px">
            <div class="text-13px op-70">
              清除平台独立浏览器 Worker 中的会话、Cookie、Storage 和登录态，不影响本机浏览器。
            </div>
            <div class="mt-12px">
              <NButton type="error" secondary :loading="clearingBrowserProfiles" @click="confirmClearBrowserProfiles">
                <template #icon><SvgIcon icon="lucide:eraser" /></template>
                清除登录态与缓存
              </NButton>
            </div>
          </NCard>
        </NTabPane>

        <NTabPane name="permissions" tab="有效权限">
          <NCard :bordered="false" title="当前账号可用范围">
            <NAlert type="info" :show-icon="false">
              权限由平台 IAM 和个人绑定资源共同决定；页面只展示当前会话的有效 UI 范围。
            </NAlert>
            <div class="mt-16px text-13px font-600">可访问页面（{{ permissions.routes.length }}）</div>
            <NSpace class="mt-8px" wrap>
              <NTag v-for="route in permissions.routes" :key="route" size="small">{{ route }}</NTag>
            </NSpace>
            <div class="mt-16px text-13px font-600">可用操作（{{ permissions.buttons.length }}）</div>
            <NSpace class="mt-8px" wrap>
              <NTag v-for="button in permissions.buttons" :key="button" size="small" type="success">{{ button }}</NTag>
            </NSpace>
          </NCard>
        </NTabPane>

        <NTabPane name="memory" tab="我的记忆">
          <NGrid :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
            <NGi span="24 s:12 m:6">
              <NCard :bordered="false" title="会话摘要">
                <div class="text-26px font-600">{{ summaries.length }}</div>
                <div class="text-12px op-60">可在记忆工作台查看和清理</div>
              </NCard>
            </NGi>
            <NGi span="24 s:12 m:6">
              <NCard :bordered="false" title="长期偏好">
                <div class="text-26px font-600">{{ Object.keys(longTermMemory).length }}</div>
                <div class="text-12px op-60">个人偏好键值</div>
              </NCard>
            </NGi>
            <NGi span="24 s:12 m:6">
              <NCard :bordered="false" title="记忆总项">
                <div class="text-26px font-600">{{ memoryCount }}</div>
                <div class="text-12px op-60">仅统计当前用户</div>
              </NCard>
            </NGi>
          </NGrid>
          <div class="mt-16px flex justify-end">
            <NButton type="primary" secondary @click="open('/memory')">
              <template #icon><SvgIcon icon="lucide:brain" /></template>
              打开记忆工作台
            </NButton>
          </div>
        </NTabPane>

        <NTabPane name="tokens" tab="Token 与额度">
          <NGrid :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
            <NGi span="24 s:12 m:6">
              <NCard :bordered="false" title="本月已用">
                <div class="text-26px font-600">{{ (quota?.used_tokens || 0).toLocaleString('zh-CN') }}</div>
                <div class="text-12px op-60">Token</div>
              </NCard>
            </NGi>
            <NGi span="24 s:12 m:6">
              <NCard :bordered="false" title="额度上限">
                <div class="text-26px font-600">
                  {{ quota?.limit_tokens == null ? '不限' : quota.limit_tokens.toLocaleString('zh-CN') }}
                </div>
                <div class="text-12px op-60">{{ quota?.source_label || '系统策略' }}</div>
              </NCard>
            </NGi>
            <NGi span="24 s:12 m:6">
              <NCard :bordered="false" title="剩余额度">
                <div class="text-26px font-600">
                  {{ quota?.remaining_tokens == null ? '不限' : quota.remaining_tokens.toLocaleString('zh-CN') }}
                </div>
                <div class="text-12px op-60">
                  {{ quota?.is_admin_bypass ? '管理员豁免' : quota?.action_on_exceed || '达到上限后提醒' }}
                </div>
              </NCard>
            </NGi>
          </NGrid>
          <NAlert v-if="quota?.policy_enabled && quotaPercent >= 80" class="mt-16px" type="warning">
            本月额度已使用 {{ quotaPercent }}%，请关注后续消耗。
          </NAlert>
          <div class="mt-16px flex justify-end">
            <NButton type="primary" secondary @click="open('/token-stats')">
              <template #icon><SvgIcon icon="lucide:calculator" /></template>
              查看 Token 明细
            </NButton>
          </div>
        </NTabPane>

        <NTabPane name="notifications" tab="通知">
          <NotificationSettings />
          <NCard :bordered="false" title="个人通知" class="mt-16px">
            <template #header-extra>
              <NSpace align="center" :size="8">
                <NTag size="small" :type="unreadCount ? 'warning' : 'default'">未读 {{ unreadCount }}</NTag>
                <NButton
                  size="small"
                  secondary
                  :disabled="!hasUnreadNotifications || markingRead"
                  :loading="markingRead"
                  @click="markAllNotificationsRead"
                >
                  <template #icon><SvgIcon icon="lucide:check-check" /></template>
                  全部已读
                </NButton>
                <NButton
                  size="small"
                  secondary
                  :disabled="!hasReadNotifications"
                  :loading="clearingRead"
                  @click="confirmClearReadNotifications"
                >
                  <template #icon><SvgIcon icon="lucide:trash-2" /></template>
                  清理已读
                </NButton>
                <NButton size="small" quaternary @click="loadNotificationInbox">
                  <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
                  刷新
                </NButton>
              </NSpace>
            </template>
            <NAlert v-if="inboxError" class="mb-10px" type="error" :show-icon="true">{{ inboxError }}</NAlert>
            <NSpace v-if="notificationRows.length" vertical :size="8">
              <div
                v-for="item in notificationRows"
                :key="item.id"
                class="cursor-pointer border-b border-color/10 pb-8px last:border-0"
                @click="markNotificationRead(item)"
              >
                <div class="flex items-center gap-8px">
                  <NTag v-if="!item.read" type="warning" size="small">未读</NTag>
                  <span class="min-w-0 flex-1 truncate font-500">{{ item.title }}</span>
                  <span class="text-12px op-60">{{ formatTime(item.createdAt) }}</span>
                  <NButton
                    quaternary
                    circle
                    size="small"
                    type="error"
                    title="删除通知"
                    :loading="deletingNotificationId === item.id"
                    :disabled="Boolean(deletingNotificationId)"
                    @click="confirmDeleteNotification(item)"
                  >
                    <template #icon><SvgIcon icon="lucide:trash-2" /></template>
                  </NButton>
                </div>
                <div class="mt-4px break-words text-12px op-60">{{ item.content || '暂无正文' }}</div>
              </div>
            </NSpace>
            <NEmpty v-else description="暂无通知" />
          </NCard>
        </NTabPane>

        <NTabPane name="data" tab="我的数据">
          <NGrid :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
            <NGi span="24 s:12 m:6">
              <NCard :bordered="false" title="已订阅报表">
                <div class="text-26px font-600">{{ dataPortal?.report_summary.subscribed || 0 }}</div>
              </NCard>
            </NGi>
            <NGi span="24 s:12 m:6">
              <NCard :bordered="false" title="已置顶报表">
                <div class="text-26px font-600">{{ dataPortal?.report_summary.pinned || 0 }}</div>
              </NCard>
            </NGi>
            <NGi span="24 s:12 m:6">
              <NCard :bordered="false" title="最近分析">
                <div class="text-26px font-600">{{ dataPortal?.recent_analysis.length || 0 }}</div>
              </NCard>
            </NGi>
          </NGrid>
          <div class="mt-16px flex justify-end">
            <NButton type="primary" secondary @click="open('/data-portal')">
              <template #icon><SvgIcon icon="lucide:chart-no-axes-combined" /></template>
              打开数据门户
            </NButton>
          </div>
        </NTabPane>

        <NTabPane name="skills" tab="我的技能">
          <NCard :bordered="false" title="技能资源">
            <div class="text-13px op-70">
              技能的创建、版本、发布和权限复用统一在资源配置中心完成。个人中心不复制另一套编辑器，避免状态分裂。
            </div>
            <div class="mt-16px flex justify-end">
              <NButton type="primary" secondary @click="open('/resource-center')">
                <template #icon><SvgIcon icon="lucide:puzzle" /></template>
                打开资源配置
              </NButton>
            </div>
          </NCard>
        </NTabPane>
        <NTabPane name="mcp" tab="MCP 工具">
          <NCard :bordered="false" title="MCP 与工具">
            <div class="text-13px op-70">工具的连接器、授权和风险策略使用资源配置中心的真实配置。</div>
            <div class="mt-16px flex justify-end">
              <NButton type="primary" secondary @click="open('/resource-center')">
                <template #icon><SvgIcon icon="lucide:wrench" /></template>
                打开资源配置
              </NButton>
            </div>
          </NCard>
        </NTabPane>
        <NTabPane name="tasks" tab="我的任务">
          <NCard :bordered="false" title="任务与运行">
            <div class="text-13px op-70">
              任务采用企业共享可见性；个人中心只提供入口，具体任务、运行和验收仍按当前权限过滤。
            </div>
            <div class="mt-16px flex justify-end">
              <NButton type="primary" secondary @click="open('/task-center')">
                <template #icon><SvgIcon icon="lucide:list-checks" /></template>
                打开任务中心
              </NButton>
            </div>
          </NCard>
        </NTabPane>
      </NTabs>
    </NSpace>
  </NSpin>
</template>
