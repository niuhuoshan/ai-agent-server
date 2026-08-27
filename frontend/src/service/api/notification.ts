import { request } from '../request';

export type NotificationChannelType = 'dingtalk' | 'wechat_work' | 'email';

export interface DingTalkNotificationConfig {
  is_enabled: boolean;
  webhook_url: string;
  secret: string;
}

export interface WechatWorkNotificationConfig {
  is_enabled: boolean;
  webhook_url: string;
}

export interface EmailNotificationConfig {
  is_enabled: boolean;
  smtp_host: string;
  smtp_port: number;
  smtp_user: string;
  smtp_password: string;
  sender_name: string;
  recipients: string;
}

export interface NotificationChannelConfigs {
  dingtalk: DingTalkNotificationConfig;
  wechat_work: WechatWorkNotificationConfig;
  email: EmailNotificationConfig;
}

export type NotificationChannelConfig<T extends NotificationChannelType> = NotificationChannelConfigs[T];

export interface NotificationConfigMutationResult<T extends NotificationChannelType = NotificationChannelType> {
  status: 'success';
  message: string;
  channel_type: T;
  config: NotificationChannelConfig<T>;
}

export interface NotificationTestResult {
  status: 'success';
  message: string;
  channel_type: NotificationChannelType;
  elapsed_ms: number;
}

export type NotificationDeliveryStatus = 'pending' | 'published' | 'failed';

export interface NotificationDeliveryView {
  id: string | number;
  sourceEventKey: string;
  channelType: NotificationChannelType;
  status: NotificationDeliveryStatus;
  attemptNo: number;
  nextAttemptAt: string | null;
  publishedAt: string | null;
  lastError: string | null;
  createdAt: string;
}

export interface PortalInboxItem {
  id: string | number;
  category: string;
  level: 'info' | 'success' | 'warning' | 'error';
  title: string;
  content: string | null;
  resource_type: string | null;
  resource_id: string | number | null;
  metadata: Record<string, unknown>;
  read_at: string | null;
  created_at: string;
}

export function fetchNotificationChannelConfigs() {
  return request<NotificationChannelConfigs>({ url: '/api/portal/notifications/config', method: 'get' });
}

export function saveNotificationChannelConfig<T extends NotificationChannelType>(
  channelType: T,
  configData: NotificationChannelConfig<T>
) {
  return request<NotificationConfigMutationResult<T>>({
    url: '/api/portal/notifications/config',
    method: 'put',
    data: { channel_type: channelType, config_data: configData }
  });
}

export function testNotificationChannel<T extends NotificationChannelType>(
  channelType: T,
  configData: NotificationChannelConfig<T>
) {
  return request<NotificationTestResult>({
    url: '/api/portal/notifications/test',
    method: 'post',
    data: { channel_type: channelType, config_data: configData }
  });
}

export function fetchPortalInboxNotifications(params?: { limit?: number; offset?: number; unread_only?: boolean }) {
  return request<PortalInboxItem[]>({
    url: '/api/portal/inbox',
    method: 'get',
    params: { limit: 20, offset: 0, unread_only: false, ...params }
  });
}

export function fetchPortalInboxUnreadCount() {
  return request<{ count: number }>({ url: '/api/portal/inbox/unread-count', method: 'get' });
}

export function markPortalInboxNotificationRead(notificationId: string | number) {
  return request<{ status: string }>({
    url: `/api/portal/inbox/${encodeURIComponent(String(notificationId))}/read`,
    method: 'post'
  });
}

export function markAllPortalInboxNotificationsRead() {
  return request<{ updated: number }>({ url: '/api/portal/inbox/read-all', method: 'post' });
}

export function deletePortalInboxNotification(notificationId: string | number) {
  return request<void>({
    url: `/api/portal/inbox/${encodeURIComponent(String(notificationId))}`,
    method: 'delete'
  });
}

export function deleteReadPortalInboxNotifications() {
  return request<{ deleted: number }>({ url: '/api/portal/inbox/read', method: 'delete' });
}

export function fetchNotificationDeliveries(limit = 50) {
  return request<NotificationDeliveryView[]>({
    url: '/api/portal/notifications/deliveries',
    method: 'get',
    params: { limit }
  });
}

export function retryNotificationDelivery(deliveryId: string | number) {
  return request<NotificationDeliveryView>({
    url: `/api/portal/notifications/deliveries/${encodeURIComponent(String(deliveryId))}/retry`,
    method: 'post'
  });
}
