import { afterEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import {
  createConversationCanvas,
  deleteConversationCanvas,
  fetchConversationCanvas,
  fetchConversationCanvases,
  fetchConversationCanvasVersions,
  restoreConversationCanvasVersion,
  saveConversationCanvasToWorkspace,
  updateConversationCanvas
} from './platform';

vi.mock('../request', () => ({ request: vi.fn() }));

afterEach(() => {
  vi.mocked(request).mockReset();
});

describe('conversation Canvas API', () => {
  it('uses the conversation-owned list and detail routes', () => {
    fetchConversationCanvases('12');
    fetchConversationCanvas('12', '34');
    fetchConversationCanvasVersions('12', '34');

    expect(request).toHaveBeenNthCalledWith(1, {
      url: '/platform/conversations/12/canvases',
      method: 'get'
    });
    expect(request).toHaveBeenNthCalledWith(2, {
      url: '/platform/conversations/12/canvases/34',
      method: 'get'
    });
    expect(request).toHaveBeenNthCalledWith(3, {
      url: '/platform/conversations/12/canvases/34/versions',
      method: 'get'
    });
  });

  it('creates and updates typed content with an optimistic version', () => {
    const content = {
      title: '分析结果',
      contentType: 'markdown' as const,
      content: '# 结果',
      metadata: { sourceMessageId: '56' }
    };
    createConversationCanvas('12', content);
    updateConversationCanvas('12', '34', { ...content, expectedVersion: 7 });

    expect(request).toHaveBeenNthCalledWith(1, {
      url: '/platform/conversations/12/canvases',
      method: 'post',
      data: content
    });
    expect(request).toHaveBeenNthCalledWith(2, {
      url: '/platform/conversations/12/canvases/34',
      method: 'put',
      data: { ...content, expectedVersion: 7 }
    });
  });

  it('carries expectedVersion through delete and restore operations', () => {
    deleteConversationCanvas('12', '34', 7);
    restoreConversationCanvasVersion('12', '34', 3, 7);

    expect(request).toHaveBeenNthCalledWith(1, {
      url: '/platform/conversations/12/canvases/34',
      method: 'delete',
      params: { expectedVersion: 7 }
    });
    expect(request).toHaveBeenNthCalledWith(2, {
      url: '/platform/conversations/12/canvases/34/versions/3/restore',
      method: 'post',
      data: { expectedVersion: 7 }
    });
  });

  it('requires explicit overwrite choice when saving into the workspace', () => {
    saveConversationCanvasToWorkspace('12', '34', {
      path: 'canvas/report.md',
      overwrite: false,
      expectedVersion: 7
    });

    expect(request).toHaveBeenCalledWith({
      url: '/platform/conversations/12/canvases/34/save-to-workspace',
      method: 'post',
      data: {
        path: 'canvas/report.md',
        overwrite: false,
        expectedVersion: 7
      }
    });
  });
});
