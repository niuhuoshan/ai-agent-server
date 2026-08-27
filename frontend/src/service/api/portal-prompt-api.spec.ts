import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from '../request';
import { restorePortalPrompt, savePortalPrompt } from './portal';

vi.mock('../request', () => ({ request: vi.fn() }));

describe('portal prompt mutation API contract', () => {
  beforeEach(() => vi.mocked(request).mockClear());

  it('creates an immutable-version-backed draft when restoring history', () => {
    restorePortalPrompt({ source: 'agent', targetId: 'agent_12', versionNumber: 3 });

    expect(request).toHaveBeenCalledWith({
      url: '/api/portal/prompts/restore',
      method: 'post',
      data: { source: 'agent', targetId: 'agent_12', versionNumber: 3 }
    });
  });

  it('keeps prompt saves content-addressable at the API boundary', () => {
    savePortalPrompt({ source: 'agent', targetId: 'agent_12', content: '只回答事实' });

    expect(request).toHaveBeenCalledWith({
      url: '/api/portal/prompts/save',
      method: 'post',
      data: { source: 'agent', targetId: 'agent_12', content: '只回答事实', versionNote: undefined }
    });
  });
});
