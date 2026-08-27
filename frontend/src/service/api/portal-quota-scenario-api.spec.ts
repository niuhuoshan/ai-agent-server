import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import {
  deletePortalQuotaPolicy,
  fetchPortalQuotaPolicy,
  getScenarioTemplateInstance,
  uninstallScenarioTemplateInstance,
  updatePortalQuotaPolicy
} from './portal';

vi.mock('../request', () => ({ request: vi.fn() }));

describe('portal quota and scenario API contract', () => {
  beforeEach(() => {
    vi.mocked(request).mockClear();
  });

  it.each([
    ['system', undefined, '/api/portal/quota/system'],
    ['user', '42', '/api/portal/quota/users/42'],
    ['role', '7', '/api/portal/quota/roles/7']
  ] as const)('maps the %s quota scope to its backend path', (scope, scopeId, url) => {
    fetchPortalQuotaPolicy(scope, scopeId);

    expect(request).toHaveBeenCalledWith({ url, method: 'get' });
  });

  it('updates and clears a user override instead of mutating the system policy', () => {
    updatePortalQuotaPolicy('user', '42', { enabled: true, limitTokens: 500_000 });
    deletePortalQuotaPolicy('user', '42');

    expect(request).toHaveBeenNthCalledWith(1, {
      url: '/api/portal/quota/users/42',
      method: 'put',
      data: { enabled: true, limitTokens: 500_000 }
    });
    expect(request).toHaveBeenNthCalledWith(2, {
      url: '/api/portal/quota/users/42',
      method: 'delete'
    });
  });

  it('loads a persisted scenario instance by its encoded identifier', () => {
    getScenarioTemplateInstance('instance/42');

    expect(request).toHaveBeenCalledWith({
      url: '/api/portal/scenario-templates/instances/instance%2F42',
      method: 'get'
    });
  });

  it('uninstalls a scenario instance with explicit confirmation and idempotency', () => {
    uninstallScenarioTemplateInstance('instance/42', {
      confirm: true,
      reason: '不再使用该场景',
      idempotencyKey: 'uninstall-42'
    });

    expect(request).toHaveBeenCalledWith({
      url: '/api/portal/scenario-templates/instances/instance%2F42/uninstall',
      method: 'post',
      data: {
        confirm: true,
        reason: '不再使用该场景',
        idempotencyKey: 'uninstall-42'
      }
    });
  });
});
