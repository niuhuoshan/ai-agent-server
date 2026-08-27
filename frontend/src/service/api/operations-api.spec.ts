import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import {
  deleteRedisKey,
  deleteRedisKeys,
  executeLogCleanup,
  fetchLogMaintenanceRuns,
  fetchLogPartitionStatus,
  fetchLogRetentionConfig,
  fetchRedisKeyDetail,
  fetchRedisKeys,
  fetchPlatformConfiguration,
  fetchPlatformConfigurationHistory,
  fetchPublicPlatformConfiguration,
  fetchSystemHealthOverview,
  fetchSystemDiagnostics,
  flushRedisCaches,
  previewLogCleanup,
  updateLogRetentionConfig,
  updatePlatformConfiguration
} from './operations';

vi.mock('../request', () => ({ request: vi.fn() }));

describe('system operations API', () => {
  beforeEach(() => {
    vi.mocked(request).mockClear();
  });

  it('loads the protected structured health overview', () => {
    fetchSystemHealthOverview();
    fetchSystemDiagnostics();

    expect(request).toHaveBeenNthCalledWith(1, {
      url: '/platform/operations/health',
      method: 'get'
    });
    expect(request).toHaveBeenNthCalledWith(2, {
      url: '/platform/operations/diagnostics',
      method: 'get'
    });
  });

  it('uses the guarded Redis administration endpoints', () => {
    fetchRedisKeys('cache:*');
    fetchRedisKeyDetail('cache:item');
    deleteRedisKey('cache:item');
    deleteRedisKeys(['cache:a', 'cache:b']);
    flushRedisCaches();

    expect(request).toHaveBeenNthCalledWith(1, {
      url: '/platform/operations/redis/keys', method: 'get', params: { pattern: 'cache:*' }
    });
    expect(request).toHaveBeenNthCalledWith(2, {
      url: '/platform/operations/redis/key-detail', method: 'get', params: { key: 'cache:item' }
    });
    expect(request).toHaveBeenNthCalledWith(3, {
      url: '/platform/operations/redis/key', method: 'delete', params: { key: 'cache:item', confirm: true }
    });
    expect(request).toHaveBeenNthCalledWith(4, {
      url: '/platform/operations/redis/delete-keys', method: 'post',
      data: { keys: ['cache:a', 'cache:b'], confirm: true }
    });
    expect(request).toHaveBeenNthCalledWith(5, {
      url: '/platform/operations/redis/flush', method: 'post',
      data: { confirm: true, preserveConversations: true }
    });
  });

  it('uses the two-step PostgreSQL log maintenance endpoints', () => {
    fetchLogRetentionConfig();
    fetchLogPartitionStatus();
    previewLogCleanup();
    executeLogCleanup('preview-token');
    fetchLogMaintenanceRuns(30);
    updateLogRetentionConfig({ retentionDays: 120, expectedRevision: 4, changeReason: '调整合规留存期' });

    expect(request).toHaveBeenNthCalledWith(1, {
      url: '/platform/operations/logs/config', method: 'get'
    });
    expect(request).toHaveBeenNthCalledWith(2, {
      url: '/platform/operations/logs/partitions', method: 'get'
    });
    expect(request).toHaveBeenNthCalledWith(3, {
      url: '/platform/operations/logs/cleanup/preview', method: 'post'
    });
    expect(request).toHaveBeenNthCalledWith(4, {
      url: '/platform/operations/logs/cleanup', method: 'post',
      data: { confirmationToken: 'preview-token', confirm: true }
    });
    expect(request).toHaveBeenNthCalledWith(5, {
      url: '/platform/operations/logs/maintenance-runs', method: 'get', params: { limit: 30 }
    });
    expect(request).toHaveBeenNthCalledWith(6, {
      url: '/platform/operations/logs/config', method: 'put',
      data: { retentionDays: 120, expectedRevision: 4, changeReason: '调整合规留存期' }
    });
  });

  it('loads public and administrator platform configuration', () => {
    fetchPublicPlatformConfiguration();
    fetchPlatformConfiguration();
    fetchPlatformConfigurationHistory(30);

    expect(request).toHaveBeenNthCalledWith(1, {
      url: '/open/platform/configuration',
      method: 'get'
    });
    expect(request).toHaveBeenNthCalledWith(2, {
      url: '/platform/operations/configuration',
      method: 'get'
    });
    expect(request).toHaveBeenNthCalledWith(3, {
      url: '/platform/operations/configuration/history',
      method: 'get',
      params: { limit: 30 }
    });
  });

  it('sends the optimistic revision and change reason when saving configuration', () => {
    const payload = {
      productName: '企业级智能体工作平台',
      productShortName: '智能体平台',
      logoUrl: '/assets/logo.svg',
      faviconUrl: '/favicon.svg',
      primaryColor: '#18A058',
      platformTimezone: 'Asia/Shanghai',
      defaultLocale: 'zh-CN' as const,
      watermarkEnabled: true,
      expectedRevision: 4,
      changeReason: '更新企业品牌'
    };

    updatePlatformConfiguration(payload);

    expect(request).toHaveBeenCalledWith({
      url: '/platform/operations/configuration',
      method: 'put',
      data: payload
    });
  });
});
