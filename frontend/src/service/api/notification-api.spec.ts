import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import {
  deletePortalInboxNotification,
  deleteReadPortalInboxNotifications,
  fetchNotificationChannelConfigs,
  saveNotificationChannelConfig,
  testNotificationChannel
} from './notification';

vi.mock('../request', () => ({ request: vi.fn() }));

describe('personal notification API contract', () => {
  beforeEach(() => {
    vi.mocked(request).mockClear();
  });

  it('loads and saves a typed channel through the Nhs-compatible routes', () => {
    const config = {
      is_enabled: true,
      webhook_url: 'https://oapi.dingtalk.com/robot/send?access_token=test',
      secret: '******'
    };

    fetchNotificationChannelConfigs();
    saveNotificationChannelConfig('dingtalk', config);

    expect(request).toHaveBeenNthCalledWith(1, {
      url: '/api/portal/notifications/config',
      method: 'get'
    });
    expect(request).toHaveBeenNthCalledWith(2, {
      url: '/api/portal/notifications/config',
      method: 'put',
      data: { channel_type: 'dingtalk', config_data: config }
    });
  });

  it('uses the real test-send endpoint without returning a local fake result', () => {
    const config = { is_enabled: true, webhook_url: 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=test' };

    testNotificationChannel('wechat_work', config);

    expect(request).toHaveBeenCalledWith({
      url: '/api/portal/notifications/test',
      method: 'post',
      data: { channel_type: 'wechat_work', config_data: config }
    });
  });

  it('maps single-delete and clear-read to distinct owner-scoped inbox endpoints', () => {
    deletePortalInboxNotification('notification/42');
    deleteReadPortalInboxNotifications();

    expect(request).toHaveBeenNthCalledWith(1, {
      url: '/api/portal/inbox/notification%2F42',
      method: 'delete'
    });
    expect(request).toHaveBeenNthCalledWith(2, {
      url: '/api/portal/inbox/read',
      method: 'delete'
    });
  });
});
