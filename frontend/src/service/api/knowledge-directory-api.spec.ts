import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import {
  createKnowledgeDirectory,
  deleteKnowledgeDirectory,
  fetchKnowledgeDirectories,
  fetchKnowledgeTree,
  updateKnowledgeDirectory,
  updateKnowledgeDocument,
  uploadKnowledgeDocument
} from './platform';

vi.mock('../request', () => ({ request: vi.fn() }));

describe('knowledge directory and document catalog API contract', () => {
  beforeEach(() => vi.mocked(request).mockClear());

  it('loads a flat tree without coercing Snowflake IDs', () => {
    fetchKnowledgeTree('90071992547409931');
    fetchKnowledgeDirectories('90071992547409931');

    expect(vi.mocked(request).mock.calls).toEqual([
      [{ url: '/platform/knowledge-bases/90071992547409931/tree', method: 'get' }],
      [{ url: '/platform/knowledge-bases/90071992547409931/directories', method: 'get' }]
    ]);
  });

  it('creates a root directory and does not leak an optimistic revision field', () => {
    createKnowledgeDirectory('100', { name: '产品手册', parentId: null, expectedRevision: 'ignored' });

    expect(vi.mocked(request)).toHaveBeenCalledWith({
      url: '/platform/knowledge-bases/100/directories',
      method: 'post',
      data: { name: '产品手册', parentId: null }
    });
  });

  it('updates and deletes a directory with a string revision', () => {
    updateKnowledgeDirectory('100', '200', {
      name: '归档',
      parentId: null,
      expectedRevision: '7'
    });
    deleteKnowledgeDirectory('100', '200', '8');

    expect(vi.mocked(request).mock.calls).toEqual([
      [{
        url: '/platform/knowledge-bases/100/directories/200',
        method: 'put',
        data: { name: '归档', parentId: null, expectedRevision: '7' }
      }],
      [{
        url: '/platform/knowledge-bases/100/directories/200',
        method: 'delete',
        params: { expectedRevision: '8' }
      }]
    ]);
  });

  it('updates document metadata and explicitly moves root documents with null', () => {
    updateKnowledgeDocument('100', '300', {
      name: '制度.pdf',
      directoryId: null,
      tags: ['制度', '人事'],
      remark: null,
      expectedRevision: '9'
    });

    expect(vi.mocked(request)).toHaveBeenCalledWith({
      url: '/platform/knowledge-bases/100/documents/300',
      method: 'put',
      data: {
        name: '制度.pdf',
        directoryId: null,
        tags: ['制度', '人事'],
        remark: null,
        expectedRevision: '9'
      }
    });
  });

  it('passes the selected directory as an optional upload query parameter', () => {
    const file = new File(['内容'], '制度.txt', { type: 'text/plain' });
    uploadKnowledgeDocument('100', file, '200');

    expect(vi.mocked(request)).toHaveBeenCalledWith(expect.objectContaining({
      url: '/platform/knowledge-bases/100/documents',
      method: 'post',
      params: { directoryId: '200' },
      data: expect.any(FormData)
    }));
  });
});
