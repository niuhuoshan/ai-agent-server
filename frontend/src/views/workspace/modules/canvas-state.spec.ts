import { describe, expect, it } from 'vitest';
import type { CanvasView, ConversationMessageView } from '@/service/api';
import {
  buildCanvasLineComparison,
  canvasDraftFromMessage,
  canvasDraftFromView,
  canvasDraftSignature,
  createCanvasCsvPivot,
  isCanvasDraftDirty,
  parseCanvasCompare,
  parseCanvasCsv,
  resolveCanvasMediaSource,
  sanitizedCanvasHtmlDocument,
  validateCanvasWorkspacePath
} from './canvas-state';

function canvas(): CanvasView {
  return {
    id: '2',
    conversationId: '1',
    title: '结果',
    contentType: 'markdown',
    content: '# 结果',
    workspacePath: null,
    sourceMessageId: null,
    currentVersion: 3,
    revision: 1,
    contentSize: 8,
    contentSha256: 'hash',
    metadata: { language: 'text' },
    createdAt: '2026-08-17T00:00:00Z',
    updatedAt: '2026-08-17T00:00:00Z'
  };
}

describe('Canvas draft state', () => {
  it('detects semantic draft changes while ignoring metadata key order', () => {
    const draft = canvasDraftFromView(canvas());
    const baseline = canvasDraftSignature(draft);
    draft.metadata = { z: 1, language: 'text' };
    expect(isCanvasDraftDirty(draft, baseline)).toBe(true);

    draft.metadata = { language: 'text' };
    expect(isCanvasDraftDirty(draft, baseline)).toBe(false);
    draft.content = '# 新结果';
    expect(isCanvasDraftDirty(draft, baseline)).toBe(true);
  });

  it('infers a Mermaid Canvas from a chat message and records its source', () => {
    const message: ConversationMessageView = {
      id: '9',
      conversationId: '1',
      sequenceNo: 2,
      traceId: null,
      role: 'assistant',
      content: '```mermaid\ngraph TD\n A --> B\n```',
      agentId: null,
      agentVersionId: null,
      modelId: null,
      status: 'completed',
      promptTokens: 0,
      completionTokens: 0,
      totalTokens: 0,
      createdAt: '2026-08-17T00:00:00Z'
    };
    const draft = canvasDraftFromMessage(message);
    expect(draft.contentType).toBe('mermaid');
    expect(draft.content).toContain('A --> B');
    expect(draft.sourceMessageId).toBe('9');
    expect(draft.metadata.sourceRole).toBe('assistant');
  });
});

describe('Canvas CSV preview', () => {
  it('parses quoted CSV, de-duplicates headers, and creates a numeric pivot', () => {
    const parsed = parseCanvasCsv('team,amount,amount\n"A,组",10,1\nB,20,2\nB,30,3');
    expect(parsed.columns).toEqual(['team', 'amount', 'amount (2)']);
    expect(parsed.rows[0]?.team).toBe('A,组');
    expect(createCanvasCsvPivot(parsed, 'team', 'amount', 'sum')).toEqual([
      { group: 'A,组', count: 1, value: 10 },
      { group: 'B', count: 2, value: 50 }
    ]);
  });

  it('reports a bounded preview instead of accepting excessive columns', () => {
    const header = Array.from({ length: 101 }, (_, index) => `c${index}`).join(',');
    expect(parseCanvasCsv(`${header}\n${header}`).error).toContain('100 列');
  });
});

describe('Canvas safe rendering helpers', () => {
  it('removes executable HTML and adds a restrictive document policy', () => {
    const document = sanitizedCanvasHtmlDocument(
      '<script>alert(1)</script><img src="https://tracker.example/a.png" onerror="alert(2)"><p>ok</p>'
    );
    expect(document).not.toContain('<script');
    expect(document).not.toContain('onerror');
    expect(document).toContain("default-src 'none'");
    expect(document).toContain('<p>ok</p>');
  });

  it('only accepts safe media MIME types and same-origin platform URLs', () => {
    expect(
      resolveCanvasMediaSource('/platform/canvases/2/content', 'image', {
        encoding: 'url',
        mimeType: 'image/png'
      }, 'https://agent.example').requiresAuthorization
    ).toBe(true);
    expect(
      resolveCanvasMediaSource('https://outside.example/image.png', 'image', {
        encoding: 'url',
        mimeType: 'image/png'
      }, 'https://agent.example').error
    ).toContain('同源');
    expect(
      resolveCanvasMediaSource('PHN2Zz48c2NyaXB0Pg==', 'image', {
        encoding: 'base64',
        mimeType: 'image/svg+xml'
      }).error
    ).toContain('不安全');
  });
});

describe('Canvas compare and workspace paths', () => {
  it('aligns inserted lines in a two-file comparison', () => {
    const parsed = parseCanvasCompare(
      JSON.stringify({ left: 'one\ntwo', right: 'one\ninserted\ntwo' }),
      { leftLabel: 'before.ts', rightLabel: 'after.ts' }
    );
    expect(parsed.left.label).toBe('before.ts');
    expect(buildCanvasLineComparison(parsed.left.content, parsed.right.content)).toEqual([
      expect.objectContaining({ left: 'one', right: 'one', state: 'same' }),
      expect.objectContaining({ left: null, right: 'inserted', state: 'added' }),
      expect.objectContaining({ left: 'two', right: 'two', state: 'same' })
    ]);
  });

  it('rejects absolute and traversing workspace paths', () => {
    expect(validateCanvasWorkspacePath('canvas/report.md')).toBeNull();
    expect(validateCanvasWorkspacePath('../report.md')).toContain('相对路径');
    expect(validateCanvasWorkspacePath('/tmp/report.md')).toContain('相对路径');
  });
});
