import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import {
  applyMetadataImportPreview,
  createMetadataImportPreview,
  downloadDatasetMetadataYaml,
  fetchMetadataImportPreview
} from './platform';

vi.mock('../request', () => ({ request: vi.fn() }));

describe('metadata catalog import API contract', () => {
  beforeEach(() => vi.mocked(request).mockClear());

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('creates, restores and atomically applies a dataset-scoped import preview', () => {
    const previewPayload = { format: 'ddl' as const, content: 'CREATE TABLE public.orders (id BIGINT);' };
    const applyPayload = { revisionNo: 3, itemIds: ['90071992547409931', '90071992547409932'] };

    createMetadataImportPreview('10', previewPayload);
    fetchMetadataImportPreview('10', '51');
    applyMetadataImportPreview('10', '51', applyPayload);

    expect(vi.mocked(request).mock.calls).toEqual([
      [{
        url: '/platform/datasets/10/metadata-import/previews',
        method: 'post',
        data: previewPayload
      }],
      [{ url: '/platform/datasets/10/metadata-import/previews/51', method: 'get' }],
      [{
        url: '/platform/datasets/10/metadata-import/previews/51/apply',
        method: 'post',
        data: applyPayload
      }]
    ]);
  });

  it('downloads canonical YAML without converting bytes through JSON', async () => {
    const content = 'version: 1\ndataset:\n  key: sales\n';
    const bytes = new TextEncoder().encode(content);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(new Blob([content]), {
      status: 200,
      headers: {
        'content-type': 'application/yaml;charset=UTF-8',
        'content-disposition': "attachment; filename*=UTF-8''sales-metadata.yaml"
      }
    })));

    const result = await downloadDatasetMetadataYaml('10');

    expect(result.fileName).toBe('sales-metadata.yaml');
    expect(new Uint8Array(await result.blob.arrayBuffer())).toEqual(bytes);
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining('/platform/datasets/10/metadata.yaml'),
      expect.objectContaining({ headers: expect.any(Headers) })
    );
  });
});
