import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import { downloadKnowledgeDocument, fetchKnowledgeChunks } from './platform';

vi.mock('../request', () => ({ request: vi.fn() }));

describe('knowledge document detail API', () => {
  beforeEach(() => vi.mocked(request).mockClear());

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('uses document-scoped chunk pagination', () => {
    fetchKnowledgeChunks('100', '200', 50, 25);

    expect(vi.mocked(request)).toHaveBeenCalledWith({
      url: '/platform/knowledge-bases/100/documents/200/chunks',
      method: 'get',
      params: { offset: 50, limit: 25 }
    });
  });

  it('downloads binary source bytes and preserves the server filename', async () => {
    const bytes = new Uint8Array([0, 255, 1, 2]);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(new Blob([bytes]), {
      status: 200,
      headers: {
        'content-type': 'application/pdf',
        'content-disposition': "attachment; filename*=UTF-8''制度.pdf"
      }
    })));

    const result = await downloadKnowledgeDocument('100', '200');

    expect(result.fileName).toBe('制度.pdf');
    expect(new Uint8Array(await result.blob.arrayBuffer())).toEqual(bytes);
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining('/platform/knowledge-bases/100/documents/200/file'),
      expect.objectContaining({ headers: expect.any(Headers) })
    );
  });
});
