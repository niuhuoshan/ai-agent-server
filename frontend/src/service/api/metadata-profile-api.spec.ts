import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import {
  applyMetadataSmartImportPreview,
  cancelMetadataProfileJob,
  createMetadataProfileJob,
  createMetadataSmartImportPreview,
  fetchMetadataProfileJob,
  fetchMetadataProfileJobs,
  fetchMetadataRelatedTables,
  fetchMetadataSmartImportPreview,
  fetchMetadataTableProfile,
  fetchMetadataTableProfiles,
  fetchMetadataTableProfileStats,
  resumeMetadataProfileJob,
  updateMetadataTableProfileIgnore
} from './platform';

vi.mock('../request', () => ({ request: vi.fn() }));

describe('durable metadata profile API contract', () => {
  beforeEach(() => vi.mocked(request).mockClear());

  it('uses dataset-scoped durable job endpoints without reshaping table ids', () => {
    const payload = { mode: 'incremental' as const, tableIds: ['101', '102'] };
    createMetadataProfileJob('10', payload);
    fetchMetadataProfileJobs('10', 25);
    fetchMetadataProfileJob('10', '31');
    cancelMetadataProfileJob('10', '31');
    resumeMetadataProfileJob('10', '31');

    expect(vi.mocked(request).mock.calls).toEqual([
      [{ url: '/platform/datasets/10/profile-jobs', method: 'post', data: payload }],
      [{ url: '/platform/datasets/10/profile-jobs', method: 'get', params: { limit: 25 } }],
      [{ url: '/platform/datasets/10/profile-jobs/31', method: 'get' }],
      [{ url: '/platform/datasets/10/profile-jobs/31/cancel', method: 'post' }],
      [{ url: '/platform/datasets/10/profile-jobs/31/resume', method: 'post' }]
    ]);
  });

  it('preserves profile pagination, tag, classification and sort filters', () => {
    const params = {
      page: 3,
      pageSize: 50,
      query: 'order',
      tag: '交易',
      ignored: false,
      classification: 'business' as const,
      status: 'success' as const,
      sortBy: 'confidence' as const,
      sortOrder: 'desc' as const
    };
    fetchMetadataTableProfiles('10', params);
    fetchMetadataTableProfileStats('10');
    fetchMetadataTableProfile('10', '101');
    fetchMetadataRelatedTables('10', '101', 15);
    updateMetadataTableProfileIgnore('10', '101', { revisionNo: 4, ignored: true });

    expect(vi.mocked(request).mock.calls).toEqual([
      [{ url: '/platform/datasets/10/table-profiles', method: 'get', params }],
      [{ url: '/platform/datasets/10/table-profiles/stats', method: 'get' }],
      [{ url: '/platform/datasets/10/table-profiles/101', method: 'get', params: { relatedLimit: 15 } }],
      [{ url: '/platform/datasets/10/table-profiles/101/related', method: 'get', params: { limit: 15 } }],
      [{ url: '/platform/datasets/10/table-profiles/101/ignore', method: 'put', data: { revisionNo: 4, ignored: true } }]
    ]);
  });

  it('creates, restores and selectively applies a revision-guarded smart import preview', () => {
    createMetadataSmartImportPreview('10', { profileJobId: '31', tableIds: ['101', '102'] });
    fetchMetadataSmartImportPreview('10', '51');
    applyMetadataSmartImportPreview('10', '51', { revisionNo: 2, itemIds: ['61', '63'] });

    expect(vi.mocked(request).mock.calls).toEqual([
      [{
        url: '/platform/datasets/10/smart-import/previews',
        method: 'post',
        data: { profileJobId: '31', tableIds: ['101', '102'] }
      }],
      [{ url: '/platform/datasets/10/smart-import/previews/51', method: 'get' }],
      [{
        url: '/platform/datasets/10/smart-import/previews/51/apply',
        method: 'post',
        data: { revisionNo: 2, itemIds: ['61', '63'] }
      }]
    ]);
  });
});
