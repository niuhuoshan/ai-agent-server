<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import {
  NAlert,
  NButton,
  NCard,
  NEmpty,
  NForm,
  NFormItem,
  NGrid,
  NGi,
  NInput,
  NInputNumber,
  NList,
  NListItem,
  NSpace,
  NSpin,
  NSwitch,
  NTag,
  useMessage
} from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import {
  fetchNotificationChannelConfigs,
  fetchNotificationDeliveries,
  retryNotificationDelivery,
  saveNotificationChannelConfig,
  testNotificationChannel
} from '@/service/api';
import type {
  NotificationChannelConfig,
  NotificationChannelConfigs,
  NotificationChannelType,
  NotificationDeliveryView
} from '@/service/api';
import {
  createDefaultNotificationConfigs,
  mergeNotificationConfigs,
  notificationRequestError
} from './notification-settings-state';

const message = useMessage();
const loading = ref(false);
const loadError = ref('');
const configs = reactive<NotificationChannelConfigs>(createDefaultNotificationConfigs());
const saving = reactive<Record<NotificationChannelType, boolean>>({ dingtalk: false, wechat_work: false, email: false });
const testing = reactive<Record<NotificationChannelType, boolean>>({ dingtalk: false, wechat_work: false, email: false });
const errors = reactive<Record<NotificationChannelType, string>>({ dingtalk: '', wechat_work: '', email: '' });
const testResults = reactive<Record<NotificationChannelType, string>>({ dingtalk: '', wechat_work: '', email: '' });
const deliveries = ref<NotificationDeliveryView[]>([]);
const deliveryLoading = ref(false);
const deliveryError = ref('');
const retryingDeliveryId = ref<string | number | null>(null);

const channelNames: Record<NotificationChannelType, string> = {
  dingtalk: '钉钉群机器人',
  wechat_work: '企业微信群机器人',
  email: 'SMTP 邮件'
};

function replaceConfigs(value: NotificationChannelConfigs) {
  const merged = mergeNotificationConfigs(value);
  Object.assign(configs.dingtalk, merged.dingtalk);
  Object.assign(configs.wechat_work, merged.wechat_work);
  Object.assign(configs.email, merged.email);
}

async function loadConfigs() {
  loading.value = true;
  loadError.value = '';
  const result = await fetchNotificationChannelConfigs();
  if (result.error) {
    loadError.value = notificationRequestError(result.error, '通知渠道配置加载失败');
  } else {
    replaceConfigs(result.data);
  }
  loading.value = false;
}

async function save<T extends NotificationChannelType>(channel: T) {
  saving[channel] = true;
  errors[channel] = '';
  testResults[channel] = '';
  const result = await saveNotificationChannelConfig(channel, { ...configs[channel] } as NotificationChannelConfig<T>);
  if (result.error) {
    errors[channel] = notificationRequestError(result.error, `${channelNames[channel]}配置保存失败`);
  } else {
    Object.assign(configs[channel], result.data.config);
    message.success(`${channelNames[channel]}配置已保存`);
  }
  saving[channel] = false;
}

async function test<T extends NotificationChannelType>(channel: T) {
  testing[channel] = true;
  errors[channel] = '';
  testResults[channel] = '';
  const result = await testNotificationChannel(channel, { ...configs[channel] } as NotificationChannelConfig<T>);
  if (result.error) {
    errors[channel] = notificationRequestError(result.error, `${channelNames[channel]}测试消息发送失败`);
  } else {
    testResults[channel] = `测试消息已发送，供应商响应耗时 ${result.data.elapsed_ms} ms`;
    message.success(`${channelNames[channel]}连通正常`);
  }
  testing[channel] = false;
}

async function loadDeliveries() {
  deliveryLoading.value = true;
  deliveryError.value = '';
  try {
    const result = await fetchNotificationDeliveries();
    if (result.error) {
      deliveries.value = [];
      deliveryError.value = notificationRequestError(result.error, '通知投递记录加载失败');
    } else {
      deliveries.value = result.data;
    }
  } catch (error) {
    deliveries.value = [];
    deliveryError.value = notificationRequestError(error, '通知投递记录加载失败');
  } finally {
    deliveryLoading.value = false;
  }
}

async function retryDelivery(delivery: NotificationDeliveryView) {
  if (retryingDeliveryId.value !== null) return;
  retryingDeliveryId.value = delivery.id;
  const result = await retryNotificationDelivery(delivery.id);
  if (result.error) {
    message.error(notificationRequestError(result.error, '通知投递重试失败'));
  } else {
    message.success('通知投递已重新排队');
    await loadDeliveries();
  }
  retryingDeliveryId.value = null;
}

function deliveryStatusLabel(status: NotificationDeliveryView['status']) {
  return status === 'published' ? '已送达' : status === 'failed' ? '失败' : '等待投递';
}

function deliveryStatusType(status: NotificationDeliveryView['status']) {
  return status === 'published' ? 'success' : status === 'failed' ? 'error' : 'warning';
}

function deliveryChannelLabel(channel: NotificationChannelType) {
  return channelNames[channel];
}

function deliveryTime(value: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN') : '-';
}

onMounted(() => {
  loadConfigs();
  loadDeliveries();
});
</script>

<template>
  <NSpin :show="loading">
    <div class="mb-12px flex flex-wrap items-center justify-between gap-8px">
      <div>
        <div class="text-15px font-600">外部通知渠道</div>
      </div>
      <NButton quaternary size="small" :loading="loading" @click="loadConfigs">
        <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
        刷新配置
      </NButton>
    </div>
    <NAlert v-if="loadError" class="mb-12px" type="error" title="配置未加载" :show-icon="true">
      {{ loadError }}
    </NAlert>

    <NGrid :x-gap="12" :y-gap="12" responsive="screen" item-responsive>
      <NGi span="24 m:12 xl:8">
        <NCard size="small" title="钉钉群机器人" class="h-full">
          <template #header-extra>
            <NSpace align="center" :size="6">
              <NTag size="small" :type="configs.dingtalk.is_enabled ? 'success' : 'default'">
                {{ configs.dingtalk.is_enabled ? '已启用' : '未启用' }}
              </NTag>
              <NSwitch v-model:value="configs.dingtalk.is_enabled" size="small" />
            </NSpace>
          </template>
          <NForm label-placement="top" size="small">
            <NFormItem label="Webhook 地址">
              <NInput v-model:value="configs.dingtalk.webhook_url" type="password" show-password-on="click" placeholder="https://oapi.dingtalk.com/robot/send?..." />
            </NFormItem>
            <NFormItem label="加签密钥（可选）">
              <NInput v-model:value="configs.dingtalk.secret" type="password" show-password-on="click" placeholder="SEC..." />
            </NFormItem>
          </NForm>
          <NAlert v-if="errors.dingtalk" class="mb-10px" type="error" :show-icon="true">{{ errors.dingtalk }}</NAlert>
          <NAlert v-if="testResults.dingtalk" class="mb-10px" type="success" :show-icon="true">{{ testResults.dingtalk }}</NAlert>
          <NSpace justify="end">
            <NButton size="small" secondary :loading="testing.dingtalk" :disabled="saving.dingtalk" @click="test('dingtalk')">
              <template #icon><SvgIcon icon="lucide:send" /></template>
              发送测试
            </NButton>
            <NButton size="small" type="primary" :loading="saving.dingtalk" :disabled="testing.dingtalk" @click="save('dingtalk')">
              <template #icon><SvgIcon icon="lucide:save" /></template>
              保存
            </NButton>
          </NSpace>
        </NCard>
      </NGi>

      <NGi span="24 m:12 xl:8">
        <NCard size="small" title="企业微信群机器人" class="h-full">
          <template #header-extra>
            <NSpace align="center" :size="6">
              <NTag size="small" :type="configs.wechat_work.is_enabled ? 'success' : 'default'">
                {{ configs.wechat_work.is_enabled ? '已启用' : '未启用' }}
              </NTag>
              <NSwitch v-model:value="configs.wechat_work.is_enabled" size="small" />
            </NSpace>
          </template>
          <NForm label-placement="top" size="small">
            <NFormItem label="Webhook 地址">
              <NInput v-model:value="configs.wechat_work.webhook_url" type="password" show-password-on="click" placeholder="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?..." />
            </NFormItem>
          </NForm>
          <NAlert v-if="errors.wechat_work" class="mb-10px" type="error" :show-icon="true">{{ errors.wechat_work }}</NAlert>
          <NAlert v-if="testResults.wechat_work" class="mb-10px" type="success" :show-icon="true">{{ testResults.wechat_work }}</NAlert>
          <NSpace justify="end">
            <NButton size="small" secondary :loading="testing.wechat_work" :disabled="saving.wechat_work" @click="test('wechat_work')">
              <template #icon><SvgIcon icon="lucide:send" /></template>
              发送测试
            </NButton>
            <NButton size="small" type="primary" :loading="saving.wechat_work" :disabled="testing.wechat_work" @click="save('wechat_work')">
              <template #icon><SvgIcon icon="lucide:save" /></template>
              保存
            </NButton>
          </NSpace>
        </NCard>
      </NGi>

      <NGi span="24 m:24 xl:8">
        <NCard size="small" title="SMTP 邮件" class="h-full">
          <template #header-extra>
            <NSpace align="center" :size="6">
              <NTag size="small" :type="configs.email.is_enabled ? 'success' : 'default'">
                {{ configs.email.is_enabled ? '已启用' : '未启用' }}
              </NTag>
              <NSwitch v-model:value="configs.email.is_enabled" size="small" />
            </NSpace>
          </template>
          <NForm label-placement="top" size="small">
            <NGrid :cols="2" :x-gap="10">
              <NFormItem label="SMTP 主机"><NInput v-model:value="configs.email.smtp_host" placeholder="smtp.example.com" /></NFormItem>
              <NFormItem label="端口"><NInputNumber v-model:value="configs.email.smtp_port" :min="1" :max="65535" class="w-full" /></NFormItem>
            </NGrid>
            <NFormItem label="SMTP 账号"><NInput v-model:value="configs.email.smtp_user" placeholder="notice@example.com" /></NFormItem>
            <NFormItem label="SMTP 授权码"><NInput v-model:value="configs.email.smtp_password" type="password" show-password-on="click" /></NFormItem>
            <NGrid :cols="2" :x-gap="10">
              <NFormItem label="发件人名称"><NInput v-model:value="configs.email.sender_name" /></NFormItem>
              <NFormItem label="测试收件人"><NInput v-model:value="configs.email.recipients" placeholder="留空则发给 SMTP 账号" /></NFormItem>
            </NGrid>
          </NForm>
          <NAlert v-if="errors.email" class="mb-10px" type="error" :show-icon="true">{{ errors.email }}</NAlert>
          <NAlert v-if="testResults.email" class="mb-10px" type="success" :show-icon="true">{{ testResults.email }}</NAlert>
          <NSpace justify="end">
            <NButton size="small" secondary :loading="testing.email" :disabled="saving.email" @click="test('email')">
              <template #icon><SvgIcon icon="lucide:send" /></template>
              发送测试
            </NButton>
            <NButton size="small" type="primary" :loading="saving.email" :disabled="testing.email" @click="save('email')">
              <template #icon><SvgIcon icon="lucide:save" /></template>
              保存
            </NButton>
          </NSpace>
        </NCard>
      </NGi>
    </NGrid>

    <NCard class="mt-16px" size="small" title="外部通知投递记录">
      <template #header-extra>
        <NButton quaternary size="small" :loading="deliveryLoading" @click="loadDeliveries">
          <template #icon><SvgIcon icon="lucide:refresh-cw" /></template>
          刷新
        </NButton>
      </template>
      <NAlert v-if="deliveryError" class="mb-10px" type="error" :show-icon="true">{{ deliveryError }}</NAlert>
      <NList v-if="deliveries.length" bordered>
        <NListItem v-for="delivery in deliveries" :key="delivery.id">
          <div class="flex min-w-0 flex-wrap items-center gap-8px">
            <NTag size="small">{{ deliveryChannelLabel(delivery.channelType) }}</NTag>
            <NTag size="small" :type="deliveryStatusType(delivery.status)">{{ deliveryStatusLabel(delivery.status) }}</NTag>
            <span class="text-12px op-60">尝试 {{ delivery.attemptNo }} 次 · {{ deliveryTime(delivery.publishedAt || delivery.createdAt) }}</span>
            <span v-if="delivery.lastError" class="min-w-180px flex-1 truncate text-12px text-error">{{ delivery.lastError }}</span>
            <NButton v-if="delivery.status === 'failed'" size="small" secondary :loading="retryingDeliveryId === delivery.id" @click="retryDelivery(delivery)">
              <template #icon><SvgIcon icon="lucide:rotate-cw" /></template>
              重试
            </NButton>
          </div>
        </NListItem>
      </NList>
      <NEmpty v-else-if="!deliveryError && !deliveryLoading" description="暂无外部渠道投递记录" />
    </NCard>
  </NSpin>
</template>
