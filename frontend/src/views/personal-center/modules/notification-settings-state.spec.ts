import { describe, expect, it } from 'vitest';
import {
  createDefaultNotificationConfigs,
  mergeNotificationConfigs,
  notificationRequestError
} from './notification-settings-state';

describe('notification settings state', () => {
  it('creates independent complete defaults for all supported channels', () => {
    const first = createDefaultNotificationConfigs();
    const second = createDefaultNotificationConfigs();

    first.dingtalk.webhook_url = 'changed';

    expect(second.dingtalk.webhook_url).toBe('');
    expect(second.email.smtp_port).toBe(465);
    expect(Object.keys(second)).toEqual(['dingtalk', 'wechat_work', 'email']);
  });

  it('merges partial server values without losing required form fields', () => {
    const merged = mergeNotificationConfigs({
      email: {
        is_enabled: true,
        smtp_host: 'smtp.example.com',
        smtp_port: 587,
        smtp_user: 'notice@example.com',
        smtp_password: '******',
        sender_name: 'Agent',
        recipients: ''
      }
    });

    expect(merged.email.is_enabled).toBe(true);
    expect(merged.dingtalk).toEqual({ is_enabled: false, webhook_url: '', secret: '' });
  });

  it('surfaces the provider error returned by a 502 or 503 response', () => {
    const error = { response: { data: { message: '钉钉通知供应商当前不可用' } } };

    expect(notificationRequestError(error, '测试失败')).toBe('钉钉通知供应商当前不可用');
    expect(notificationRequestError(null, '测试失败')).toBe('测试失败');
  });
});
