import type { NotificationChannelConfigs } from '@/service/api';

export const notificationMask = '******';

export function createDefaultNotificationConfigs(): NotificationChannelConfigs {
  return {
    dingtalk: { is_enabled: false, webhook_url: '', secret: '' },
    wechat_work: { is_enabled: false, webhook_url: '' },
    email: {
      is_enabled: false,
      smtp_host: '',
      smtp_port: 465,
      smtp_user: '',
      smtp_password: '',
      sender_name: 'AI Agent',
      recipients: ''
    }
  };
}

export function mergeNotificationConfigs(value?: Partial<NotificationChannelConfigs> | null): NotificationChannelConfigs {
  const defaults = createDefaultNotificationConfigs();
  return {
    dingtalk: { ...defaults.dingtalk, ...value?.dingtalk },
    wechat_work: { ...defaults.wechat_work, ...value?.wechat_work },
    email: { ...defaults.email, ...value?.email }
  };
}

export function notificationRequestError(error: unknown, fallback: string) {
  const response = (error as { response?: { data?: { message?: string; msg?: string } } } | null)?.response;
  if (response?.data?.message) return response.data.message;
  if (response?.data?.msg) return response.data.msg;
  if (error instanceof Error && error.message.trim()) return error.message;
  return fallback;
}
